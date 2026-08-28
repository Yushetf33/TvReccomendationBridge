package com.tunombre.tvbridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener

/**
 * Pantalla de "Recomendado para ti" dentro de la propia app — la vía
 * garantizada, ya que la fila equivalente en la pantalla de inicio de
 * Android TV depende de que el launcher del usuario apruebe el canal, cosa
 * que muchos no hacen (ver RecommendationChannelManager). Una fila "Porque
 * viste X" por cada uno de los últimos títulos vistos (por separado en
 * películas y series, ver RecommendationEngine), filtradas por la pestaña
 * activa (ver [Category] y [onCategoryChanged]), más una tarjeta de acceso
 * a Ajustes al final.
 *
 * Se usa tanto como contenido raíz de [MainActivity] (una vez la app ya
 * está configurada, ver su onCreate) como dentro de [RecommendationsActivity]
 * (accesible desde Ajustes con "Ver recomendaciones").
 */
class RecommendationsRowsFragment : RowsSupportFragment() {

    enum class Category { MOVIES, SERIES }

    /** Marcador para la tarjeta de "Ajustes" al final de las filas — no es
     * una recomendación real, solo un acceso directo. */
    private object SettingsMenuItem

    /** Notifica el título actualmente enfocado — usado por
     * [RecommendationsHomeFragment] para actualizar el panel de ficha
     * ampliada a la izquierda (póster grande + sinopsis). Null cuando el
     * foco está en algo que no es una recomendación (p.ej. la tarjeta de
     * Ajustes, o la categoría activa no tiene ninguna fila). */
    var onSelectionChanged: ((TmdbClient.TmdbRecommendation?) -> Unit)? = null

    /** Mantener pulsado sobre una tarjeta — dispara el tráiler si lo tiene
     * (ver RecommendationCardPresenter/RecommendationsHomeFragment). */
    var onTrailerRequested: ((TmdbClient.TmdbRecommendation) -> Unit)? = null

    /** Notifica la categoría actualmente mostrada — tanto por un clic en
     * una pestaña (ver [setCategory]) como por la selección automática
     * inicial cuando la categoría por defecto no tiene nada que mostrar
     * (ver [onResume]) — para que [RecommendationsHomeFragment] mantenga
     * las pestañas de arriba sincronizadas con lo que se ve debajo. */
    var onCategoryChanged: ((Category) -> Unit)? = null

    private var recommendations: RecommendationEngine.Recommendations? = null
    private var currentCategory: Category = Category.MOVIES

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        onItemViewClickedListener =
            OnItemViewClickedListener { _, item, _, _ ->
                when (item) {
                    is TmdbClient.TmdbRecommendation -> openRecommendation(item)
                    is SettingsMenuItem -> openSettings()
                }
            }
        onItemViewSelectedListener =
            OnItemViewSelectedListener { _, item, _, _ ->
                onSelectionChanged?.invoke(item as? TmdbClient.TmdbRecommendation)
            }
    }

    // En onResume (no en onActivityCreated) para que se recalculen también
    // al volver a esta pantalla sin recrearla — p.ej. al salir de Ajustes,
    // o al volver de abrir un título — y no solo la primera vez que se crea.
    override fun onResume() {
        super.onResume()
        val context = requireContext()
        Thread {
            val result = RecommendationEngine.compute(context)
            activity?.runOnUiThread {
                if (isAdded) {
                    recommendations = result
                    // Si la categoría activa se queda sin filas mientras la
                    // otra sí tiene (p.ej. todavía no se ha abierto ninguna
                    // serie), cambia sola a la que sí tiene algo en vez de
                    // dejar la pestaña activa vacía sin explicación.
                    if (rowsFor(currentCategory).isEmpty() && rowsFor(otherCategory()).isNotEmpty()) {
                        currentCategory = otherCategory()
                    }
                    onCategoryChanged?.invoke(currentCategory)
                    rebuildRows()
                    if (result.isEmpty) {
                        Toast.makeText(context, R.string.recommendations_empty, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    /** Cambia qué categoría se muestra (llamado desde las pestañas en
     * [RecommendationsHomeFragment]) — solo reordena filas ya calculadas,
     * sin volver a pedir nada a TMDb. */
    fun setCategory(category: Category) {
        if (category == currentCategory) return
        currentCategory = category
        onCategoryChanged?.invoke(category)
        rebuildRows()
    }

    private fun otherCategory(): Category =
        if (currentCategory == Category.MOVIES) Category.SERIES else Category.MOVIES

    private fun rowsFor(category: Category): List<RecommendationEngine.SeedRow> {
        val recs = recommendations ?: return emptyList()
        return if (category == Category.MOVIES) recs.movieRows else recs.seriesRows
    }

    private fun rebuildRows() {
        val rowsAdapter = ArrayObjectAdapter(ListRowPresenter(FocusHighlight.ZOOM_FACTOR_MEDIUM))
        var nextHeaderId = 1L
        val seedRows = rowsFor(currentCategory)
        for (seedRow in seedRows) {
            rowsAdapter.add(buildContentRow(nextHeaderId++, seedRow))
        }

        val settingsAdapter = ArrayObjectAdapter(SettingsCardPresenter())
        settingsAdapter.add(SettingsMenuItem)
        rowsAdapter.add(ListRow(HeaderItem(HEADER_ID_SETTINGS, getString(R.string.recommendations_more_header)), settingsAdapter))

        adapter = rowsAdapter

        // El listener de selección de Leanback no siempre dispara un
        // evento inicial solo con poner el adapter — sin esto, el panel de
        // ficha (ver RecommendationsHomeFragment) se queda vacío (o con el
        // título de la categoría anterior) hasta que el usuario mueve el
        // foco por primera vez.
        val firstRec = seedRows.firstOrNull()?.recommendations?.firstOrNull()
        onSelectionChanged?.invoke(firstRec)
    }

    private fun buildContentRow(headerId: Long, seedRow: RecommendationEngine.SeedRow): ListRow {
        val cardAdapter = ArrayObjectAdapter(RecommendationCardPresenter { rec -> onTrailerRequested?.invoke(rec) })
        seedRow.recommendations.forEach { cardAdapter.add(it) }
        val title = getString(R.string.recommendations_because_you_watched, seedRow.seedTitle)
        return ListRow(HeaderItem(headerId, title), cardAdapter)
    }

    private fun openRecommendation(rec: TmdbClient.TmdbRecommendation) {
        // Reutiliza el mismo deep link que usan las tarjetas de la fila de
        // Android TV — RecommendationOpenActivity ya sabe resolverlo y
        // abrirlo respetando el player/Jellyfin elegidos.
        val uri = Uri.parse(
            "tvbridge://recommend?tmdbId=${rec.tmdbId}&mediaPath=${rec.mediaPath}" +
                "&title=${Uri.encode(rec.title)}"
        )
        startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(requireContext().packageName))
    }

    private fun openSettings() {
        val hostActivity = activity
        if (hostActivity is MainActivity) {
            // Somos el contenido raíz de MainActivity (app ya configurada,
            // ver su onCreate) — sustituimos por la pantalla de Ajustes de
            // toda la vida dentro de la misma Activity.
            hostActivity.showSettingsFromHome()
        } else {
            // Nos abrieron desde Ajustes con "Ver recomendaciones"
            // (RecommendationsActivity) — Ajustes ya está debajo.
            hostActivity?.finish()
        }
    }

    companion object {
        // Reservado y muy por encima de los headers dinámicos de las filas
        // "Porque viste X" (ver buildRows) — nunca van a llegar tan alto.
        private const val HEADER_ID_SETTINGS = 1000L
    }
}
