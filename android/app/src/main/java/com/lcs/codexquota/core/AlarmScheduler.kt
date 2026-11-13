package com.lcs.codexquota.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.lcs.codexquota.work.PrimeReceiver
import java.time.Instant

/**
 * Single exact alarm pointing at the next activation.
 *
 * setExactAndAllowWhileIdle survives Doze; the receiving broadcast is one of the
 * few cases where Android 12+ still lets us start a foreground service.
 */
object AlarmScheduler {

    private const val REQ = 0x51A7

    fun schedule(context: Context, at: Instant) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context)
        val triggerAt = at.toEpochMilli()
        val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else {
            true
        }
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            // Degraded mode: may drift by minutes under Doze, the periodic
            // worker covers the gap.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pending(context))
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, PrimeReceiver::class.java)
            .setAction(PrimeReceiver.ACTION_PRIME)
        return PendingIntent.getBroadcast(
            context,
            REQ,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
