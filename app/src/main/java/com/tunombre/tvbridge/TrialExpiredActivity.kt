package com.tunombre.tvbridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Se muestra en vez de abrir la app de destino cuando el usuario pulsa una
 * recomendación y su prueba gratuita ya ha terminado (ver
 * TvRecommendationAccessibilityService/StremioLauncher) — justo el momento
 * de mayor intención de compra, en vez de dejar que la app deje de
 * funcionar en silencio como antes.
 *
 * Mismo patrón visual/de foco que ConfirmOpenActivity (diálogo propio,
 * botón en pantalla en vez de depender de Atrás).
 */
class TrialExpiredActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.7f)

        val title = TextView(this).apply {
            text = getString(R.string.trial_expired_dialog_title)
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 16)
        }
        val message = TextView(this).apply {
            text = getString(R.string.trial_expired_dialog_body)
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, 0, 0, 32)
        }
        val unlockButton = Button(this).apply {
            text = getString(R.string.trial_expired_dialog_unlock_button)
            isFocusableInTouchMode = true
            setBackgroundResource(R.drawable.bg_button)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setOnClickListener {
                startActivity(Intent(this@TrialExpiredActivity, SubscriptionActivity::class.java))
                finish()
            }
        }
        val closeButton = Button(this).apply {
            text = getString(R.string.help_back_button)
            isFocusableInTouchMode = true
            setBackgroundResource(R.drawable.bg_button)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setOnClickListener { finish() }
        }
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(unlockButton)
            addView(closeButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (16 * resources.displayMetrics.density).toInt() })
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 48, 56, 48)
            setBackgroundResource(R.drawable.bg_card)
            addView(title)
            addView(message)
            addView(buttonRow)
        }
        setContentView(layout)
        unlockButton.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
