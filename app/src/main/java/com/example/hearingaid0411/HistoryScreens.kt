package com.example.hearingaid0411

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hearingaid0411.data.AppDb
import com.example.hearingaid0411.data.SessionSummary

// ============ 對話紀錄：日期 → 第幾個對話 → 內容（幾點幾分） ============

@Composable
private fun HistoryTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.heightIn(min = 56.dp),
        ) {
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

/** 第一層：日期清單 */
@Composable
fun DatesScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenDate: (String) -> Unit,
) {
    val context = LocalContext.current
    val dao = remember { AppDb.get(context).dao() }
    val dates by dao.dates().collectAsState(initial = emptyList())

    Column(modifier = modifier.fillMaxSize()) {
        HistoryTopBar(title = "對話紀錄", onBack = onBack)

        if (dates.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "還沒有對話紀錄\n開始聆聽後，說過的話會自動存在這裡",
                    fontSize = 22.sp,
                    lineHeight = 32.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(dates, key = { it.date }) { d ->
                    Surface(
                        onClick = { onOpenDate(d.date) },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = formatDateLabel(d.date),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${d.sessionCount} 個對話",
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 第二層：某一天的對話清單（第 N 個對話、起訖時間、句數、首句預覽） */
@Composable
fun SessionsScreen(
    modifier: Modifier = Modifier,
    date: String,
    onBack: () -> Unit,
    onOpenSession: (SessionSummary) -> Unit,
) {
    val context = LocalContext.current
    val dao = remember { AppDb.get(context).dao() }
    val sessions by dao.sessionsOn(date).collectAsState(initial = emptyList())

    Column(modifier = modifier.fillMaxSize()) {
        HistoryTopBar(title = formatDateLabel(date), onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(sessions, key = { it.id }) { s ->
                Surface(
                    onClick = { onOpenSession(s) },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 88.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    ) {
                        Text(
                            text = "第 ${s.seqInDay} 個對話",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${formatClock(s.startedAt)} – ${formatClock(s.endedAt)}（${s.sentenceCount} 句）",
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (!s.preview.isNullOrBlank()) {
                            Text(
                                text = s.preview,
                                fontSize = 18.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 第三層：對話內容（每句顯示幾點幾分） */
@Composable
fun DetailScreen(
    modifier: Modifier = Modifier,
    sessionId: Long,
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val dao = remember { AppDb.get(context).dao() }
    val rows by dao.captionsOf(sessionId).collectAsState(initial = emptyList())
    val fontSize = (HearingState.fontSizeSp * 0.8f).coerceAtLeast(24f)

    Column(modifier = modifier.fillMaxSize()) {
        HistoryTopBar(title = title, onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp
            ),
        ) {
            itemsIndexed(rows, key = { _, r -> r.id }) { index, row ->
                val showTime = index == 0 ||
                    minuteOf(rows[index - 1].timeMillis) != minuteOf(row.timeMillis)
                if (showTime) {
                    Text(
                        text = formatClock(row.timeMillis),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                    )
                }
                Text(
                    text = row.text,
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.4f).sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                )
            }
        }
    }
}
