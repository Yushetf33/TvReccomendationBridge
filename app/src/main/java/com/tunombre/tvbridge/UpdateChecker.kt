package com.tunombre.tvbridge

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Comprueba periódicamente si hay una versión más nueva publicada en las
 * Releases del repo público, y si la hay, la descarga sola en segundo plano
 * vía DownloadManager. Android exige confirmación manual del usuario para
 * instalar cualquier APK fuera de Play Store — no hay forma de saltarse eso
 * — así que lo más "automático" posible es dejar solo ese último toque.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val RELEASES_LATEST_URL =
        "https://api.github.com/repos/Yushetf33/TvReccomendationBridge/releases/latest"
    private const val APK_ASSET_NAME = "app-release.apk"
    private const val DOWNLOAD_FILE_NAME = "tvbridge-update.apk"
    private const val PREFS_NAME = "tvbridge_update"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_DOWNLOAD_VERSION = "download_version"
    private const val NOTIFICATION_CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 1001
    private const val WORK_NAME = "update_check"
    private const val KEY_LAST_CHECK_AT = "last_check_at"

    // Una TV se enciende/apaga (standby) muchas veces al día sin que eso
    // sea un reinicio real de Android — por eso el chequeo se dispara en
    // cada ACTION_SCREEN_ON (ver TvRecommendationAccessibilityService) en
    // vez de esperar a BOOT_COMPLETED, que casi nunca llegaría a dispararse.
    // Este mínimo evita machacar la API de GitHub si el usuario enciende y
    // apaga la TV varias veces seguidas; el chequeo periódico de cada 3 días
    // queda solo como red de seguridad por si la TV se deja siempre encendida.
    private val MIN_CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(4)

    // Con los valores por defecto de OkHttp, un chequeo en segundo plano en
    // una TV con una red rara (DNS lento, sin salida real a internet aunque
    // el wifi esté "conectado", etc.) puede quedarse colgado mucho más de lo
    // esperable. Un timeout corto y explícito asegura que nunca bloquee el
    // hilo de fondo del servicio de accesibilidad indefinidamente.
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Registra el chequeo periódico como red de seguridad (cada 3 días),
     * para el caso de una TV que se deja siempre encendida y por tanto no
     * dispara ACTION_SCREEN_ON. Llamar una vez, p.ej. desde
     * MainActivity.onCreate — enqueueUniquePeriodicWork con KEEP hace que
     * llamarlo varias veces no reprograme nada. */
    fun schedulePeriodicCheck(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(3, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** Comprueba la última release y, si es más nueva que la instalada,
     * lanza la descarga. Bloqueante — llamar desde un hilo de fondo.
     * No hace nada si ya se comprobó hace menos de [MIN_CHECK_INTERVAL_MS]. */
    fun checkAndDownloadIfNewer(context: Context) {
        val prefs = prefs(context)
        val lastCheckAt = prefs.getLong(KEY_LAST_CHECK_AT, 0L)
        if (System.currentTimeMillis() - lastCheckAt < MIN_CHECK_INTERVAL_MS) {
            Log.d(TAG, "Chequeo omitido: ya se comprobó hace menos de ${MIN_CHECK_INTERVAL_MS / 60000} min")
            return
        }
        prefs.edit().putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis()).apply()

        val release = fetchLatestRelease() ?: return
        val latestVersion = release.tagName.removePrefix("v")
        if (!isNewer(latestVersion, BuildConfig.VERSION_NAME)) {
            Log.d(TAG, "Ya en la última versión ($latestVersion)")
            return
        }
        if (release.apkUrl == null) {
            Log.w(TAG, "Release $latestVersion no tiene asset $APK_ASSET_NAME")
            return
        }
        startDownload(context, release.apkUrl, latestVersion)
    }

    /** Llamado por [UpdateDownloadReceiver] cuando DownloadManager termina
     * una descarga (comprueba que sea la nuestra antes de notificar). */
    fun onDownloadComplete(context: Context, completedDownloadId: Long) {
        val prefs = prefs(context)
        val ourDownloadId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (completedDownloadId != ourDownloadId) return

        val version = prefs.getString(KEY_DOWNLOAD_VERSION, null) ?: return
        val file = downloadedFile(context)
        if (!file.exists()) return

        showInstallNotification(context, file, version)
    }

    private data class LatestRelease(val tagName: String, val apkUrl: String?)

    private fun fetchLatestRelease(): LatestRelease? {
        val request = Request.Builder().url(RELEASES_LATEST_URL).build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "GitHub respondió ${response.code} comprobando actualizaciones")
                    return null
                }
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "")
                if (tagName.isBlank()) return null
                var apkUrl: String? = null
                json.optJSONArray("assets")?.let { assets ->
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.optString("name") == APK_ASSET_NAME) {
                            apkUrl = asset.optString("browser_download_url")
                        }
                    }
                }
                LatestRelease(tagName, apkUrl)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error comprobando actualizaciones", e)
            null
        }
    }

    /** Compara versiones "X.Y.Z" numéricamente, componente a componente. */
    private fun isNewer(remote: String, local: String): Boolean {
        val remoteParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val localParts = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(remoteParts.size, localParts.size)) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }

    private fun downloadedFile(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_FILE_NAME)

    private fun startDownload(context: Context, apkUrl: String, version: String) {
        // Por si quedó una descarga anterior a medias, para no dejar restos
        // de una versión vieja mezclados con la nueva.
        downloadedFile(context).let { if (it.exists()) it.delete() }

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle(context.getString(R.string.update_notification_downloading_title))
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, DOWNLOAD_FILE_NAME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)
        prefs(context).edit()
            .putLong(KEY_DOWNLOAD_ID, downloadId)
            .putString(KEY_DOWNLOAD_VERSION, version)
            .apply()
        Log.d(TAG, "Descargando actualización $version (downloadId=$downloadId)")
    }

    private fun showInstallNotification(context: Context, apkFile: File, version: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        ensureNotificationChannel(context)

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.update_notification_ready_title))
            .setContentText(context.getString(R.string.update_notification_ready_text, version))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.update_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
