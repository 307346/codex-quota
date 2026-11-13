package com.lcs.codexquota.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lcs.codexquota.UiState
import com.lcs.codexquota.data.QuotaConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    ui: UiState,
    onDismiss: () -> Unit,
    onSave: (QuotaConfig) -> Unit,
    onImport: (String) -> Unit,
    onTest: () -> Unit,
    onClearLog: () -> Unit,
    onFixAlarm: () -> Unit,
    onFixBattery: () -> Unit,
) {
    var cfg by remember(ui.config) { mutableStateOf(ui.config) }
    var authText by remember { mutableStateOf("") }
    var pickStart by remember { mutableStateOf(false) }
    var pickCutoff by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink.Surface) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("设置", style = MaterialTheme.typography.titleLarge, color = Ink.TextPrimary)

            Label("账号凭证")
            Text(
                if (ui.state.tokensPresent) "已导入 · 账号 ${ui.state.accountId?.take(8) ?: "未知"}" else "未导入",
                style = MaterialTheme.typography.bodySmall,
                color = if (ui.state.tokensPresent) Ink.AccentSoft else Ink.Warn,
            )
            OutlinedTextField(
                value = authText,
                onValueChange = { authText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("粘贴 ~/.codex/auth.json 全文") },
                minLines = 3,
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { if (authText.isNotBlank()) { onImport(authText); authText = "" } },
                    enabled = authText.isNotBlank() && !ui.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Ink.Accent, contentColor = Ink.Background),
                ) { Text("导入并验证") }
                TextButton(onClick = onTest, enabled = !ui.busy) { Text("立即测试激活") }
            }

            Divider()

            Label("每天首次激活")
            TimeRow(cfg.startTime.hhmm()) { pickStart = true }
            Text(
                "第二次及以后按窗口长度自动顺延。",
                style = MaterialTheme.typography.bodySmall,
                color = Ink.TextMuted,
            )

            Label("停止激活时刻（超过则等次日）")
            TimeRow(cfg.cutoffTime.hhmm()) { pickCutoff = true }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(
                    label = "窗口长度（分钟）",
                    value = cfg.windowMinutes,
                    modifier = Modifier.weight(1f),
                ) { cfg = cfg.copy(windowMinutes = it.coerceIn(30, 1440)) }
                NumberField(
                    label = "重置后延迟（秒）",
                    value = cfg.graceSeconds,
                    modifier = Modifier.weight(1f),
                ) { cfg = cfg.copy(graceSeconds = it.coerceIn(0, 1800)) }
            }

            Label("模型")
            OutlinedTextField(
                value = cfg.model,
                onValueChange = { cfg = cfg.copy(model = it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("激活时使用的模型 slug") },
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "填账号实际可用的模型，写错会导致激活 400。",
                style = MaterialTheme.typography.bodySmall,
                color = Ink.TextMuted,
            )

            Label("激活提示词")
            OutlinedTextField(
                value = cfg.prompt,
                onValueChange = { cfg = cfg.copy(prompt = it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
            )

            Divider()

            Label("系统权限")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onFixAlarm,
                    enabled = !ui.exactAlarmOk,
                    colors = ButtonDefaults.buttonColors(containerColor = Ink.SurfaceHigh, contentColor = Ink.TextPrimary),
                ) { Text(if (ui.exactAlarmOk) "闹钟权限已授权" else "授予精确闹钟") }
                Button(
                    onClick = onFixBattery,
                    enabled = !ui.batteryIgnored,
                    colors = ButtonDefaults.buttonColors(containerColor = Ink.SurfaceHigh, contentColor = Ink.TextPrimary),
                ) { Text(if (ui.batteryIgnored) "电池无限制" else "加入白名单") }
            }

            Divider()

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSave(cfg); onDismiss() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink.Accent, contentColor = Ink.Background),
                ) { Text("保存设置") }
                Button(
                    onClick = onClearLog,
                    colors = ButtonDefaults.buttonColors(containerColor = Ink.SurfaceHigh, contentColor = Ink.TextMuted),
                ) { Text("清空日志") }
            }
        }
    }

    if (pickStart) {
        TimePickerDialog(
            title = "首次激活时间",
            hour = cfg.startHour,
            minute = cfg.startMinute,
            onDismiss = { pickStart = false },
            onConfirm = { h, m ->
                cfg = cfg.copy(startHour = h, startMinute = m)
                pickStart = false
            },
        )
    }
    if (pickCutoff) {
        TimePickerDialog(
            title = "停止激活时刻",
            hour = cfg.cutoffHour,
            minute = cfg.cutoffMinute,
            onDismiss = { pickCutoff = false },
            onConfirm = { h, m ->
                cfg = cfg.copy(cutoffHour = h, cutoffMinute = m)
                pickCutoff = false
            },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Ink.TextMuted,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun Divider() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Ink.Border)
    )
}

@Composable
private fun TimeRow(value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Ink.SurfaceHigh)
            .border(1.dp, Ink.Border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = Ink.TextPrimary,
        )
        Spacer(Modifier.weight(1f))
        Text("修改", fontSize = 13.sp, color = Ink.AccentSoft)
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(4)
            text = digits
            digits.toIntOrNull()?.let(onChange)
        },
        modifier = modifier,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    title: String,
    hour: Int,
    minute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Surface,
        title = { Text(title, color = Ink.TextPrimary) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = state)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                Text("确定", color = Ink.AccentSoft)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Ink.TextMuted) }
        },
    )
}

