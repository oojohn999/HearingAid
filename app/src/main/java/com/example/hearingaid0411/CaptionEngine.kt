package com.example.hearingaid0411

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** 字幕辨識引擎的共同介面（SpeechRecognizer 備援與 sherpa-onnx 離線引擎可互換） */
interface AsrEngine {
    fun start()
    fun stop()
    fun destroy()
}

/**
 * 語音辨識引擎（系統 SpeechRecognizer 備援版）：連續聆聽的狀態機、
 * 錯誤分類重試、partial/final 分流。只能在主執行緒操作。
 * 定稿句子透過 [onFinal] 回呼交給呼叫端（服務）處理持久化。
 */
class CaptionEngine(
    private val context: Context,
    private val onFinal: (String) -> Unit,
) : AsrEngine {
    private enum class RecState { IDLE, STARTING, LISTENING }

    private var speechRecognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var recState = RecState.IDLE
    private var consecutiveErrors = 0
    private var lastPartialHandledAt = 0L
    private var lastRmsHandledAt = 0L

    companion object {
        private const val PARTIAL_THROTTLE_MS = 150L
        private const val RMS_THROTTLE_MS = 100L
        private const val MAX_NETWORK_RETRIES = 5
        private const val MAX_CLIENT_RETRIES = 2
    }

    /** 開始（或恢復）聆聽。前置條件：RECORD_AUDIO 已授權、HearingState.wantsListening 已設 true */
    override fun start() {
        startInternal()
    }

    /** 使用者主動停止：清排程、取消辨識、保留未定稿文字 */
    override fun stop() {
        handler.removeCallbacksAndMessages(null)
        cancelRecognizer()
        HearingState.isListening = false
        HearingState.isSpeaking = false
        HearingState.rmsLevel = 0
        if (HearingState.partialText.isNotBlank()) {
            onFinal(HearingState.partialText)
        }
    }

    override fun destroy() {
        handler.removeCallbacksAndMessages(null)
        try { speechRecognizer?.setRecognitionListener(null) } catch (_: Exception) {}
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        speechRecognizer = null
        recState = RecState.IDLE
    }

    private fun cancelRecognizer() {
        if (recState != RecState.IDLE) {
            try { speechRecognizer?.cancel() } catch (_: Exception) {}
            recState = RecState.IDLE
        }
    }

    private fun stopWithError(error: UiError) {
        HearingState.wantsListening = false
        handler.removeCallbacksAndMessages(null)
        cancelRecognizer()
        HearingState.isListening = false
        HearingState.isSpeaking = false
        HearingState.rmsLevel = 0
        HearingState.errorMessage = error
    }

    private fun scheduleRestart(delayMs: Long) {
        handler.postDelayed({
            if (HearingState.wantsListening && recState == RecState.IDLE) startInternal()
        }, delayMs)
    }

    private fun startInternal() {
        if (!HearingState.wantsListening || recState != RecState.IDLE) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            stopWithError(UiError("這支手機沒有語音辨識功能，字幕無法使用"))
            return
        }
        val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            speechRecognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 750)
        }
        try {
            recognizer.startListening(intent)
            recState = RecState.STARTING
        } catch (e: Exception) {
            recState = RecState.IDLE
            handleError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            recState = RecState.LISTENING
            HearingState.isListening = true
            HearingState.errorMessage = null
        }

        override fun onBeginningOfSpeech() {
            HearingState.isSpeaking = true
        }

        override fun onRmsChanged(rmsdB: Float) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRmsHandledAt < RMS_THROTTLE_MS) return
            lastRmsHandledAt = now
            HearingState.rmsLevel = ((rmsdB + 2f) / 2.4f).toInt().coerceIn(0, 5)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            HearingState.isSpeaking = false
        }

        override fun onError(error: Int) {
            handleError(error)
        }

        override fun onResults(results: Bundle?) {
            recState = RecState.IDLE
            HearingState.isSpeaking = false
            consecutiveErrors = 0
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                onFinal(text)
            } else if (HearingState.partialText.isNotBlank()) {
                onFinal(HearingState.partialText)
            }
            // 立即重啟，消除句間收音空窗
            if (HearingState.wantsListening) startInternal()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            consecutiveErrors = 0
            val now = SystemClock.elapsedRealtime()
            if (now - lastPartialHandledAt < PARTIAL_THROTTLE_MS) return
            lastPartialHandledAt = now
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) HearingState.updatePartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun handleError(error: Int) {
        recState = RecState.IDLE
        HearingState.isSpeaking = false
        if (!HearingState.wantsListening) return
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                consecutiveErrors = 0
                startInternal()
            }

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                try { speechRecognizer?.cancel() } catch (_: Exception) {}
                scheduleRestart(500)
            }

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stopWithError(
                UiError(
                    message = "需要麥克風權限，才能聽到聲音變成字幕",
                    actionLabel = "去開啟權限",
                    action = ErrorAction.OPEN_SETTINGS,
                )
            )

            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> {
                consecutiveErrors++
                if (consecutiveErrors <= MAX_NETWORK_RETRIES) {
                    val backoff = (1000L shl (consecutiveErrors - 1)).coerceAtMost(8000L)
                    scheduleRestart(backoff)
                } else {
                    stopWithError(
                        UiError(
                            message = "沒有網路，暫時聽不到。請確認網路後再試一次",
                            actionLabel = "重試",
                            action = ErrorAction.RETRY,
                        )
                    )
                }
            }

            else -> {
                consecutiveErrors++
                if (consecutiveErrors <= MAX_CLIENT_RETRIES) {
                    scheduleRestart(300)
                } else {
                    stopWithError(
                        UiError(
                            message = "語音辨識出了問題，請再按一次「開始聆聽」",
                            actionLabel = "重試",
                            action = ErrorAction.RETRY,
                        )
                    )
                }
            }
        }
    }
}
