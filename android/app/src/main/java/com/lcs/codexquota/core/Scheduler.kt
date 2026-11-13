package com.lcs.codexquota.core

import com.lcs.codexquota.data.QuotaConfig
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure scheduling logic. Deliberately free of Android types so it can be
 * unit-tested; mirrors tools/codex_quota.py exactly.
 */
object Scheduler {

    /** True when [t]'s wall clock lies inside [start, cutoff]. */
    fun inActiveSpan(t: ZonedDateTime, cfg: QuotaConfig): Boolean {
        val clock = t.toLocalTime()
        return !clock.isBefore(cfg.startTime) && !clock.isAfter(cfg.cutoffTime)
    }

    /**
     * Next moment the primer should fire.
     *
     * @param resetAt server-reported primary-window reset, or null when unknown.
     */
    fun nextActivation(
        now: ZonedDateTime,
        resetAt: Instant?,
        cfg: QuotaConfig,
    ): ZonedDateTime {
        val zone = now.zone
        val candidate: ZonedDateTime = when {
            resetAt != null -> resetAt.atZone(zone).plusSeconds(cfg.graceSeconds.toLong())
            // No data yet but we are inside the span: prime right away.
            inActiveSpan(now, cfg) -> now
            else -> {
                var c = ZonedDateTime.of(now.toLocalDate(), cfg.startTime, zone)
                if (c.isBefore(now)) c = c.plusDays(1)
                c
            }
        }

        // A reset that elapsed while the phone slept must not fire in the past.
        val effective = if (candidate.isBefore(now)) now else candidate

        if (inActiveSpan(effective, cfg)) return effective

        return if (effective.toLocalTime().isBefore(cfg.startTime)) {
            ZonedDateTime.of(effective.toLocalDate(), cfg.startTime, zone)
        } else {
            ZonedDateTime.of(effective.toLocalDate().plusDays(1), cfg.startTime, zone)
        }
    }

    /** Activation instants for one calendar day, e.g. 05:30 / 10:30 / 15:30 / 20:30. */
    fun dailyChain(date: LocalDate, cfg: QuotaConfig, windowMinutes: Int = cfg.windowMinutes): List<LocalTime> {
        val out = mutableListOf<LocalTime>()
        var t = LocalDateTime.of(date, cfg.startTime)
        while (true) {
            if (t.toLocalTime().isAfter(cfg.cutoffTime)) break
            out += t.toLocalTime()
            t = t.plusMinutes(windowMinutes.toLong())
            if (t.toLocalDate() != date) break
        }
        return out
    }

    /**
     * Chain for today annotated for the UI.
     * Steps before [next] that already happened are DONE, [next]'s slot is NEXT,
     * the rest are PENDING.
     */
    fun annotatedChain(
        today: LocalDate,
        cfg: QuotaConfig,
        windowMinutes: Int,
        next: ZonedDateTime?,
        now: ZonedDateTime,
    ): List<com.lcs.codexquota.data.ChainStep> {
        val slots = dailyChain(today, cfg, windowMinutes)
        val nextClock = next?.takeIf { it.toLocalDate() == today }?.toLocalTime()
        return slots.map { slot ->
            when {
                nextClock != null && slot == nextClock ->
                    com.lcs.codexquota.data.ChainStep(slot, com.lcs.codexquota.data.StepState.NEXT)
                slot <= now.toLocalTime() ->
                    com.lcs.codexquota.data.ChainStep(slot, com.lcs.codexquota.data.StepState.DONE)
                else ->
                    com.lcs.codexquota.data.ChainStep(slot, com.lcs.codexquota.data.StepState.PENDING)
            }
        }
    }

    fun zone(): ZoneId = ZoneId.systemDefault()
}
