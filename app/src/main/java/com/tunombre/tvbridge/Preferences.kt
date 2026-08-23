package com.tunombre.tvbridge

import android.content.Context

/** App de destino donde se abre la ficha de la película/serie detectada. */
enum class PlayerApp(val packageName: String, val label: String) {
    NUVIO("com.nuvio.app", "Nuvio"),
    STREMIO("com.stremio.one", "Stremio"),
    PLEX("com.plexapp.android", "Plex"),
    JELLYFIN("org.jellyfin.androidtv", "Jellyfin")
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
}
