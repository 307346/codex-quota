package com.lcs.codexquota.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lcs.codexquota.core.Engine
import com.lcs.codexquota.data.Prefs
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Safety net for the exact alarm: Doze, OEM battery killers or a reboot can make
 * an alarm late or missing entirely. This runs every 15 minutes and only reacts
 * when the planned activation is already overdue.
 */
class MaintenanceWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs.get(ctx)
        val cfg = prefs.config()
        if (!cfg.enabled) return Result.success()

        val next = prefs.state().nextActivation ?: return Result.success()
        val now = Instant.now()
        if (now.isBefore(next)) return Result.success()

        val lateMinutes = Duration.between(next, now).toMinutes()
        // Executing inside the worker instead of a foreground service: Android 12+
        // forbids background FGS starts, but a CoroutineWorker may run for minutes.
        val engine = Engine(ctx)
        if (lateMinutes <= 90) {
            engine.runCycle("兜底补跑 +${lateMinutes}m")
        } else {
            engine.rescheduleOnly()
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "quota-maintenance"

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(3, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
