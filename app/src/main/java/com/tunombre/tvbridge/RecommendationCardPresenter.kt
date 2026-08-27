package com.tunombre.tvbridge

import android.graphics.drawable.BitmapDrawable
import android.view.ViewGroup
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter

/**
 * Tarjeta de póster para la fila de "Recomendado para ti" dentro de la app
 * (ver RecommendationsRowsFragment) — esquinas redondeadas al estilo de
 * apps como WuPlay, póster cargado a mano vía [PosterLoader] con un guard
 * por tag en el ImageView para que una carga tardía de una tarjeta ya
 * reciclada por el RecyclerView no pinte el póster equivocado.
 */
class RecommendationCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(CARD_WIDTH_PX, CARD_HEIGHT_PX)
            applyRoundedCorners(CORNER_RADIUS_PX)
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val rec = item as? TmdbClient.TmdbRecommendation ?: return
        val cardView = viewHolder.view as ImageCardView
        cardView.titleText = rec.title
        cardView.contentText = null
        cardView.mainImageView?.tag = rec.posterPath
        cardView.mainImage = null

        val posterPath = rec.posterPath ?: return
        PosterLoader.load(posterPath, POSTER_WIDTH) { bitmap ->
            if (bitmap != null && cardView.mainImageView?.tag == posterPath) {
                cardView.mainImage = BitmapDrawable(cardView.resources, bitmap)
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder.view as ImageCardView).mainImage = null
    }

    companion object {
        private const val CARD_WIDTH_PX = 300
        private const val CARD_HEIGHT_PX = 450
        private const val CORNER_RADIUS_PX = 20f
        private const val POSTER_WIDTH = 342
    }
}
