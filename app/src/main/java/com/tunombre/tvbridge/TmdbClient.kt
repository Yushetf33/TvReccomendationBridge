package com.tunombre.tvbridge

import android.content.Context
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

data class TmdbMatch(val imdbId: String, val type: MediaType, val title: String, val tmdbId: Int)

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
    private val VOSE_MARKER_REGEX = Regex("VOSE|subtitulad", RegexOption.IGNORE_CASE)

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

    fun findImdbId(context: Context, title: String): TmdbMatch? {
        // Wrapper de compatibilidad para llamadores que no soportan el
        // selector de ambigüedad (ver FireTvCaptureService, que ya tiene su
        // propio diálogo de confirmación y no necesita uno segundo encima):
        // si resolve() devuelve Ambiguous (tras agotar la elección recordada
        // y la heurística de popularidad, ver resolveTitle), coge el primer
        // candidato — mismo comportamiento que tenía esta función antes de
        // existir el picker.
        return when (val resolution = resolve(context, title)) {
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
    fun resolve(context: Context, title: String): TmdbResolution? {
        // Quita TODOS los paréntesis finales, no solo uno: algunos títulos
        // traen más de uno seguido, p.ej. "El Cuervo (The Crow) (The Crow)".
        var cleanedTitle = title
        while (true) {
            val next = cleanedTitle.replace(TRAILING_PARENTHETICAL, "").trim()
            if (next == cleanedTitle) break
            cleanedTitle = next
        }
        if (cleanedTitle.isNotBlank() && cleanedTitle != title) {
            resolveTitle(context, cleanedTitle)?.let { return it }
        }
        return resolveTitle(context, title)
    }

    /** Segunda llamada (external_ids) para el candidato que el usuario ha
     * elegido en [MatchPickerActivity], o para el primero cuando se
     * colapsa un [TmdbResolution.Ambiguous] automáticamente. */
    fun resolveCandidate(candidate: TmdbCandidate): TmdbMatch? {
        val imdbId = fetchImdbId(candidate.tmdbId, candidate.mediaPath) ?: return null
        return TmdbMatch(imdbId, candidate.type, candidate.title, candidate.tmdbId)
    }

    // Umbrales de la heurística de popularidad (ver resolveTitle): solo
    // auto-resuelve sin preguntar cuando la diferencia es clara, a
    // propósito conservadores — el objetivo es evitar la pregunta en casos
    // obvios (un estreno reciente frente a un homónimo oscuro sin apenas
    // votos), no adivinar en empates reales, que es justo lo que este
    // selector existe para evitar.
    private const val MIN_CONFIDENT_POPULARITY = 10.0
    private const val CONFIDENT_POPULARITY_RATIO = 4.0

    private fun resolveTitle(context: Context, title: String): TmdbResolution? {
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
            // Antes de preguntar, dos formas de resolverlo sin interrumpir:

            // 1) Ya se preguntó por este mismo título antes y el usuario
            // eligió uno de estos candidatos (ver MatchPickerActivity) — se
            // reutiliza esa elección en vez de volver a preguntar, p.ej. si
            // una serie ambigua reaparece en recomendaciones más adelante.
            val remembered = Preferences.getRememberedDisambiguation(context, title)
            val rememberedMatch = remembered?.let { id -> exactMatches.find { it.tmdbId == id } }
            resolveExact(rememberedMatch)?.let { return it }

            // 2) Un candidato es muchísimo más popular que el resto — caso
            // típico de un estreno reciente frente a un homónimo oscuro de
            // hace décadas sin apenas votos: casi seguro que el usuario se
            // refiere al popular. Umbrales conservadores a propósito, ver
            // arriba — si la diferencia no es clara, se sigue preguntando.
            val byPopularity = exactMatches.sortedByDescending { it.popularity }
            val top = byPopularity[0]
            val runnerUp = byPopularity[1]
            if (top.popularity >= MIN_CONFIDENT_POPULARITY &&
                top.popularity >= runnerUp.popularity * CONFIDENT_POPULARITY_RATIO
            ) {
                resolveExact(top)?.let { return it }
            }

            return TmdbResolution.Ambiguous(
                query = title,
                candidates = exactMatches.map { TmdbCandidate(it.tmdbId, it.mediaPath, it.title, it.year) }
            )
        }

        val candidatesInOrder = exactMatches.ifEmpty { results.take(1) }
        for (candidate in candidatesInOrder) {
            resolveExact(candidate)?.let { return it }
        }
        return null
    }

    private fun resolveExact(candidate: SearchResult?): TmdbResolution.Resolved? {
        if (candidate == null) return null
        val imdbId = fetchImdbId(candidate.tmdbId, candidate.mediaPath) ?: return null
        val type = if (candidate.mediaPath == "tv") MediaType.SERIES else MediaType.MOVIE
        return TmdbResolution.Resolved(TmdbMatch(imdbId, type, candidate.title, candidate.tmdbId))
    }

    private fun normalizeTitle(title: String) = title.trim().lowercase()

    private data class SearchResult(
        val tmdbId: Int,
        val mediaPath: String,
        val title: String,
        val year: String?,
        val popularity: Double
    )

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
                        val popularity = result.optDouble("popularity", 0.0)
                        matches.add(SearchResult(result.getInt("id"), mediaType, resolvedTitle, year, popularity))
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

    /** Una recomendación de TMDb para la fila de "Recomendado para ti" que
     * publicamos en la pantalla de inicio (ver RecommendationChannelWorker)
     * — no necesita imdb_id hasta que el usuario la pulsa de verdad
     * (RecommendationOpenActivity resuelve eso al vuelo con
     * [resolveCandidate]), así que aquí basta con lo que trae /recommendations
     * directamente, sin la llamada extra a external_ids. */
    data class TmdbRecommendation(
        val tmdbId: Int,
        val mediaPath: String,
        val title: String,
        val posterPath: String?,
        val popularity: Double,
        val overview: String?
    ) {
        val type: MediaType get() = if (mediaPath == "tv") MediaType.SERIES else MediaType.MOVIE
    }

    /** Títulos "más como este" para [tmdbId] (una entrada del historial de
     * lo que el usuario ya ha abierto) — usa el propio endpoint de
     * recomendaciones de TMDb, sin ningún LLM: es gratis, no alucina
     * títulos que no existen, y ya tenemos la key. */
    fun fetchRecommendations(tmdbId: Int, mediaPath: String): List<TmdbRecommendation> {
        val language = Locale.getDefault().toLanguageTag()
        val url = "https://api.themoviedb.org/3/$mediaPath/$tmdbId/recommendations?api_key=$TMDB_API_KEY&language=$language"
        val request = Request.Builder().url(url).build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "recommendations ($mediaPath/$tmdbId) falló: ${response.code}")
                    return emptyList()
                }
                val body = response.body?.string() ?: return emptyList()
                val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
                val out = mutableListOf<TmdbRecommendation>()
                for (i in 0 until results.length()) {
                    val r = results.getJSONObject(i)
                    val title = r.optString("title", r.optString("name", "")).takeIf { it.isNotBlank() } ?: continue
                    val posterPath = r.optString("poster_path", "").takeIf { it.isNotBlank() }
                    val overview = r.optString("overview", "").takeIf { it.isNotBlank() }
                    out.add(TmdbRecommendation(r.getInt("id"), mediaPath, title, posterPath, r.optDouble("popularity", 0.0), overview))
                }
                out
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo recomendaciones ($mediaPath/$tmdbId)", e)
            emptyList()
        }
    }

    /** ID de vídeo de YouTube del tráiler de [tmdbId], o null si no hay
     * ninguno publicado — se pide al vuelo cuando el usuario enfoca una
     * recomendación (ver RecommendationsHomeFragment), no de golpe para
     * toda la fila, mismo motivo que [fetchImdbId]. Prioriza un tráiler
     * marcado "official" si hay varios.
     *
     * TMDb filtra /videos por idioma (por defecto en-US si no se manda
     * ninguno) en vez de devolver todos y traducir solo el nombre — muchos
     * títulos no tienen tráiler cargado en el idioma del dispositivo, así
     * que se prueba primero con ese idioma y, si no hay nada, se repite sin
     * parámetro (cae al inglés) antes de darse por vencido. */
    fun fetchTrailerKey(tmdbId: Int, mediaPath: String): String? {
        val language = Locale.getDefault().toLanguageTag()
        return fetchTrailerKey(tmdbId, mediaPath, language) ?: fetchTrailerKey(tmdbId, mediaPath, language = null)
    }

    private fun fetchTrailerKey(tmdbId: Int, mediaPath: String, language: String?): String? {
        val languageParam = if (language != null) "&language=$language" else ""
        val url = "https://api.themoviedb.org/3/$mediaPath/$tmdbId/videos?api_key=$TMDB_API_KEY$languageParam"
        val request = Request.Builder().url(url).build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "videos ($mediaPath/$tmdbId) falló: ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                val results = JSONObject(body).optJSONArray("results") ?: return null
                val trailers = (0 until results.length())
                    .map { results.getJSONObject(it) }
                    .filter { it.optString("site") == "YouTube" && it.optString("type") == "Trailer" }
                // TMDb etiqueta con el mismo idioma tanto un tráiler doblado
                // como su versión "VOSE" (audio original, solo subtítulos
                // traducidos) — sin este filtro, un VOSE en la respuesta
                // antes que el doblado real hace que se abra con audio en el
                // idioma original igualmente. No hay otro campo que los
                // distinga, así que se descarta por el nombre.
                val dubbed = trailers.filterNot {
                    VOSE_MARKER_REGEX.containsMatchIn(it.optString("name"))
                }
                val candidates = dubbed.ifEmpty { trailers }
                val best = candidates.firstOrNull { it.optBoolean("official", false) } ?: candidates.firstOrNull()
                best?.optString("key")?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo tráiler ($mediaPath/$tmdbId)", e)
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
