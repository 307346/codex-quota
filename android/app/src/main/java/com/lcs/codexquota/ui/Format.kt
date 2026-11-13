package com.lcs.codexquota.ui

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val CLOCK_SEC: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DAY_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

fun Instant.clock(): String = atZone(ZoneId.systemDefault()).format(CLOCK)
fun Instant.clockSec(): String = atZone(ZoneId.systemDefault()).format(CLOCK_SEC)
fun Instant.dayClock(): String = atZone(ZoneId.systemDefault()).format(DAY_CLOCK)
fun LocalTime.hhmm(): String = format(CLOCK)

/** "4h33m" / "12m30s" / "已到" */
fun formatCountdown(target: Instant?, now: Instant): String {
    if (target == null) return "--"
    val d = Duration.between(now, target)
    if (d.isNegative) return "已到"
    val h = d.toHours()
    val m = d.toMinutes() % 60
    val s = d.seconds % 60
    return when {
        h > 0 -> "${h}小时${m}分"
        m > 0 -> "${m}分${s}秒"
        else -> "${s}秒"
    }
}

fun formatSpan(hours: Int, minutes: Int): String {
    val h = if (hours > 0) "${hours}小时" else ""
    return "$h${minutes}分"
}
