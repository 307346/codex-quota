package com.lcs.codexquota.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lcs.codexquota.MainViewModel
import com.lcs.codexquota.UiState
import com.lcs.codexquota.core.Scheduler
import com.lcs.codexquota.data.ChainStep
import com.lcs.codexquota.data.Event
import com.lcs.codexquota.data.StepState
import com.lcs.codexquota.data.UsageSnapshot
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable
fun AppScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now()
            delay(1000)
        }
    }
    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    LifecycleResumeEffect(Unit) {
        vm.refreshSystemFlags()
        onPauseOrDispose { }
    }

    Scaffold(
        containerColor = Ink.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Header(
                ui = ui,
                onRefresh = { vm.refreshQuota() },
                onSettings = { showSettings = true },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = pad.calculateTopPadding() + 4.dp,
                bottom = 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!ui.state.tokensPresent) {
                item { ImportPromptCard(onImport = { showSettings = true }) }
            }
            if (!ui.exactAlarmOk || !ui.batteryIgnored) {
                item {
                    HealthCard(
                        exactAlarmOk = ui.exactAlarmOk,
                        batteryIgnored = ui.batteryIgnored,
                        onFixAlarm = { vm.openExactAlarmSettings() },
                        onFixBattery = { vm.requestIgnoreBattery() },
                    )
                }
            }
            item { PrimaryCard(ui, now) }
            item { WeeklyCard(ui, now) }
            item { NextCard(ui, now, onToggle = { vm.setEnabled(it) }) }
            item { ChainCard(ui, now) }
            item {
                SectionTitle("活动日志")
                Spacer(Modifier.height(6.dp))
                LogCard(ui.state.log)
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            ui = ui,
            onDismiss = { showSettings = false },
            onSave = { vm.saveConfig(it) },
            onImport = { vm.importAuth(it) },
            onTest = { vm.testPrime() },
            onClearLog = { vm.clearLog() },
            onFixAlarm = { vm.openExactAlarmSettings() },
            onFixBattery = { vm.requestIgnoreBattery() },
        )
    }
}

@Composable
private fun Header(ui: UiState, onRefresh: () -> Unit, onSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Codex 额度", style = MaterialTheme.typography.titleLarge, color = Ink.TextPrimary)
            Text(
                text = ui.state.accountId?.take(8)?.let { "账号 $it…" } ?: "未连接",
                style = MaterialTheme.typography.bodySmall,
                color = Ink.TextMuted,
            )
        }
        if (ui.busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = Ink.AccentSoft,
            )
            Spacer(Modifier.width(10.dp))
        }
        ui.snapshot?.let { PlanChip(it.planType) }
        IconButton(onClick = onRefresh, enabled = !ui.busy) {
            Icon(Icons.Outlined.Refresh, contentDescription = "刷新", tint = Ink.TextMuted)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Outlined.Settings, contentDescription = "设置", tint = Ink.TextMuted)
        }
    }
}

@Composable
private fun PlanChip(plan: String) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(Ink.Accent.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            plan.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Ink.AccentSoft,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CardBox(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Ink.Surface)
            .border(1.dp, Ink.Border, RoundedCornerShape(20.dp))
            .padding(18.dp),
        content = content,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Ink.TextMuted,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
}

private fun usageColor(percent: Double): Color = when {
    percent >= 85.0 -> Ink.Danger
    percent >= 60.0 -> Ink.Warn
    else -> Ink.Accent
}

@Composable
private fun QuotaRing(
    fraction: Float,
    color: Color,
    diameter: Dp = 112.dp,
    strokeWidth: Dp = 12.dp,
    centerLabel: String,
    centerSub: String,
) {
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = strokeWidth.toPx()
            val side = this.size.minDimension - sw
            val topLeft = Offset((this.size.width - side) / 2f, (this.size.height - side) / 2f)
            drawArc(
                color = Ink.SurfaceHigh,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = Size(side, side),
                style = Stroke(sw, cap = StrokeCap.Round),
            )
            val sweep = 360f * fraction.coerceIn(0f, 1f)
            if (sweep > 0.5f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(side, side),
                    style = Stroke(sw, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerLabel, style = MaterialTheme.typography.displaySmall, color = Ink.TextPrimary)
            Text(centerSub, style = MaterialTheme.typography.labelSmall, color = Ink.TextMuted)
        }
    }
}

@Composable
private fun PrimaryCard(ui: UiState, now: Instant) {
    val snap = ui.snapshot
    val used = snap?.primary?.usedPercent ?: 0.0
    val reset = snap?.primary?.resetAt
    val minutes = snap?.primary?.windowSeconds?.div(60)?.takeIf { it > 0 } ?: ui.config.windowMinutes

    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            QuotaRing(
                fraction = (used / 100.0).toFloat(),
                color = usageColor(used),
                centerLabel = "${used.roundToInt()}%",
                centerSub = "已用",
            )
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text("5 小时额度", style = MaterialTheme.typography.titleMedium, color = Ink.TextPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "窗口 ${formatSpan(minutes / 60, minutes % 60)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.TextMuted,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    reset?.clock() ?: "--:--",
                    style = MaterialTheme.typography.titleLarge,
                    color = Ink.TextPrimary,
                )
                Text(
                    "重置 · 还有 ${formatCountdown(reset, now)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun WeeklyCard(ui: UiState, now: Instant) {
    val snap: UsageSnapshot? = ui.snapshot
    val weekly = snap?.secondary
    val used = weekly?.usedPercent ?: 0.0
    val reset = weekly?.resetAt
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("每周额度", style = MaterialTheme.typography.titleMedium, color = Ink.TextPrimary)
            Spacer(Modifier.weight(1f))
            Text(
                "${used.roundToInt()}%",
                style = MaterialTheme.typography.titleMedium,
                color = usageColor(used),
            )
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { (used / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = usageColor(used),
            trackColor = Ink.SurfaceHigh,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "${reset?.dayClock() ?: "--"} 重置 · 还有 ${formatCountdown(reset, now)}",
            style = MaterialTheme.typography.bodySmall,
            color = Ink.TextMuted,
        )
        if (snap?.isWeeklyExhausted == true) {
            Spacer(Modifier.height(8.dp))
            Text(
                "周额度已用尽，激活已暂停",
                style = MaterialTheme.typography.bodySmall,
                color = Ink.Danger,
            )
        }
    }
}

@Composable
private fun NextCard(ui: UiState, now: Instant, onToggle: (Boolean) -> Unit) {
    val next = ui.state.nextActivation
    val cfg = ui.config
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("下次激活", style = MaterialTheme.typography.labelSmall, color = Ink.TextMuted)
                Spacer(Modifier.height(4.dp))
                Text(
                    next?.dayClock() ?: "未排程",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (cfg.enabled) Ink.TextPrimary else Ink.TextMuted,
                )
                Text(
                    when {
                        !cfg.enabled -> "已暂停"
                        next == null -> "未排程"
                        !next.isAfter(now) -> "正在执行…"
                        else -> "还有 ${formatCountdown(next, now)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.TextMuted,
                )
            }
            Switch(
                checked = cfg.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF04110D),
                    checkedTrackColor = Ink.Accent,
                    uncheckedThumbColor = Ink.TextMuted,
                    uncheckedTrackColor = Ink.SurfaceHigh,
                ),
            )
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = Ink.Border)
        Spacer(Modifier.height(12.dp))
        Text(
            buildString {
                append("每天 ${cfg.startTime.hhmm()} 起每 ")
                append(cfg.windowMinutes / 60)
                append("h 激活一次，")
                append("${cfg.cutoffTime.hhmm()} 之后不再激活，等到次日 ${cfg.startTime.hhmm()}。")
            },
            style = MaterialTheme.typography.bodySmall,
            color = Ink.TextMuted,
        )
    }
}

@Composable
private fun ChainCard(ui: UiState, now: Instant) {
    val zone = ZoneId.systemDefault()
    val windowMinutes = ui.snapshot?.primary?.windowSeconds?.div(60)?.takeIf { it > 0 }
        ?: ui.config.windowMinutes
    val steps: List<ChainStep> = Scheduler.annotatedChain(
        today = LocalDate.now(zone),
        cfg = ui.config,
        windowMinutes = windowMinutes,
        next = ui.state.nextActivation?.atZone(zone),
        now = now.atZone(zone),
    )
    CardBox {
        Text("今日计划", style = MaterialTheme.typography.titleMedium, color = Ink.TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            "共 ${steps.size} 次 · 窗口 ${windowMinutes / 60}h",
            style = MaterialTheme.typography.bodySmall,
            color = Ink.TextMuted,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (steps.isEmpty()) {
                Text("今天没有可激活的时间点", style = MaterialTheme.typography.bodySmall, color = Ink.TextMuted)
            }
            steps.forEach { StepChip(it) }
        }
    }
}

@Composable
private fun StepChip(step: ChainStep) {
    val (bg, fg) = when (step.state) {
        StepState.DONE -> Ink.SurfaceHigh to Ink.TextMuted
        StepState.NEXT -> Ink.Accent.copy(alpha = 0.18f) to Ink.AccentSoft
        StepState.PENDING -> Ink.SurfaceHigh to Ink.TextPrimary
        StepState.SKIPPED -> Ink.SurfaceHigh to Ink.TextMuted
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, if (step.state == StepState.NEXT) Ink.Accent else Ink.Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(step.time.hhmm(), style = MaterialTheme.typography.titleMedium, color = fg)
        Text(
            when (step.state) {
                StepState.DONE -> "已执行"
                StepState.NEXT -> "下一次"
                StepState.PENDING -> "待执行"
                StepState.SKIPPED -> "跳过"
            },
            style = MaterialTheme.typography.labelSmall,
            color = fg.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun LogCard(log: List<Event>) {
    CardBox {
        if (log.isEmpty()) {
            Text("还没有记录", style = MaterialTheme.typography.bodySmall, color = Ink.TextMuted)
            return@CardBox
        }
        log.take(10).forEachIndexed { index, e ->
            if (index > 0) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = Ink.Border.copy(alpha = 0.6f))
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .padding(top = 5.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(logColor(e.kind))
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.message, style = MaterialTheme.typography.bodyMedium, color = Ink.TextPrimary)
                    Text(
                        e.at.clockSec(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink.TextMuted,
                    )
                }
            }
        }
    }
}

private fun logColor(kind: Event.Kind): Color = when (kind) {
    Event.Kind.PRIME -> Ink.Accent
    Event.Kind.FAIL -> Ink.Danger
    Event.Kind.AUTH -> Ink.Warn
    Event.Kind.SKIP -> Ink.TextMuted
    Event.Kind.SCHEDULE, Event.Kind.INFO -> Ink.AccentSoft
}

@Composable
private fun ImportPromptCard(onImport: () -> Unit) {
    CardBox {
        Text("还没有连接账号", style = MaterialTheme.typography.titleMedium, color = Ink.TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            "把电脑上 ~/.codex/auth.json 的内容粘贴进来，应用会自己刷新登录态并接管定时激活。",
            style = MaterialTheme.typography.bodySmall,
            color = Ink.TextMuted,
        )
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onImport) { Text("去导入") }
    }
}

@Composable
private fun HealthCard(
    exactAlarmOk: Boolean,
    batteryIgnored: Boolean,
    onFixAlarm: () -> Unit,
    onFixBattery: () -> Unit,
) {
    CardBox {
        Text("省电设置需要授权", style = MaterialTheme.typography.titleMedium, color = Ink.Warn)
        Spacer(Modifier.height(6.dp))
        if (!exactAlarmOk) {
            Text(
                "未授予精确闹钟权限，激活时间可能被系统推迟。",
                style = MaterialTheme.typography.bodySmall,
                color = Ink.TextMuted,
            )
            TextButton(onClick = onFixAlarm, contentPadding = PaddingValues(0.dp)) {
                Text("去授权", fontSize = 13.sp)
            }
        }
        if (!batteryIgnored) {
            Text(
                "应用未加入电池优化白名单，后台闹钟可能被系统杀掉。",
                style = MaterialTheme.typography.bodySmall,
                color = Ink.TextMuted,
            )
            TextButton(onClick = onFixBattery, contentPadding = PaddingValues(0.dp)) {
                Text("去设置", fontSize = 13.sp)
            }
        }
    }
}
