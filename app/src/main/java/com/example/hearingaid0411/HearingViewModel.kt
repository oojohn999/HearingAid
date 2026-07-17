package com.example.hearingaid0411

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel

/** 一句已定稿的字幕，含時間戳（為日後「對話紀錄」按日期/時間回看預留） */
data class CaptionEntry(
    val id: Long,
    val text: String,
    val timeMillis: Long,
)

/** 顯示在畫面上的錯誤訊息（取代 Toast 與靜默失敗） */
data class UiError(
    val message: String,
    val actionLabel: String? = null,
    val action: ErrorAction = ErrorAction.NONE,
)

enum class ErrorAction { NONE, OPEN_SETTINGS, RETRY }

class HearingViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val MIN_FONT_SP = 24f
        const val MAX_FONT_SP = 96f
        const val FONT_STEP_SP = 4f
        const val DEFAULT_FONT_SP = 40f
        private const val MAX_CAPTIONS = 200
        private const val KEY_FONT_SIZE = "font_size_sp"
    }

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 字幕流：由舊到新，新句接在底部 */
    val captions = mutableStateListOf<CaptionEntry>()

    /** 辨識中的暫時文字（partial result），只用於顯示，不進歷史 */
    var partialText by mutableStateOf("")
        private set

    /** 使用者「想要」聆聽（按下開始後即為 true，權限/引擎失敗才轉回 false） */
    var wantsListening by mutableStateOf(false)

    /** 引擎「實際」在聆聽（onReadyForSpeech 之後才為 true） */
    var isListening by mutableStateOf(false)

    /** 偵測到有人正在說話 */
    var isSpeaking by mutableStateOf(false)

    /** 麥克風音量等級 0-5，驅動音量條 */
    var rmsLevel by mutableStateOf(0)

    /** 畫面上的錯誤訊息；null 表示無錯誤 */
    var errorMessage by mutableStateOf<UiError?>(null)

    var fontSizeSp by mutableStateOf(prefs.getFloat(KEY_FONT_SIZE, DEFAULT_FONT_SP))
        private set

    private var nextId = 0L

    init {
        // 一次性隱私清理：舊版把逐字稿明文存在 user_speech_patterns，全部清除
        app.getSharedPreferences("user_speech_patterns", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    fun increaseFont() {
        if (fontSizeSp < MAX_FONT_SP) {
            fontSizeSp = (fontSizeSp + FONT_STEP_SP).coerceAtMost(MAX_FONT_SP)
            prefs.edit().putFloat(KEY_FONT_SIZE, fontSizeSp).apply()
        }
    }

    fun decreaseFont() {
        if (fontSizeSp > MIN_FONT_SP) {
            fontSizeSp = (fontSizeSp - FONT_STEP_SP).coerceAtLeast(MIN_FONT_SP)
            prefs.edit().putFloat(KEY_FONT_SIZE, fontSizeSp).apply()
        }
    }

    fun updatePartial(text: String) {
        partialText = text
    }

    /** 定稿一句話：進入字幕流（只跟最後一句比對去重，允許正常的重複句） */
    fun finalizeSentence(text: String) {
        val t = text.trim()
        partialText = ""
        if (t.isEmpty()) return
        if (captions.lastOrNull()?.text == t) return
        captions.add(CaptionEntry(nextId++, t, System.currentTimeMillis()))
        while (captions.size > MAX_CAPTIONS) captions.removeAt(0)
    }

    fun clearAll() {
        captions.clear()
        partialText = ""
    }
}
