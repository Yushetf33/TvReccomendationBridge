package com.tunombre.tvbridge

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Ejecutado periódicamente por WorkManager (ver UpdateChecker.schedulePeriodicCheck). */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        UpdateChecker.checkAndDownloadIfNewer(applicationContext)
        return Result.success()
    }
}
