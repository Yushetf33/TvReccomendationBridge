package com.tunombre.tvbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

        // Ver WatchNowConfirmActivity: necesita avisar al servicio cuando se
        // descarta (Atrás) o se confirma, y el servicio necesita poder
        // relanzarla. Mismo patrón que FireTvCaptureService.instance.
        var instance: TvRecommendationAccessibilityService? = null
            private set

        private const val WATCH_NOW_REAPPEAR_DELAY_MS = 4000L

        // Ver comentario junto a lastMovieClickHandledAt: cubre con margen
        // los ~200-400ms observados en dispositivo real entre un clic
        // resuelto directamente y el eco de EntityActivity que lo sigue.
        private const val ENTITY_ECHO_SUPPRESS_WINDOW_MS = 2000L

        // Marcadores que separan el título del resto del content-desc en las
        // tarjetas de fila. Usarlos para cortar (en vez de la primera coma)
        // evita truncar títulos que ya traen coma de por sí, como
        // "Monstruos, S.A." (cortar por la primera coma daría solo "Monstruos").
        //
        // Guardados codificados (ver Obfuscated.kt) en vez de como literales
        // de texto plano: son justo las cadenas que un tercero copió leyendo
        // el APK compilado, ya que R8 no ofusca literales de texto.
        private val TITLE_MARKERS = listOf(
            Obfuscated.decode("OS8/KS47YA=="), // "cuesta:"
            Obfuscated.decode("KT96ND85PykzLjt6LzQ7eikvKTkoMyo5M5npNHo7"), // "se necesita una suscripción a"
            Obfuscated.decode("Ki80Li87OTOZ6TRg") // "puntuación:"
        )

        // Marcador de las tarjetas de recomendación de YouTube: el launcher
        // les añade "{Título}, Duración: X minutos Y segundos" al
        // content-desc, en vez de precio/puntuación — pero en el idioma del
        // propio vídeo, no necesariamente el del dispositivo (confirmado
        // por ADB en dispositivo real con "Duration is X minutes Y
        // seconds" en un vídeo en inglés estando el resto de la interfaz
        // en español). En vez de una lista de traducciones conocidas de
        // "Duración:" (nunca cubriría todos los idiomas que YouTube
        // soporta), se detecta por la forma que tiene esa frase en
        // cualquier idioma: una o varias parejas "número + palabra"
        // (minutos, segundos, horas...) justo al final, después de la coma
        // que separa el título del resto. Se comprueba antes que
        // TITLE_MARKERS porque, si no, isMovieOrShowCard las cuela
        // igualmente como "{Título}, {resto}" y las manda a TMDb, donde
        // nunca van a encontrarse (no son películas ni series) — visto
        // también en dispositivo real.
        private val YOUTUBE_DURATION_SUFFIX_REGEX =
            Regex(Obfuscated.decode("cgY+cQYpcQYqIRYncQYpcHMha3ZpJ34="))

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

    // Cierto mientras la ficha de detalle (EntityActivity) sigue en primer
    // plano — los reintentos de OCR de tryOcrFallbackForEntityDetails() se
    // programan con postDelayed y no se cancelan solos al volver a Inicio;
    // sin esta bandera, un reintento tardío podía leer por OCR cualquier
    // texto que hubiera en la pantalla de Inicio (p.ej. el título de OTRA
    // tarjeta de recomendación) y tratarlo como si fuera un resultado de
    // búsqueda por voz — confirmado en dispositivo real: al volver rápido a
    // Inicio tras abrir "Toy Story 5", un reintento OCR tardío leyó "Scary
    // Movie" de una tarjeta distinta y abrió eso en su lugar.
    private var isOnEntityDetailsWindow = false

    // Instante del último título resuelto vía handleMovieClick (clic
    // directo, ficha por voz, u OCR) — ver el eco de EntityActivity más
    // arriba en onAccessibilityEvent.
    private var lastMovieClickHandledAt = 0L

    // Recomendación pendiente de confirmar por WatchNowConfirmActivity (ver
    // Preferences.isWatchNowConfirmEnabled) — null si esa opción está
    // desactivada o no hay ninguna pendiente ahora mismo.
    private var pendingWatchNowMatch: TmdbMatch? = null
    private var pendingWatchNowAppLabel: String? = null
    private val watchNowHandler = Handler(Looper.getMainLooper())
    private val watchNowReappearRunnable = Runnable {
        pendingWatchNowMatch?.let { showWatchNowConfirm(it, pendingWatchNowAppLabel.orEmpty()) }
    }

    // ACTION_SCREEN_ON no se puede declarar en el manifest (broadcast
    // implícito restringido desde Android 8), así que se registra aquí en
    // caliente, ya que este servicio vive todo el tiempo que la TV está en
    // uso. Sirve para comprobar actualizaciones cada vez que se enciende la
    // TV, ya que en la mayoría de casos eso es solo salir de standby, no un
    // reinicio real de Android (BOOT_COMPLETED no llegaría a dispararse).
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            backgroundExecutor.execute { UpdateChecker.checkAndDownloadIfNewer(applicationContext) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

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

        // No debe poder tumbar el servicio de accesibilidad si algo va mal
        // aquí (p.ej. una ROM que trate distinto el registro de un receiver
        // dinámico) — lo esencial (detectar clics) no debe depender de esto.
        try {
            registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
            Log.d(TAG, "screenOnReceiver registrado")
            // También al arrancar el servicio (p.ej. tras un reinicio real, o
            // la primera vez que se activa), por si la TV no llega a apagar
            // nunca la pantalla entre medias.
            backgroundExecutor.execute {
                Log.d(TAG, "Comprobando actualizaciones al arrancar el servicio")
                UpdateChecker.checkAndDownloadIfNewer(applicationContext)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error preparando el comprobador de actualizaciones", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // La comprobación de licencia se hace en handleMovieClick, no aquí
        // arriba — este método recibe TODOS los eventos de la pantalla
        // (cualquier cambio de ventana, no solo clics de recomendación), así
        // que cortar aquí en cuanto la licencia no es válida impediría que
        // el aviso de "prueba terminada" (ver handleMovieClick) llegase a
        // mostrarse nunca.

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(
                TAG,
                "WINDOW_STATE_CHANGED pkg=${event.packageName} class=${event.className} " +
                    "text=${event.text} contentDesc=${event.contentDescription} " +
                    "sourceDesc=${event.source?.contentDescription} sourceText=${event.source?.text}"
            )
            // La ficha de detalle abierta por búsqueda de voz (o por texto)
            // navega a esta Activity concreta dentro del launcher —
            // confirmado en dispositivo real disparando una búsqueda por voz
            // ("Ok Google, abre X"). Es la única señal fiable en este
            // dispositivo, donde rootInActiveWindow() falla siempre: sin
            // este filtro, cualquier otro cambio de ventana del launcher
            // (volver a Inicio, etc.) también intentaría procesarse.
            if (event.packageName == GOOGLE_TV_LAUNCHER_PACKAGE &&
                event.className?.toString()?.endsWith(".entity.EntityActivity") == true
            ) {
                // Un clic normal sobre una tarjeta (ya resuelto directamente
                // por content-desc, sin pasar por aquí) puede hacer que el
                // propio launcher pase de refilón por esta misma pantalla
                // como efecto secundario — confirmado en dispositivo real:
                // "The Super Mario Galaxy Movie" resuelto y abierto al
                // instante, y ~400ms después este evento llega igualmente y
                // rearma el reintento de OCR, que 10s más tarde lee
                // cualquier cosa de lo que haya en pantalla en ESE momento
                // (ya la app de destino) y abre eso también. Si acabamos de
                // resolver un título por esa vía hace un instante, este
                // EntityActivity es ese eco, no una ficha nueva que
                // necesite OCR — se ignora.
                if (System.currentTimeMillis() - lastMovieClickHandledAt < ENTITY_ECHO_SUPPRESS_WINDOW_MS) {
                    Log.d(TAG, "EntityActivity ignorada: eco de un clic ya resuelto hace <${ENTITY_ECHO_SUPPRESS_WINDOW_MS}ms")
                } else {
                    isOnEntityDetailsWindow = true
                    handleEntityDetailsWindow()
                }
            } else {
                // Al salir de la ficha de detalle (Inicio, otra app...) se
                // libera el título ya procesado, para poder volver a buscar
                // por voz ese mismo título más tarde sin que el filtro
                // anti-doble-disparo de arriba lo bloquee para siempre. Esto
                // es inofensivo hacerlo siempre: en el peor caso permite un
                // duplicado.
                lastHandledEntityTitle = null

                // Cortar el reintento de OCR pendiente y el watch-now (ver
                // isOnEntityDetailsWindow más arriba) es más delicado: el
                // propio launcher dispara eventos de ventana internos y
                // transitorios (p.ej. un FrameLayout "Pantalla de inicio del
                // usuario principal") DURANTE el flujo normal de abrir una
                // ficha, sin que el usuario haya salido de verdad —
                // confirmado en dispositivo real que cortar ahí mataba el
                // reintento de OCR antes de que le diera tiempo a leer el
                // título, dejando la app sin reaccionar a partir del segundo
                // clic. Solo se corta ante la señal fiable de haber vuelto
                // de verdad a Inicio (HomeActivity).
                if (event.className?.toString()?.endsWith(".home.HomeActivity") == true) {
                    isOnEntityDetailsWindow = false
                    cancelPendingWatchNow()
                }
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
            val youtubeTitle = extractYoutubeTitle(desc)
            if (youtubeTitle != null) {
                Log.d(TAG, "Vídeo de YouTube detectado: $youtubeTitle")
                YoutubeLauncher.openSearch(this, youtubeTitle)
                return
            }
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
            if (!isOnEntityDetailsWindow) return@postDelayed
            val retryRoot = rootInActiveWindow
            val retryTitle = retryRoot?.let { findEntityDetailsTitle(it) }
            Log.d(TAG, "handleEntityDetailsWindow (retraso): root=${retryRoot != null} title=$retryTitle")
            if (retryTitle != null) {
                onEntityTitleFound(retryTitle)
            } else {
                tryOcrFallbackForEntityDetails()
            }
        }, 600)
    }

    /** Red de seguridad opcional (ver [VoiceSearchCaptureService]): si el
     * árbol de accesibilidad no trae el título (algunos dispositivos, p.ej.
     * TCL, bloquean esto específicamente para esta pantalla) y el usuario ha
     * activado la captura de pantalla para búsqueda por voz, intenta leer el
     * título con OCR en su lugar. Si no está activada, no hace nada — sigue
     * siendo un límite conocido, no un fallo silencioso.
     *
     * Reintenta una vez con más margen: confirmado en dispositivo real que
     * al abrir la ficha con el botón del micro del mando el primer intento
     * ya encuentra el título, pero al abrirla diciendo "Ok Google" (hotword
     * del televisor) el primer intento sale en blanco — probablemente por el
     * paso extra de red/reconocimiento de ese camino, que retrasa cuándo
     * termina de pintarse el título. */
    private fun tryOcrFallbackForEntityDetails(attempt: Int = 1) {
        if (!isOnEntityDetailsWindow) {
            // Ya no estamos en la ficha de detalle (el usuario volvió a
            // Inicio, p.ej.) — abortar en vez de hacer OCR a ciegas de lo
            // que sea que haya en pantalla ahora.
            return
        }
        val captureService = VoiceSearchCaptureService.instance
        if (captureService == null) {
            lastHandledEntityTitle = null
            return
        }
        captureService.captureFrame { bitmap ->
            backgroundExecutor.execute {
                if (!isOnEntityDetailsWindow) return@execute
                val title = bitmap?.let {
                    try {
                        TitleOcr.recognizeFirstLine(cropEntityDetailsTitleRegion(it))?.trim()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error en OCR de ficha de detalle", e)
                        null
                    }
                }
                Log.d(TAG, "OCR de ficha de detalle (búsqueda por voz, intento $attempt): \"$title\"")
                if (!isOnEntityDetailsWindow) return@execute
                if (!title.isNullOrBlank()) {
                    onEntityTitleFound(title)
                } else if (attempt < 3) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        tryOcrFallbackForEntityDetails(attempt + 1)
                    }, 700)
                } else {
                    lastHandledEntityTitle = null
                }
            }
        }
    }

    /** Franja donde está el título en la ficha de detalle del launcher de
     * Google TV — medido sobre una captura real en dispositivo (búsqueda
     * por voz de "La casa de papel"), igual que se hizo para Fire TV. */
    private fun cropEntityDetailsTitleRegion(bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
        val left = (bitmap.width * 0.04).toInt()
        val top = (bitmap.height * 0.32).toInt()
        val right = (bitmap.width * 0.75).toInt().coerceAtMost(bitmap.width)
        val bottom = (bitmap.height * 0.52).toInt()
        return android.graphics.Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
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

    private fun extractYoutubeTitle(contentDesc: String): String? {
        val commaIndex = contentDesc.indexOf(',')
        if (commaIndex <= 0) return null
        val rest = contentDesc.substring(commaIndex + 1).trim()
        if (!YOUTUBE_DURATION_SUFFIX_REGEX.containsMatchIn(rest)) return null
        return contentDesc.substring(0, commaIndex).trim().takeIf { it.isNotBlank() }
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
        // Un título ya se ha resuelto para este ciclo (por clic directo o
        // por el propio OCR) — cualquier reintento de OCR de respaldo que
        // siga pendiente (ver tryOcrFallbackForEntityDetails) queda
        // obsoleto a partir de aquí. Sin esto: un clic normal que SÍ abre
        // bien la app puede, unos segundos después, seguir teniendo vivo el
        // reintento de OCR programado por el breve paso por la ficha de
        // detalle del launcher — y como ya no estamos viendo el launcher
        // (la app de destino ya está en primer plano, fuera de los paquetes
        // que este servicio observa, así que nunca llega un evento que
        // cancele el reintento por sí solo), ese reintento acaba haciendo
        // OCR de lo que sea que haya en pantalla EN ESE MOMENTO — la propia
        // app de destino — y abre lo que sea que haya leído ahí. Confirmado
        // en dispositivo real.
        isOnEntityDetailsWindow = false
        // Ver el eco de EntityActivity en onAccessibilityEvent: marca este
        // instante para poder distinguir un WINDOW_STATE_CHANGED genuino
        // (una ficha nueva de verdad) de uno que es solo efecto secundario
        // de este mismo clic ya resuelto.
        lastMovieClickHandledAt = System.currentTimeMillis()

        if (!LicenseManager.isLikelyValid(this)) {
            // Justo el momento de mayor intención de compra: el usuario
            // acaba de intentar usar la función que le interesa, en vez de
            // dejar que la app se quede muda sin explicar por qué.
            startActivity(
                Intent(this, TrialExpiredActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }

        backgroundExecutor.execute {
            when (val resolution = TmdbClient.resolve(this, title)) {
                is TmdbResolution.Resolved -> {
                    Log.d(TAG, "IMDb ID resuelto: $title -> ${resolution.match.imdbId} (${resolution.match.type})")
                    openOrConfirm(resolution.match)
                }
                is TmdbResolution.Ambiguous -> {
                    if (Preferences.isAskWhenAmbiguousEnabled(this)) {
                        Log.d(TAG, "Match ambiguo para \"$title\" (${resolution.candidates.size} candidatos) — preguntando al usuario")
                        MatchPickerActivity.launch(this, resolution)
                    } else {
                        val match = TmdbClient.resolveCandidate(resolution.candidates.first())
                        if (match == null) {
                            Log.w(TAG, "No se pudo resolver el primer candidato ambiguo para: $title")
                        } else {
                            openOrConfirm(match)
                        }
                    }
                }
                null -> Log.w(TAG, "No se pudo resolver IMDb ID para: $title")
            }
        }
    }

    /** Abre directo, salvo que el usuario haya activado la confirmación
     * "Watch now" opcional (ver Preferences.isWatchNowConfirmEnabled) — en
     * ese caso muestra WatchNowConfirmActivity en su lugar y espera a que
     * confirme. NOTA: [MatchPickerActivity] no pasa por aquí — elegir entre
     * varias opciones ya es en sí mismo un paso de confirmación explícito,
     * apilar un segundo encima sería redundante. */
    private fun openOrConfirm(match: TmdbMatch) {
        if (!Preferences.isWatchNowConfirmEnabled(this)) {
            StremioLauncher.open(this, match)
            return
        }
        showWatchNowConfirm(match, Preferences.getSelectedApp(this).label)
    }

    private fun showWatchNowConfirm(match: TmdbMatch, appLabel: String) {
        pendingWatchNowMatch = match
        pendingWatchNowAppLabel = appLabel
        watchNowHandler.removeCallbacks(watchNowReappearRunnable)
        val intent = Intent(this, WatchNowConfirmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(WatchNowConfirmActivity.EXTRA_TITLE, match.title)
            putExtra(WatchNowConfirmActivity.EXTRA_APP_LABEL, appLabel)
            putExtra(WatchNowConfirmActivity.EXTRA_IMDB_ID, match.imdbId)
            putExtra(WatchNowConfirmActivity.EXTRA_TYPE, match.type.name)
            putExtra(WatchNowConfirmActivity.EXTRA_TMDB_ID, match.tmdbId)
        }
        startActivity(intent)
    }

    /** Llamado por WatchNowConfirmActivity cuando se descarta (Atrás) sin
     * confirmar — la reprograma para dentro de unos segundos, salvo que
     * mientras tanto ya se haya cancelado (ver cancelPendingWatchNow, p.ej.
     * al volver a Inicio). */
    fun onWatchNowDismissed() {
        if (pendingWatchNowMatch == null) return
        watchNowHandler.postDelayed(watchNowReappearRunnable, WATCH_NOW_REAPPEAR_DELAY_MS)
    }

    /** Llamado por WatchNowConfirmActivity cuando el usuario confirma — ya
     * abrió la app elegida, aquí solo se limpia el estado pendiente. */
    fun onWatchNowConfirmed() {
        pendingWatchNowMatch = null
        pendingWatchNowAppLabel = null
    }

    private fun cancelPendingWatchNow() {
        if (pendingWatchNowMatch == null) return
        pendingWatchNowMatch = null
        pendingWatchNowAppLabel = null
        watchNowHandler.removeCallbacks(watchNowReappearRunnable)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Servicio interrumpido")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        watchNowHandler.removeCallbacks(watchNowReappearRunnable)
        unregisterReceiver(screenOnReceiver)
        backgroundExecutor.shutdown()
    }
}
