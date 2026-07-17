package com.example.hearingaid0411.asr

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 聲紋功能：註冊「自己的聲音」，之後字幕自動標記自己說的話（淡化顯示）。
 *
 * - 模型：3D-Speaker CAM++ 中文聲紋模型（約 27MB，單一 onnx 檔，
 *   從 sherpa-onnx 官方 GitHub release 下載）
 * - 聲紋向量只存本機 App 私有目錄（不上傳、可刪除）；屬生物特徵個資，
 *   全程離線處理
 */
object VoiceprintStore {

    private const val SPEAKER_MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"
    const val SPEAKER_MODEL_BYTES = 28_281_138L

    /** 比對閾值：偏保守（寧可漏標自己，不可把家人誤標成自己） */
    const val VERIFY_THRESHOLD = 0.5f

    /** 段落短於此秒數不做判定（短語音誤判率高） */
    const val MIN_SEGMENT_SECONDS = 1.5f

    fun modelFile(context: Context): File =
        File(
            File(context.getExternalFilesDir(null) ?: context.filesDir, "speaker-model"),
            "campplus_zh.onnx"
        )

    fun modelReady(context: Context): Boolean =
        modelFile(context).length() == SPEAKER_MODEL_BYTES

    /** 聲紋向量存內部私有儲存（不進備份、不上雲） */
    private fun embeddingFile(context: Context): File =
        File(context.filesDir, "voiceprint.json")

    fun isEnrolled(context: Context): Boolean = embeddingFile(context).exists()

    fun save(context: Context, embeddings: List<FloatArray>) {
        val arr = JSONArray()
        for (e in embeddings) {
            val row = JSONArray()
            for (v in e) row.put(v.toDouble())
            arr.put(row)
        }
        embeddingFile(context).writeText(arr.toString())
    }

    fun load(context: Context): List<FloatArray> {
        val f = embeddingFile(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val row = arr.getJSONArray(i)
                FloatArray(row.length()) { j -> row.getDouble(j).toFloat() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clear(context: Context) {
        embeddingFile(context).delete()
    }

    /**
     * 下載聲紋模型（單一檔案）。阻塞呼叫，請在背景執行緒執行。
     * onProgress 回傳 0f..1f。
     */
    fun downloadModel(context: Context, onProgress: (Float) -> Unit) {
        val dst = modelFile(context)
        if (dst.length() == SPEAKER_MODEL_BYTES) {
            onProgress(1f)
            return
        }
        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, dst.name + ".part")
        val conn = URL(SPEAKER_MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        onProgress((total.toFloat() / SPEAKER_MODEL_BYTES).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (tmp.length() != SPEAKER_MODEL_BYTES) {
            tmp.delete()
            throw IOException("size mismatch")
        }
        dst.delete()
        if (!tmp.renameTo(dst)) throw IOException("rename failed")
    }

    /**
     * 錄一段 16kHz 單聲道音訊（阻塞，請在背景執行緒呼叫）。
     * 回傳 Float PCM [-1,1]；失敗回傳 null。
     */
    @SuppressLint("MissingPermission") // 呼叫端先確認 RECORD_AUDIO
    fun recordSeconds(seconds: Float, onProgress: (Float) -> Unit): FloatArray? {
        val sampleRate = 16000
        val totalSamples = (sampleRate * seconds).toInt()
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 2, 8192)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        val out = FloatArray(totalSamples)
        val buf = ShortArray(1600)
        var filled = 0
        try {
            record.startRecording()
            while (filled < totalSamples) {
                val n = record.read(buf, 0, minOf(buf.size, totalSamples - filled))
                if (n <= 0) break
                for (i in 0 until n) out[filled + i] = buf[i] / 32768f
                filled += n
                onProgress(filled.toFloat() / totalSamples)
            }
        } catch (_: Exception) {
            return null
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
        }
        return if (filled >= totalSamples * 3 / 4) out else null
    }
}

/**
 * 聲紋比對器：載入 CAM++ 模型與已註冊的 embedding，
 * 判定一段 16kHz 音訊是否為「自己」。
 */
class SpeakerIdentifier(modelPath: String, enrolled: List<FloatArray>) {

    companion object {
        const val SELF = "me"
    }

    private val extractor = SpeakerEmbeddingExtractor(
        null,
        SpeakerEmbeddingExtractorConfig(modelPath, 1, false, "cpu")
    )
    private val manager: SpeakerEmbeddingManager?

    init {
        manager = if (enrolled.isNotEmpty()) {
            SpeakerEmbeddingManager(extractor.dim()).also {
                it.add(SELF, enrolled.toTypedArray())
            }
        } else null
    }

    /** 計算一段 16kHz Float PCM 的聲紋向量 */
    fun computeEmbedding(samples16k: FloatArray): FloatArray {
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples16k, 16000)
            stream.inputFinished()
            return extractor.compute(stream)
        } finally {
            stream.release()
        }
    }

    /** 這段話是不是「自己」說的（閾值偏保守） */
    fun isSelf(samples16k: FloatArray): Boolean {
        val m = manager ?: return false
        return try {
            val emb = computeEmbedding(samples16k)
            m.verify(SELF, emb, VoiceprintStore.VERIFY_THRESHOLD)
        } catch (_: Throwable) {
            false
        }
    }

    fun release() {
        try { manager?.release() } catch (_: Throwable) {}
        try { extractor.release() } catch (_: Throwable) {}
    }
}
