package com.example.hearingaid0411.asr

import android.content.Context
import java.io.File

/**
 * 離線串流辨識模型的檔案位置。
 * 模型不打包進 APK：放在 App 外部私有目錄，開發期用 adb push、
 * 正式版走首次啟動下載（Phase 2 後續）。
 *
 * 目錄：/sdcard/Android/data/<pkg>/files/sherpa-model/
 * 需要檔案：encoder*.onnx、decoder*.onnx、joiner*.onnx、tokens.txt
 * （encoder/joiner 優先挑 int8 版本）
 */
data class SherpaModel(
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
)

object SherpaModelLocator {

    fun modelDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "sherpa-model")

    /** 找齊四個檔案才回傳；否則 null（走 SpeechRecognizer 備援） */
    fun find(context: Context): SherpaModel? {
        val dir = modelDir(context)
        if (!dir.isDirectory) return null
        val files = dir.listFiles() ?: return null

        fun pick(prefix: String): File? {
            val candidates = files.filter {
                it.name.startsWith(prefix) && it.name.endsWith(".onnx")
            }
            return candidates.firstOrNull { it.name.contains("int8") } ?: candidates.firstOrNull()
        }

        val encoder = pick("encoder") ?: return null
        val decoder = files.firstOrNull {
            it.name.startsWith("decoder") && it.name.endsWith(".onnx") && !it.name.contains("int8")
        } ?: pick("decoder") ?: return null
        val joiner = pick("joiner") ?: return null
        val tokens = files.firstOrNull { it.name == "tokens.txt" } ?: return null

        return SherpaModel(
            encoder = encoder.absolutePath,
            decoder = decoder.absolutePath,
            joiner = joiner.absolutePath,
            tokens = tokens.absolutePath,
        )
    }
}
