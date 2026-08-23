package com.tunombre.tvbridge

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Cliente mínimo para TMDb: busca una película o serie por título y
 * devuelve su IMDb ID (formato "tt1234567"), que es lo que necesita el
 * deep link de Nuvio.
 *
 * La API key se lee de BuildConfig.TMDB_API_KEY, generada a partir de
 * TMDB_API_KEY en local.properties (no versionado). Consigue la tuya
 * gratis en https://www.themoviedb.org/settings/api
 */
/** Tipo de contenido resuelto en TMDb, usado para elegir el deep link correcto en Nuvio. */
enum class MediaType { MOVIE, SERIES }

data class TmdbMatch(val imdbId: String, val type: MediaType, val title: String)

object TmdbClient {

    private val TMDB_API_KEY = BuildConfig.TMDB_API_KEY
    private const val TAG = "TmdbClient"

    private val httpClient = OkHttpClient()

    init {
        if (TMDB_API_KEY.isBlank()) {
            Log.w(TAG, "TMDB_API_KEY vacía: añade TMDB_API_KEY=tu_key en local.properties")
        }
    }

    /**
     * Busca el título en TMDb usando /search/multi (películas y series
     * mezcladas, ordenadas por relevancia real) y devuelve el imdb_id del
     * primer resultado que sea película o serie (se ignoran resultados de
     * tipo "person"), junto con su tipo. Null si no hay nada aprovechable.
     *
     * Usar /search/multi en vez de /search/movie evita que un match de
     * película poco relevante (p.ej. una película antigua homónima) gane
     * por defecto frente a la serie realmente recomendada.
     *
     * OJO: esto hace peticiones de red de forma SÍNCRONA (bloqueante).
     * Debe llamarse siempre desde un hilo de fondo (ver Executors en el
     * Accessibility Service), nunca desde el hilo principal.
     */
    // Sufijos entre paréntesis como "(VO)", "(VE)", "(VOSE)" que el launcher
    // añade para indicar versión original/doblada/subtitulada. No forman
    // parte del título real y rompen la búsqueda en TMDb si se dejan.
    private val TRAILING_PARENTHETICAL = Regex("\\s*\\([^)]*\\)\\s*$")

    fun findImdbId(title: String): TmdbMatch? {
        // Quita TODOS los paréntesis finales, no solo uno: algunos títulos
        // traen más de uno seguido, p.ej. "El Cuervo (The Crow) (The Crow)".
        var cleanedTitle = title
        while (true) {
            val next = cleanedTitle.replace(TRAILING_PARENTHETICAL, "").trim()
            if (next == cleanedTitle) break
            cleanedTitle = next
        }
        if (cleanedTitle.isNotBlank() && cleanedTitle != title) {
            resolveTitle(cleanedTitle)?.let { return it }
        }
        return resolveTitle(title)
    }

    private fun resolveTitle(title: String): TmdbMatch? {
        val (tmdbId, mediaPath, resolvedTitle) = searchId(title) ?: return null
        val imdbId = fetchImdbId(tmdbId, mediaPath) ?: return null
        val type = if (mediaPath == "tv") MediaType.SERIES else MediaType.MOVIE
        return TmdbMatch(imdbId, type, resolvedTitle)
    }

    private data class SearchResult(val tmdbId: Int, val mediaPath: String, val title: String)

    private fun searchId(title: String): SearchResult? {
        val encoded = URLEncoder.encode(title, "UTF-8")
        val url = "https://api.themoviedb.org/3/search/multi?query=$encoded&api_key=$TMDB_API_KEY&language=es-ES"

        val request = Request.Builder().url(url).build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Búsqueda TMDb falló: ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val results = json.getJSONArray("results")
                // TMDb ya ordena por relevancia/popularidad; nos quedamos con
                // el primer resultado que sea película o serie.
                for (i in 0 until results.length()) {
                    val result = results.getJSONObject(i)
                    val mediaType = result.optString("media_type", "")
                    if (mediaType == "movie" || mediaType == "tv") {
                        // Las películas traen "title", las series "name".
                        val resolvedTitle = result.optString("title", result.optString("name", title))
                        return SearchResult(result.getInt("id"), mediaType, resolvedTitle)
                    }
                }
                Log.d(TAG, "Sin resultados aprovechables en TMDb para: $title")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error buscando en TMDb", e)
            null
        }
    }

    private fun fetchImdbId(tmdbId: Int, mediaPath: String): String? {
        val url = "https://api.themoviedb.org/3/$mediaPath/$tmdbId/external_ids?api_key=$TMDB_API_KEY"
        val request = Request.Builder().url(url).build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "external_ids ($mediaPath) falló: ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val imdbId = json.optString("imdb_id", "")
                if (imdbId.isBlank() || imdbId == "null") null else imdbId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo imdb_id ($mediaPath)", e)
            null
        }
    }
}
