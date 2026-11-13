package com.lcs.codexquota.data

import java.time.LocalTime

/**
 * User-tunable schedule. Times are local wall-clock times.
 *
 * Rule: the primer fires only while the clock is inside [startTime, cutoffTime].
 * Every firing is followed by the server-reported reset instant; if that instant
 * (plus grace) falls outside the span, the next firing moves to tomorrow's start.
 */
data class QuotaConfig(
    val enabled: Boolean = true,
    val startHour: Int = 5,
    val startMinute: Int = 30,
    val cutoffHour: Int = 22,
    val cutoffMinute: Int = 0,
    /** Fallback window length, overridden by the server's limit_window_seconds. */
    val windowMinutes: Int = 300,
    /** Wait this long after a reset before firing, to avoid boundary races. */
    val graceSeconds: Int = 60,
    val model: String = "gpt-6-astra",
    val prompt: String = "Reply with exactly OK.",
) {
    val startTime: LocalTime get() = LocalTime.of(startHour, startMinute)
    val cutoffTime: LocalTime get() = LocalTime.of(cutoffHour, cutoffMinute)

    fun withStart(h: Int, m: Int): QuotaConfig = copy(startHour = h, startMinute = m)
    fun withCutoff(h: Int, m: Int): QuotaConfig = copy(cutoffHour = h, cutoffMinute = m)

    companion object {
        val DEFAULT = QuotaConfig()
    }
}

/** A single activation-chain step for the "today" timeline in the UI. */
data class ChainStep(
    val time: LocalTime,
    val state: StepState,
)

enum class StepState { DONE, NEXT, PENDING, SKIPPED }
