package com.lcs.codexquota.work

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.lcs.codexquota.core.CycleOutcome
import com.lcs.codexquota.core.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs exactly one activation cycle, then stops.
 *
 * Started from the exact-alarm broadcast, which Android 12+ still allows to
 * launch a foreground service from the background.
 */
class PrimeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.ensureChannels(this)
        val notification = Notifier.serviceNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Notifier.SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifier.SERVICE_ID, notification)
        }

        scope.launch {
            try {
                val outcome = Engine(this@PrimeService).runCycle("定时")
                Notifier.showResult(this@PrimeService, outcome)
                if (outcome is CycleOutcome.NeedAuth) {
                    Notifier.notify(this@PrimeService, "需要重新登录", "请在应用中重新导入 auth.json")
                }
            } catch (t: Throwable) {
                Notifier.notify(this@PrimeService, "激活异常", t.message ?: t.javaClass.simpleName)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_RUN = "com.lcs.codexquota.PRIME_NOW"

        fun start(context: Context) {
            val i = Intent(context, PrimeService::class.java).setAction(ACTION_RUN)
            ContextCompat.startForegroundService(context, i)
        }
    }
}
