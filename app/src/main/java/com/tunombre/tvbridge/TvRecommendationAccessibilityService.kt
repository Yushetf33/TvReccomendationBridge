package com.tunombre.tvbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors

/**
 * Servicio de accesibilidad que escucha clics en el launcher de Android TV
 * (com.google.android.apps.tv.launcherx).
 *
 * Comportamiento:
 *  - No hace nada si no hay una suscripción verificada vigente (ver
 *    [LicenseManager]).
 *  - Si el nodo pulsado es una tarjeta de película/serie recomendada
 *    (detectado por patrones típicos del content-desc, como "cuesta:" o
 *    "puntuación:"), extrae el título, lo resuelve a un IMDb ID vía TMDb,
 *    y abre la app elegida (Nuvio o Stremio) directamente en la ficha de
 *    esa película o serie.
 *  - Para cualquier otro clic (iconos de apps, fila "Tus aplicaciones",
 *    etc.) no hace absolutamente nada: el sistema procesa el clic con su
 *    comportamiento normal.
 */
class TvRecommendationAccessibilityService : AccessibilityService() {

    // Un solo hilo de fondo para las llamadas de red (TMDb), para no
    // bloquear nunca el hilo principal del servicio de accesibilidad.
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    companion object {
        private const val TAG = "TvRecService"

        // Marcadores que separan el título del resto del content-desc en las
        // tarjetas de fila. Usarlos para cortar (en vez de la primera coma)
        // evita truncar títulos que ya traen coma de por sí, como
        // "Monstruos, S.A." (cortar por la primera coma daría solo "Monstruos").
        private val TITLE_MARKERS = listOf("cuesta:", "se necesita una suscripción a", "puntuación:")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Configuración programática del servicio: en este dispositivo (TCL,
        // Android 12) el meta-data de accessibility_service_config.xml no se
        // estaba aplicando en tiempo de ejecución (dumpsys accessibility
        // mostraba capabilities=0, eventTypes= vacío pese a que el XML
        // compilado en el APK era correcto). Configurarlo aquí evita
        // depender de ese parseo.
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            packageNames = arrayOf("com.google.android.apps.tv.launcherx")
        }

        // Refresca la verificación de suscripción en segundo plano al
        // arrancar el servicio, para que la caché (usada por isLikelyValid)
        // no dependa solo de que el usuario abra MainActivity.
        LicenseManager.getSavedEmail(this)?.let { email ->
            backgroundExecutor.execute { LicenseManager.verifyNow(this, email) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        if (!LicenseManager.isLikelyValid(this)) return

        // El content-desc de las tarjetas de recomendación del launcher viaja
        // en event.contentDescription, NO en event.source.contentDescription
        // (que siempre es null para estas tarjetas). Confirmado con logging
        // en dispositivo real.
        val desc = event.contentDescription?.toString()
        if (!desc.isNullOrBlank()) {
            if (isMovieOrShowCard(event, desc)) {
                val title = extractTitle(desc)
                if (title.isNotBlank()) {
                    Log.d(TAG, "Película/serie detectada: $title")
                    handleMovieClick(title)
                }
            }
            return
        }

        // Cartel grande con autoplay (fila superior de "Inicio"): el título
        // viaja en event.text, no en contentDescription. Formato:
        // [Título, subtítulo, sinopsis, CTA]. Los patrocinados van primero
        // con "Patrocinado" y se ignoran (no son recomendaciones reales).
        val heroTitle = extractHeroTitle(event)
        if (heroTitle != null) {
            Log.d(TAG, "Película/serie detectada (cartel grande): $heroTitle")
            handleMovieClick(heroTitle)
            return
        }

        // Algunas filas (p.ej. RTVE en "Recomendaciones destacadas" de
        // Inicio) rellenan el content-desc del nodo con retraso tras el
        // clic: en el momento del evento aún está vacío. Reintentamos una
        // vez, poco después, releyendo el nodo.
        val source = event.source ?: return
        Handler(Looper.getMainLooper()).postDelayed({
            source.refresh()
            val delayedDesc = source.contentDescription?.toString()
            if (!delayedDesc.isNullOrBlank() && isMovieOrShowCard(event, delayedDesc)) {
                val title = extractTitle(delayedDesc)
                if (title.isNotBlank()) {
                    Log.d(TAG, "Película/serie detectada (retraso): $title")
                    handleMovieClick(title)
                }
            }
        }, 600)
    }

    private fun extractHeroTitle(event: AccessibilityEvent): String? {
        if (event.className != "android.view.ViewGroup") return null
        val parts = event.text
        if (parts.isNullOrEmpty()) return null
        val first = parts[0]?.toString()?.trim() ?: return null
        if (first.isBlank() || first.equals("Patrocinado", ignoreCase = true)) return null
        return first
    }

    private fun isMovieOrShowCard(event: AccessibilityEvent, contentDesc: String): Boolean {
        if (TITLE_MARKERS.any { contentDesc.contains(it) }) {
            return true
        }

        // Otros formatos sin marcador de precio/puntuación:
        //  - Carteles grandes ("Google TV") de Películas/Series: "{Título}, {sinopsis}".
        //  - Plataformas gratuitas, p.ej. RTVE Play: "{Título}, RTVE Play".
        // Ambos son "{Título}, {resto}" en una tarjeta real android.view.View
        // sin texto propio. Los banners/anuncios (p.ej. "Netflix, Ver ahora")
        // son android.view.ViewGroup y sí traen texto ("VER AHORA") — así los
        // distinguimos sin necesitar una lista de plataformas conocidas.
        if (event.className != "android.view.View") return false
        if (!event.text.isNullOrEmpty()) return false
        val commaIndex = contentDesc.indexOf(',')
        if (commaIndex <= 0) return false
        return contentDesc.substring(commaIndex + 1).isNotBlank()
    }

    private fun extractTitle(contentDesc: String): String {
        val markerIndex = TITLE_MARKERS
            .map { contentDesc.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()

        if (markerIndex != null) {
            return contentDesc.substring(0, markerIndex).trim().trimEnd(',').trim()
        }

        // Formato de cartel grande sin marcador: "{Título}, {sinopsis}".
        return contentDesc.substringBefore(",").trim()
    }

    private fun handleMovieClick(title: String) {
        backgroundExecutor.execute {
            val match = TmdbClient.findImdbId(title)
            if (match == null) {
                Log.w(TAG, "No se pudo resolver IMDb ID para: $title")
                return@execute
            }
            Log.d(TAG, "IMDb ID resuelto: $title -> ${match.imdbId} (${match.type})")
            StremioLauncher.open(this, match)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Servicio interrumpido")
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdown()
    }
}
