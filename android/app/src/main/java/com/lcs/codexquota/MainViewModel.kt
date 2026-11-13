package com.lcs.codexquota

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lcs.codexquota.core.AlarmScheduler
import com.lcs.codexquota.core.CycleOutcome
import com.lcs.codexquota.core.Engine
import com.lcs.codexquota.data.Prefs
import com.lcs.codexquota.data.QuotaConfig
import com.lcs.codexquota.data.RuntimeState
import com.lcs.codexquota.data.Tokens
import com.lcs.codexquota.data.UsageSnapshot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class UiState(
    val config: QuotaConfig = QuotaConfig(),
    val state: RuntimeState = RuntimeState(),
    val busy: Boolean = false,
    val exactAlarmOk: Boolean = true,
    val batteryIgnored: Boolean = true,
) {
    val snapshot: UsageSnapshot? get() = state.cached
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs.get(app)
    private val engine = Engine(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    init {
        viewModelScope.launch {
            combine(prefs.configFlow, prefs.stateFlow) { c, s -> c to s }
                .collect { (c, s) -> _ui.update { it.copy(config = c, state = s) } }
        }
        viewModelScope.launch { refreshSystemFlags() }
        viewModelScope.launch {
            // Pull fresh numbers once per cold start so the UI is never stale,
            // and realign the alarm with what the server actually reported.
            runCatching { engine.refreshQuota() }
                .onSuccess { engine.rescheduleOnly() }
                .onFailure { say("刷新失败：${it.message ?: it.javaClass.simpleName}") }
        }
    }

    fun refreshSystemFlags() {
        val ctx = getApplication<Application>()
        val pm = ctx.getSystemService(PowerManager::class.java)
        val battery = pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true
        _ui.update {
            it.copy(
                exactAlarmOk = AlarmScheduler.canScheduleExact(ctx),
                batteryIgnored = battery,
            )
        }
    }

    fun refreshQuota() {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            runCatching { engine.refreshQuota() }
                .onFailure { say("刷新失败：${it.message ?: it.javaClass.simpleName}") }
            _ui.update { it.copy(busy = false) }
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val cfg = _ui.value.config.copy(enabled = enabled)
            prefs.saveConfig(cfg)
            if (!enabled) {
                AlarmScheduler.cancel(getApplication())
                prefs.setNextActivation(null)
            } else {
                engine.rescheduleOnly()
            }
            say(if (enabled) "已开启自动激活" else "已暂停自动激活")
        }
    }

    fun saveConfig(cfg: QuotaConfig) {
        viewModelScope.launch {
            prefs.saveConfig(cfg)
            engine.rescheduleOnly()
        }
    }

    fun importAuth(raw: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                val tokens = Tokens.parseAuthJson(raw)
                prefs.saveTokens(tokens)
                val snap = engine.refreshQuota()
                engine.rescheduleOnly()
                say("导入成功：${snap.planType} · 5h 已用 ${snap.primary.usedPercent.toInt()}%")
            } catch (e: Exception) {
                say("导入失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    fun testPrime() {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val outcome = runCatching { engine.forcePrime() }
                .getOrElse { CycleOutcome.Failed(it.message ?: "未知错误") }
            say(
                when (outcome) {
                    is CycleOutcome.Ok -> "激活成功，下次 ${outcome.next.toLocalTime()}"
                    CycleOutcome.NeedAuth -> "凭证失效，请重新导入"
                    CycleOutcome.Disabled -> "自动激活已关闭"
                    is CycleOutcome.Failed -> "激活失败：${outcome.message}"
                }
            )
            _ui.update { it.copy(busy = false) }
        }
    }

    fun clearLog() {
        viewModelScope.launch { prefs.clearLog() }
    }

    fun requestIgnoreBattery() {
        val ctx = getApplication<Application>()
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
            .onFailure { say("无法打开电池优化设置") }
    }

    fun openExactAlarmSettings() {
        val ctx = getApplication<Application>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
            .onFailure { say("无法打开精确闹钟设置") }
    }

    fun nextAfter(now: Instant): Instant? = _ui.value.state.nextActivation

    private fun say(text: String) {
        _messages.tryEmit(text)
    }
}
