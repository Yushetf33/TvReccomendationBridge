package com.tunombre.tvbridge

import android.content.Context

/** App de destino donde se abre la ficha de la película/serie detectada. */
enum class PlayerApp(val packageName: String, val label: String) {
    NUVIO("com.nuvio.app", "Nuvio"),
    STREMIO("com.stremio.one", "Stremio"),
    PLEX("com.plexapp.android", "Plex"),
    JELLYFIN("org.jellyfin.androidtv", "Jellyfin"),
    WUPLAY("app.wuplay.androidtv", "WuPlay"),
    // Cliente de Jellyfin alternativo (ver WholphinLauncher) — mismo caso
    // de uso que JELLYFIN (autoalojado, sin catálogo compartido), pero
    // apunta a Wholphin en vez de a la app oficial para quien la use como
    // su cliente principal.
    WHOLPHIN("com.github.damontecres.wholphin", "Wholphin")
}

/** App de destino donde se abre la búsqueda de un vídeo de YouTube
 * recomendado (ver [YoutubeLauncher]) — independiente de [PlayerApp], ya
 * que no todo el mundo usa la misma app para YouTube que para películas. */
enum class YoutubeApp {
    SMARTTUBE,
    TIZENTUBE_COBALT
}

/**
 * Guarda la preferencia del usuario (Nuvio o Stremio) en SharedPreferences,
 * compartida entre MainActivity (donde se elige) y el Accessibility Service
 * (donde se usa para abrir la app correspondiente).
 */
object Preferences {
    private const val PREFS_NAME = "tvbridge_prefs"
    private const val KEY_PLAYER_APP = "player_app"

    fun getSelectedApp(context: Context): PlayerApp {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_PLAYER_APP, null) ?: return PlayerApp.NUVIO
        return try {
            PlayerApp.valueOf(stored)
        } catch (e: IllegalArgumentException) {
            PlayerApp.NUVIO
        }
    }

    fun setSelectedApp(context: Context, app: PlayerApp) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PLAYER_APP, app.name)
            .apply()
    }

    private const val KEY_YOUTUBE_APP = "youtube_app"

    fun getSelectedYoutubeApp(context: Context): YoutubeApp {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_YOUTUBE_APP, null) ?: return YoutubeApp.SMARTTUBE
        return try {
            YoutubeApp.valueOf(stored)
        } catch (e: IllegalArgumentException) {
            YoutubeApp.SMARTTUBE
        }
    }

    fun setSelectedYoutubeApp(context: Context, app: YoutubeApp) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_YOUTUBE_APP, app.name)
            .apply()
    }

    // Comprobación opcional contra el servidor Jellyfin personal del usuario
    // (ver JellyfinApiClient/StremioLauncher.tryOpenInPersonalJellyfin):
    // totalmente independiente de qué PlayerApp esté elegida arriba, para
    // que sirva incluso si el destino normal es Nuvio/Stremio/etc. — solo se
    // activa si el usuario ha rellenado los tres campos.
    private const val KEY_JELLYFIN_CHECK_ENABLED = "jellyfin_check_enabled"
    private const val KEY_JELLYFIN_SERVER_URL = "jellyfin_server_url"
    private const val KEY_JELLYFIN_API_KEY = "jellyfin_api_key"

    fun isJellyfinCheckEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_JELLYFIN_CHECK_ENABLED, false)

    fun setJellyfinCheckEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_JELLYFIN_CHECK_ENABLED, enabled)
            .apply()
    }

    fun getJellyfinServerUrl(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_JELLYFIN_SERVER_URL, null)

    fun getJellyfinApiKey(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_JELLYFIN_API_KEY, null)

    fun setJellyfinServerConfig(context: Context, serverUrl: String, apiKey: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JELLYFIN_SERVER_URL, serverUrl)
            .putString(KEY_JELLYFIN_API_KEY, apiKey)
            .apply()
    }

    // Si hay dos o más títulos EXACTOS con años distintos (ver
    // TmdbClient.TmdbResolution.Ambiguous), preguntar cuál es en vez de
    // coger el primero a ciegas. Activado por defecto — es la opción más
    // segura; un usuario que prefiera velocidad sobre precisión puede
    // desactivarlo en Ajustes.
    private const val KEY_ASK_WHEN_AMBIGUOUS = "ask_when_ambiguous"

    fun isAskWhenAmbiguousEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ASK_WHEN_AMBIGUOUS, true)

    fun setAskWhenAmbiguousEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ASK_WHEN_AMBIGUOUS, enabled)
            .apply()
    }

    // Confirmación "Watch now in {App}" antes de abrir, con reaparición tras
    // descartarla (ver WatchNowConfirmActivity) — opcional y DESACTIVADA por
    // defecto a petición expresa: cambia el comportamiento de siempre (abrir
    // directo) para cualquiera que la active, así que no debe imponerse.
    private const val KEY_WATCH_NOW_CONFIRM = "watch_now_confirm_enabled"

    fun isWatchNowConfirmEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WATCH_NOW_CONFIRM, false)

    fun setWatchNowConfirmEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WATCH_NOW_CONFIRM, enabled)
            .apply()
    }

    // Recuerda qué candidato eligió el usuario la última vez que un título
    // fue ambiguo (ver MatchPickerActivity/TmdbClient.resolveTitle), para no
    // volver a preguntar por el mismo título si la recomendación reaparece
    // más adelante (p.ej. una serie ambigua que sale varias veces en
    // "Recomendado para ti"). Clave normalizada igual que
    // TmdbClient.normalizeTitle (trim + minúsculas) para que ambos lados
    // coincidan sin tener que compartir esa función entre archivos.
    private const val KEY_DISAMBIGUATION_PREFIX = "disambig_tmdb_id_"
    private const val NO_REMEMBERED_CHOICE = -1

    private fun normalizeDisambiguationQuery(query: String) = query.trim().lowercase()

    fun getRememberedDisambiguation(context: Context, query: String): Int? {
        val key = KEY_DISAMBIGUATION_PREFIX + normalizeDisambiguationQuery(query)
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(key, NO_REMEMBERED_CHOICE)
        return value.takeIf { it != NO_REMEMBERED_CHOICE }
    }

    fun rememberDisambiguation(context: Context, query: String, tmdbId: Int) {
        val key = KEY_DISAMBIGUATION_PREFIX + normalizeDisambiguationQuery(query)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(key, tmdbId)
            .apply()
    }

    // Fila propia de "Recomendado para ti" en la pantalla de inicio (ver
    // RecommendationChannelManager) — opt-in como el resto de funciones que
    // dependen de trabajo en segundo plano, DESACTIVADA por defecto: solo
    // debe empezar a registrar historial y llamar a TMDb en segundo plano
    // si el usuario lo ha pedido explícitamente desde Ajustes.
    private const val KEY_RECOMMENDATIONS_ENABLED = "recommendations_channel_enabled"

    fun isRecommendationsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_RECOMMENDATIONS_ENABLED, false)

    fun setRecommendationsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RECOMMENDATIONS_ENABLED, enabled)
            .apply()
    }
}
