package com.tunombre.tvbridge

/**
 * Calcula las filas de "Porque viste X" a partir del historial local —
 * compartido entre [RecommendationChannelManager] (fila de Android TV, que
 * en la práctica Google TV no llega a mostrar en todos los launchers) y
 * [RecommendationsRowsFragment] (filas propias dentro de la app, que sí
 * funcionan siempre). Nada de LLM: solo TMDb, así que no hay riesgo de
 * "recomendar" un título que no existe.
 */
object RecommendationEngine {
    // Cuántos de los últimos títulos vistos (por separado en películas y en
    // series) se usan como semilla — una fila "Porque viste X" por cada uno.
    private const val SEEDS_PER_TYPE = 5
    private const val MAX_RESULTS_PER_SEED = 20

    /** Una fila "Porque viste [seedTitle]" con sus recomendaciones (ya sin
     * nada que el usuario haya visto ya, y ya ordenadas por popularidad). */
    data class SeedRow(val seedTitle: String, val recommendations: List<TmdbClient.TmdbRecommendation>)

    data class Recommendations(
        val movieRows: List<SeedRow>,
        val seriesRows: List<SeedRow>
    ) {
        val isEmpty: Boolean get() = movieRows.isEmpty() && seriesRows.isEmpty()
    }

    /** Bloqueante (red: TMDb + el historial fusionado con otros
     * dispositivos, ver [RecommendationHistory.fetchMerged]) — llamar
     * siempre desde un hilo de fondo. Ambas listas vacías si todavía no
     * hay historial. */
    fun compute(context: android.content.Context): Recommendations {
        val history = RecommendationHistory.fetchMerged(context)
        if (history.isEmpty()) return Recommendations(emptyList(), emptyList())

        val seen = history.map { it.tmdbId to it.mediaPath }.toSet()
        val movieSeeds = history.filter { it.mediaPath == "movie" }.take(SEEDS_PER_TYPE)
        val seriesSeeds = history.filter { it.mediaPath == "tv" }.take(SEEDS_PER_TYPE)

        return Recommendations(
            movieRows = buildRows(movieSeeds, seen),
            seriesRows = buildRows(seriesSeeds, seen)
        )
    }

    private fun buildRows(seeds: List<OpenedTitle>, seen: Set<Pair<Int, String>>): List<SeedRow> {
        return seeds.mapNotNull { seed ->
            val recs = TmdbClient.fetchRecommendations(seed.tmdbId, seed.mediaPath)
                .filter { (it.tmdbId to it.mediaPath) !in seen }
                .sortedByDescending { it.popularity }
                .take(MAX_RESULTS_PER_SEED)
            // Sin resultados nuevos para esta semilla (poco frecuente, pero
            // pasa con títulos muy de nicho) — no tiene sentido una fila
            // vacía, se omite en vez de mostrarla en blanco.
            if (recs.isEmpty()) null else SeedRow(seed.title, recs)
        }
    }
}
