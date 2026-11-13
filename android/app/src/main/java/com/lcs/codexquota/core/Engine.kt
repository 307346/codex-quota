package com.lcs.codexquota.core

import android.content.Context
import com.lcs.codexquota.data.Event
import com.lcs.codexquota.data.Prefs
import com.lcs.codexquota.data.QuotaConfig
import com.lcs.codexquota.data.Tokens
import com.lcs.codexquota.data.UsageSnapshot
import com.lcs.codexquota.net.CodexClient
import com.lcs.codexquota.net.HttpFailure
import com.lcs.codexquota.net.Unauthorized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

sealed interface CycleOutcome {
    data class Ok(val primed: Boolean, val next: ZonedDateTime, val snapshot: UsageSnapshot) : CycleOutcome
    data object Disabled : CycleOutcome
    data object NeedAuth : CycleOutcome
    data class Failed(val message: String) : CycleOutcome
}

private class NeedAuthException(message: String) : Exception(message)

/** Serialises cycles so the alarm, the fallback worker and the UI never prime twice. */
object Guard {
    val mutex = kotlinx.coroutines.sync.Mutex()
}

/**
 * One scheduling cycle: read quota -> prime if the window elapsed -> arm the
 * next alarm. Everything the alarm path does goes through here.
 */
class Engine(context: Context) {

    private val app: Context = context.applicationContext
    private val prefs = Prefs.get(app)

    suspend fun runCycle(trigger: String): CycleOutcome = withContext(Dispatchers.IO) {
        if (!Guard.mutex.tryLock()) {
            prefs.log(Event(Instant.now(), Event.Kind.SKIP, "上一轮仍在执行，本轮跳过（$trigger）"))
            return@withContext CycleOutcome.Failed("上一轮仍在执行")
        }
        try {
            performCycle(trigger)
        } finally {
            Guard.mutex.unlock()
        }
    }

    private suspend fun performCycle(trigger: String): CycleOutcome = withContext(Dispatchers.IO) {
        val cfg = prefs.config()
        if (!cfg.enabled) {
            AlarmScheduler.cancel(app)
            prefs.setNextActivation(null)
            prefs.log(Event(Instant.now(), Event.Kind.SKIP, "自动激活已关闭（$trigger）"))
            return@withContext CycleOutcome.Disabled
        }
        if (prefs.tokens() == null) {
            prefs.log(Event(Instant.now(), Event.Kind.AUTH, "未导入凭证，无法激活"))
            return@withContext CycleOutcome.NeedAuth
        }

        try {
            var snap = withSession { c -> c.usage() }
            val now = Instant.now()
            var basis = snap.primary.resetAt
            var primed = false

            val due = basis == null || !basis.isAfter(now)
            when {
                snap.isWeeklyExhausted -> prefs.log(
                    Event(now, Event.Kind.SKIP, "周额度已用尽，暂停激活直到周重置")
                )

                due -> {
                    val outcome = withSession { c -> c.prime(cfg) }
                    if (outcome.ok) {
                        primed = true
                        // A fresh window must reset in the future. Anything else means
                        // the header was unusable, so fall back to the authoritative
                        // usage payload instead of scheduling on garbage.
                        basis = outcome.resetAt?.takeIf { it.isAfter(now) }
                            ?: withSession { c -> c.usage() }.primary.resetAt
                        prefs.setLastPrime(now, "成功")
                        prefs.log(
                            Event(now, Event.Kind.PRIME, "已开启新窗口，服务端重置于 ${fmt(basis)}")
                        )
                    } else {
                        prefs.setLastPrime(now, "失败")
                        prefs.log(
                            Event(
                                now,
                                Event.Kind.FAIL,
                                "激活失败 HTTP ${outcome.status} ${outcome.detail.orEmpty()}",
                            )
                        )
                    }
                }

                else -> prefs.log(
                    Event(now, Event.Kind.INFO, "窗口仍在进行，重置于 ${fmt(basis)}")
                )
            }

            prefs.cacheUsage(snap)
            val basisForNext = if (snap.isWeeklyExhausted) snap.secondary?.resetAt ?: basis else basis
            val next = Scheduler.nextActivation(ZonedDateTime.now(), basisForNext, cfg)
            prefs.setNextActivation(next.toInstant())
            AlarmScheduler.schedule(app, next.toInstant())
            CycleOutcome.Ok(primed, next, snap)
        } catch (e: NeedAuthException) {
            prefs.log(Event(Instant.now(), Event.Kind.AUTH, e.message.orEmpty()))
            CycleOutcome.NeedAuth
        } catch (e: Unauthorized) {
            prefs.log(Event(Instant.now(), Event.Kind.AUTH, "登录已失效 401，请重新导入 auth.json"))
            CycleOutcome.NeedAuth
        } catch (e: Exception) {
            prefs.log(Event(Instant.now(), Event.Kind.FAIL, "本轮失败：${e.message ?: e.javaClass.simpleName}"))
            CycleOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Read-only quota refresh used by pull-to-refresh in the UI. */
    suspend fun refreshQuota(): UsageSnapshot = withContext(Dispatchers.IO) {
        val snap = withSession { c -> c.usage() }
        prefs.cacheUsage(snap)
        snap
    }

    /** Re-arm the alarm from cached data without touching the network. */
    suspend fun rescheduleOnly(): ZonedDateTime? = withContext(Dispatchers.IO) {
        val cfg = prefs.config()
        if (!cfg.enabled) {
            AlarmScheduler.cancel(app)
            prefs.setNextActivation(null)
            return@withContext null
        }
        val cached = prefs.state().cached
        val basis = if (cached?.isWeeklyExhausted == true) {
            cached.secondary?.resetAt ?: cached.primary.resetAt
        } else {
            cached?.primary?.resetAt
        }
        val next = Scheduler.nextActivation(ZonedDateTime.now(), basis, cfg)
        prefs.setNextActivation(next.toInstant())
        AlarmScheduler.schedule(app, next.toInstant())
        next
    }

    /** Manual "test now" from settings: primes regardless of the due check. */
    suspend fun forcePrime(): CycleOutcome = withContext(Dispatchers.IO) {
        val cfg = prefs.config()
        if (prefs.tokens() == null) return@withContext CycleOutcome.NeedAuth
        if (!Guard.mutex.tryLock()) return@withContext CycleOutcome.Failed("上一轮仍在执行")
        try {
            val outcome = withSession { c -> c.prime(cfg) }
            val now = Instant.now()
            if (!outcome.ok) {
                prefs.log(Event(now, Event.Kind.FAIL, "手动激活失败 HTTP ${outcome.status}"))
                return@withContext CycleOutcome.Failed("HTTP ${outcome.status} ${outcome.detail.orEmpty()}")
            }
            val snap = withSession { c -> c.usage() }
            prefs.cacheUsage(snap)
            prefs.setLastPrime(now, "手动成功")
            prefs.log(Event(now, Event.Kind.PRIME, "手动激活成功，重置于 ${fmt(outcome.resetAt)}"))
            val basis = outcome.resetAt?.takeIf { it.isAfter(now) } ?: snap.primary.resetAt
            val next = Scheduler.nextActivation(ZonedDateTime.now(), basis, cfg)
            prefs.setNextActivation(next.toInstant())
            AlarmScheduler.schedule(app, next.toInstant())
            CycleOutcome.Ok(true, next, snap)
        } catch (e: Unauthorized) {
            CycleOutcome.NeedAuth
        } catch (e: Exception) {
            CycleOutcome.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            Guard.mutex.unlock()
        }
    }

    private suspend fun <T> withSession(block: suspend (CodexClient) -> T): T {
        val tokens = prefs.tokens() ?: throw NeedAuthException("未导入凭证")
        val client = CodexClient(tokens)
        return try {
            block(client)
        } catch (e: Unauthorized) {
            val rt = tokens.refreshToken
                ?: throw NeedAuthException("登录已过期且没有 refresh_token，请重新导入 auth.json")
            val fresh: Tokens = try {
                client.refresh(rt)
            } catch (e2: HttpFailure) {
                throw NeedAuthException("刷新凭证失败 HTTP ${e2.code}，请重新导入 auth.json")
            }
            prefs.saveTokens(fresh)
            prefs.log(Event(Instant.now(), Event.Kind.AUTH, "已自动刷新 access_token"))
            block(CodexClient(fresh))
        }
    }

    private fun fmt(i: Instant?): String =
        i?.atZone(java.time.ZoneId.systemDefault())
            ?.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            ?: "未知"
}
