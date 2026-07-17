package com.example.hearingaid0411

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.hearingaid0411.asr.SpeakerIdentifier
import com.example.hearingaid0411.asr.VoiceprintStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================== 設定頁 ==============================

@Composable
private fun SettingsTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) {
            Text("← 返回", fontSize = 20.sp)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenVoiceprint: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SettingsTopBar(title = "設定", onBack = onBack)

        Surface(
            onClick = onOpenVoiceprint,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .heightIn(min = 80.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "認得我的聲音（聲紋）",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "設定後，字幕會把你自己說的話變淡，只突顯別人說的話",
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "關於本 App",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "「輔聽字幕」是把周圍說話聲即時變成字幕、並可放大到耳機的輔助工具。\n\n" +
                        "本產品非醫療器材，不能取代助聽器；若有聽力問題，請找耳鼻喉科醫師或聽力師檢查。\n\n" +
                        "所有語音與對話紀錄只保存在這支手機裡，不會上傳。",
                    fontSize = 16.sp,
                    lineHeight = 26.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

// ============================== 聲紋註冊 ==============================

private val PROMPTS = listOf(
    "今天天氣真好，我想出去外面走一走",
    "請問現在幾點了？我等一下要吃藥",
    "我最喜歡吃的水果是香蕉和蘋果",
)

@Composable
fun VoiceprintScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var modelReady by remember { mutableStateOf(VoiceprintStore.modelReady(context)) }
    var enrolled by remember { mutableStateOf(VoiceprintStore.isEnrolled(context)) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    /** 0=待開始；1..3=錄第幾句；4=計算中 */
    var step by remember { mutableStateOf(0) }
    var recProgress by remember { mutableStateOf(0f) }
    var message by remember { mutableStateOf<String?>(null) }
    val recordings = remember { mutableListOf<FloatArray>() }

    val hasMic = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    fun recordStep(n: Int) {
        step = n
        recProgress = 0f
        message = null
        scope.launch(Dispatchers.IO) {
            val samples = VoiceprintStore.recordSeconds(4f) { p -> recProgress = p }
            withContext(Dispatchers.Main) {
                if (samples == null) {
                    message = "錄音失敗，請再試一次"
                    step = 0
                    recordings.clear()
                    return@withContext
                }
                recordings.add(samples)
                if (n < 3) {
                    recordStep(n + 1)
                } else {
                    step = 4
                    scope.launch(Dispatchers.IO) {
                        try {
                            val id = SpeakerIdentifier(
                                VoiceprintStore.modelFile(context).absolutePath,
                                emptyList(),
                            )
                            val embeddings = recordings.map { id.computeEmbedding(it) }
                            id.release()
                            VoiceprintStore.save(context, embeddings)
                            withContext(Dispatchers.Main) {
                                enrolled = true
                                step = 0
                                recordings.clear()
                            }
                        } catch (_: Throwable) {
                            withContext(Dispatchers.Main) {
                                message = "設定失敗，請再試一次"
                                step = 0
                                recordings.clear()
                            }
                        }
                    }
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SettingsTopBar(title = "認得我的聲音", onBack = onBack)

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            when {
                // --- 尚未下載聲紋模型 ---
                !modelReady -> {
                    Text(
                        text = "第一次使用要先下載聲紋功能（約 27 MB）",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 28.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    val p = downloadProgress
                    if (p != null) {
                        Text("下載中… ${(p * 100).toInt()}%", fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { p },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp),
                        )
                    } else {
                        Button(
                            onClick = {
                                downloadProgress = 0f
                                message = null
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        VoiceprintStore.downloadModel(context) { pr ->
                                            downloadProgress = pr
                                        }
                                        withContext(Dispatchers.Main) {
                                            modelReady = true
                                            downloadProgress = null
                                        }
                                    } catch (_: Throwable) {
                                        withContext(Dispatchers.Main) {
                                            downloadProgress = null
                                            message = "下載失敗，請檢查網路後再試"
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.heightIn(min = 56.dp),
                        ) {
                            Text("下載", fontSize = 20.sp)
                        }
                    }
                }

                // --- 錄音中 ---
                step in 1..3 -> {
                    Text(
                        text = "第 $step 句（共 3 句）",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "請唸出：\n「${PROMPTS[step - 1]}」",
                        fontSize = 26.sp,
                        lineHeight = 38.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    LinearProgressIndicator(
                        progress = { recProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("正在錄音…", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // --- 計算中 ---
                step == 4 -> {
                    Text(
                        text = "正在設定，請稍等…",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }

                // --- 已完成註冊 ---
                enrolled -> {
                    Text(
                        text = "✓ 已設定完成",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "下次開始聆聽時，你自己說的話會變淡顯示。\n\n" +
                            "聲紋只保存在這支手機裡，隨時可以刪除。",
                        fontSize = 18.sp,
                        lineHeight = 28.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Row {
                        Button(
                            onClick = { recordStep(1) },
                            modifier = Modifier.heightIn(min = 56.dp),
                        ) {
                            Text("重新錄製", fontSize = 18.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        OutlinedButton(
                            onClick = {
                                VoiceprintStore.clear(context)
                                enrolled = false
                            },
                            modifier = Modifier.heightIn(min = 56.dp),
                        ) {
                            Text("刪除聲紋", fontSize = 18.sp)
                        }
                    }
                }

                // --- 待開始 ---
                else -> {
                    Text(
                        text = "讓 App 認得你的聲音",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "等一下會請你唸 3 句話，每句約 4 秒。\n" +
                            "請在安靜的地方，用平常說話的音量唸。\n\n" +
                            "設定後，字幕會把你自己說的話變淡，看別人說的話更清楚。",
                        fontSize = 18.sp,
                        lineHeight = 28.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    if (!hasMic) {
                        Text(
                            text = "還沒有麥克風權限：請先回主畫面按一次「開始聆聽」允許權限",
                            fontSize = 18.sp,
                            lineHeight = 26.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Button(
                            onClick = { recordStep(1) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 72.dp),
                        ) {
                            Text("開始錄第 1 句", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            message?.let {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = it,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
