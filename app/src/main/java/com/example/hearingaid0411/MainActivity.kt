package com.example.hearingaid0411

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.hearingaid0411.ui.theme.HearingAid0411Theme
import java.util.*

class MainActivity : ComponentActivity() {
    private lateinit var speechRecognizer: SpeechRecognizer
    private var isListening = false
    
    // 共享數據狀態
    private val currentTextState = mutableStateOf("")
    private val historyTextState = mutableStateListOf<String>()
    private val isListeningState = mutableStateOf(false)
    private val isSpeakingState = mutableStateOf(false)
    
    // 用於處理停頓檢測
    private val handler = Handler(Looper.getMainLooper())
    private var pauseRunnable: Runnable? = null
    private val PAUSE_DELAY = 3000L // 3秒停頓視為句子結束
    
    // 用於處理手動添加到歷史
    private var lastRecognizedText = ""
    private var hasNewSentence = false
    private var previousText = "" // 新增變數，用於存儲上一句話
    
    // 用於背景確認最後一句話
    private var autoSaveRunnable: Runnable? = null
    private val AUTO_SAVE_DELAY = 5000L // 5秒後自動保存

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startListening()
            isListeningState.value = true
        } else {
            Toast.makeText(
                this,
                "需要麥克風權限才能使用語音辨識功能",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize speech recognizer
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        
        // 初始化自動保存功能
        autoSaveRunnable = Runnable {
            if (currentTextState.value.isNotEmpty() && !historyTextState.contains(currentTextState.value)) {
                addToHistory(currentTextState.value)
                // 不清空當前文字，保留在畫面上
            }
        }

        setContent {
            HearingAid0411Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HearingAidApp(
                        modifier = Modifier.padding(innerPadding),
                        onStartListening = { 
                            checkPermissionAndStartListening() 
                            isListeningState.value = true
                        },
                        onStopListening = { 
                            stopListening() 
                            isListeningState.value = false
                        },
                        onAddToHistory = {
                            // 手動將當前文字添加到歷史並清空
                            if (currentTextState.value.isNotEmpty()) {
                                addToHistory(currentTextState.value)
                                lastRecognizedText = ""
                                currentTextState.value = ""
                                hasNewSentence = false
                            }
                        },
                        onClearHistory = {
                            // 清除所有歷史記錄
                            historyTextState.clear()
                        },
                        currentText = currentTextState.value,
                        textHistory = historyTextState.toList(),
                        isListening = isListeningState.value,
                        isSpeaking = isSpeakingState.value
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // 在應用退出前確保最後一句話被保存
        if (currentTextState.value.isNotEmpty() && !historyTextState.contains(currentTextState.value)) {
            addToHistory(currentTextState.value)
        }
        
        speechRecognizer.destroy()
        pauseRunnable?.let { handler.removeCallbacks(it) }
        autoSaveRunnable?.let { handler.removeCallbacks(it) }
    }

    override fun onPause() {
        super.onPause()
        
        // 在應用暫停時觸發自動保存
        autoSaveRunnable?.let { handler.removeCallbacks(it) }
        if (currentTextState.value.isNotEmpty()) {
            handler.postDelayed(autoSaveRunnable!!, AUTO_SAVE_DELAY)
        }
    }

    override fun onResume() {
        super.onResume()
        
        // 取消任何待處理的自動保存
        autoSaveRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun checkPermissionAndStartListening() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        if (isListening) return

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Toast.makeText(this@MainActivity, "準備聆聽...", Toast.LENGTH_SHORT).show()
            }
            override fun onBeginningOfSpeech() {
                // 開始說話時
                isSpeakingState.value = true
                // 取消任何待處理的停頓檢測
                pauseRunnable?.let { handler.removeCallbacks(it) }
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                // 當檢測到語音結束時
                isSpeakingState.value = false
                
                // 不要立即清空，讓用戶有時間看到最後的文字
                // 只有當文字變化時才設置停頓檢測
                if (currentTextState.value.isNotEmpty() && currentTextState.value != lastRecognizedText) {
                    lastRecognizedText = currentTextState.value
                    hasNewSentence = true
                    
                    // 設置停頓檢測
                    pauseRunnable?.let { handler.removeCallbacks(it) }
                    pauseRunnable = Runnable {
                        if (hasNewSentence && currentTextState.value.isNotEmpty()) {
                            // 不再清空當前文字，只標記為已處理
                            hasNewSentence = false
                            
                            // 設置自動保存，確保最後一句話不會丟失
                            autoSaveRunnable?.let { handler.removeCallbacks(it) }
                            handler.postDelayed(autoSaveRunnable!!, AUTO_SAVE_DELAY)
                        }
                    }
                    handler.postDelayed(pauseRunnable!!, PAUSE_DELAY)
                }
            }

            override fun onError(error: Int) {
                // 語音識別錯誤
                isSpeakingState.value = false
                
                when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> Toast.makeText(this@MainActivity, "音頻錯誤", Toast.LENGTH_SHORT).show()
                    SpeechRecognizer.ERROR_CLIENT -> Toast.makeText(this@MainActivity, "客戶端錯誤", Toast.LENGTH_SHORT).show()
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Toast.makeText(this@MainActivity, "權限不足", Toast.LENGTH_SHORT).show()
                    SpeechRecognizer.ERROR_NETWORK -> Toast.makeText(this@MainActivity, "網絡錯誤", Toast.LENGTH_SHORT).show()
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> Toast.makeText(this@MainActivity, "網絡超時", Toast.LENGTH_SHORT).show()
                    SpeechRecognizer.ERROR_NO_MATCH -> Toast.makeText(this@MainActivity, "無匹配結果", Toast.LENGTH_SHORT).show()
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Toast.makeText(this@MainActivity, "識別器忙", Toast.LENGTH_SHORT).show()
                    SpeechRecognizer.ERROR_SERVER -> Toast.makeText(this@MainActivity, "服務器錯誤", Toast.LENGTH_SHORT).show()
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Toast.makeText(this@MainActivity, "語音超時", Toast.LENGTH_SHORT).show()
                    else -> Toast.makeText(this@MainActivity, "未知錯誤: $error", Toast.LENGTH_SHORT).show()
                }
                
                if (error != SpeechRecognizer.ERROR_CLIENT && error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    handler.postDelayed({
                        speechRecognizer.cancel()
                        isListening = false
                        if (isListeningState.value) {
                            startListening()
                        }
                    }, 500) 
                } else if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    speechRecognizer.cancel()
                    isListening = false
                    handler.postDelayed({
                        if (isListeningState.value) {
                            startListening()
                        }
                    }, 1000) 
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    if (text.isNotEmpty() && text != lastRecognizedText) {
                        // 如果有新句子且當前有顯示文字，則將當前文字添加到歷史
                        if (currentTextState.value.isNotEmpty() && currentTextState.value != text) {
                            previousText = currentTextState.value
                            addToHistory(previousText)
                            // 確保UI更新
                            handler.post {
                                // 這裡不需要額外操作，因為addToHistory已經更新了historyTextState
                            }
                        }
                        
                        // 更新當前文字
                        currentTextState.value = text
                        lastRecognizedText = text
                        hasNewSentence = true
                        
                        // 設置停頓檢測，但不立即清空
                        pauseRunnable?.let { handler.removeCallbacks(it) }
                        pauseRunnable = Runnable {
                            if (hasNewSentence && currentTextState.value.isNotEmpty()) {
                                // 不再清空當前文字，只標記為已處理
                                hasNewSentence = false
                                
                                // 設置自動保存，確保最後一句話不會丟失
                                autoSaveRunnable?.let { handler.removeCallbacks(it) }
                                handler.postDelayed(autoSaveRunnable!!, AUTO_SAVE_DELAY)
                            }
                        }
                        handler.postDelayed(pauseRunnable!!, PAUSE_DELAY)
                    }
                }
                
                isListening = false
                if (isListeningState.value) {
                    handler.postDelayed({
                        startListening()
                    }, 300) // 短暫延遲後重啟，避免連續啟動
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    if (text.isNotEmpty()) {
                        // 更新當前文字
                        currentTextState.value = text
                        
                        // 不在部分結果時設置停頓檢測，避免文字跳動
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            speechRecognizer.cancel()
            handler.postDelayed({
                speechRecognizer.startListening(recognizerIntent)
                isListening = true
                Toast.makeText(this, "開始聆聽", Toast.LENGTH_SHORT).show()
            }, 200) 
        } catch (e: Exception) {
            Toast.makeText(this, "語音辨識啟動失敗: ${e.message}", Toast.LENGTH_SHORT).show()
            isListening = false
            isListeningState.value = false
        }
    }
    
    private fun addToHistory(text: String) {
        if (text.isNotEmpty()) {
            historyTextState.add(0, text)
            if (historyTextState.size > 10) {
                historyTextState.removeAt(historyTextState.size - 1)
            }
        }
    }

    private fun stopListening() {
        if (isListening) {
            speechRecognizer.stopListening()
            isListening = false
            isSpeakingState.value = false
            
            // 停止聆聽時不立即添加到歷史，但設置自動保存
            if (currentTextState.value.isNotEmpty()) {
                // 設置自動保存，確保最後一句話不會丟失
                autoSaveRunnable?.let { handler.removeCallbacks(it) }
                handler.postDelayed(autoSaveRunnable!!, AUTO_SAVE_DELAY)
                hasNewSentence = false
            }
            
            pauseRunnable?.let { handler.removeCallbacks(it) }
        }
    }
}

@Composable
fun HearingAidApp(
    modifier: Modifier = Modifier,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onAddToHistory: () -> Unit,
    onClearHistory: () -> Unit, // 新增清除歷史的回調
    currentText: String,
    textHistory: List<String>,
    isListening: Boolean,
    isSpeaking: Boolean
) {
    val scrollState = rememberScrollState()
    val localDensity = LocalDensity.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App title and clear button row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "老人助聽器",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            
            // 清除歷史按鈕
            if (textHistory.isNotEmpty()) {
                Button(
                    onClick = onClearHistory,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE57373) // 淺紅色
                    ),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text("清除記錄")
                }
            }
        }

        // 重新設計佈局，確保當前文字始終在上方
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 始終將當前文字放在頂部
                if (currentText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = currentText,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2196F3), // Blue color
                        textAlign = TextAlign.Center,
                        lineHeight = 48.sp, // 增加行高，避免文字疊在一起
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 24.dp) // 增加垂直間距
                            .fillMaxWidth()
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                } else if (isListening) {
                    // 顯示提示文字，也放在頂部
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = if (isSpeaking) "正在聆聽..." else "請開始說話...",
                        fontSize = 24.sp,
                        color = if (isSpeaking) Color(0xFF4CAF50) else Color.Gray, // 說話時顯示綠色
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                } else {
                    // 顯示未啟用提示，也放在頂部
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "點擊下方按鈕開始聆聽",
                        fontSize = 24.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                }
                
                // 歷史記錄放在當前文字下方
                if (textHistory.isNotEmpty()) {
                    Text(
                        text = "歷史記錄",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.DarkGray,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    textHistory.forEach { text ->
                        Text(
                            text = text,
                            fontSize = 18.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                            lineHeight = 28.sp, // 增加歷史記錄的行高
                            modifier = Modifier
                                .padding(vertical = 8.dp) // 增加垂直間距
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        )
                    }
                }
                
                // 確保底部有足夠空間
                Spacer(modifier = Modifier.height(32.dp))
            }
        }

        // Control button
        Button(
            onClick = {
                if (isListening) {
                    onStopListening()
                } else {
                    onStartListening()
                }
            },
            modifier = Modifier
                .padding(16.dp)
                .height(56.dp)
                .fillMaxWidth()
        ) {
            Text(
                text = if (isListening) "停止聆聽" else "開始聆聽",
                fontSize = 18.sp
            )
        }
    }

    // 當有新內容時，自動滾動到頂部
    LaunchedEffect(currentText, textHistory.size) {
        scrollState.scrollTo(0)
    }

    // Update the UI when speech recognition results come in
    DisposableEffect(key1 = Unit) {
        onDispose {
            onStopListening()
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HearingAidAppPreview() {
    HearingAid0411Theme {
        HearingAidApp(
            onStartListening = {},
            onStopListening = {},
            onAddToHistory = {},
            onClearHistory = {},
            currentText = "預覽文字",
            textHistory = listOf("歷史文字1", "歷史文字2", "歷史文字3"),
            isListening = true,
            isSpeaking = false
        )
    }
}