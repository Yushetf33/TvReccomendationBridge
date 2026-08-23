package com.tunombre.tvbridge

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Cliente mínimo para el servicio de metadatos compartido de Plex: dado un
 * IMDb ID, busca si esa película/serie está en el catálogo gratuito de
 * streaming de Plex (watch.plex.tv) y devuelve la URL directa de su ficha.
 *
 * Es un servicio de metadatos GENERAL, no ligado a ninguna biblioteca
 * personal — el mismo token (de una sola cuenta) sirve para resolver
 * cualquier título, sin que el usuario tenga que conectar su propia cuenta
 * de Plex. Confirmado por ADB en dispositivo real (Iron Man y Z Nation).
 *
 * El token se lee de BuildConfig.PLEX_API_TOKEN, generado a partir de
 * PLEX_API_TOKEN en local.properties (no versionado).
 */
object PlexClient {

    private val PLEX_API_TOKEN = BuildConfig.PLEX_API_TOKEN
    private const val TAG = "PlexClient"

    private val httpClient = OkHttpClient()

    // El endpoint devuelve XML; solo hace falta este atributo, así que se
    // extrae con una regex en vez de añadir un parser XML completo.
    private val PUBLIC_PAGES_URL = Regex("publicPagesURL=\"([^\"]+)\"")

    init {
        if (PLEX_API_TOKEN.isBlank()) {
            Log.w(TAG, "PLEX_API_TOKEN vacío: añade PLEX_API_TOKEN en local.properties")
        }
    }

    /**
     * Devuelve la URL de watch.plex.tv para este título si está en el
     * catálogo gratuito, o null si no se encuentra (no todo lo que hay en
     * TMDb está en Plex).
     *
     * OJO: petición de red SÍNCRONA — llamar siempre desde un hilo de
     * fondo, igual que TmdbClient.
     */
    fun findWatchUrl(match: TmdbMatch): String? {
        val type = if (match.type == MediaType.SERIES) 2 else 1
        val url = "https://metadata.provider.plex.tv/library/metadata/matches" +
            "?guid=imdb://${match.imdbId}&type=$type&X-Plex-Token=$PLEX_API_TOKEN"
        val request = Request.Builder().url(url).build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Búsqueda en Plex falló: ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                PUBLIC_PAGES_URL.find(body)?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error buscando en Plex", e)
            null
        }
    }
}
