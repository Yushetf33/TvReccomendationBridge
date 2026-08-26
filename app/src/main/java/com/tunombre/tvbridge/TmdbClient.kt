package com.tunombre.tvbridge

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

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

/** Un resultado de TMDb todavía sin resolver a IMDb ID — solo lo que hace
 * falta para mostrárselo al usuario en [MatchPickerActivity] cuando hay
 * ambigüedad (ver [TmdbResolution.Ambiguous]). */
data class TmdbCandidate(val tmdbId: Int, val mediaPath: String, val title: String, val year: String?) {
    val type: MediaType get() = if (mediaPath == "tv") MediaType.SERIES else MediaType.MOVIE
}

/** Resultado de [TmdbClient.resolve]: o bien se resolvió directamente a un
 * único [TmdbMatch], o bien hay varios títulos EXACTOS con años distintos
 * (p.ej. un remake) y hace falta que el usuario elija cuál — ver
 * [MatchPickerActivity]. */
sealed class TmdbResolution {
    data class Resolved(val match: TmdbMatch) : TmdbResolution()
    data class Ambiguous(val query: String, val candidates: List<TmdbCandidate>) : TmdbResolution()
}

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
        // Wrapper de compatibilidad para llamadores que no soportan el
        // selector de ambigüedad (ver FireTvCaptureService, que ya tiene su
        // propio diálogo de confirmación y no necesita uno segundo encima):
        // si resolve() devuelve Ambiguous, coge el primer candidato — mismo
        // comportamiento que tenía esta función antes de existir el picker.
        return when (val resolution = resolve(title)) {
            is TmdbResolution.Resolved -> resolution.match
            is TmdbResolution.Ambiguous -> resolveCandidate(resolution.candidates.first())
            null -> null
        }
    }

    /**
     * Igual que [findImdbId], pero sin colapsar automáticamente el caso
     * ambiguo — para llamadores que sí quieren ofrecerle el selector al
     * usuario (ver TvRecommendationAccessibilityService.handleMovieClick y
     * MatchPickerActivity).
     */
    fun resolve(title: String): TmdbResolution? {
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

    /** Segunda llamada (external_ids) para el candidato que el usuario ha
     * elegido en [MatchPickerActivity], o para el primero cuando se
     * colapsa un [TmdbResolution.Ambiguous] automáticamente. */
    fun resolveCandidate(candidate: TmdbCandidate): TmdbMatch? {
        val imdbId = fetchImdbId(candidate.tmdbId, candidate.mediaPath) ?: return null
        return TmdbMatch(imdbId, candidate.type, candidate.title)
    }

    private fun resolveTitle(title: String): TmdbResolution? {
        val results = searchAll(title) ?: return null
        if (results.isEmpty()) return null

        // Preferimos un resultado con el título EXACTO buscado sobre el más
        // popular de TMDb: para títulos de una franquicia con una entrega
        // reciente muy popular (p.ej. "Dune"), TMDb pone esa entrega primero
        // aunque el título exacto pedido sea otro ("Dune: Parte Dos" (2024)
        // por delante de "Dune" (2021)).
        val normalizedQuery = normalizeTitle(title)
        val exactMatches = results.filter { normalizeTitle(it.title) == normalizedQuery }

        // A veces TMDb trae más de una entrada con el título exacto (p.ej.
        // duplicados basura sin datos completos, mismo año) — eso NO cuenta
        // como ambigüedad real, solo probamos cada una hasta encontrar una
        // con imdb_id real. Ambigüedad real es cuando hay dos o más títulos
        // EXACTOS con AÑOS DISTINTOS (p.ej. un remake) — ahí sí puede ser
        // contenido genuinamente distinto y no hay forma de adivinar cuál
        // quería el usuario (confirmado por quejas reales de "abre la
        // película equivocada" en apps similares).
        val distinctYears = exactMatches.mapNotNull { it.year }.toSet()
        if (exactMatches.size > 1 && distinctYears.size > 1) {
            return TmdbResolution.Ambiguous(
                query = title,
                candidates = exactMatches.map { TmdbCandidate(it.tmdbId, it.mediaPath, it.title, it.year) }
            )
        }

        val candidatesInOrder = exactMatches.ifEmpty { results.take(1) }
        for (candidate in candidatesInOrder) {
            val imdbId = fetchImdbId(candidate.tmdbId, candidate.mediaPath) ?: continue
            val type = if (candidate.mediaPath == "tv") MediaType.SERIES else MediaType.MOVIE
            return TmdbResolution.Resolved(TmdbMatch(imdbId, type, candidate.title))
        }
        return null
    }

    private fun normalizeTitle(title: String) = title.trim().lowercase()

    private data class SearchResult(val tmdbId: Int, val mediaPath: String, val title: String, val year: String?)

    private fun searchAll(title: String): List<SearchResult>? {
        val encoded = URLEncoder.encode(title, "UTF-8")
        // El idioma del dispositivo, no fijo a español: el título que TMDb
        // devuelve depende de este parámetro, y la comparación de título
        // exacto (ver resolveTitle) necesita que venga en el mismo idioma
        // que la consulta o nunca puede coincidir — con "es-ES" fijo, esa
        // comprobación no se activaba nunca para usuarios con el launcher
        // en otro idioma (la mayoría de la comunidad de Reddit, en inglés).
        val language = Locale.getDefault().toLanguageTag()
        val url = "https://api.themoviedb.org/3/search/multi?query=$encoded&api_key=$TMDB_API_KEY&language=$language"

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
                // los que sean película o serie (se ignoran los de "person").
                val matches = mutableListOf<SearchResult>()
                for (i in 0 until results.length()) {
                    val result = results.getJSONObject(i)
                    val mediaType = result.optString("media_type", "")
                    if (mediaType == "movie" || mediaType == "tv") {
                        // Las películas traen "title"/"release_date", las
                        // series "name"/"first_air_date".
                        val resolvedTitle = result.optString("title", result.optString("name", title))
                        val date = result.optString("release_date", result.optString("first_air_date", ""))
                        val year = date.take(4).takeIf { it.length == 4 }
                        matches.add(SearchResult(result.getInt("id"), mediaType, resolvedTitle, year))
                    }
                }
                if (matches.isEmpty()) {
                    Log.d(TAG, "Sin resultados aprovechables en TMDb para: $title")
                }
                matches
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
