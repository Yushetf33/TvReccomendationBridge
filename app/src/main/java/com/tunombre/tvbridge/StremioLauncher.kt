package com.tunombre.tvbridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Lanza la app de destino elegida por el usuario (ver [Preferences]) en la
 * ficha de detalle de una película o serie.
 *
 * Nuvio: nuvio://movie/{imdbId} (películas), nuvio://detail/tv/{imdbId} (series).
 * Stremio: stremio:///detail/movie/{imdbId}, stremio:///detail/series/{imdbId}.
 * Todos confirmados por ADB en dispositivo real.
 */
object StremioLauncher {

    private const val TAG = "StremioLauncher"

    fun open(service: Context, match: TmdbMatch) {
        when (val app = Preferences.getSelectedApp(service)) {
            PlayerApp.NUVIO -> {
                val uri = if (match.type == MediaType.MOVIE) {
                    Uri.parse("nuvio://movie/${match.imdbId}")
                } else {
                    Uri.parse("nuvio://detail/tv/${match.imdbId}")
                }
                openWithFallback(service, uri, app.packageName, app.label)
            }
            PlayerApp.STREMIO -> {
                val stremioType = if (match.type == MediaType.SERIES) "series" else "movie"
                val uri = Uri.parse("stremio:///detail/$stremioType/${match.imdbId}")
                openWithFallback(service, uri, app.packageName, app.label)
            }
            PlayerApp.PLEX -> {
                // A diferencia de Nuvio/Stremio, Plex no tiene un esquema de
                // deep link por IMDb ID directo — hay que resolver antes la
                // URL de watch.plex.tv vía PlexClient (puede no encontrarse:
                // el catálogo gratuito de Plex no tiene todo lo que hay en
                // TMDb).
                val watchUrl = PlexClient.findWatchUrl(match)
                if (watchUrl == null) {
                    Log.w(TAG, "No encontrado en el catálogo gratuito de Plex: ${match.imdbId}")
                    return
                }
                openWithFallback(service, Uri.parse(watchUrl), app.packageName, app.label)
            }
            PlayerApp.JELLYFIN -> {
                // Jellyfin es autoalojado: no hay un ID de contenido
                // universal como el IMDb ID, así que se abre con una
                // búsqueda del título en vez de ir directo a la ficha.
                JellyfinLauncher.openSearch(service, match.title)
            }
        }
    }

    private fun openWithFallback(service: Context, uri: Uri, targetPackage: String, appLabel: String) {
        // FLAG_ACTIVITY_CLEAR_TASK + NEW_TASK: tanto Nuvio como Stremio
        // declaran su Activity como launchMode="singleTask". Sin CLEAR_TASK,
        // si la app ya estaba abierta en segundo plano, Android a veces solo
        // trae su task existente al frente sin volver a procesar el nuevo
        // deep link (se queda en el último título abierto). CLEAR_TASK fuerza
        // un arranque limpio de la Activity en cada apertura.
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = uri
            setPackage(targetPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        try {
            service.startActivity(intent)
            Log.d(TAG, "Abriendo $appLabel: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir $appLabel con $targetPackage, reintentando sin package", e)
            // Fallback: deja que Android elija la app que resuelva el esquema.
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = uri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                service.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Tampoco funcionó el fallback. ¿$appLabel está instalado?", e2)
            }
        }
    }
}
