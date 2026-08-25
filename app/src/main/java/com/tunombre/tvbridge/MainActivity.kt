package com.tunombre.tvbridge

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
        requestNotificationPermissionIfNeeded()
        UpdateChecker.schedulePeriodicCheck(this)

        setupActivateServiceButton()
    }

    /** Un único botón que hace lo correcto según el dispositivo: en Fire TV
     * (donde Fire OS bloquea AccessibilityService para apps sideloaded —
     * comprobado a fondo) activa el modo de captura de pantalla + OCR; en
     * cualquier otro Android TV, abre los ajustes de Accesibilidad de
     * siempre. */
    private fun setupActivateServiceButton() {
        val button = findViewById<Button>(R.id.button_activate_service)
        val explainer = findViewById<TextView>(R.id.activate_service_explainer)

        if (isFireTv()) {
            button.setText(R.string.main_firetv_mode_button)
            explainer.visibility = TextView.VISIBLE
            button.setOnClickListener {
                if (!hasUsageAccess()) {
                    Toast.makeText(this, R.string.main_firetv_usage_access_needed, Toast.LENGTH_LONG).show()
                    try {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    } catch (e: Exception) {
                        Toast.makeText(this, R.string.main_accessibility_settings_unavailable, Toast.LENGTH_LONG).show()
                    }
                    return@setOnClickListener
                }
                val projectionManager = getSystemService(MediaProjectionManager::class.java)
                startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)
            }
        } else {
            button.setOnClickListener {
                if (tryEnableAccessibilityDirectly()) {
                    Toast.makeText(this, R.string.main_accessibility_enabled_directly, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                // En algunos launchers de terceros el sistema bloquea este
                // intent para apps que no tengan un permiso propio del
                // fabricante, y lanza SecurityException en vez de abrir la
                // pantalla. En ese caso, evitamos el crash y pedimos al
                // usuario que navegue manualmente.
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
            setupVoiceSearchButton()
        }
    }

    /** Si el usuario ya nos ha concedido WRITE_SECURE_SETTINGS por ADB (una
     * vez, ver README) activamos el servicio directamente sin pasar por la
     * pantalla de Ajustes — hace falta sobre todo en dispositivos como el
     * Google TV Streamer, donde "Restricted Settings" bloquea el toggle de
     * Ajustes para cualquier app sideloaded y no hay ninguna opción visible
     * para desbloquearlo desde la propia TV.
     *
     * Se añade el servicio a la lista existente en vez de sobrescribirla —
     * a diferencia del comando manual `settings put` de toda la vida, esto
     * no se carga otros servicios de accesibilidad que el usuario ya tenga
     * activos (p.ej. TalkBack). Devuelve false si no tenemos el permiso,
     * para que el llamador recurra al método normal (abrir Ajustes). */
    private fun tryEnableAccessibilityDirectly(): Boolean {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_SECURE_SETTINGS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return try {
            val component = ComponentName(this, TvRecommendationAccessibilityService::class.java).flattenToString()
            val resolver = contentResolver
            val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val services = current?.split(':')?.filter { it.isNotBlank() }?.toMutableSet() ?: mutableSetOf()
            if (services.add(component)) {
                Settings.Secure.putString(
                    resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":")
                )
            }
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Solo en Google TV (Fire TV ya captura pantalla siempre como parte de
     * su propio modo). Opcional porque implica dejar la notificación de
     * grabación de pantalla siempre visible — ver el explicador en pantalla. */
    private fun setupVoiceSearchButton() {
        val button = findViewById<Button>(R.id.button_voice_search)
        val explainer = findViewById<TextView>(R.id.voice_search_explainer)
        button.visibility = TextView.VISIBLE
        explainer.visibility = TextView.VISIBLE
        button.setOnClickListener {
            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_VOICE_SEARCH_CAPTURE)
        }
    }

    private fun isFireTv(): Boolean = Build.MANUFACTURER.equals("Amazon", ignoreCase = true)

    /** "Acceso a datos de uso" — permiso especial (no aparece como diálogo
     * normal) que el modo Fire TV necesita para saber qué app está en
     * primer plano y no procesar OCR de otras apps (ver
     * FireTvCaptureService.isLauncherForeground). */
    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java)
        // checkOpNoThrow (no unsafeCheckOpNoThrow, que no existe todavía en
        // Android 7.1 / Fire OS 6) — mismo resultado, disponible desde API 19.
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_SCREEN_CAPTURE -> {
                if (resultCode != RESULT_OK || data == null) {
                    Toast.makeText(this, R.string.main_firetv_mode_denied, Toast.LENGTH_LONG).show()
                    return
                }
                val serviceIntent = Intent(this, FireTvCaptureService::class.java).apply {
                    putExtra(FireTvCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(FireTvCaptureService.EXTRA_DATA, data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                Toast.makeText(this, R.string.main_firetv_mode_enabled, Toast.LENGTH_LONG).show()
            }
            REQUEST_CODE_VOICE_SEARCH_CAPTURE -> {
                if (resultCode != RESULT_OK || data == null) {
                    Toast.makeText(this, R.string.main_voice_search_denied, Toast.LENGTH_LONG).show()
                    return
                }
                val serviceIntent = Intent(this, VoiceSearchCaptureService::class.java).apply {
                    putExtra(VoiceSearchCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(VoiceSearchCaptureService.EXTRA_DATA, data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                Toast.makeText(this, R.string.main_voice_search_enabled, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Sin este permiso (Android 13+) la notificación de "actualización
     * lista para instalar" de UpdateChecker no se mostraría. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
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
        val radioWuplay = findViewById<RadioButton>(R.id.radio_wuplay)

        when (Preferences.getSelectedApp(this)) {
            PlayerApp.NUVIO -> radioNuvio.isChecked = true
            PlayerApp.STREMIO -> radioStremio.isChecked = true
            PlayerApp.PLEX -> radioPlex.isChecked = true
            PlayerApp.JELLYFIN -> radioJellyfin.isChecked = true
            PlayerApp.WUPLAY -> radioWuplay.isChecked = true
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val selected = when (checkedId) {
                R.id.radio_stremio -> PlayerApp.STREMIO
                R.id.radio_plex -> PlayerApp.PLEX
                R.id.radio_jellyfin -> PlayerApp.JELLYFIN
                R.id.radio_wuplay -> PlayerApp.WUPLAY
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

    companion object {
        private const val REQUEST_CODE_SCREEN_CAPTURE = 100
        private const val REQUEST_CODE_VOICE_SEARCH_CAPTURE = 101
    }
}
