package com.tunombre.tvbridge

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Pantalla de "Recomendado para ti": panel de ficha ampliada a la
 * izquierda (póster grande + sinopsis del título enfocado, al estilo de
 * apps como WuPlay) y las filas de recomendaciones a la derecha (ver
 * RecommendationsRowsFragment, alojado como fragmento hijo).
 *
 * Se usa tanto como contenido raíz de [MainActivity] (una vez la app ya
 * está configurada, ver su onCreate) como dentro de [RecommendationsActivity]
 * (accesible desde Ajustes con "Ver recomendaciones").
 */
class RecommendationsHomeFragment : Fragment(R.layout.fragment_recommendations_home) {

    // Evita que un listener todavía pendiente de un cambio de foco anterior
    // (el usuario movió el mando otra vez antes de que terminase el layout)
    // dispare con el texto ya desactualizado.
    private var pendingLayoutListener: ViewTreeObserver.OnPreDrawListener? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val posterView = view.findViewById<ImageView>(R.id.detail_poster)
        val titleView = view.findViewById<TextView>(R.id.detail_title)
        val overviewContainer = view.findViewById<FrameLayout>(R.id.detail_overview_container)
        val overviewView = view.findViewById<TextView>(R.id.detail_overview)
        val tabMovies = view.findViewById<TextView>(R.id.tab_movies)
        val tabSeries = view.findViewById<TextView>(R.id.tab_series)
        posterView.applyRoundedCorners(CORNER_RADIUS_PX)

        // El fragmento hijo puede que ya exista (p.ej. tras un cambio de
        // configuración) — se reutiliza en vez de duplicarlo. Se guarda la
        // referencia directa a la instancia en vez de volver a buscarla con
        // findFragmentById justo después del commit(): la transacción se
        // aplica en el siguiente ciclo, así que una segunda búsqueda
        // inmediata puede no encontrarla todavía.
        val rowsFragment = childFragmentManager.findFragmentById(R.id.rows_container) as? RecommendationsRowsFragment
            ?: RecommendationsRowsFragment().also { fragment ->
                childFragmentManager.beginTransaction()
                    .replace(R.id.rows_container, fragment)
                    .commit()
            }
        rowsFragment.onSelectionChanged =
            { rec -> updateDetailPanel(rec, posterView, titleView, overviewContainer, overviewView) }
        rowsFragment.onCategoryChanged =
            { category -> updateTabSelection(category, tabMovies, tabSeries) }

        tabMovies.setOnClickListener { rowsFragment.setCategory(RecommendationsRowsFragment.Category.MOVIES) }
        tabSeries.setOnClickListener { rowsFragment.setCategory(RecommendationsRowsFragment.Category.SERIES) }
    }

    /** El fondo azul de acento marca la pestaña activa (independiente del
     * foco del mando, que además sigue resaltando en azul la que NO está
     * activa al pasar por encima — ver bg_button). Negrita de más para que
     * la pestaña activa se note incluso sin foco encima. */
    private fun updateTabSelection(
        category: RecommendationsRowsFragment.Category,
        tabMovies: TextView,
        tabSeries: TextView
    ) {
        val moviesActive = category == RecommendationsRowsFragment.Category.MOVIES
        tabMovies.setBackgroundResource(if (moviesActive) R.drawable.bg_button_focused else R.drawable.bg_button)
        tabSeries.setBackgroundResource(if (moviesActive) R.drawable.bg_button else R.drawable.bg_button_focused)
        tabMovies.setTypeface(null, if (moviesActive) Typeface.BOLD else Typeface.NORMAL)
        tabSeries.setTypeface(null, if (moviesActive) Typeface.NORMAL else Typeface.BOLD)
    }

    private fun updateDetailPanel(
        rec: TmdbClient.TmdbRecommendation?,
        posterView: ImageView,
        titleView: TextView,
        overviewContainer: FrameLayout,
        overviewView: TextView
    ) {
        overviewView.animate().cancel()
        overviewView.translationY = 0f
        pendingLayoutListener?.let { overviewView.viewTreeObserver.removeOnPreDrawListener(it) }
        pendingLayoutListener = null

        if (rec == null) {
            titleView.text = ""
            overviewView.text = ""
            posterView.setImageDrawable(null)
            posterView.tag = null
            return
        }
        titleView.text = rec.title
        overviewView.text = rec.overview.orEmpty()
        // El TextView tiene una altura fija de sobra (ver layout — NO
        // wrap_content, para que mida siempre el texto completo aunque no
        // quepa en el hueco visible) y es el FrameLayout contenedor el que
        // recorta lo que sobra. OnPreDrawListener espera a que ESE layout
        // ya esté resuelto antes de leer las medidas reales.
        val preDrawListener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                overviewView.viewTreeObserver.removeOnPreDrawListener(this)
                if (pendingLayoutListener === this) pendingLayoutListener = null
                if (!isAdded || overviewView.width <= 0) return true
                val fullHeight = overviewView.layout?.height ?: 0
                val containerHeight = overviewContainer.height
                val overflow = fullHeight - containerHeight
                if (overflow > 0 && containerHeight > 0) {
                    overviewView.animate()
                        .translationY(-overflow.toFloat())
                        .setStartDelay(SCROLL_START_DELAY_MS)
                        .setDuration((overflow * MS_PER_OVERFLOW_PX).toLong().coerceAtLeast(MIN_SCROLL_DURATION_MS))
                        .setInterpolator(LinearInterpolator())
                        .start()
                }
                return true
            }
        }
        pendingLayoutListener = preDrawListener
        overviewView.viewTreeObserver.addOnPreDrawListener(preDrawListener)

        val posterPath = rec.posterPath
        if (posterPath == null) {
            posterView.setImageDrawable(null)
            posterView.tag = null
            return
        }
        posterView.tag = posterPath
        PosterLoader.load(posterPath, DETAIL_POSTER_WIDTH) { bitmap ->
            if (bitmap != null && isAdded && posterView.tag == posterPath) {
                posterView.setImageBitmap(bitmap)
            }
        }
    }

    companion object {
        private const val CORNER_RADIUS_PX = 24f
        private const val DETAIL_POSTER_WIDTH = 500
        private const val SCROLL_START_DELAY_MS = 2500L
        private const val MS_PER_OVERFLOW_PX = 35L
        private const val MIN_SCROLL_DURATION_MS = 3000L
    }
}
