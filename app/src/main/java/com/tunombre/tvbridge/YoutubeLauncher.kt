package com.tunombre.tvbridge

import android.content.Context

/** Reparte la apertura de un vídeo de YouTube recomendado a la app elegida
 * por el usuario (ver [Preferences.YoutubeApp]) — independiente de a qué
 * app se abren las películas/series. */
object YoutubeLauncher {

    fun openSearch(service: Context, title: String) {
        when (Preferences.getSelectedYoutubeApp(service)) {
            YoutubeApp.SMARTTUBE -> SmartTubeLauncher.openSearch(service, title)
            YoutubeApp.TIZENTUBE_COBALT -> TizenTubeLauncher.openSearch(service, title)
        }
    }

    /** Abre un vídeo concreto (p.ej. un tráiler) directamente, en vez de una
     * búsqueda por título — ver [SmartTubeLauncher.openWatch]/[TizenTubeLauncher.openWatch]. */
    fun openWatch(service: Context, videoKey: String) {
        when (Preferences.getSelectedYoutubeApp(service)) {
            YoutubeApp.SMARTTUBE -> SmartTubeLauncher.openWatch(service, videoKey)
            YoutubeApp.TIZENTUBE_COBALT -> TizenTubeLauncher.openWatch(service, videoKey)
        }
    }
}
