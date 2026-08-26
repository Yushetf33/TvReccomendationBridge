package com.tunombre.tvbridge

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Diálogo de confirmación para el "modo Fire TV" (ver FireTvCaptureService):
 * al no poder detectar un clic real sobre la tarjeta del launcher (Fire OS
 * bloquea AccessibilityService, y no existe ninguna otra vía para observar
 * pulsaciones de otra app — investigado a fondo), la única forma de
 * garantizar que nada se abre sin que el usuario lo decida de verdad es
 * pedirle que confirme aquí, en NUESTRA propia ventana, que sí recibe el
 * pulso de OK real del mando.
 *
 * Se cierra sola (sin abrir nada) si no se confirma antes de
 * [CONFIRM_TIMEOUT_MS], o si se pulsa Atrás.
 */
class ConfirmOpenActivity : Activity() {

    private var resolved = false
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { finishWith(confirmed = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.7f)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val appLabel = intent.getStringExtra(EXTRA_APP_LABEL) ?: ""

        val message = TextView(this).apply {
            text = getString(R.string.firetv_confirm_message, title, appLabel)
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(16, 16, 16, 32)
        }
        val openButton = Button(this).apply {
            text = getString(R.string.firetv_confirm_button)
            isFocusableInTouchMode = true
            setBackgroundResource(R.drawable.bg_button)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setOnClickListener { finishWith(confirmed = true) }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 48, 56, 48)
            setBackgroundResource(R.drawable.bg_card)
            addView(message)
            addView(openButton)
        }
        setContentView(layout)
        openButton.requestFocus()

        timeoutHandler.postDelayed(timeoutRunnable, CONFIRM_TIMEOUT_MS)
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
        timeoutHandler.removeCallbacks(timeoutRunnable)

        if (confirmed) {
            val imdbId = intent.getStringExtra(EXTRA_IMDB_ID)
            val typeName = intent.getStringExtra(EXTRA_TYPE)
            val title = intent.getStringExtra(EXTRA_TITLE)
            if (imdbId != null && typeName != null && title != null) {
                val match = TmdbMatch(imdbId, MediaType.valueOf(typeName), title)
                StremioLauncher.open(this, match)
            }
        }
        FireTvCaptureService.instance?.onConfirmResult()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!resolved) {
            FireTvCaptureService.instance?.onConfirmResult()
        }
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_APP_LABEL = "appLabel"
        const val EXTRA_IMDB_ID = "imdbId"
        const val EXTRA_TYPE = "type"
        private const val CONFIRM_TIMEOUT_MS = 6000L
    }
}
