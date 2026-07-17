package com.example.hearingaid0411.asr

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.hearingaid0411.HearingState
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 離線模型下載狀態（給 UI 顯示） */
sealed interface ModelDownloadState {
    data class InProgress(val progress: Float) : ModelDownloadState
    data object Done : ModelDownloadState
    data object Failed : ModelDownloadState
}

/**
 * App 內建的離線辨識模型下載器。
 * 從 HuggingFace 官方 k2-fsa repo 直接抓 4 個必要檔案（共約 76MB，
 * 比 GitHub release 的 280MB 壓縮包省 3/4 流量、也不需要解壓縮）。
 *
 * 單檔完成才改成正式檔名（先寫 .part 再 rename），中斷重試時
 * 已完成的檔案自動跳過。
 */
object ModelDownloader {

    private const val BASE =
        "https://huggingface.co/k2-fsa/sherpa-onnx-streaming-zipformer-multi-zh-hans-2023-12-12/resolve/main/"

    /** 檔名與預期大小（bytes；用於進度計算與完成判斷） */
    private val FILES = listOf(
        "encoder-epoch-20-avg-1-chunk-16-left-128.int8.onnx" to 70_109_350L,
        "decoder-epoch-20-avg-1-chunk-16-left-128.onnx" to 5_165_083L,
        "joiner-epoch-20-avg-1-chunk-16-left-128.int8.onnx" to 1_033_416L,
        "tokens.txt" to 18_626L,
    )

    val totalBytes: Long = FILES.sumOf { it.second }

    @Volatile
    private var running = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastPublishAt = 0L

    fun start(context: Context) {
        if (running) return
        running = true
        publishState(ModelDownloadState.InProgress(0f))
        val dir = SherpaModelLocator.modelDir(context.applicationContext)

        Thread({
            try {
                if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
                var doneBytes = 0L
                for ((name, expected) in FILES) {
                    val dst = File(dir, name)
                    if (dst.length() == expected) {
                        doneBytes += expected
                        publishProgress(doneBytes, force = true)
                        continue
                    }
                    val tmp = File(dir, "$name.part")
                    downloadFile(BASE + name, tmp) { d -> publishProgress(doneBytes + d) }
                    if (tmp.length() != expected) {
                        tmp.delete()
                        throw IOException("size mismatch for $name")
                    }
                    dst.delete()
                    if (!tmp.renameTo(dst)) throw IOException("rename failed for $name")
                    doneBytes += expected
                    publishProgress(doneBytes, force = true)
                }
                mainHandler.post {
                    HearingState.modelDownload = ModelDownloadState.Done
                    HearingState.offlineModelReady = true
                }
            } catch (_: Throwable) {
                publishState(ModelDownloadState.Failed)
            } finally {
                running = false
            }
        }, "ModelDownload").start()
    }

    private fun publishProgress(bytes: Long, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPublishAt < 300) return
        lastPublishAt = now
        val p = (bytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        publishState(ModelDownloadState.InProgress(p))
    }

    private fun publishState(state: ModelDownloadState) {
        mainHandler.post { HearingState.modelDownload = state }
    }

    private fun downloadFile(url: String, dst: File, onProgress: (Long) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input ->
                FileOutputStream(dst).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        onProgress(total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
