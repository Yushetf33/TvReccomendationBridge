package com.tunombre.tvbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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

/**
 * Servicio auxiliar y OPCIONAL para Google TV: mantiene un MediaProjection
 * listo para hacer una captura puntual bajo demanda, para cuando
 * [TvRecommendationAccessibilityService] detecta la ficha de detalle
 * abierta por búsqueda por voz pero no consigue leer el título por árbol de
 * accesibilidad — en algunos dispositivos (p.ej. TCL) `rootInActiveWindow()`
 * puede devolver un árbol sin ese nodo pese a que el resto del árbol/eventos
 * de clic funcionan con normalidad.
 *
 * A diferencia de [FireTvCaptureService] no hay ningún sondeo continuo —
 * accesibilidad sigue siendo el mecanismo principal para todo lo demás,
 * esto es solo una red de seguridad puntual para el caso de la voz. Por eso
 * es opt-in desde MainActivity: mantener el MediaProjection vivo implica la
 * notificación de grabación de pantalla siempre visible, algo que la
 * mayoría de usuarios de Google TV no necesita.
 */
class VoiceSearchCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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
        return START_STICKY
    }

    private fun setUpProjection(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

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
            "TvBridgeVoiceCapture",
            width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, mainHandler
        )
        Log.d(TAG, "VoiceSearchCaptureService listo (${width}x$height)")
    }

    /** Captura un frame y llama a [callback] con el bitmap completo (o null
     * si algo falla), en el hilo principal. Un pequeño retraso antes de leer
     * el frame le da tiempo a la ficha de detalle a terminar de pintarse. */
    fun captureFrame(callback: (Bitmap?) -> Unit) {
        val reader = imageReader
        if (reader == null) {
            callback(null)
            return
        }
        mainHandler.postDelayed({
            val image = try {
                reader.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (image == null) {
                callback(null)
                return@postDelayed
            }
            val bitmap = try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val width = image.width
                val rowPadding = rowStride - pixelStride * width
                Bitmap.createBitmap(
                    width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
                ).apply { copyPixelsFromBuffer(buffer) }
            } catch (e: Exception) {
                Log.e(TAG, "Error convirtiendo frame a bitmap", e)
                null
            } finally {
                image.close()
            }
            callback(bitmap)
        }, 300)
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        getString(R.string.voice_search_notification_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.voice_search_notification_title))
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "VoiceSearchCapture"
        private const val NOTIFICATION_CHANNEL_ID = "voice_search_capture"
        private const val NOTIFICATION_ID = 3001
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        var instance: VoiceSearchCaptureService? = null
            private set
    }
}
