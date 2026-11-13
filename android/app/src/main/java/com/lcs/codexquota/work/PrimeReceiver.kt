package com.lcs.codexquota.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class PrimeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PRIME) return
        Log.i(TAG, "exact alarm fired, priming")
        runCatching { PrimeService.start(context) }
            .onFailure { Log.e(TAG, "cannot start PrimeService", it) }
    }

    companion object {
        const val ACTION_PRIME = "com.lcs.codexquota.PRIME_ALARM"
        private const val TAG = "PrimeReceiver"
    }
}
