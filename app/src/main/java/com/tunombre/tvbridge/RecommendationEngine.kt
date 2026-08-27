package com.tunombre.tvbridge

/**
 * Calcula la lista de "más como esto" a partir del historial local —
 * compartido entre [RecommendationChannelManager] (fila de Android TV, que
 * en la práctica Google TV no llega a mostrar en todos los launchers) y
 * [RecommendationsRowsFragment] (filas propias dentro de la app, que sí
 * funcionan siempre). Nada de LLM: solo TMDb, así que no hay riesgo de
 * "recomendar" un título que no existe.
 */
object RecommendationEngine {
    // Cuántas entradas recientes del historial se usan como semilla para
    // pedir "más como esto" a TMDb, y cuántas recomendaciones finales se
    // devuelven por categoría — límites conservadores para no machacar la
    // API.
    private const val HISTORY_SEED_COUNT = 8
    private const val MAX_RESULTS_PER_TYPE = 20

    data class Recommendations(
        val movies: List<TmdbClient.TmdbRecommendation>,
        val series: List<TmdbClient.TmdbRecommendation>
    ) {
        val isEmpty: Boolean get() = movies.isEmpty() && series.isEmpty()
    }

    /** Bloqueante (llamadas de red a TMDb) — llamar siempre desde un hilo
     * de fondo. Ambas listas vacías si todavía no hay historial. */
    fun compute(context: android.content.Context): Recommendations {
        val history = RecommendationHistory.getAll(context)
        if (history.isEmpty()) return Recommendations(emptyList(), emptyList())

        val seen = history.map { it.tmdbId to it.mediaPath }.toSet()
        val scored = LinkedHashMap<Pair<Int, String>, ScoredRecommendation>()
        for (seed in history.take(HISTORY_SEED_COUNT)) {
            val recs = TmdbClient.fetchRecommendations(seed.tmdbId, seed.mediaPath)
            for (rec in recs) {
                val key = rec.tmdbId to rec.mediaPath
                if (key in seen) continue // ya lo ha abierto, no tiene sentido "recomendárselo"
                val existing = scored[key]
                scored[key] = ScoredRecommendation(
                    rec = rec,
                    occurrences = (existing?.occurrences ?: 0) + 1
                )
            }
        }

        val ordering = compareByDescending<ScoredRecommendation> { it.occurrences }
            .thenByDescending { it.rec.popularity }
        val movies = scored.values
            .filter { it.rec.type == MediaType.MOVIE }
            .sortedWith(ordering)
            .take(MAX_RESULTS_PER_TYPE)
            .map { it.rec }
        val series = scored.values
            .filter { it.rec.type == MediaType.SERIES }
            .sortedWith(ordering)
            .take(MAX_RESULTS_PER_TYPE)
            .map { it.rec }

        return Recommendations(movies, series)
    }

    private data class ScoredRecommendation(val rec: TmdbClient.TmdbRecommendation, val occurrences: Int)
}
