package com.tunombre.tvbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors

/**
 * Alternativa a [TvRecommendationAccessibilityService] para Fire TV, donde
 * Fire OS bloquea a nivel de plataforma que una app sideloaded active un
 * AccessibilityService (comprobado exhaustivamente: ni por ADB, ni desde la
 * propia app con WRITE_SECURE_SETTINGS, ni tras reinicio). MediaProjection,
 * en cambio, SÍ está permitido.
 *
 * Captura la pantalla periódicamente, recorta la franja donde el launcher
 * de Fire TV pinta el título de la tarjeta seleccionada (fijo: debajo del
 * breadcrumb "Inicio", encima de la fila IMDb/duración/año — confirmado en
 * dispositivo real), le pasa ese recorte a [TitleOcr], y si el texto
 * reconocido es estable (igual en dos capturas seguidas) y distinto del
 * último título ya procesado, resuelve el título en TMDb y muestra
 * [ConfirmOpenActivity] para que el usuario confirme con el mando antes de
 * abrir nada — comprobado que no existe ninguna forma de detectar un clic
 * real sobre la tarjeta sin AccessibilityService (ni InputManager, ni
 * MediaSession, ni ninguna API propia de Fire OS), así que esta
 * confirmación es la única vía para no abrir cosas por simple navegación.
 */
class FireTvCaptureService : Service() {

    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var lastRawText: String? = null
    private var stableRepeatCount = 0
    private var lastProcessedTitle: String? = null
    private var resolving = false
    private var confirmationPending = false

    private val pollTick = object : Runnable {
        override fun run() {
            try {
                pollFrame()
            } catch (e: Exception) {
                Log.e(TAG, "Excepción en pollTick", e)
            }
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        if (mediaProjection == null && intent != null) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
            if (data != null) {
                setUpProjection(resultCode, data)
            } else {
                Log.e(TAG, "Falta el Intent de resultado del permiso de captura, parando servicio")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun setUpProjection(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        // Desde Android 14, un MediaProjection se cierra si no se registra
        // un callback antes de crear el VirtualDisplay.
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection detenido por el sistema")
                stopSelf()
            }
        }, mainHandler)

        val metrics = DisplayMetrics()
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        display.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "TvBridgeCapture",
            width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, mainHandler
        )

        Log.d(TAG, "Modo Fire TV activo: capturando cada ${POLL_INTERVAL_MS}ms (${width}x$height)")
        mainHandler.post(pollTick)
    }

    private fun pollFrame() {
        val reader = imageReader ?: return
        // No solapar mientras ya se está resolviendo un título o esperando
        // confirmación del usuario sobre el anterior.
        if (resolving || confirmationPending) return
        // Nada de esto tiene sentido si ahora mismo no estás en el launcher
        // (p.ej. has abierto Prime Video, o la propia Stremio): la franja
        // fija que recortamos mostraría contenido de esa otra app, no un
        // título de recomendación, y el OCR podría "leer" cualquier cosa.
        if (!isLauncherForeground()) return

        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return

        val bitmap = try {
            imageToBitmap(image)
        } finally {
            image.close()
        } ?: return

        backgroundExecutor.execute {
            try {
                val crop = cropTitleRegion(bitmap)
                val text = TitleOcr.recognizeFirstLine(crop)?.trim()
                handleRecognizedText(text)
            } catch (e: Exception) {
                Log.e(TAG, "Error en OCR", e)
            }
        }
    }

    /** true si no hay ninguna OTRA app (que no sea el propio launcher, ni
     * nuestra app) actualmente en primer plano — es decir, presumiblemente
     * estamos viendo el launcher.
     *
     * Ojo: NO comprobamos positivamente "¿el launcher está en primer
     * plano?" con MOVE_TO_FOREGROUND, porque en este Fire OS el launcher NO
     * genera un evento nuevo cada vez que vuelves a él (comprobado con
     * `dumpsys usagestats`: tras la primera vez, nunca vuelve a aparecer un
     * MOVE_TO_FOREGROUND suyo aunque se le devuelva el foco muchas veces) —
     * si comprobásemos así, la primera vez que abrieras cualquier otra app
     * (p.ej. Stremio) el chequeo se quedaría "pegado" en esa app para
     * siempre y jamás volvería a detectar nada.
     *
     * En cambio, sí funciona bien reconstruir qué apps han tenido un
     * FOREGROUND sin su correspondiente BACKGROUND todavía (ignorando
     * nuestra propia app y el propio launcher) — eso sí se registra de
     * forma fiable para apps normales como Stremio, Netflix, etc. */
    private fun isLauncherForeground(): Boolean {
        val usageStatsManager = getSystemService(UsageStatsManager::class.java) ?: return true
        val now = System.currentTimeMillis()
        // Ventana amplia: si el usuario lleva un rato quieto dentro de otra
        // app (sin eventos nuevos), seguimos queriendo ver que esa app
        // sigue "abierta" aunque el evento de apertura sea de hace rato.
        val events = usageStatsManager.queryEvents(now - 30 * 60_000, now)
        val openForegroundApps = mutableSetOf<String>()
        val event = android.app.usage.UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName
            if (pkg == packageName || LAUNCHER_PACKAGES.contains(pkg)) continue
            when (event.eventType) {
                android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND -> openForegroundApps.add(pkg)
                android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND -> openForegroundApps.remove(pkg)
            }
        }
        return openForegroundApps.isEmpty()
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val width = image.width
            val rowPadding = rowStride - pixelStride * width
            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error convirtiendo frame a bitmap", e)
            null
        }
    }

    /** Franja fija donde el launcher de Fire TV pinta el título de la
     * tarjeta seleccionada, como fracción del ancho/alto de pantalla
     * (medido sobre una captura real en un Fire TV 1080p, ver conversación
     * de diagnóstico). Se expresa en fracciones, no en píxeles fijos, para
     * no romperse en resoluciones distintas de 1920x1080. */
    private fun cropTitleRegion(bitmap: Bitmap): Bitmap {
        val left = (bitmap.width * 0.04).toInt()
        val top = (bitmap.height * 0.12).toInt()
        val right = (bitmap.width * 0.75).toInt().coerceAtMost(bitmap.width)
        val bottom = (bitmap.height * 0.21).toInt()
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun handleRecognizedText(text: String?) {
        if (text.isNullOrBlank()) {
            lastRawText = null
            stableRepeatCount = 0
            return
        }

        if (text == lastRawText) {
            stableRepeatCount++
        } else {
            lastRawText = text
            stableRepeatCount = 1
        }

        // Solo procesamos cuando el mismo texto se ha leído igual dos veces
        // seguidas (evita disparar con OCR a medias mientras la interfaz
        // todavía está animando la transición entre tarjetas) y es distinto
        // del último título ya mostrado en el diálogo de confirmación.
        if (stableRepeatCount >= 2 && text != lastProcessedTitle) {
            resolveAndConfirm(text)
        }
    }

    private fun resolveAndConfirm(title: String) {
        if (!LicenseManager.isLikelyValid(this)) return
        resolving = true
        backgroundExecutor.execute {
            try {
                Log.d(TAG, "Título OCR: \"$title\" — resolviendo en TMDb")
                val match = TmdbClient.findImdbId(this, title)
                if (match != null) {
                    lastProcessedTitle = title
                    confirmationPending = true
                    showConfirmDialog(match)
                } else {
                    Log.d(TAG, "No se pudo resolver IMDb ID para: $title")
                }
            } finally {
                resolving = false
            }
        }
    }

    private fun showConfirmDialog(match: TmdbMatch) {
        val appLabel = Preferences.getSelectedApp(this).label
        val intent = Intent(this, ConfirmOpenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ConfirmOpenActivity.EXTRA_TITLE, match.title)
            putExtra(ConfirmOpenActivity.EXTRA_APP_LABEL, appLabel)
            putExtra(ConfirmOpenActivity.EXTRA_IMDB_ID, match.imdbId)
            putExtra(ConfirmOpenActivity.EXTRA_TYPE, match.type.name)
            putExtra(ConfirmOpenActivity.EXTRA_TMDB_ID, match.tmdbId)
        }
        startActivity(intent)
    }

    /** Llamado por [ConfirmOpenActivity] al confirmar, cancelar, o agotarse
     * el tiempo de espera — en cualquier caso, libera el bloqueo para poder
     * volver a detectar el siguiente título. */
    fun onConfirmResult() {
        confirmationPending = false
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        getString(R.string.firetv_notification_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.firetv_notification_title))
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        mainHandler.removeCallbacks(pollTick)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        backgroundExecutor.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "FireTvCapture"
        private const val NOTIFICATION_CHANNEL_ID = "firetv_capture"
        private const val NOTIFICATION_ID = 2001
        private const val POLL_INTERVAL_MS = 1000L
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private val LAUNCHER_PACKAGES = setOf(
            "com.amazon.tv.launcher",
            "com.google.android.apps.tv.launcherx"
        )

        /** Referencia al servicio en marcha, para que [ConfirmOpenActivity]
         * pueda avisarle cuando el usuario responde al diálogo. Válida solo
         * mientras el servicio está vivo (mismo proceso, sin necesidad de
         * bind/AIDL para algo tan simple). */
        var instance: FireTvCaptureService? = null
            private set
    }
}
