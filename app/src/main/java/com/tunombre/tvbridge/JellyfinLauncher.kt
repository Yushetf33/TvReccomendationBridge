package com.tunombre.tvbridge

import android.app.SearchManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.util.Log

/**
 * Abre la app de Jellyfin para Android TV con una búsqueda del título, en
 * vez de ir directo a la ficha (a diferencia de Nuvio/Stremio/Plex, Jellyfin
 * es autoalojado: cada usuario tiene su propio servidor privado con IDs de
 * contenido distintos, no hay forma de saber el ID interno sin consultar su
 * servidor).
 *
 * StartupActivity declara android.intent.action.SEARCH (confirmado con
 * dumpsys en dispositivo real, v0.19.10), pero su intent-filter solo trae
 * las categorías LAUNCHER/LEANBACK_LAUNCHER, no DEFAULT — así que un intent
 * implícito con solo setPackage() no la encuentra (Context.startActivity()
 * exige category DEFAULT para resolver intents implícitos). Hay que apuntar
 * al componente explícito, igual que hace `am start -n .../.StartupActivity`
 * en los ejemplos de la comunidad.
 */
object JellyfinLauncher {
    private const val TAG = "JellyfinLauncher"
    private const val JELLYFIN_PACKAGE = "org.jellyfin.androidtv"
    private const val STARTUP_ACTIVITY = "org.jellyfin.androidtv.ui.startup.StartupActivity"

    fun openSearch(service: Context, title: String) {
        val intent = Intent(Intent.ACTION_SEARCH).apply {
            putExtra(SearchManager.QUERY, title)
            component = ComponentName(JELLYFIN_PACKAGE, STARTUP_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        try {
            service.startActivity(intent)
            Log.d(TAG, "Abriendo Jellyfin con búsqueda: $title")
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir Jellyfin. ¿Está instalado?", e)
        }
    }
}
