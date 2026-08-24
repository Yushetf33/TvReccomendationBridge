package com.tunombre.tvbridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Pantalla principal: verificación de la suscripción (email → backend de
 * Stripe), elegir si las recomendaciones se abren en Nuvio o Stremio, y
 * acceso directo a la pantalla de Accesibilidad para activar el servicio.
 */
class MainActivity : Activity() {

    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    private lateinit var subscriptionStatus: TextView
    private lateinit var emailInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        subscriptionStatus = findViewById(R.id.subscription_status)
        emailInput = findViewById(R.id.email_input)

        LicenseManager.getSavedEmail(this)?.let { emailInput.setText(it) }
        refreshSubscriptionLabel()

        findViewById<Button>(R.id.button_verify).setOnClickListener {
            val email = emailInput.text.toString().trim()
            if (email.isBlank() || !email.contains("@")) {
                subscriptionStatus.text = getString(R.string.main_subscription_not_subscribed)
                return@setOnClickListener
            }
            verifyEmail(email)
        }

        findViewById<Button>(R.id.button_manage_devices).setOnClickListener {
            if (LicenseManager.getSavedEmail(this) == null) {
                Toast.makeText(this, R.string.main_manage_devices_needs_verification, Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, DeviceManagerActivity::class.java))
            }
        }

        setupPaymentLinks()
        setupPlayerAppSelector()

        findViewById<Button>(R.id.button_accessibility_settings).setOnClickListener {
            // En algunos launchers de terceros (p.ej. Fire TV) el sistema
            // bloquea este intent para apps que no tengan un permiso propio
            // del fabricante, y lanza SecurityException en vez de abrir la
            // pantalla. En ese caso, evitamos el crash y pedimos al usuario
            // que navegue manualmente.
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    R.string.main_accessibility_settings_unavailable,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setupPaymentLinks() {
        setQrFor(R.id.monthly_qr, BuildConfig.MONTHLY_PAYMENT_URL)
        setQrFor(R.id.lifetime_qr, BuildConfig.LIFETIME_PAYMENT_URL)
    }

    private fun setQrFor(imageViewId: Int, url: String) {
        val imageView = findViewById<ImageView>(imageViewId)
        val bitmap = QrCodeGenerator.generate(url)
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap)
        }
    }

    private fun setupPlayerAppSelector() {
        val radioGroup = findViewById<RadioGroup>(R.id.player_app_group)
        val radioNuvio = findViewById<RadioButton>(R.id.radio_nuvio)
        val radioStremio = findViewById<RadioButton>(R.id.radio_stremio)
        val radioPlex = findViewById<RadioButton>(R.id.radio_plex)
        val radioJellyfin = findViewById<RadioButton>(R.id.radio_jellyfin)

        when (Preferences.getSelectedApp(this)) {
            PlayerApp.NUVIO -> radioNuvio.isChecked = true
            PlayerApp.STREMIO -> radioStremio.isChecked = true
            PlayerApp.PLEX -> radioPlex.isChecked = true
            PlayerApp.JELLYFIN -> radioJellyfin.isChecked = true
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val selected = when (checkedId) {
                R.id.radio_stremio -> PlayerApp.STREMIO
                R.id.radio_plex -> PlayerApp.PLEX
                R.id.radio_jellyfin -> PlayerApp.JELLYFIN
                else -> PlayerApp.NUVIO
            }
            Preferences.setSelectedApp(this, selected)
        }
    }

    private fun verifyEmail(email: String) {
        subscriptionStatus.text = getString(R.string.main_subscription_checking)
        backgroundExecutor.execute {
            val result = LicenseManager.verifyNow(this, email)
            runOnUiThread { showResult(email, result) }
        }
    }

    private fun showResult(email: String, result: VerifyResult) {
        subscriptionStatus.text = when (result) {
            is VerifyResult.Valid -> getString(R.string.main_subscription_active, email)
            is VerifyResult.Invalid -> when (result.reason) {
                "device_mismatch" -> getString(
                    R.string.main_subscription_device_mismatch,
                    result.retryInDays ?: 30
                )
                "trial_already_used" -> getString(R.string.main_subscription_trial_already_used)
                else -> getString(R.string.main_subscription_not_subscribed)
            }
            is VerifyResult.NetworkError -> getString(R.string.main_subscription_error)
        }
    }

    private fun refreshSubscriptionLabel() {
        val email = LicenseManager.getSavedEmail(this) ?: return
        if (LicenseManager.isLikelyValid(this)) {
            subscriptionStatus.text = getString(R.string.main_subscription_active, email)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdown()
    }
}
