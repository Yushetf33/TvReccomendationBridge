package com.tunombre.tvbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/** Construye y muestra los avisos de fin de prueba gratuita (ver
 * [TrialReminderWorker]) — mismo patrón de canal/notificación que
 * [UpdateChecker], en un canal aparte porque esto es sobre la suscripción,
 * no sobre actualizaciones de la app. */
object TrialNotificationHelper {

    private const val CHANNEL_ID = "trial"
    private const val NOTIFICATION_ID = 2001

    fun show(context: Context, type: TrialReminderScheduler.ReminderType) {
        ensureChannel(context)

        val (titleRes, textRes) = when (type) {
            TrialReminderScheduler.ReminderType.DAY_BEFORE ->
                R.string.trial_notif_day_before_title to R.string.trial_notif_day_before_text
            TrialReminderScheduler.ReminderType.HOURS_BEFORE ->
                R.string.trial_notif_hours_before_title to R.string.trial_notif_hours_before_text
            TrialReminderScheduler.ReminderType.EXPIRED ->
                R.string.trial_notif_expired_title to R.string.trial_notif_expired_text
        }

        val intent = Intent(context, SubscriptionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, type.ordinal, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(titleRes))
            .setContentText(context.getString(textRes))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Mismo NOTIFICATION_ID para los tres — si llegan a coincidir en el
        // tiempo (no debería, pero por si acaso), el más reciente
        // reemplaza al anterior en vez de amontonarse.
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.trial_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager.createNotificationChannel(channel)
    }
}
