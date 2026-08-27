package com.tunombre.tvbridge

import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter

/** Tarjeta única de acceso a Ajustes al final de las filas de
 * recomendaciones (ver RecommendationsRowsFragment) — mismo estilo de
 * tarjeta que los pósters, pero con un icono de engranaje centrado en vez
 * de una imagen. */
class SettingsCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(CARD_WIDTH_PX, CARD_HEIGHT_PX)
            mainImageView?.scaleType = ImageView.ScaleType.CENTER
            mainImage = ContextCompat.getDrawable(context, R.drawable.ic_settings_gear)
            setBackgroundColor(ContextCompat.getColor(context, R.color.accent_dim))
            titleText = context.getString(R.string.recommendations_settings_card)
            applyRoundedCorners(CORNER_RADIUS_PX)
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) = Unit

    override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit

    companion object {
        private const val CARD_WIDTH_PX = 300
        private const val CARD_HEIGHT_PX = 200
        private const val CORNER_RADIUS_PX = 20f
    }
}
