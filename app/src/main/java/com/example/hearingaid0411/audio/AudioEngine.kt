package com.example.hearingaid0411.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.tanh

/**
 * 單一麥克風擷取引擎：App 自己持有唯一的 AudioRecord（48kHz mono），
 * 把 PCM 分流給多個 Sink（擴音播放、離線 ASR 餵料…）。
 *
 * 這是「單一收音源」架構的核心：同一 App 內共享收音不受 Android
 * 麥克風併發政策限制，擴音與離線字幕可真正同時運作。
 */
class AudioEngine {

    companion object {
        const val SAMPLE_RATE = 48000
        private const val CHUNK_SAMPLES = 960 // 20ms @ 48kHz
    }

    /** PCM 消費者；onPcm 在擷取執行緒被呼叫，不可阻塞 */
    interface Sink {
        fun onPcm(buf: ShortArray, n: Int)
    }

    private val sinks = CopyOnWriteArrayList<Sink>()

    @Volatile
    private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    fun addSink(sink: Sink) {
        sinks.addIfAbsent(sink)
    }

    fun removeSink(sink: Sink) {
        sinks.remove(sink)
    }

    /** 回傳 false 表示麥克風開啟失敗 */
    @SuppressLint("MissingPermission") // RECORD_AUDIO 由呼叫端檢查
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 2, CHUNK_SAMPLES * 4)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        running = true
        thread = Thread {
            val buf = ShortArray(CHUNK_SAMPLES)
            try {
                record.startRecording()
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) {
                        for (sink in sinks) sink.onPcm(buf, n)
                    } else if (n < 0) {
                        break
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { record.stop() } catch (_: Exception) {}
                record.release()
            }
        }.apply {
            name = "AudioEngine"
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
}

/**
 * 擴音 Sink：把麥克風 PCM 套增益＋tanh 軟限幅後播到媒體路由（耳機）。
 * 掛到 AudioEngine 上即開始出聲；detach 即停。
 */
class AmpSink : AudioEngine.Sink {

    @Volatile
    var gain = 2.0f

    private var track: AudioTrack? = null
    private val work = ShortArray(AudioEngine.SAMPLE_RATE / 25) // 40ms 工作緩衝

    /** 建立播放通路；失敗回傳 false */
    fun open(): Boolean {
        if (track != null) return true
        return try {
            val minOut = AudioTrack.getMinBufferSize(
                AudioEngine.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(AudioEngine.SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minOut * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
                .also { it.play() }
            true
        } catch (_: Exception) {
            track = null
            false
        }
    }

    fun close() {
        track?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        track = null
    }

    override fun onPcm(buf: ShortArray, n: Int) {
        val t = track ?: return
        val g = gain
        val len = minOf(n, work.size)
        for (i in 0 until len) {
            val v = buf[i] * g / 32767f
            work[i] = (tanh(v.toDouble()) * 32767.0).toInt().toShort()
        }
        t.write(work, 0, len)
    }
}
