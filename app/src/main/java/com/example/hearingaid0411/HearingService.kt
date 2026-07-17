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
import com.example.hearingaid0411.asr.SherpaCaptionEngine
import com.example.hearingaid0411.asr.SherpaModelLocator
import com.example.hearingaid0411.asr.SpeakerIdentifier
import com.example.hearingaid0411.asr.VoiceprintStore
import com.example.hearingaid0411.audio.AmpSink
import com.example.hearingaid0411.audio.AudioEngine
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
 * 前景服務（microphone 類型）：持有辨識引擎、共享音源與擴音通路，
 * 讓熄屏/切到背景後字幕與擴音持續運作；並把定稿句子寫入 Room（對話紀錄）。
 *
 * 引擎自動選擇：
 * - 裝置上有 sherpa 離線模型 → SherpaCaptionEngine（單一麥克風分流，
 *   擴音與字幕真正同時、離線可用、輸出台灣繁體）
 * - 沒有模型 → CaptionEngine（系統 SpeechRecognizer 備援；擴音與字幕
 *   同開時受 Android 麥克風併發政策影響，行為依機型而異）
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

    private lateinit var asr: AsrEngine
    private var sherpaMode = false
    private var speakerId: SpeakerIdentifier? = null
    private val audioEngine = AudioEngine()
    private val ampSink = AmpSink()
    private lateinit var routeMonitor: RouteMonitor
    private lateinit var dao: CaptionDao

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dbScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private var currentSessionId = -1L
    private var lastSentenceAt = 0L

    override fun onCreate() {
        super.onCreate()
        HearingState.init(this)
        dao = AppDb.get(this).dao()

        val model = SherpaModelLocator.find(this)
        sherpaMode = model != null
        asr = if (model != null) {
            SherpaCaptionEngine.prewarmOpenCc()
            // 聲紋（若已註冊且模型就緒）：判定每句是否為「自己」說的
            speakerId = try {
                if (VoiceprintStore.modelReady(this) && VoiceprintStore.isEnrolled(this)) {
                    SpeakerIdentifier(
                        VoiceprintStore.modelFile(this).absolutePath,
                        VoiceprintStore.load(this),
                    )
                } else null
            } catch (_: Throwable) {
                null
            }
            SherpaCaptionEngine(
                model,
                audioEngine,
                identifySelf = speakerId?.let { id -> { samples -> id.isSelf(samples) } },
            ) { text, isSelf -> onFinalSentence(text, isSelf) }
        } else {
            CaptionEngine(this) { text -> onFinalSentence(text, false) }
        }

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

            else -> { // ACTION_START
                startAsForeground()
                HearingState.wantsListening = true
                HearingState.errorMessage = null
                if (sherpaMode) {
                    if (audioEngine.start()) {
                        asr.start()
                    } else {
                        HearingState.wantsListening = false
                        HearingState.errorMessage = UiError(
                            message = "麥克風開啟失敗，請再試一次",
                            actionLabel = "重試",
                            action = ErrorAction.RETRY,
                        )
                    }
                } else {
                    asr.start()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopEverything()
        asr.destroy()
        speakerId?.release()
        speakerId = null
        routeMonitor.stop()
        dbScope.cancel()
        super.onDestroy()
    }

    // ---- 字幕定稿 → 記憶體 + Room 持久化（含場次切分） ----

    private fun onFinalSentence(text: String, isSelf: Boolean) {
        val entry = HearingState.finalizeSentence(text, isSelf) ?: return
        dbScope.launch {
            ensureSession(entry.timeMillis)
            dao.insertCaption(
                CaptionRow(
                    sessionId = currentSessionId,
                    text = entry.text,
                    timeMillis = entry.timeMillis,
                    isSelf = entry.isSelf,
                )
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

    // ---- 擴音（掛在共享音源上） ----

    private fun startAmp() {
        if (HearingState.headsetRoute == HeadsetRoute.NONE) {
            // 安全規則：沒接耳機絕不放大（手機喇叭會嘯叫）
            HearingState.ampWanted = false
            HearingState.ampRunning = false
            return
        }
        HearingState.ampWanted = true
        val micOk = audioEngine.start()
        val trackOk = micOk && ampSink.open()
        if (trackOk) {
            audioEngine.addSink(ampSink)
            HearingState.ampRunning = true
        } else {
            HearingState.ampWanted = false
            HearingState.ampRunning = false
            if (!sherpaMode && !audioEngineNeeded()) audioEngine.stop()
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
        audioEngine.removeSink(ampSink)
        ampSink.close()
        // 備援模式下音源只服務擴音；擴音關了就釋放麥克風
        if (!audioEngineNeeded()) audioEngine.stop()
    }

    /** 共享音源目前是否還有人在用 */
    private fun audioEngineNeeded(): Boolean =
        (sherpaMode && HearingState.wantsListening) || HearingState.ampRunning

    private fun stopEverything() {
        HearingState.wantsListening = false
        asr.stop()
        HearingState.ampWanted = false
        HearingState.ampRunning = false
        audioEngine.removeSink(ampSink)
        ampSink.close()
        audioEngine.stop()
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
            .setContentText(if (sherpaMode) "正在聽聲音並顯示字幕（離線模式）" else "正在聽聲音並顯示字幕")
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
