package com.example.hearingaid0411

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: HearingViewModel by viewModels()

    private var speechRecognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())

    /** 辨識引擎狀態機：消除「排程中/已啟動」的競態 */
    private enum class RecState { IDLE, STARTING, LISTENING }

    private var recState = RecState.IDLE
    private var consecutiveErrors = 0
    private var lastPartialHandledAt = 0L
    private var lastRmsHandledAt = 0L

    companion object {
        private const val PARTIAL_THROTTLE_MS = 150L
        private const val RMS_THROTTLE_MS = 100L
        private const val MAX_NETWORK_RETRIES = 5
        private const val MAX_CLIENT_RETRIES = 2
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.errorMessage = null
            startListeningInternal()
        } else {
            viewModel.wantsListening = false
            viewModel.isListening = false
            viewModel.errorMessage = UiError(
                message = "需要麥克風權限，才能聽到聲音變成字幕",
                actionLabel = "去開啟權限",
                action = ErrorAction.OPEN_SETTINGS,
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            HearingAid0411Theme(dynamicColor = false) {
                // 聆聽中保持螢幕常亮（對話中螢幕熄滅是高齡使用者的致命傷）
                val view = LocalView.current
                LaunchedEffect(viewModel.isListening) {
                    view.keepScreenOn = viewModel.isListening
                }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HearingScreen(
                        modifier = Modifier.padding(innerPadding),
                        vm = viewModel,
                        onStartListening = { onUserStart() },
                        onStopListening = { onUserStop() },
                        onErrorAction = { action ->
                            when (action) {
                                ErrorAction.OPEN_SETTINGS -> openAppSettings()
                                ErrorAction.RETRY -> {
                                    viewModel.errorMessage = null
                                    onUserStart()
                                }
                                ErrorAction.NONE -> viewModel.errorMessage = null
                            }
                        },
                    )
                }
            }
        }
    }

    // ---- 使用者操作 ----

    private fun onUserStart() {
        if (viewModel.wantsListening) return
        viewModel.wantsListening = true
        viewModel.errorMessage = null
        if (hasMicPermission()) {
            startListeningInternal()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun onUserStop() {
        viewModel.wantsListening = false
        handler.removeCallbacksAndMessages(null)
        stopEngine()
        // 停止時把還沒定稿的字保留進字幕流，不讓最後一句消失
        if (viewModel.partialText.isNotBlank()) {
            viewModel.finalizeSentence(viewModel.partialText)
        }
    }

    // ---- 引擎控制 ----

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun stopEngine() {
        if (recState != RecState.IDLE) {
            try { speechRecognizer?.cancel() } catch (_: Exception) {}
            recState = RecState.IDLE
        }
        viewModel.isListening = false
        viewModel.isSpeaking = false
        viewModel.rmsLevel = 0
    }

    private fun stopWithError(error: UiError) {
        viewModel.wantsListening = false
        handler.removeCallbacksAndMessages(null)
        stopEngine()
        viewModel.errorMessage = error
    }

    private fun scheduleRestart(delayMs: Long) {
        handler.postDelayed({
            if (viewModel.wantsListening && recState == RecState.IDLE) startListeningInternal()
        }, delayMs)
    }

    private fun startListeningInternal() {
        if (!viewModel.wantsListening || recState != RecState.IDLE) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopWithError(UiError("這支手機沒有語音辨識功能，字幕無法使用"))
            return
        }
        val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(recognitionListener)
            speechRecognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // 明確指定台灣繁中（原本傳 Locale 物件會被辨識服務忽略）
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 750)
        }
        try {
            recognizer.startListening(intent)
            recState = RecState.STARTING
        } catch (e: Exception) {
            recState = RecState.IDLE
            handleError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            recState = RecState.LISTENING
            viewModel.isListening = true
            viewModel.errorMessage = null
        }

        override fun onBeginningOfSpeech() {
            viewModel.isSpeaking = true
        }

        override fun onRmsChanged(rmsdB: Float) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRmsHandledAt < RMS_THROTTLE_MS) return
            lastRmsHandledAt = now
            viewModel.rmsLevel = ((rmsdB + 2f) / 2.4f).toInt().coerceIn(0, 5)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            viewModel.isSpeaking = false
        }

        override fun onError(error: Int) {
            handleError(error)
        }

        override fun onResults(results: Bundle?) {
            recState = RecState.IDLE
            viewModel.isSpeaking = false
            consecutiveErrors = 0
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                viewModel.finalizeSentence(text)
            } else if (viewModel.partialText.isNotBlank()) {
                viewModel.finalizeSentence(viewModel.partialText)
            }
            // 立即重啟，消除原本 cancel+200ms+300ms 造成的每句 500ms 收音空窗
            if (viewModel.wantsListening) startListeningInternal()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            consecutiveErrors = 0
            val now = SystemClock.elapsedRealtime()
            if (now - lastPartialHandledAt < PARTIAL_THROTTLE_MS) return
            lastPartialHandledAt = now
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) viewModel.updatePartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** 按錯誤種類分別處理：可恢復的重試、不可恢復的停止並顯示大字說明 */
    private fun handleError(error: Int) {
        recState = RecState.IDLE
        viewModel.isSpeaking = false
        if (!viewModel.wantsListening) return
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                // 一段時間沒人說話是常態，直接重啟繼續聽
                consecutiveErrors = 0
                startListeningInternal()
            }

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                try { speechRecognizer?.cancel() } catch (_: Exception) {}
                scheduleRestart(500)
            }

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stopWithError(
                UiError(
                    message = "需要麥克風權限，才能聽到聲音變成字幕",
                    actionLabel = "去開啟權限",
                    action = ErrorAction.OPEN_SETTINGS,
                )
            )

            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> {
                consecutiveErrors++
                if (consecutiveErrors <= MAX_NETWORK_RETRIES) {
                    val backoff = (1000L shl (consecutiveErrors - 1)).coerceAtMost(8000L)
                    scheduleRestart(backoff)
                } else {
                    stopWithError(
                        UiError(
                            message = "沒有網路，暫時聽不到。請確認網路後再試一次",
                            actionLabel = "重試",
                            action = ErrorAction.RETRY,
                        )
                    )
                }
            }

            else -> {
                // ERROR_CLIENT、ERROR_AUDIO 等：重試少數幾次後放棄並明確告知
                consecutiveErrors++
                if (consecutiveErrors <= MAX_CLIENT_RETRIES) {
                    scheduleRestart(300)
                } else {
                    stopWithError(
                        UiError(
                            message = "語音辨識出了問題，請再按一次「開始聆聽」",
                            actionLabel = "重試",
                            action = ErrorAction.RETRY,
                        )
                    )
                }
            }
        }
    }

    // ---- 生命週期 ----

    override fun onStart() {
        super.onStart()
        // 回到前景：若使用者先前在聆聽，自動續聽
        if (viewModel.wantsListening && recState == RecState.IDLE && hasMicPermission()) {
            startListeningInternal()
        }
    }

    override fun onStop() {
        super.onStop()
        // 進背景：停止引擎與所有排程重試（Android 9+ 背景不能收音，避免無限重試空轉）
        // 保留 wantsListening，回前景自動續聽
        handler.removeCallbacksAndMessages(null)
        if (recState != RecState.IDLE) {
            try { speechRecognizer?.cancel() } catch (_: Exception) {}
            recState = RecState.IDLE
        }
        viewModel.isListening = false
        viewModel.isSpeaking = false
        viewModel.rmsLevel = 0
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { speechRecognizer?.setRecognitionListener(null) } catch (_: Exception) {}
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        speechRecognizer = null
        super.onDestroy()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }
}

// ============================== UI ==============================

@Composable
fun HearingScreen(
    modifier: Modifier = Modifier,
    vm: HearingViewModel,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onErrorAction: (ErrorAction) -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    // 高對比操作色（過 WCAG AA）：綠=開始/正常、紅=停止/說話中
    val actionGreen = if (isDark) Color(0xFFA5D6A7) else Color(0xFF1B5E20)
    val onActionGreen = if (isDark) Color(0xFF0D3B12) else Color.White
    val stopRed = if (isDark) Color(0xFFF2B8B5) else Color(0xFFB3261E)
    val onStopRed = if (isDark) Color(0xFF601410) else Color.White

    var showClearDialog by remember { mutableStateOf(false) }
    var lastActionAt by remember { mutableStateOf(0L) }

    Column(modifier = modifier.fillMaxSize()) {

        // ---- 頂列：清除 + 字級調整 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (vm.captions.isNotEmpty() || vm.partialText.isNotBlank()) {
                OutlinedButton(
                    onClick = { showClearDialog = true },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("清除", fontSize = 20.sp)
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { vm.decreaseFont() },
                enabled = vm.fontSizeSp > HearingViewModel.MIN_FONT_SP,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("字小", fontSize = 20.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(
                onClick = { vm.increaseFont() },
                enabled = vm.fontSizeSp < HearingViewModel.MAX_FONT_SP,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("字大", fontSize = 20.sp)
            }
        }

        // ---- 狀態列：狀態燈 + 文字 + 音量條（不只靠顏色傳達狀態） ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val statusColor = when {
                !vm.wantsListening -> MaterialTheme.colorScheme.outline
                vm.isSpeaking -> stopRed
                vm.isListening -> actionGreen
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
                    !vm.wantsListening -> "已停止"
                    vm.isSpeaking -> "有聽到聲音…"
                    vm.isListening -> "正在聽…"
                    else -> "準備中…"
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            RmsBars(level = vm.rmsLevel, active = vm.isListening, activeColor = actionGreen)
        }

        // ---- 錯誤訊息（畫面大字，取代 Toast 與靜默失敗） ----
        vm.errorMessage?.let { err ->
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

        // ---- 字幕流（新句在底部，自動捲動；上滑閱讀時暫停並顯示「回到最新」） ----
        Box(modifier = Modifier.weight(1f)) {
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val totalCount = vm.captions.size + if (vm.partialText.isNotBlank()) 1 else 0

            val nearBottom by remember {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                    lastVisible >= info.totalItemsCount - 2
                }
            }

            LaunchedEffect(vm.captions.size, vm.partialText) {
                if (totalCount > 0 && nearBottom) {
                    listState.animateScrollToItem(totalCount - 1)
                }
            }

            if (totalCount == 0 && vm.errorMessage == null) {
                Text(
                    text = if (vm.wantsListening) "正在聽，請說話…" else "按下面的綠色按鈕，開始聽",
                    fontSize = 26.sp,
                    lineHeight = 36.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                )
            }

            val timeFormat = remember { SimpleDateFormat("a h:mm", Locale.TAIWAN) }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            ) {
                itemsIndexed(vm.captions, key = { _, e -> e.id }) { index, entry ->
                    val showTime = index == 0 ||
                        minuteOf(vm.captions[index - 1].timeMillis) != minuteOf(entry.timeMillis)
                    if (showTime) {
                        Text(
                            text = timeFormat.format(Date(entry.timeMillis)),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                        )
                    }
                    Text(
                        text = entry.text,
                        fontSize = vm.fontSizeSp.sp,
                        lineHeight = (vm.fontSizeSp * 1.4f).sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    )
                }
                if (vm.partialText.isNotBlank()) {
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
                                text = vm.partialText + "…",
                                fontSize = vm.fontSizeSp.sp,
                                lineHeight = (vm.fontSizeSp * 1.4f).sp,
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

        // ---- 開始/停止大按鈕（96dp，高齡觸控目標） ----
        Button(
            onClick = {
                val now = SystemClock.elapsedRealtime()
                if (now - lastActionAt < 600) return@Button // 防連點
                lastActionAt = now
                if (vm.wantsListening) onStopListening() else onStartListening()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (vm.wantsListening) stopRed else actionGreen,
                contentColor = if (vm.wantsListening) onStopRed else onActionGreen,
            ),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .heightIn(min = 96.dp),
        ) {
            Text(
                text = if (vm.wantsListening) "■ 停止" else "▶ 開始聆聽",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    // ---- 清除確認對話框（防誤觸一鍵全毀） ----
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = {
                Text("確定要清除所有字幕嗎？", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Text("清除後就看不到之前的內容了", fontSize = 18.sp, lineHeight = 26.sp)
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.clearAll()
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
}

/** 音量條：5 格，依 RMS 等級點亮 */
@Composable
private fun RmsBars(level: Int, active: Boolean, activeColor: Color) {
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

private fun minuteOf(timeMillis: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = timeMillis
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}
