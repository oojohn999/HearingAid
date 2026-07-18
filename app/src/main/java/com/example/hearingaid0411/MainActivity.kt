package com.example.hearingaid0411

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.hearingaid0411.ui.theme.HearingAid0411Theme
import kotlinx.coroutines.launch

/** 簡易畫面導覽（不引入 nav library，長輩情境層級淺） */
sealed interface Screen {
    data object Main : Screen
    data object Dates : Screen
    data class Sessions(val date: String) : Screen
    data class Detail(val date: String, val sessionId: Long, val title: String) : Screen
    data object Settings : Screen
    data object Voiceprint : Screen
}

class MainActivity : ComponentActivity() {

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            HearingState.errorMessage = null
            startListeningService()
        } else {
            HearingState.wantsListening = false
            HearingState.errorMessage = UiError(
                message = "需要麥克風權限，才能聽到聲音變成字幕",
                actionLabel = "去開啟權限",
                action = ErrorAction.OPEN_SETTINGS,
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HearingState.init(this)
        HearingState.offlineModelReady =
            com.example.hearingaid0411.asr.SherpaModelLocator.find(this) != null
        enableEdgeToEdge()

        setContent {
            HearingAid0411Theme(dynamicColor = false) {
                val view = LocalView.current
                LaunchedEffect(HearingState.wantsListening) {
                    view.keepScreenOn = HearingState.wantsListening
                }

                var screen by remember { mutableStateOf<Screen>(Screen.Main) }
                BackHandler(enabled = screen != Screen.Main) {
                    screen = when (val s = screen) {
                        is Screen.Detail -> Screen.Sessions(s.date)
                        is Screen.Sessions -> Screen.Dates
                        Screen.Voiceprint -> Screen.Settings
                        else -> Screen.Main
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when (val s = screen) {
                        Screen.Main -> HearingScreen(
                            modifier = Modifier.padding(innerPadding),
                            onStartListening = { onUserStart() },
                            onStopListening = { onUserStop() },
                            onToggleAmp = { onToggleAmp() },
                            onOpenHistory = { screen = Screen.Dates },
                            onOpenSettings = { screen = Screen.Settings },
                            onErrorAction = { action ->
                                when (action) {
                                    ErrorAction.OPEN_SETTINGS -> openAppSettings()
                                    ErrorAction.RETRY -> {
                                        HearingState.errorMessage = null
                                        onUserStart()
                                    }
                                    ErrorAction.NONE -> HearingState.errorMessage = null
                                }
                            },
                        )

                        Screen.Dates -> DatesScreen(
                            modifier = Modifier.padding(innerPadding),
                            onBack = { screen = Screen.Main },
                            onOpenDate = { date -> screen = Screen.Sessions(date) },
                        )

                        is Screen.Sessions -> SessionsScreen(
                            modifier = Modifier.padding(innerPadding),
                            date = s.date,
                            onBack = { screen = Screen.Dates },
                            onOpenSession = { summary ->
                                screen = Screen.Detail(
                                    date = s.date,
                                    sessionId = summary.id,
                                    title = "第 ${summary.seqInDay} 個對話",
                                )
                            },
                        )

                        is Screen.Detail -> DetailScreen(
                            modifier = Modifier.padding(innerPadding),
                            sessionId = s.sessionId,
                            title = s.title,
                            onBack = { screen = Screen.Sessions(s.date) },
                        )

                        Screen.Settings -> SettingsScreen(
                            modifier = Modifier.padding(innerPadding),
                            onBack = { screen = Screen.Main },
                            onOpenVoiceprint = { screen = Screen.Voiceprint },
                        )

                        Screen.Voiceprint -> VoiceprintScreen(
                            modifier = Modifier.padding(innerPadding),
                            onBack = { screen = Screen.Settings },
                        )
                    }
                }
            }
        }
    }

    // ---- 服務控制 ----

    private fun onUserStart() {
        if (HearingState.wantsListening) return
        HearingState.errorMessage = null
        if (hasMicPermission()) {
            startListeningService()
        } else {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissionsLauncher.launch(perms.toTypedArray())
        }
    }

    private fun startListeningService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, HearingService::class.java).setAction(HearingService.ACTION_START)
        )
    }

    private fun onUserStop() {
        startService(Intent(this, HearingService::class.java).setAction(HearingService.ACTION_STOP))
    }

    private fun onToggleAmp() {
        val action =
            if (HearingState.ampRunning) HearingService.ACTION_AMP_OFF else HearingService.ACTION_AMP_ON
        startService(Intent(this, HearingService::class.java).setAction(action))
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }
}

// ============================== 主畫面 ==============================

@Composable
fun HearingScreen(
    modifier: Modifier = Modifier,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onToggleAmp: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onErrorAction: (ErrorAction) -> Unit,
) {
    val st = HearingState
    val isDark = isSystemInDarkTheme()
    val actionGreen = if (isDark) Color(0xFFA5D6A7) else Color(0xFF1B5E20)
    val onActionGreen = if (isDark) Color(0xFF0D3B12) else Color.White
    val stopRed = if (isDark) Color(0xFFF2B8B5) else Color(0xFFB3261E)
    val onStopRed = if (isDark) Color(0xFF601410) else Color.White

    var showClearDialog by remember { mutableStateOf(false) }
    var ampInfoDialog by remember { mutableStateOf<String?>(null) }
    var lastActionAt by remember { mutableStateOf(0L) }

    Column(modifier = modifier.fillMaxSize()) {

        // ---- 頂列：紀錄／清除／字級 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onOpenHistory,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("紀錄", fontSize = 20.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("設定", fontSize = 20.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (st.captions.isNotEmpty() || st.partialText.isNotBlank()) {
                OutlinedButton(
                    onClick = { showClearDialog = true },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("清除", fontSize = 20.sp)
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { st.decreaseFont() },
                enabled = st.fontSizeSp > HearingState.MIN_FONT_SP,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("字小", fontSize = 20.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(
                onClick = { st.increaseFont() },
                enabled = st.fontSizeSp < HearingState.MAX_FONT_SP,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("字大", fontSize = 20.sp)
            }
        }

        // ---- 狀態列 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val statusColor = when {
                !st.wantsListening -> MaterialTheme.colorScheme.outline
                st.isSpeaking -> stopRed
                st.isListening -> actionGreen
                else -> MaterialTheme.colorScheme.outline
            }
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(statusColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = when {
                    !st.wantsListening -> "已停止"
                    st.isSpeaking -> "有聽到聲音…"
                    st.isListening -> "正在聽…"
                    else -> "準備中…"
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            RmsBars(level = st.rmsLevel, active = st.isListening, activeColor = actionGreen)
        }

        // ---- 離線字幕下載卡 ----
        ModelDownloadCard()

        // ---- 錯誤/提示訊息 ----
        st.errorMessage?.let { err ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = err.message,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 30.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    if (err.actionLabel != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { onErrorAction(err.action) },
                            modifier = Modifier.heightIn(min = 56.dp),
                        ) {
                            Text(err.actionLabel, fontSize = 20.sp)
                        }
                    }
                }
            }
        }

        // ---- 字幕流 ----
        Box(modifier = Modifier.weight(1f)) {
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val totalCount = st.captions.size + if (st.partialText.isNotBlank()) 1 else 0

            val nearBottom by remember {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                    lastVisible >= info.totalItemsCount - 2
                }
            }

            LaunchedEffect(st.captions.size, st.partialText) {
                if (totalCount > 0 && nearBottom) {
                    listState.animateScrollToItem(totalCount - 1)
                }
            }

            if (totalCount == 0 && st.errorMessage == null) {
                Text(
                    text = if (st.wantsListening) "正在聽，請說話…\n\n手機放在說話的人附近，效果最好"
                    else "按下面的綠色按鈕，開始聽",
                    fontSize = 26.sp,
                    lineHeight = 38.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            ) {
                itemsIndexed(st.captions, key = { _, e -> e.id }) { index, entry ->
                    val showTime = index == 0 ||
                        minuteOf(st.captions[index - 1].timeMillis) != minuteOf(entry.timeMillis)
                    if (showTime) {
                        Text(
                            text = formatClock(entry.timeMillis),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                        )
                    }
                    val fs = if (entry.isSelf) st.fontSizeSp * 0.85f else st.fontSizeSp
                    Text(
                        text = if (entry.isSelf) "（我）${entry.text}" else entry.text,
                        fontSize = fs.sp,
                        lineHeight = (fs * 1.4f).sp,
                        fontWeight = FontWeight.Medium,
                        color = if (entry.isSelf) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    )
                }
                if (st.partialText.isNotBlank()) {
                    item(key = "partial") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .padding(vertical = 6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .fillMaxHeight()
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(2.dp)
                                    )
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = st.partialText + "…",
                                fontSize = st.fontSizeSp.sp,
                                lineHeight = (st.fontSizeSp * 1.4f).sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            if (totalCount > 0 && !nearBottom) {
                Button(
                    onClick = { scope.launch { listState.scrollToItem(totalCount - 1) } },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .heightIn(min = 56.dp),
                ) {
                    Text("↓ 回到最新", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ---- 擴音大開關卡 ----
        val route = st.headsetRoute
        val ampOn = st.ampRunning
        val ampEnabled = st.wantsListening && route != HeadsetRoute.NONE
        Surface(
            onClick = {
                when {
                    !st.wantsListening ->
                        ampInfoDialog = "請先按下面的綠色按鈕開始聆聽，再開擴音"
                    route == HeadsetRoute.NONE ->
                        ampInfoDialog = "要先接上耳機（有線或藍牙）才能開擴音。\n沒接耳機直接放大，手機喇叭會發出尖叫聲。"
                    else -> onToggleAmp()
                }
            },
            shape = RoundedCornerShape(20.dp),
            color = when {
                ampOn -> actionGreen
                !ampEnabled -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surface
            },
            border = if (!ampOn) androidx.compose.foundation.BorderStroke(
                2.dp, MaterialTheme.colorScheme.outline
            ) else null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp)
                .heightIn(min = 80.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            ampOn -> "擴音：開"
                            route == HeadsetRoute.NONE -> "擴音：要先接耳機"
                            else -> "擴音：關"
                        },
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (ampOn) onActionGreen else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when {
                            !st.wantsListening -> "先按開始聆聽，再開擴音"
                            route == HeadsetRoute.NONE -> "沒接耳機不能開（避免尖叫聲）"
                            ampOn && route == HeadsetRoute.BLUETOOTH -> "⚠ 藍牙耳機聲音會慢半拍"
                            ampOn -> "聲音正在放大到耳機"
                            route == HeadsetRoute.BLUETOOTH -> "按一下開擴音（藍牙會慢半拍）"
                            else -> "按一下，耳機會變大聲"
                        },
                        fontSize = 16.sp,
                        color = if (ampOn) onActionGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 視覺開關軌道
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(36.dp)
                        .background(
                            if (ampOn) onActionGreen.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(18.dp)
                        ),
                    contentAlignment = if (ampOn) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(28.dp)
                            .background(
                                if (ampOn) onActionGreen else MaterialTheme.colorScheme.outline,
                                CircleShape
                            )
                    )
                }
            }
        }

        // ---- 開始/停止大按鈕 ----
        Button(
            onClick = {
                val now = SystemClock.elapsedRealtime()
                if (now - lastActionAt < 600) return@Button
                lastActionAt = now
                if (st.wantsListening) onStopListening() else onStartListening()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (st.wantsListening) stopRed else actionGreen,
                contentColor = if (st.wantsListening) onStopRed else onActionGreen,
            ),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .heightIn(min = 96.dp),
        ) {
            Text(
                text = if (st.wantsListening) "■ 停止" else "▶ 開始聆聽",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    // ---- 對話框 ----
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = {
                Text("確定要清除畫面上的字幕嗎？", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "只會清除目前畫面，之前的內容仍可在「紀錄」裡找到",
                    fontSize = 18.sp, lineHeight = 26.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        HearingState.clearLive()
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("清除", fontSize = 20.sp)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showClearDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("取消", fontSize = 20.sp)
                }
            },
        )
    }

    ampInfoDialog?.let { message ->
        AlertDialog(
            onDismissRequest = { ampInfoDialog = null },
            title = { Text("擴音", fontSize = 22.sp, fontWeight = FontWeight.Bold) },
            text = { Text(message, fontSize = 18.sp, lineHeight = 26.sp) },
            confirmButton = {
                Button(
                    onClick = { ampInfoDialog = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("知道了", fontSize = 20.sp)
                }
            },
        )
    }
}

/** 離線字幕模型下載卡：未安裝→邀請下載；下載中→進度條；完成/失敗→提示 */
@Composable
private fun ModelDownloadCard() {
    val st = HearingState
    val context = androidx.compose.ui.platform.LocalContext.current
    val dl = st.modelDownload

    // 已就緒且沒有待顯示的完成訊息 → 不佔畫面
    if (st.offlineModelReady && dl !is com.example.hearingaid0411.asr.ModelDownloadState.Done) return
    if (!st.offlineModelReady && dl == null && st.modelBannerDismissed) return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (dl) {
                is com.example.hearingaid0411.asr.ModelDownloadState.InProgress -> {
                    val percent = (dl.progress * 100).toInt()
                    Text(
                        text = "正在下載離線字幕… $percent%",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { dl.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "下載時請不要關閉 App",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }

                is com.example.hearingaid0411.asr.ModelDownloadState.Done -> {
                    Text(
                        text = "離線字幕安裝完成！",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = "下次按「開始聆聽」就會使用，沒有網路也能看字幕",
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { st.modelDownload = null },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("知道了", fontSize = 18.sp)
                    }
                }

                is com.example.hearingaid0411.asr.ModelDownloadState.Failed -> {
                    Text(
                        text = "下載失敗，請檢查網路後再試",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row {
                        Button(
                            onClick = { com.example.hearingaid0411.asr.ModelDownloader.start(context) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("重試", fontSize = 18.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        OutlinedButton(
                            onClick = { st.modelDownload = null },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("先不要", fontSize = 18.sp)
                        }
                    }
                }

                null -> {
                    Text(
                        text = "安裝離線字幕（約 76 MB）",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = "裝了以後沒有網路也能看字幕，建議在 Wi-Fi 環境下載",
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row {
                        Button(
                            onClick = { com.example.hearingaid0411.asr.ModelDownloader.start(context) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("下載", fontSize = 18.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        OutlinedButton(
                            onClick = { st.modelBannerDismissed = true },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("先不要", fontSize = 18.sp)
                        }
                    }
                }
            }
        }
    }
}

/** 音量條：5 格，依 RMS 等級點亮 */
@Composable
fun RmsBars(level: Int, active: Boolean, activeColor: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        val heights = listOf(8, 12, 16, 20, 24)
        heights.forEachIndexed { i, h ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .width(8.dp)
                    .height(h.dp)
                    .background(
                        if (active && level > i) activeColor
                        else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}
