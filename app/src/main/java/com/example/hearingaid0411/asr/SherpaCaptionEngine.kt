package com.example.hearingaid0411.asr

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.hearingaid0411.AsrEngine
import com.example.hearingaid0411.ErrorAction
import com.example.hearingaid0411.HearingState
import com.example.hearingaid0411.UiError
import com.example.hearingaid0411.audio.AudioEngine
import com.github.houbb.opencc4j.util.ZhTwConverterUtil
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 離線串流中文辨識引擎（sherpa-onnx streaming zipformer）。
 *
 * 音訊來源是共享的 AudioEngine（App 自己的單一麥克風）——與擴音通路
 * 同源，不再依賴 Google 辨識服務，徹底解決麥克風併發衝突，且離線可用。
 *
 * 資料流：AudioEngine 48kHz PCM → 3:1 降採樣至 16kHz Float → 佇列 →
 * 解碼執行緒（acceptWaveform/decode/endpoint）→ OpenCC 簡轉繁（台灣用語）→
 * partial/final 發佈到主執行緒。
 */
class SherpaCaptionEngine(
    private val model: SherpaModel,
    private val audioEngine: AudioEngine,
    /** 聲紋判定（可為 null＝未啟用）；輸入為該句 16kHz 音訊，回傳是否為「自己」 */
    private val identifySelf: ((FloatArray) -> Boolean)?,
    private val onFinal: (String, Boolean) -> Unit,
) : AsrEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingQueue<FloatArray>(64)

    @Volatile
    private var running = false
    private var decodeThread: Thread? = null
    private var recognizer: OnlineRecognizer? = null
    private var lastLevelAt = 0L

    /** 48k→16k 降採樣 Sink：每 3 個樣本平均為 1 個（簡易抗混疊） */
    private val sink = object : AudioEngine.Sink {
        override fun onPcm(buf: ShortArray, n: Int) {
            val outN = n / 3
            if (outN == 0) return
            val out = FloatArray(outN)
            var peak = 0
            var i = 0
            for (j in 0 until outN) {
                val s = (buf[i].toInt() + buf[i + 1].toInt() + buf[i + 2].toInt()) / 3
                if (abs(s) > peak) peak = abs(s)
                out[j] = s / 32768f
                i += 3
            }
            queue.offer(out) // 佇列滿時丟棄（辨識落後就先犧牲舊音訊，不阻塞擷取執行緒）
            postLevel(peak)
        }
    }

    private fun postLevel(peak: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLevelAt < 100) return
        lastLevelAt = now
        val level = (peak / 4000).coerceIn(0, 5)
        mainHandler.post { HearingState.rmsLevel = level }
    }

    override fun start() {
        if (running) return
        running = true
        decodeThread = Thread { runLoop() }.apply {
            name = "SherpaDecode"
            start()
        }
    }

    override fun stop() {
        running = false
        decodeThread?.let {
            try { it.join(2000) } catch (_: InterruptedException) {}
        }
        decodeThread = null
        queue.clear()
        HearingState.isListening = false
        HearingState.isSpeaking = false
        HearingState.rmsLevel = 0
    }

    override fun destroy() {
        stop()
        try { recognizer?.release() } catch (_: Throwable) {}
        recognizer = null
    }

    private fun runLoop() {
        var attached = false
        try {
            // 模型載入（首次約數秒）；期間 UI 顯示「準備中…」
            val rec = recognizer ?: OnlineRecognizer(null, buildConfig()).also { recognizer = it }
            val stream = rec.createStream()
            mainHandler.post {
                if (HearingState.wantsListening) {
                    HearingState.isListening = true
                    HearingState.errorMessage = null
                }
            }
            audioEngine.addSink(sink)
            attached = true

            var lastPartial = ""
            val segmentChunks = ArrayList<FloatArray>()
            var segmentSamples = 0
            while (running) {
                val chunk = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                stream.acceptWaveform(chunk, AsrConstants.SAMPLE_RATE)
                // 累積本句音訊供聲紋判定（只留最後 N 秒）
                segmentChunks.add(chunk)
                segmentSamples += chunk.size
                while (segmentSamples > AsrConstants.SAMPLE_RATE * SEGMENT_MAX_SECONDS &&
                    segmentChunks.size > 1
                ) {
                    segmentSamples -= segmentChunks.removeAt(0).size
                }
                while (rec.isReady(stream)) rec.decode(stream)
                val raw = rec.getResult(stream).text
                if (rec.isEndpoint(stream)) {
                    if (raw.isNotBlank()) {
                        val isSelf = judgeSelf(segmentChunks, segmentSamples)
                        val text = toTaiwan(raw)
                        mainHandler.post {
                            HearingState.isSpeaking = false
                            onFinal(text, isSelf)
                        }
                    } else {
                        mainHandler.post { HearingState.isSpeaking = false }
                    }
                    rec.reset(stream)
                    segmentChunks.clear()
                    segmentSamples = 0
                    lastPartial = ""
                } else if (raw.isNotBlank() && raw != lastPartial) {
                    lastPartial = raw
                    val text = toTaiwan(raw)
                    mainHandler.post {
                        HearingState.isSpeaking = true
                        HearingState.updatePartial(text)
                    }
                }
            }

            // 停止時把最後未定稿的內容保留下來
            val raw = rec.getResult(stream).text
            if (raw.isNotBlank()) {
                val text = toTaiwan(raw)
                mainHandler.post { onFinal(text, false) }
            }
            stream.release()
        } catch (t: Throwable) {
            mainHandler.post {
                HearingState.wantsListening = false
                HearingState.isListening = false
                HearingState.errorMessage = UiError(
                    message = "離線辨識啟動失敗，請再按一次「開始聆聽」",
                    actionLabel = "重試",
                    action = ErrorAction.RETRY,
                )
            }
        } finally {
            if (attached) audioEngine.removeSink(sink)
        }
    }

    /** 聲紋判定：段落太短或未啟用時回傳 false（不標記） */
    private fun judgeSelf(chunks: List<FloatArray>, totalSamples: Int): Boolean {
        val judge = identifySelf ?: return false
        if (totalSamples < MIN_JUDGE_SAMPLES) return false
        val seg = FloatArray(totalSamples)
        var pos = 0
        for (c in chunks) {
            System.arraycopy(c, 0, seg, pos, c.size)
            pos += c.size
        }
        return try {
            judge(seg)
        } catch (_: Throwable) {
            false
        }
    }

    private fun buildConfig() = OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = AsrConstants.SAMPLE_RATE, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = model.encoder,
                decoder = model.decoder,
                joiner = model.joiner,
            ),
            tokens = model.tokens,
            numThreads = 2,
            provider = "cpu",
        ),
        endpointConfig = EndpointConfig(),
        enableEndpoint = true,
        decodingMethod = "greedy_search",
    )

    companion object {
        /** 句段音訊緩衝上限（秒）：只保留最後 N 秒供聲紋判定 */
        private const val SEGMENT_MAX_SECONDS = 12
        private val MIN_JUDGE_SAMPLES = (16000 * VoiceprintStore.MIN_SEGMENT_SECONDS).toInt()

        /** 簡體→台灣繁體（含台灣用語詞彙級轉換）；轉換失敗時原文返回 */
        fun toTaiwan(text: String): String = try {
            ZhTwConverterUtil.toTraditional(text)
        } catch (_: Throwable) {
            text
        }

        /** App 啟動時預熱詞典（首次載入較慢，避免第一句卡頓） */
        fun prewarmOpenCc() {
            Thread {
                try { ZhTwConverterUtil.toTraditional("预热") } catch (_: Throwable) {}
            }.apply { name = "OpenCcPrewarm" }.start()
        }
    }
}

object AsrConstants {
    const val SAMPLE_RATE = 16000
}
