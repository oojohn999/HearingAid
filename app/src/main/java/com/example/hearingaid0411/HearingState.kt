package com.example.hearingaid0411

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 一句已定稿的字幕，含時間戳（對話紀錄回看用）與「自己說的」標記 */
data class CaptionEntry(
    val id: Long,
    val text: String,
    val timeMillis: Long,
    val isSelf: Boolean = false,
)

/** 顯示在畫面上的錯誤/提示訊息（取代 Toast 與靜默失敗） */
data class UiError(
    val message: String,
    val actionLabel: String? = null,
    val action: ErrorAction = ErrorAction.NONE,
)

enum class ErrorAction { NONE, OPEN_SETTINGS, RETRY }

/** 目前的耳機輸出路由（決定擴音能否開啟與延遲提示） */
enum class HeadsetRoute { NONE, WIRED, BLUETOOTH, BLE }

/**
 * 全 App 共用的 UI 狀態單例：前景服務寫入、Compose UI 讀取。
 * 服務與 Activity 同一 process、狀態更新都在主執行緒。
 */
object HearingState {

    const val MIN_FONT_SP = 24f
    const val MAX_FONT_SP = 96f
    const val FONT_STEP_SP = 4f
    const val DEFAULT_FONT_SP = 40f
    private const val MAX_CAPTIONS = 200
    private const val KEY_FONT_SIZE = "font_size_sp"

    /** 本次使用中的字幕流（記憶體；持久化由 HearingService 寫入 Room） */
    val captions = mutableStateListOf<CaptionEntry>()

    var partialText by mutableStateOf("")
        private set

    /** 使用者「想要」聆聽（按下開始後即為 true，權限/引擎失敗才轉回 false） */
    var wantsListening by mutableStateOf(false)

    /** 引擎「實際」在聆聽（onReadyForSpeech 之後才為 true） */
    var isListening by mutableStateOf(false)

    var isSpeaking by mutableStateOf(false)

    /** 麥克風音量等級 0-5 */
    var rmsLevel by mutableStateOf(0)

    var errorMessage by mutableStateOf<UiError?>(null)

    /** 擴音：使用者開關意圖與實際運轉狀態 */
    var ampWanted by mutableStateOf(false)
    var ampRunning by mutableStateOf(false)

    var headsetRoute by mutableStateOf(HeadsetRoute.NONE)

    /** 離線辨識模型是否已就緒（App 啟動與下載完成時更新） */
    var offlineModelReady by mutableStateOf(false)

    /** 模型下載狀態（null = 尚未開始） */
    var modelDownload by mutableStateOf<com.example.hearingaid0411.asr.ModelDownloadState?>(null)

    /** 本次啟動中使用者按了「先不要」隱藏下載卡片 */
    var modelBannerDismissed by mutableStateOf(false)

    var fontSizeSp by mutableStateOf(DEFAULT_FONT_SP)
        private set

    private var prefs: SharedPreferences? = null
    private var nextId = 0L

    /** 由 Activity 與 Service 的 onCreate 呼叫，重複呼叫無害 */
    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE).also {
            fontSizeSp = it.getFloat(KEY_FONT_SIZE, DEFAULT_FONT_SP)
        }
        // 一次性隱私清理：舊版把逐字稿明文存在 user_speech_patterns
        app.getSharedPreferences("user_speech_patterns", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    fun increaseFont() {
        if (fontSizeSp < MAX_FONT_SP) {
            fontSizeSp = (fontSizeSp + FONT_STEP_SP).coerceAtMost(MAX_FONT_SP)
            prefs?.edit()?.putFloat(KEY_FONT_SIZE, fontSizeSp)?.apply()
        }
    }

    fun decreaseFont() {
        if (fontSizeSp > MIN_FONT_SP) {
            fontSizeSp = (fontSizeSp - FONT_STEP_SP).coerceAtLeast(MIN_FONT_SP)
            prefs?.edit()?.putFloat(KEY_FONT_SIZE, fontSizeSp)?.apply()
        }
    }

    fun updatePartial(text: String) {
        partialText = text
    }

    /** 定稿一句話；回傳新增的項目（若因重複被略過則回傳 null） */
    fun finalizeSentence(text: String, isSelf: Boolean = false): CaptionEntry? {
        val t = text.trim()
        partialText = ""
        if (t.isEmpty()) return null
        if (captions.lastOrNull()?.text == t) return null
        val entry = CaptionEntry(nextId++, t, System.currentTimeMillis(), isSelf)
        captions.add(entry)
        while (captions.size > MAX_CAPTIONS) captions.removeAt(0)
        return entry
    }

    fun clearLive() {
        captions.clear()
        partialText = ""
    }
}

// ---- 時間格式工具（台灣口語格式） ----

private val clockFmt = SimpleDateFormat("a h:mm", Locale.TAIWAN)
private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.TAIWAN)
private val dateLabelFmt = SimpleDateFormat("M月d日 EEEE", Locale.TAIWAN)

/** 「下午 3:07」 */
fun formatClock(timeMillis: Long): String = clockFmt.format(Date(timeMillis))

/** "2026-07-17"（可直接做字串排序/比較） */
fun formatDateKey(timeMillis: Long): String = dateFmt.format(Date(timeMillis))

/** 「今天」「昨天」「7月15日 星期三」 */
fun formatDateLabel(dateKey: String): String {
    val today = Calendar.getInstance()
    val todayKey = dateFmt.format(today.time)
    today.add(Calendar.DAY_OF_YEAR, -1)
    val yesterdayKey = dateFmt.format(today.time)
    return when (dateKey) {
        todayKey -> "今天"
        yesterdayKey -> "昨天"
        else -> try {
            dateLabelFmt.format(dateFmt.parse(dateKey)!!)
        } catch (_: Exception) {
            dateKey
        }
    }
}

fun minuteOf(timeMillis: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = timeMillis
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}
