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
import android.util.Log
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
        if (Preferences.isRecommendationsEnabled(this)) {
            RecommendationChannelManager.schedulePeriodicRefresh(this)
        }

        // Refresca la verificación de suscripción en segundo plano al
        // abrir, para que main_subscription_active no dependa de que el
        // usuario entre a la pantalla de Suscripción.
        LicenseManager.getSavedEmail(this)?.let { email ->
            Thread { LicenseManager.verifyNow(this, email) }.start()
        }

        if (savedInstanceState == null) {
            // Una vez el usuario ha activado recomendaciones y ya tiene
            // historial, ir directo a "Recomendado para ti" en vez de a
            // Ajustes — la primera vez (o mientras no haya nada que
            // recomendar todavía) se sigue viendo Ajustes como siempre.
            val hasRecommendationsReady = Preferences.isRecommendationsEnabled(this) &&
                RecommendationHistory.getAll(this).isNotEmpty()
            if (hasRecommendationsReady) {
                supportFragmentManager.beginTransaction()
                    .replace(android.R.id.content, RecommendationsHomeFragment())
                    .commit()
            } else {
                GuidedStepSupportFragment.addAsRoot(this, MainMenuStepFragment(), android.R.id.content)
            }
        }
    }

    /** Vuelve a Ajustes desde la fila de recomendaciones cuando esta es el
     * contenido raíz de la Activity (ver onCreate) — llamado desde
     * RecommendationsRowsFragment al pulsar su tarjeta de "Ajustes". */
    fun showSettingsFromHome() {
        GuidedStepSupportFragment.addAsRoot(this, MainMenuStepFragment(), android.R.id.content)
    }

    /** Comprobación manual de actualizaciones (botón en Ajustes), a
     * diferencia del chequeo automático en segundo plano — esta siempre
     * consulta a GitHub de verdad (sin el límite de frecuencia de
     * [UpdateChecker.checkAndDownloadIfNewer]) y avisa con un Toast del
     * resultado, ya que el usuario la ha pedido explícitamente. */
    fun performCheckForUpdate() {
        Thread {
            val outcome = UpdateChecker.checkNow(this)
            runOnUiThread {
                val message = when (outcome) {
                    is UpdateChecker.CheckOutcome.UpToDate ->
                        getString(R.string.update_check_up_to_date, BuildConfig.VERSION_NAME)
                    is UpdateChecker.CheckOutcome.DownloadStarted ->
                        getString(R.string.update_check_downloading, outcome.version)
                    is UpdateChecker.CheckOutcome.NetworkError ->
                        getString(R.string.update_check_network_error)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
            // Pedido en primer plano por el propio usuario — no nos fiamos
            // solo de la notificación de fin de descarga para avisar de que
            // ya se puede instalar, ya que muchos launchers de Android TV
            // no la muestran nunca (ver comentario en
            // UpdateChecker.awaitDownloadAndInstall).
            if (outcome is UpdateChecker.CheckOutcome.DownloadStarted) {
                UpdateChecker.awaitDownloadAndInstall(this)
            }
        }.start()
    }

    /** true en Fire TV — donde Fire OS bloquea AccessibilityService para
     * apps sideloaded (comprobado a fondo, sin workaround posible), así
     * que hace falta el modo de captura de pantalla + OCR en su lugar. */
    fun isFireTvDevice(): Boolean = Build.MANUFACTURER.equals("Amazon", ignoreCase = true)

    /** Activa la fila de "Recomendado para ti" (ver
     * RecommendationChannelManager) — crea el canal si hace falta, pide que
     * sea visible en la pantalla de inicio, y programa el primer refresco
     * (puede no tener nada que mostrar todavía si el historial está vacío,
     * ver main_recommendations_enabled). Llamado desde
     * [MainMenuStepFragment] al marcar la casilla. */
    fun performActivateRecommendations() {
        Toast.makeText(this, R.string.main_recommendations_enabled, Toast.LENGTH_LONG).show()
        RecommendationChannelManager.schedulePeriodicRefresh(this)
        RecommendationChannelManager.scheduleOneShotRefresh(this)
        Thread {
            val browsableIntent = RecommendationChannelManager.requestBrowsable(this)
            if (browsableIntent == null) {
                Log.w(TAG, "requestBrowsable() devolvió null — no se pudo crear/leer el canal")
                return@Thread
            }
            runOnUiThread {
                try {
                    Log.d(TAG, "Lanzando ACTION_REQUEST_CHANNEL_BROWSABLE: $browsableIntent")
                    startActivityForResult(browsableIntent, REQUEST_CODE_CHANNEL_BROWSABLE)
                } catch (e: Exception) {
                    // Sin actividad del sistema que lo gestione (poco
                    // probable en un Google TV real) — el canal ya se
                    // creó igualmente, solo no se le pudo pedir permiso
                    // aparte; se comportará como el primer canal de la
                    // app, que ya se marca visible por su cuenta.
                    Log.e(TAG, "No se pudo lanzar ACTION_REQUEST_CHANNEL_BROWSABLE", e)
                }
            }
        }.start()
    }

    /** Un único punto de entrada que hace lo correcto según el
     * dispositivo — llamado desde [MainMenuStepFragment]. */
    fun performActivateService() {
        if (isFireTvDevice()) {
            if (!hasUsageAccess()) {
                Toast.makeText(this, R.string.main_firetv_usage_access_needed, Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.main_usage_access_settings_unavailable, Toast.LENGTH_LONG).show()
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
            REQUEST_CODE_CHANNEL_BROWSABLE -> {
                Log.d(TAG, "ACTION_REQUEST_CHANNEL_BROWSABLE resultCode=$resultCode (RESULT_OK=$RESULT_OK)")
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
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_SCREEN_CAPTURE = 100
        private const val REQUEST_CODE_VOICE_SEARCH_CAPTURE = 101
        private const val REQUEST_CODE_CHANNEL_BROWSABLE = 102
    }
}
