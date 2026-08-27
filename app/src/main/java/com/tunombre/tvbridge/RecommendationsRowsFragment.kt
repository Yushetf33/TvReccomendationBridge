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
 * que muchos no hacen (ver RecommendationChannelManager). Dos filas
 * (Películas / Series) más una tarjeta de acceso a Ajustes al final.
 *
 * Se usa tanto como contenido raíz de [MainActivity] (una vez la app ya
 * está configurada, ver su onCreate) como dentro de [RecommendationsActivity]
 * (accesible desde Ajustes con "Ver recomendaciones").
 */
class RecommendationsRowsFragment : RowsSupportFragment() {

    /** Marcador para la tarjeta de "Ajustes" al final de las filas — no es
     * una recomendación real, solo un acceso directo. */
    private object SettingsMenuItem

    /** Notifica el título actualmente enfocado — usado por
     * [RecommendationsHomeFragment] para actualizar el panel de ficha
     * ampliada a la izquierda (póster grande + sinopsis). Null cuando el
     * foco está en algo que no es una recomendación (p.ej. la tarjeta de
     * Ajustes) o todavía no hay nada. */
    var onSelectionChanged: ((TmdbClient.TmdbRecommendation?) -> Unit)? = null

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
            val recommendations = RecommendationEngine.compute(context)
            activity?.runOnUiThread {
                if (isAdded) buildRows(recommendations)
            }
        }.start()
    }

    private fun buildRows(recommendations: RecommendationEngine.Recommendations) {
        if (recommendations.isEmpty) {
            Toast.makeText(requireContext(), R.string.recommendations_empty, Toast.LENGTH_LONG).show()
        }

        val rowsAdapter = ArrayObjectAdapter(ListRowPresenter(FocusHighlight.ZOOM_FACTOR_MEDIUM))
        if (recommendations.movies.isNotEmpty()) {
            rowsAdapter.add(buildContentRow(HEADER_ID_MOVIES, R.string.recommendations_movies_header, recommendations.movies))
        }
        if (recommendations.series.isNotEmpty()) {
            rowsAdapter.add(buildContentRow(HEADER_ID_SERIES, R.string.recommendations_series_header, recommendations.series))
        }

        val settingsAdapter = ArrayObjectAdapter(SettingsCardPresenter())
        settingsAdapter.add(SettingsMenuItem)
        rowsAdapter.add(ListRow(HeaderItem(HEADER_ID_SETTINGS, getString(R.string.recommendations_more_header)), settingsAdapter))

        adapter = rowsAdapter

        // El listener de selección de Leanback no siempre dispara un
        // evento inicial solo con poner el adapter — sin esto, el panel de
        // ficha (ver RecommendationsHomeFragment) se queda vacío hasta que
        // el usuario mueve el foco por primera vez.
        (recommendations.movies.firstOrNull() ?: recommendations.series.firstOrNull())
            ?.let { onSelectionChanged?.invoke(it) }
    }

    private fun buildContentRow(headerId: Long, titleRes: Int, items: List<TmdbClient.TmdbRecommendation>): ListRow {
        val cardAdapter = ArrayObjectAdapter(RecommendationCardPresenter())
        items.forEach { cardAdapter.add(it) }
        return ListRow(HeaderItem(headerId, getString(titleRes)), cardAdapter)
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
        private const val HEADER_ID_MOVIES = 1L
        private const val HEADER_ID_SERIES = 2L
        private const val HEADER_ID_SETTINGS = 3L
    }
}
