package com.tunombre.tvbridge

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Redirige un vídeo de YouTube recomendado por el launcher a SmartTube.
 *
 * No tenemos el ID del vídeo (el launcher solo expone el título en el
 * content-desc, no el videoId), así que en vez de abrir el vídeo exacto
 * abrimos una búsqueda por ese mismo título dentro de SmartTube — mismo
 * resultado práctico que el usuario buscándolo él mismo, pero automático.
 *
 * Hay dos variantes de la app con distinto paquete (estable y beta), y un
 * usuario puede tener cualquiera de las dos instalada. Ambas declaran el
 * mismo intent-filter para youtube.com/https, confirmado por ADB en
 * dispositivo real. Se prueba la estable primero y, si no está instalada,
 * la beta.
 *
 * En la fila con logo de YouTube ("Videos recomendados", "Destinos
 * turísticos"...) esto gana la carrera igual que con las películas. En la
 * fila mixta "Recomendaciones destacadas para ti", la propia YouTube TV
 * suele ganar la carrera (ya está "caliente" en memoria) y se queda en
 * pantalla — probado en dispositivo real forzar la transición con
 * GLOBAL_ACTION_HOME antes del intent, pero el parpadeo resultante
 * empeora la experiencia más de lo que soluciona, así que se ha
 * descartado: en esos casos concretos simplemente se abre YouTube.
 */
object SmartTubeLauncher {

    private const val TAG = "SmartTubeLauncher"
    private val SMARTTUBE_PACKAGES = listOf("org.smarttube.stable", "org.smarttube.beta")

    fun openSearch(service: AccessibilityService, title: String) {
        val uri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(title)}")
        launch(service, uri)
    }

    private fun launch(service: AccessibilityService, uri: Uri): Boolean {
        for (targetPackage in SMARTTUBE_PACKAGES) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = uri
                setPackage(targetPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            try {
                service.startActivity(intent)
                Log.d(TAG, "Abriendo $targetPackage: $uri")
                return true
            } catch (e: Exception) {
                Log.d(TAG, "$targetPackage no disponible, probando siguiente opción", e)
            }
        }
        // Ninguna de las dos está instalada: no hacemos nada y dejamos que
        // YouTube abra con su comportamiento normal (ya en marcha por el
        // propio clic del launcher).
        Log.d(TAG, "SmartTube no disponible (ni estable ni beta), se deja el comportamiento normal")
        return false
    }
}
