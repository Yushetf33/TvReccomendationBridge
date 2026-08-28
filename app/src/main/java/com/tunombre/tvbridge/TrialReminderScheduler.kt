package com.tunombre.tvbridge

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Programa los avisos de fin de prueba gratuita (24h antes, unas horas
 * antes, y justo al terminar) como trabajos puntuales de WorkManager, uno
 * por momento — mucho más simple que un único trabajo periódico que tenga
 * que llevar la cuenta de cuáles ya se mandaron.
 *
 * Se reprograma cada vez que [LicenseManager.verifyNow] confirma una fecha
 * de fin de trial (nueva o repetida): al usar ExistingWorkPolicy.REPLACE con
 * nombres fijos por tipo de aviso, no se duplican aunque se llame varias
 * veces con la misma fecha.
 */
object TrialReminderScheduler {

    enum class ReminderType { DAY_BEFORE, HOURS_BEFORE, EXPIRED }

    const val KEY_REMINDER_TYPE = "reminder_type"

    // Cuánto antes del fin del trial dispara cada aviso — EXPIRED es 0
    // (justo en el momento exacto de expirar).
    private val OFFSET_BEFORE_END_MS = mapOf(
        ReminderType.DAY_BEFORE to TimeUnit.HOURS.toMillis(24),
        ReminderType.HOURS_BEFORE to TimeUnit.HOURS.toMillis(3),
        ReminderType.EXPIRED to 0L
    )

    /** Programa los tres avisos relativos a [trialEndsAt] — los que ya
     * habrían tocado en el pasado (p.ej. si el trial ya casi termina cuando
     * se llama esto por primera vez) se omiten en vez de dispararse con
     * retraso negativo. */
    fun scheduleFor(context: Context, trialEndsAt: Long) {
        val now = System.currentTimeMillis()
        for ((type, offsetBeforeEnd) in OFFSET_BEFORE_END_MS) {
            val fireAt = trialEndsAt - offsetBeforeEnd
            val delayMs = fireAt - now
            if (delayMs <= 0) continue

            val data = Data.Builder().putString(KEY_REMINDER_TYPE, type.name).build()
            val request = OneTimeWorkRequestBuilder<TrialReminderWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                workNameFor(type),
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    /** Al confirmarse lifetime (o cualquier estado que ya no sea trial), no
     * tiene sentido que salte un aviso de "tu prueba está a punto de
     * terminar" más tarde — se cancelan los tres. */
    fun cancelAll(context: Context) {
        for (type in ReminderType.entries) {
            WorkManager.getInstance(context).cancelUniqueWork(workNameFor(type))
        }
    }

    private fun workNameFor(type: ReminderType) = "trial_reminder_${type.name}"
}
