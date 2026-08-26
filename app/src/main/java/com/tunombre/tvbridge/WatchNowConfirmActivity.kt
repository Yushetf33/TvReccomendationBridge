package com.tunombre.tvbridge

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Confirmación opcional "Watch now in {App}" en Google TV (ver
 * Preferences.isWatchNowConfirmEnabled) — a diferencia de
 * [ConfirmOpenActivity] (modo Fire TV, sin Accessibility posible, así que
 * SIEMPRE hace falta confirmar), esta es puramente una capa extra de
 * seguridad opcional para quien prefiera no abrir nada sin confirmar antes,
 * ya que en Google TV el clic sí se detecta de forma fiable.
 *
 * Pulsar Atrás no cancela para siempre: solo la oculta. El servicio (ver
 * TvRecommendationAccessibilityService.onWatchNowDismissed) la vuelve a
 * lanzar unos segundos después, y sigue así hasta que el usuario confirma o
 * navega fuera de la ficha/pantalla actual (vuelve a Inicio, abre otra app,
 * etc.) — en ese momento el servicio cancela el reintento para siempre.
 */
class WatchNowConfirmActivity : Activity() {

    private var resolved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.7f)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val appLabel = intent.getStringExtra(EXTRA_APP_LABEL) ?: ""

        val message = TextView(this).apply {
            text = getString(R.string.watch_now_confirm_message, title)
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(16, 16, 16, 32)
        }
        val openButton = Button(this).apply {
            text = getString(R.string.watch_now_confirm_button, appLabel)
            isFocusableInTouchMode = true
            setOnClickListener { finishWith(confirmed = true) }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 48, 56, 48)
            addView(message)
            addView(openButton)
        }
        setContentView(layout)
        openButton.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finishWith(confirmed = false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun finishWith(confirmed: Boolean) {
        if (resolved) return
        resolved = true

        if (confirmed) {
            val imdbId = intent.getStringExtra(EXTRA_IMDB_ID)
            val typeName = intent.getStringExtra(EXTRA_TYPE)
            val title = intent.getStringExtra(EXTRA_TITLE)
            if (imdbId != null && typeName != null && title != null) {
                val match = TmdbMatch(imdbId, MediaType.valueOf(typeName), title)
                StremioLauncher.open(this, match)
            }
            TvRecommendationAccessibilityService.instance?.onWatchNowConfirmed()
        } else {
            TvRecommendationAccessibilityService.instance?.onWatchNowDismissed()
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Si la Activity se destruye sin pasar por finishWith (p.ej. el
        // sistema la mata), tratarlo igual que un descarte — mejor
        // reintentar de más que dejar la recomendación colgada sin más.
        if (!resolved) {
            TvRecommendationAccessibilityService.instance?.onWatchNowDismissed()
        }
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_APP_LABEL = "appLabel"
        const val EXTRA_IMDB_ID = "imdbId"
        const val EXTRA_TYPE = "type"
    }
}
