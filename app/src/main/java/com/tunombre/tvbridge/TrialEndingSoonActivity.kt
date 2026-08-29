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
 * Se muestra ADEMÁS de abrir la app de destino (no en su lugar, a
 * diferencia de [TrialExpiredActivity]) cuando el usuario pulsa una
 * recomendación y su prueba gratuita está a punto de terminar — ver
 * [LicenseManager.dueTrialReminderStage] en
 * TvRecommendationAccessibilityService. Solo un aviso de paso: no bloquea
 * nada, la recomendación se sigue abriendo igual.
 *
 * Existe por separado del aviso de sistema ([TrialNotificationHelper]):
 * en algunos launchers (confirmado TCL) las notificaciones no se ven de
 * forma fiable, así que esto es la vía garantizada de que el usuario lo
 * vea, en el momento de mayor intención de compra.
 */
class TrialEndingSoonActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.7f)

        val stage = intent.getStringExtra(EXTRA_STAGE)
            ?.let { runCatching { LicenseManager.TrialReminderStage.valueOf(it) }.getOrNull() }
            ?: LicenseManager.TrialReminderStage.DAY_BEFORE
        val (titleRes, bodyRes) = when (stage) {
            LicenseManager.TrialReminderStage.DAY_BEFORE ->
                R.string.trial_notif_day_before_title to R.string.trial_notif_day_before_text
            LicenseManager.TrialReminderStage.HOURS_BEFORE ->
                R.string.trial_notif_hours_before_title to R.string.trial_notif_hours_before_text
            LicenseManager.TrialReminderStage.HOUR_BEFORE ->
                R.string.trial_notif_hour_before_title to R.string.trial_notif_hour_before_text
        }

        val title = TextView(this).apply {
            text = getString(titleRes)
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 16)
        }
        val message = TextView(this).apply {
            text = getString(bodyRes)
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
                startActivity(Intent(this@TrialEndingSoonActivity, SubscriptionActivity::class.java))
                finish()
            }
        }
        val dismissButton = Button(this).apply {
            text = getString(R.string.trial_ending_soon_dialog_dismiss_button)
            isFocusableInTouchMode = true
            setBackgroundResource(R.drawable.bg_button)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setOnClickListener { finish() }
        }
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(unlockButton)
            addView(dismissButton, LinearLayout.LayoutParams(
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
        dismissButton.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val EXTRA_STAGE = "stage"
    }
}
