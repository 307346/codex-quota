package com.lcs.codexquota.data

import org.json.JSONObject
import java.time.Instant

/** One rate-limit window as reported by the Codex backend. */
data class RateWindow(
    val usedPercent: Double,
    val windowSeconds: Int,
    val resetAt: Instant?,
) {
    /** 0f..1f for progress bars; clamped because the backend can exceed 100. */
    val fraction: Float get() = (usedPercent / 100.0).coerceIn(0.0, 1.0).toFloat()

    fun toJson(): JSONObject = JSONObject().apply {
        put("used", usedPercent)
        put("win", windowSeconds)
        put("reset", resetAt?.toEpochMilli() ?: JSONObject.NULL)
    }

    companion object {
        /** Reads the compact cache format written by [toJson], not the server payload. */
        fun fromCache(o: JSONObject?): RateWindow? {
            if (o == null) return null
            return RateWindow(
                usedPercent = o.optDouble("used", 0.0),
                windowSeconds = o.optInt("win", 0),
                resetAt = o.optLongOrNull("reset")?.let(Instant::ofEpochMilli),
            )
        }
    }
}

data class UsageSnapshot(
    val planType: String,
    val email: String?,
    val primary: RateWindow,
    val secondary: RateWindow?,
    val limitReached: Boolean,
    val resetCredits: Int,
    val unlimited: Boolean,
    val balance: String?,
    val fetchedAt: Instant,
) {
    val isWeeklyExhausted: Boolean
        get() = limitReached || (secondary?.usedPercent ?: 0.0) >= 100.0

    fun toJson(): JSONObject = JSONObject().apply {
        put("plan", planType)
        put("email", email ?: JSONObject.NULL)
        put("primary", primary.toJson())
        put("secondary", secondary?.toJson() ?: JSONObject.NULL)
        put("limitReached", limitReached)
        put("resetCredits", resetCredits)
        put("unlimited", unlimited)
        put("balance", balance ?: JSONObject.NULL)
        put("fetchedAt", fetchedAt.toEpochMilli())
    }

    companion object {
        fun fromJson(o: JSONObject): UsageSnapshot? {
            val primary = RateWindow.fromCache(o.optJSONObject("primary")) ?: return null
            return UsageSnapshot(
                planType = o.optString("plan", "unknown"),
                email = o.optStringOrNull("email"),
                primary = primary,
                secondary = RateWindow.fromCache(o.optJSONObject("secondary")),
                limitReached = o.optBoolean("limitReached", false),
                resetCredits = o.optInt("resetCredits", 0),
                unlimited = o.optBoolean("unlimited", false),
                balance = o.optStringOrNull("balance"),
                fetchedAt = Instant.ofEpochMilli(o.optLong("fetchedAt", 0L)),
            )
        }
    }
}

/** Parses the pristine `GET /wham/usage` payload. */
object UsageParser {

    /** Server fields: used_percent / limit_window_seconds / reset_at (epoch seconds). */
    private fun window(o: JSONObject?): RateWindow? {
        if (o == null) return null
        val resetSeconds = o.optLong("reset_at", 0L)
        return RateWindow(
            usedPercent = o.optDouble("used_percent", 0.0),
            windowSeconds = o.optInt("limit_window_seconds", 0),
            resetAt = if (resetSeconds > 0L) Instant.ofEpochSecond(resetSeconds) else null,
        )
    }

    fun parse(raw: String): UsageSnapshot {
        val root = JSONObject(raw)
        val rl = root.optJSONObject("rate_limit")
            ?: throw IllegalStateException("payload has no rate_limit")
        val primary = window(rl.optJSONObject("primary_window"))
            ?: throw IllegalStateException("payload has no primary_window")
        val credits = root.optJSONObject("credits")
        return UsageSnapshot(
            planType = root.optString("plan_type", "unknown"),
            email = root.optStringOrNull("email"),
            primary = primary,
            secondary = window(rl.optJSONObject("secondary_window")),
            limitReached = rl.optBoolean("limit_reached", false),
            resetCredits = root.optJSONObject("rate_limit_reset_credits")
                ?.optInt("available_count", 0) ?: 0,
            unlimited = credits?.optBoolean("unlimited", false) ?: false,
            balance = credits?.optStringOrNull("balance"),
            fetchedAt = Instant.now(),
        )
    }
}

/** Credentials imported from a desktop `~/.codex/auth.json`. */
data class Tokens(
    val accessToken: String,
    val refreshToken: String?,
    val accountId: String?,
) {
    companion object {
        fun parseAuthJson(raw: String): Tokens {
            val root = JSONObject(raw.trim())
            val tokens = root.optJSONObject("tokens")
            val access = tokens?.optStringOrNull("access_token")
                ?: root.optStringOrNull("OPENAI_API_KEY")
                ?: throw IllegalArgumentException("没有找到 access_token，请粘贴完整的 auth.json")
            return Tokens(
                accessToken = access,
                refreshToken = tokens?.optStringOrNull("refresh_token"),
                accountId = tokens?.optStringOrNull("account_id"),
            )
        }
    }
}

fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = optString(key, "")
    return v.ifEmpty { null }
}

fun JSONObject.optLongOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    val v = optLong(key, 0L)
    return if (v == 0L) null else v
}
