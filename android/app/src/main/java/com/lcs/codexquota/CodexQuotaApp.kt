package com.lcs.codexquota

import android.app.Application
import com.lcs.codexquota.work.MaintenanceWorker
import com.lcs.codexquota.work.Notifier

class CodexQuotaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        MaintenanceWorker.ensureScheduled(this)
    }
}
