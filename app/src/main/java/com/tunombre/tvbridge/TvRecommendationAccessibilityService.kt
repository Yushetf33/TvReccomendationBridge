package com.tunombre.tvbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors

/**
 * Servicio de accesibilidad que escucha clics en el launcher de Google TV
 * (com.google.android.apps.tv.launcherx) y en el de Fire TV
 * (com.amazon.tv.launcher).
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

        private const val AMAZON_LAUNCHER_PACKAGE = "com.amazon.tv.launcher"
        private const val GOOGLE_TV_LAUNCHER_PACKAGE = "com.google.android.apps.tv.launcherx"

        // En las tarjetas de contenido del launcher de Fire TV, el título vive
        // en el content-desc de este ImageView hijo, no en el nodo pulsado
        // (que siempre tiene content-desc vacío). Los iconos de apps normales
        // usan el mismo resource-id pero con content-desc vacío, lo que sirve
        // para distinguir tarjetas de contenido real de iconos de apps sin
        // necesitar una lista de apps conocidas.
        private const val FIRE_TV_MAIN_IMAGE_ID = "com.amazon.tv.launcher:id/main_image"

        // Cuando se abre una ficha de detalle sin pasar por un clic nuestro
        // (búsqueda por voz, búsqueda por texto, y algunos flujos internos
        // del propio launcher), Google TV navega a esta pantalla dentro del
        // mismo paquete del launcher. El título vive en este resource-id
        // concreto y estable — confirmado en dispositivo real disparando una
        // búsqueda por voz ("Ok Google, abre X").
        private const val ENTITY_DETAILS_TITLE_ID =
            "com.google.android.apps.tv.launcherx:id/entity_details_title_row"
    }

    // Evita relanzar el mismo título repetidas veces mientras la ficha de
    // detalle sigue en pantalla (TYPE_WINDOW_STATE_CHANGED puede dispararse
    // más de una vez para la misma ventana). Se resetea en cuanto se navega
    // a una pantalla sin esa ficha.
    private var lastHandledEntityTitle: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Configuración programática del servicio: en este dispositivo (TCL,
        // Android 12) el meta-data de accessibility_service_config.xml no se
        // estaba aplicando en tiempo de ejecución (dumpsys accessibility
        // mostraba capabilities=0, eventTypes= vacío pese a que el XML
        // compilado en el APK era correcto). Configurarlo aquí evita
        // depender de ese parseo.
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            packageNames = arrayOf(GOOGLE_TV_LAUNCHER_PACKAGE, AMAZON_LAUNCHER_PACKAGE)
            // Sin este flag, rootInActiveWindow() siempre devuelve null —
            // necesario para leer el árbol de la ficha de detalle abierta
            // por voz/búsqueda (handleEntityDetailsWindow).
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        // Refresca la verificación de suscripción en segundo plano al
        // arrancar el servicio, para que la caché (usada por isLikelyValid)
        // no dependa solo de que el usuario abra MainActivity.
        LicenseManager.getSavedEmail(this)?.let { email ->
            backgroundExecutor.execute { LicenseManager.verifyNow(this, email) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!LicenseManager.isLikelyValid(this)) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(
                TAG,
                "WINDOW_STATE_CHANGED pkg=${event.packageName} class=${event.className} " +
                    "text=${event.text} contentDesc=${event.contentDescription} " +
                    "sourceDesc=${event.source?.contentDescription} sourceText=${event.source?.text}"
            )
            if (event.packageName == GOOGLE_TV_LAUNCHER_PACKAGE) {
                handleEntityDetailsWindow()
            }
            return
        }

        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return

        Log.d(
            TAG,
            "TYPE_VIEW_CLICKED pkg=${event.packageName} class=${event.className} " +
                "text=${event.text} contentDesc=${event.contentDescription} source=${event.source != null}"
        )

        if (event.packageName == AMAZON_LAUNCHER_PACKAGE) {
            val title = extractFireTvTitle(event)
            if (!title.isNullOrBlank()) {
                Log.d(TAG, "Película/serie detectada (Fire TV): $title")
                handleMovieClick(title)
            }
            return
        }

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

        // Botón de "ver en Netflix/Prime/etc." de la ficha de detalle
        // (se llega aquí al abrir esa ficha por voz/búsqueda y pulsar el
        // botón del servicio). El botón en sí no tiene texto ni
        // content-desc propios — solo un logotipo — así que se sube por
        // los nodos padre desde el pulsado hasta encontrar el título de
        // la ficha (entity_details_title_row) como hermano. No requiere
        // rootInActiveWindow: solo navega el subárbol del propio nodo del
        // evento, que sí llega siempre con el clic.
        if (event.text.isNullOrEmpty()) {
            val entityTitle = event.source?.let { findEntityTitleFromClickedNode(it) }
            if (entityTitle != null) {
                Log.d(TAG, "Película/serie detectada (botón de ficha): $entityTitle")
                handleMovieClick(entityTitle)
                return
            }
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

    private fun handleEntityDetailsWindow() {
        val root = rootInActiveWindow
        Log.d(TAG, "handleEntityDetailsWindow: root=${root != null}")
        val title = root?.let { findEntityDetailsTitle(it) }
        if (title != null) {
            onEntityTitleFound(title)
            return
        }

        // La ventana puede tardar en poblarse (igual que el retraso ya
        // observado en algunas tarjetas de fila). Reintentamos una vez.
        Handler(Looper.getMainLooper()).postDelayed({
            val retryRoot = rootInActiveWindow
            val retryTitle = retryRoot?.let { findEntityDetailsTitle(it) }
            Log.d(TAG, "handleEntityDetailsWindow (retraso): root=${retryRoot != null} title=$retryTitle")
            if (retryTitle != null) {
                onEntityTitleFound(retryTitle)
            } else {
                lastHandledEntityTitle = null
            }
        }, 600)
    }

    private fun onEntityTitleFound(title: String) {
        if (title == lastHandledEntityTitle) return
        lastHandledEntityTitle = title
        Log.d(TAG, "Ficha de detalle detectada (voz/búsqueda): $title")
        handleMovieClick(title)
    }

    private fun findEntityTitleFromClickedNode(clicked: AccessibilityNodeInfo): String? {
        var current: AccessibilityNodeInfo? = clicked
        var level = 0
        while (current != null && level < 8) {
            val title = findEntityDetailsTitle(current)
            if (title != null) return title
            val parent = current.parent
            Log.d(TAG, "findEntityTitleFromClickedNode: level=$level parent=${parent != null}")
            current = parent
            level++
        }
        return null
    }

    private fun findEntityDetailsTitle(node: AccessibilityNodeInfo): String? {
        if (node.viewIdResourceName == ENTITY_DETAILS_TITLE_ID) {
            return node.text?.toString()?.takeIf { it.isNotBlank() }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEntityDetailsTitle(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun extractFireTvTitle(event: AccessibilityEvent): String? {
        val source = event.source ?: return null
        return findFireTvMainImageDescription(source)
    }

    private fun findFireTvMainImageDescription(node: AccessibilityNodeInfo): String? {
        if (node.viewIdResourceName == FIRE_TV_MAIN_IMAGE_ID) {
            val desc = node.contentDescription?.toString()
            if (!desc.isNullOrBlank()) return desc
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFireTvMainImageDescription(child)
            child.recycle()
            if (result != null) return result
        }
        return null
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
