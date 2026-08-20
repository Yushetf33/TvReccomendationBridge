package com.tunombre.tvbridge

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Lanza Nuvio (com.nuvio.app) en la ficha de detalle de una película o serie.
 *
 * Películas: nuvio://movie/{imdbId}
 * Series: nuvio://detail/tv/{imdbId}
 * Ambos confirmados por ADB en el dispositivo de pruebas.
 */
object StremioLauncher {

    private const val TAG = "StremioLauncher"

    // Confirmado por ADB en el dispositivo de pruebas:
    //   adb shell pm list packages | findstr nuvio
    private const val NUVIO_PACKAGE = "com.nuvio.app"

    fun open(service: AccessibilityService, match: TmdbMatch) {
        val uri = if (match.type == MediaType.MOVIE) {
            Uri.parse("nuvio://movie/${match.imdbId}")
        } else {
            Uri.parse("nuvio://detail/tv/${match.imdbId}")
        }
        openWithFallback(service, uri, NUVIO_PACKAGE, "Nuvio")
    }

    private fun openWithFallback(service: AccessibilityService, uri: Uri, targetPackage: String, appLabel: String) {
        // FLAG_ACTIVITY_CLEAR_TASK + NEW_TASK: tanto Nuvio como Stremio
        // declaran su Activity como launchMode="singleTask". Sin CLEAR_TASK,
        // si la app ya estaba abierta en segundo plano, Android a veces solo
        // trae su task existente al frente sin volver a procesar el nuevo
        // deep link (se queda en el último título abierto). CLEAR_TASK fuerza
        // un arranque limpio de la Activity en cada apertura.
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = uri
            setPackage(targetPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        try {
            service.startActivity(intent)
            Log.d(TAG, "Abriendo $appLabel: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir $appLabel con $targetPackage, reintentando sin package", e)
            // Fallback: deja que Android elija la app que resuelva el esquema.
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = uri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                service.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Tampoco funcionó el fallback. ¿$appLabel está instalado?", e2)
            }
        }
    }
}
