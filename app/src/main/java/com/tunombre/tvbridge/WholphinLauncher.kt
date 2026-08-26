package com.tunombre.tvbridge

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Abre Wholphin (https://github.com/damontecres/Wholphin), un cliente de
 * Jellyfin de código abierto alternativo a la app oficial — mismo caso de
 * uso que [JellyfinLauncher] (autoalojado, sin catálogo ni ID compartido),
 * para quien usa Wholphin en vez de la app oficial de Jellyfin como
 * cliente principal.
 *
 * Confirmado contra el código fuente real de Wholphin (services/
 * IntentService.kt): su MainActivity acepta tanto ACTION_SEARCH (con el
 * extra estándar SearchManager.QUERY) como ACTION_VIEW con un extra
 * "itemId" (UUID del ítem en el servidor Jellyfin) para ir directo a la
 * ficha — igual patrón de dos niveles que [JellyfinLauncher.openSearch]/
 * [JellyfinLauncher.openItem].
 */
object WholphinLauncher {
    private const val TAG = "WholphinLauncher"
    private const val WHOLPHIN_PACKAGE = "com.github.damontecres.wholphin"
    private const val MAIN_ACTIVITY = "com.github.damontecres.wholphin.MainActivity"
    private const val EXTRA_ITEM_ID = "itemId"

    fun openSearch(service: Context, title: String) {
        if (!StremioLauncher.isPackageInstalled(service, WHOLPHIN_PACKAGE)) {
            Log.w(TAG, "Wholphin no está instalado — abriendo su ficha en la Play Store")
            StremioLauncher.openPlayStoreListing(service, WHOLPHIN_PACKAGE, "Wholphin")
            return
        }
        val intent = Intent(Intent.ACTION_SEARCH).apply {
            putExtra(SearchManager.QUERY, title)
            component = ComponentName(WHOLPHIN_PACKAGE, MAIN_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        try {
            service.startActivity(intent)
            Log.d(TAG, "Abriendo Wholphin con búsqueda: $title")
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir Wholphin, ¿está instalado de verdad?", e)
            StremioLauncher.openPlayStoreListing(service, WHOLPHIN_PACKAGE, "Wholphin")
        }
    }

    /** Igual que [JellyfinLauncher.openItem] — abre directo en la ficha de
     * [itemId] (ya confirmado presente en la biblioteca del usuario vía
     * JellyfinApiClient.findExactItemId), sin pasar por una búsqueda. */
    fun openItem(service: Context, itemId: String): Boolean {
        if (!StremioLauncher.isPackageInstalled(service, WHOLPHIN_PACKAGE)) {
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(WHOLPHIN_PACKAGE, MAIN_ACTIVITY)
            putExtra(EXTRA_ITEM_ID, itemId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        return try {
            service.startActivity(intent)
            Log.d(TAG, "Abriendo Wholphin directo en el ítem de la biblioteca del usuario: $itemId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir Wholphin en el ítem $itemId", e)
            false
        }
    }
}
