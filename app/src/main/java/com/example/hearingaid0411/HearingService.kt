package com.example.hearingaid0411

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.hearingaid0411.data.AppDb
import com.example.hearingaid0411.data.CaptionDao
import com.example.hearingaid0411.data.CaptionRow
import com.example.hearingaid0411.data.SessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 前景服務（microphone 類型）：持有辨識引擎與擴音通路，
 * 讓熄屏/切到背景後字幕與擴音持續運作；並把定稿句子寫入 Room（對話紀錄）。
 *
 * Android 14+ 規則：此服務只能在 App 於前景時啟動（使用者按「開始聆聽」）。
 */
class HearingService : Service() {

    companion object {
        const val ACTION_START = "com.example.hearingaid0411.START"
        const val ACTION_STOP = "com.example.hearingaid0411.STOP"
        const val ACTION_AMP_ON = "com.example.hearingaid0411.AMP_ON"
        const val ACTION_AMP_OFF = "com.example.hearingaid0411.AMP_OFF"

        private const val CHANNEL_ID = "hearing_service"
        private const val NOTIFICATION_ID = 1

        /** 同一場對話的判定：兩句之間空白超過 15 分鐘就切成新的對話 */
        private const val SESSION_IDLE_SPLIT_MS = 15 * 60 * 1000L

        /** 對話紀錄預設保留 30 天 */
        private const val RETENTION_DAYS = 30
    }

    private lateinit var engine: CaptionEngine
    private lateinit var routeMonitor: RouteMonitor
    private lateinit var dao: CaptionDao
    private val amp = AmpEngine()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dbScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private var currentSessionId = -1L
    private var lastSentenceAt = 0L

    override fun onCreate() {
        super.onCreate()
        HearingState.init(this)
        dao = AppDb.get(this).dao()
        engine = CaptionEngine(this) { text -> onFinalSentence(text) }
        routeMonitor = RouteMonitor(this).apply {
            onHeadsetLost = {
                if (HearingState.ampRunning || HearingState.ampWanted) {
                    stopAmp()
                    HearingState.errorMessage = UiError(
                        message = "耳機拔掉了，擴音已自動關閉",
                        actionLabel = "知道了",
                        action = ErrorAction.NONE,
                    )
                }
            }
            start()
        }
        // 保留期限清理
        dbScope.launch {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -RETENTION_DAYS)
            val minDate = formatDateKey(cal.timeInMillis)
            dao.deleteCaptionsBefore(minDate)
            dao.deleteSessionsBefore(minDate)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
            }

            ACTION_AMP_ON -> startAmp()

            ACTION_AMP_OFF -> stopAmp()

            else -> { // ACTION_START 或 null（系統重啟服務）
                startAsForeground()
                HearingState.wantsListening = true
                HearingState.errorMessage = null
                engine.start()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopEverything()
        engine.destroy()
        routeMonitor.stop()
        dbScope.cancel()
        super.onDestroy()
    }

    // ---- 字幕定稿 → 記憶體 + Room 持久化（含場次切分） ----

    private fun onFinalSentence(text: String) {
        val entry = HearingState.finalizeSentence(text) ?: return
        dbScope.launch {
            ensureSession(entry.timeMillis)
            dao.insertCaption(
                CaptionRow(sessionId = currentSessionId, text = entry.text, timeMillis = entry.timeMillis)
            )
            dao.updateSessionEnd(currentSessionId, entry.timeMillis)
        }
    }

    private suspend fun ensureSession(now: Long) {
        if (currentSessionId < 0 || now - lastSentenceAt > SESSION_IDLE_SPLIT_MS) {
            val date = formatDateKey(now)
            val seq = dao.sessionCountOn(date) + 1
            currentSessionId = dao.insertSession(
                SessionEntity(date = date, seqInDay = seq, startedAt = now, endedAt = now)
            )
        }
        lastSentenceAt = now
    }

    // ---- 擴音 ----

    private fun startAmp() {
        if (HearingState.headsetRoute == HeadsetRoute.NONE) {
            // 安全規則：沒接耳機絕不放大（手機喇叭會嘯叫）
            HearingState.ampWanted = false
            HearingState.ampRunning = false
            return
        }
        HearingState.ampWanted = true
        val ok = amp.start()
        HearingState.ampRunning = ok
        if (!ok) {
            HearingState.ampWanted = false
            HearingState.errorMessage = UiError(
                message = "擴音開啟失敗（麥克風可能被其他功能占用），請再試一次",
                actionLabel = "知道了",
                action = ErrorAction.NONE,
            )
        }
    }

    private fun stopAmp() {
        HearingState.ampWanted = false
        HearingState.ampRunning = false
        amp.stop()
    }

    private fun stopEverything() {
        HearingState.wantsListening = false
        engine.stop()
        stopAmp()
    }

    // ---- 前景通知 ----

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "輔聽字幕", NotificationManager.IMPORTANCE_LOW).apply {
                description = "聆聽進行中的常駐通知"
            }
        )

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, HearingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("輔聽字幕運作中")
            .setContentText("正在聽聲音並顯示字幕")
            .setContentIntent(openIntent)
            .addAction(0, "停止", stopIntent)
            .setOngoing(true)
            .build()

        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
    }
}
