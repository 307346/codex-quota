package com.lcs.codexquota.core

import com.lcs.codexquota.data.QuotaConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Mirrors the assertions in tools/codex_quota.py --self-test so the Kotlin and
 * Python implementations cannot drift apart.
 */
class SchedulerTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 11, 13)
    private val cfg = QuotaConfig() // 05:30 start, 22:00 cutoff, 300 min window, 60 s grace

    private fun at(d: LocalDate, h: Int, m: Int): ZonedDateTime =
        ZonedDateTime.of(d, LocalTime.of(h, m), zone)

    private fun clockList(c: QuotaConfig, date: LocalDate = day): List<String> =
        Scheduler.dailyChain(date, c).map { it.toString() }

    @Test
    fun `default chain is 05_30 10_30 15_30 20_30`() {
        assertEquals(listOf("05:30", "10:30", "15:30", "20:30"), clockList(cfg))
    }

    @Test
    fun `user example chain with 05_00 start`() {
        val ex = QuotaConfig(startHour = 5, startMinute = 0)
        assertEquals(listOf("05:00", "10:00", "15:00", "20:00"), clockList(ex))
    }

    @Test
    fun `four hour window chain`() {
        val four = QuotaConfig(startHour = 5, startMinute = 0, windowMinutes = 240)
        assertEquals(listOf("05:00", "09:00", "13:00", "17:00", "21:00"), clockList(four))
    }

    @Test
    fun `reset past midnight jumps to next morning`() {
        val next = Scheduler.nextActivation(at(day, 20, 30), at(day.plusDays(1), 1, 30).toInstant(), cfg)
        assertEquals(at(day.plusDays(1), 5, 30), next)
    }

    @Test
    fun `reset just before cutoff still fires`() {
        val next = Scheduler.nextActivation(at(day, 17, 0), at(day, 21, 58).toInstant(), cfg)
        assertEquals(at(day, 21, 59), next)
    }

    @Test
    fun `reset exactly at cutoff is skipped`() {
        val next = Scheduler.nextActivation(at(day, 17, 0), at(day, 22, 0).toInstant(), cfg)
        assertEquals(at(day.plusDays(1), 5, 30), next)
    }

    @Test
    fun `missed reset is caught up while still in span`() {
        val next = Scheduler.nextActivation(at(day, 12, 0), at(day, 10, 5).toInstant(), cfg)
        assertEquals(at(day, 12, 0), next)
    }

    @Test
    fun `missed reset after cutoff waits for tomorrow`() {
        val next = Scheduler.nextActivation(at(day, 23, 10), at(day, 20, 30).toInstant(), cfg)
        assertEquals(at(day.plusDays(1), 5, 30), next)
    }

    @Test
    fun `fresh install before start waits for today start`() {
        assertEquals(at(day, 5, 30), Scheduler.nextActivation(at(day, 3, 0), null, cfg))
    }

    @Test
    fun `fresh install inside span primes immediately`() {
        assertEquals(at(day, 12, 0), Scheduler.nextActivation(at(day, 12, 0), null, cfg))
    }

    @Test
    fun `fresh install after cutoff waits for tomorrow`() {
        assertEquals(at(day.plusDays(1), 5, 30), Scheduler.nextActivation(at(day, 23, 0), null, cfg))
    }

    @Test
    fun `chain never crosses midnight even with early start`() {
        val early = QuotaConfig(startHour = 1, startMinute = 0)
        assertEquals(listOf("01:00", "06:00", "11:00", "16:00", "21:00"), clockList(early))
    }
}
