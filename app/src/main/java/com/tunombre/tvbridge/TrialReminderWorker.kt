package com.tunombre.tvbridge

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Ejecutado por WorkManager en el momento programado por
 * [TrialReminderScheduler] — muestra el aviso correspondiente, salvo que ya
 * no haya trial pendiente en caché (p.ej. si mientras tanto se verificó de
 * nuevo y resultó ser lifetime), en cuyo caso no hace nada. */
class TrialReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val typeName = inputData.getString(TrialReminderScheduler.KEY_REMINDER_TYPE)
            ?: return Result.failure()
        val type = try {
            TrialReminderScheduler.ReminderType.valueOf(typeName)
        } catch (e: IllegalArgumentException) {
            return Result.failure()
        }

        if (LicenseManager.getTrialEndsAt(applicationContext) == null) {
            return Result.success()
        }

        TrialNotificationHelper.show(applicationContext, type)
        return Result.success()
    }
}
