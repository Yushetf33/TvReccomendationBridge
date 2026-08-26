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

    // Nombre del extra que StartupActivity.openNextActivity() lee para ir
    // directo a la ficha de un ítem (Destinations.itemDetails), en vez de a
    // una búsqueda — confirmado contra el código fuente de
    // jellyfin-androidtv (StartupActivity.EXTRA_ITEM_ID). Solo se usa
    // cuando ya sabemos el Id exacto vía JellyfinApiClient (biblioteca
    // personal del usuario, no el catálogo de búsqueda a ciegas de abajo).
    private const val EXTRA_ITEM_ID = "ItemId"

    fun openSearch(service: Context, title: String) {
        if (!StremioLauncher.isPackageInstalled(service, JELLYFIN_PACKAGE)) {
            Log.w(TAG, "Jellyfin no está instalado — abriendo su ficha en la Play Store")
            StremioLauncher.openPlayStoreListing(service, JELLYFIN_PACKAGE, "Jellyfin")
            return
        }
        val intent = Intent(Intent.ACTION_SEARCH).apply {
            putExtra(SearchManager.QUERY, title)
            component = ComponentName(JELLYFIN_PACKAGE, STARTUP_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        try {
            service.startActivity(intent)
            Log.d(TAG, "Abriendo Jellyfin con búsqueda: $title")
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir Jellyfin, ¿está instalado de verdad?", e)
            StremioLauncher.openPlayStoreListing(service, JELLYFIN_PACKAGE, "Jellyfin")
        }
    }

    /**
     * Abre Jellyfin directo en la ficha de [itemId] (ya confirmado presente
     * en la biblioteca del usuario vía JellyfinApiClient.findExactItemId) —
     * a diferencia de [openSearch], no hace falta que el usuario elija
     * entre resultados. Devuelve false si Jellyfin no está instalado o el
     * intent falla, para que el llamador (StremioLauncher) siga con el
     * flujo normal en vez de dejar al usuario sin nada.
     */
    fun openItem(service: Context, itemId: String): Boolean {
        if (!StremioLauncher.isPackageInstalled(service, JELLYFIN_PACKAGE)) {
            return false
        }
        val intent = Intent().apply {
            component = ComponentName(JELLYFIN_PACKAGE, STARTUP_ACTIVITY)
            putExtra(EXTRA_ITEM_ID, itemId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        return try {
            service.startActivity(intent)
            Log.d(TAG, "Abriendo Jellyfin directo en el ítem de la biblioteca del usuario: $itemId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir Jellyfin en el ítem $itemId", e)
            false
        }
    }
}
