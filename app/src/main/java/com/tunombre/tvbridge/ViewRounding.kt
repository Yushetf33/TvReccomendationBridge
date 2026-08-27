package com.tunombre.tvbridge

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider

/** Recorta la vista a un rectángulo con las esquinas redondeadas — usado en
 * las tarjetas de recomendaciones (ver RecommendationCardPresenter /
 * SettingsCardPresenter) para que se parezcan a las de apps como WuPlay en
 * vez del rectángulo recto por defecto de ImageCardView. */
fun View.applyRoundedCorners(radiusPx: Float) {
    clipToOutline = true
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
        }
    }
}
