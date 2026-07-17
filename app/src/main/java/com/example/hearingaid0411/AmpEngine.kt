package com.example.hearingaid0411

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlin.math.tanh

/**
 * 擴音通路 MVP：麥克風（VOICE_RECOGNITION 音源、48kHz）→ 軟體增益＋軟限幅 → 媒體路由播放。
 * 媒體路由（USAGE_MEDIA）由系統自動送到已連線的有線/藍牙/LE Audio 耳機。
 * 呼叫端（HearingService）負責：只在偵測到耳機時啟動、拔耳機立即停止。
 */
class AmpEngine {

    companion object {
        private const val SAMPLE_RATE = 48000
        private const val DEFAULT_GAIN = 2.0f
    }

    @Volatile
    private var running = false

    @Volatile
    var gain = DEFAULT_GAIN

    private var thread: Thread? = null

    /** 回傳 false 表示麥克風開啟失敗（例如被其他 App 佔用） */
    @SuppressLint("MissingPermission") // RECORD_AUDIO 由服務啟動前檢查
    fun start(): Boolean {
        if (running) return true

        val minIn = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minIn <= 0) return false
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            minIn * 2
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        val minOut = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minOut * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        running = true
        thread = Thread {
            val buf = ShortArray(960) // 20ms @ 48kHz
            try {
                record.startRecording()
                track.play()
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) {
                        applyGain(buf, n)
                        track.write(buf, 0, n)
                    } else if (n < 0) {
                        break
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { record.stop() } catch (_: Exception) {}
                record.release()
                try { track.stop() } catch (_: Exception) {}
                track.release()
            }
        }.apply {
            name = "AmpEngine"
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    fun stop() {
        running = false
        thread?.let {
            try { it.join(500) } catch (_: InterruptedException) {}
        }
        thread = null
    }

    /** 軟體增益＋tanh 軟限幅（避免硬削波爆音，兼作簡易 limiter） */
    private fun applyGain(buf: ShortArray, n: Int) {
        val g = gain
        for (i in 0 until n) {
            val v = buf[i] * g / 32767f
            buf[i] = (tanh(v.toDouble()) * 32767.0).toInt().toShort()
        }
    }
}
