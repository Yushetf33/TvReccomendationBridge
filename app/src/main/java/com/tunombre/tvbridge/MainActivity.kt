package com.tunombre.tvbridge

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment

/**
 * Host de la pantalla principal: aloja el paso raíz de Leanback
 * ([MainMenuStepFragment]) y conserva la lógica que necesita ser una
 * Activity de verdad (permisos especiales, MediaProjection, Accesibilidad
 * directa) — los pasos de Leanback la disparan llamando a los métodos
 * públicos de aquí en vez de duplicarla.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()
        UpdateChecker.schedulePeriodicCheck(this)

        // Refresca la verificación de suscripción en segundo plano al
        // abrir, para que main_subscription_active no dependa de que el
        // usuario entre a la pantalla de Suscripción.
        LicenseManager.getSavedEmail(this)?.let { email ->
            Thread { LicenseManager.verifyNow(this, email) }.start()
        }

        if (savedInstanceState == null) {
            GuidedStepSupportFragment.addAsRoot(this, MainMenuStepFragment(), android.R.id.content)
        }
    }

    /** true en Fire TV — donde Fire OS bloquea AccessibilityService para
     * apps sideloaded (comprobado a fondo, sin workaround posible), así
     * que hace falta el modo de captura de pantalla + OCR en su lugar. */
    fun isFireTvDevice(): Boolean = Build.MANUFACTURER.equals("Amazon", ignoreCase = true)

    /** Un único punto de entrada que hace lo correcto según el
     * dispositivo — llamado desde [MainMenuStepFragment]. */
    fun performActivateService() {
        if (isFireTvDevice()) {
            if (!hasUsageAccess()) {
                Toast.makeText(this, R.string.main_firetv_usage_access_needed, Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.main_accessibility_settings_unavailable, Toast.LENGTH_LONG).show()
                }
                return
            }
            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)
            return
        }

        if (tryEnableAccessibilityDirectly()) {
            Toast.makeText(this, R.string.main_accessibility_enabled_directly, Toast.LENGTH_LONG).show()
            return
        }
        // En algunos launchers de terceros el sistema bloquea este intent
        // para apps que no tengan un permiso propio del fabricante, y
        // lanza SecurityException en vez de abrir la pantalla. En ese
        // caso, evitamos el crash y pedimos al usuario que navegue
        // manualmente.
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.main_accessibility_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    /** Solo en Google TV (Fire TV ya captura pantalla siempre como parte
     * de su propio modo). Opcional porque implica dejar la notificación
     * de grabación de pantalla siempre visible. */
    fun performVoiceSearchSetup() {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_VOICE_SEARCH_CAPTURE)
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

    /** "Acceso a datos de uso" — permiso especial (no aparece como diálogo
     * normal) que el modo Fire TV necesita para saber qué app está en
     * primer plano y no procesar OCR de otras apps (ver
     * FireTvCaptureService.isLauncherForeground). */
    fun hasUsageAccess(): Boolean {
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

    companion object {
        private const val REQUEST_CODE_SCREEN_CAPTURE = 100
        private const val REQUEST_CODE_VOICE_SEARCH_CAPTURE = 101
    }
}
