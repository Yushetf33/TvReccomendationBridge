package com.tunombre.tvbridge

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Resultado de una verificación (en vivo o desde caché) de la suscripción. */
sealed class VerifyResult {
    /** [trialEndsAt] es null si no está en periodo de prueba (lifetime, plan
     * antiguo, o email exento) — solo viene relleno durante el trial.
     * [status] es el estado devuelto por el backend ("lifetime", "active" o
     * "trialing"), para poder distinguir lifetime de una suscripción normal
     * activa aunque ninguna de las dos tenga trialEndsAt. */
    data class Valid(val trialEndsAt: Long? = null, val status: String? = null) : VerifyResult()
    data class Invalid(val reason: String, val retryInDays: Int? = null) : VerifyResult()
    object NetworkError : VerifyResult()
}

/**
 * Comprueba contra el backend (ver TvRecommendationBridge-backend/) si el
 * email guardado tiene una suscripción activa, y cachea el resultado
 * localmente para que el servicio de accesibilidad no dependa de tener
 * internet en cada clic.
 *
 * La verificación en caché es válida durante [GRACE_PERIOD_MS]: pasado ese
 * tiempo sin poder confirmar una suscripción activa, se considera inválida
 * aunque la última comprobación real hubiese sido positiva.
 */
object LicenseManager {

    private const val TAG = "LicenseManager"
    private const val PREFS_NAME = "tvbridge_license"
    private const val KEY_EMAIL = "email"
    private const val KEY_LAST_OK = "last_ok"
    private const val KEY_LAST_CHECK_AT = "last_check_at"
    private const val KEY_TRIAL_ENDS_AT = "trial_ends_at"
    private const val KEY_LICENSE_STATUS = "license_status"

    private val GRACE_PERIOD_MS = 3 * 24 * 60 * 60 * 1000L // 3 días

    private val httpClient = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun getSavedEmail(context: Context): String? =
        prefs(context).getString(KEY_EMAIL, null)

    fun getDeviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"

    /** Nombre legible del dispositivo (fabricante + modelo), para que el
     * usuario pueda identificarlo en la pantalla "Gestionar dispositivos". */
    fun getDeviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /** Borra el email guardado y el estado de verificación de este
     * dispositivo (p.ej. tras eliminarlo a sí mismo en "Gestionar
     * dispositivos"), forzando a verificar de nuevo antes de seguir usándolo. */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** true si la última verificación con éxito sigue dentro del periodo de gracia. */
    fun isLikelyValid(context: Context): Boolean {
        val prefs = prefs(context)
        val lastOk = prefs.getBoolean(KEY_LAST_OK, false)
        if (!lastOk) return false
        val lastCheckAt = prefs.getLong(KEY_LAST_CHECK_AT, 0L)
        return (System.currentTimeMillis() - lastCheckAt) < GRACE_PERIOD_MS
    }

    /** Fecha (epoch ms) en la que termina el trial en curso, o null si no
     * hay trial activo (lifetime, plan antiguo, o directamente sin
     * suscripción) — viene de la última verificación con el backend, ver
     * [verifyNow]. */
    fun getTrialEndsAt(context: Context): Long? {
        val value = prefs(context).getLong(KEY_TRIAL_ENDS_AT, -1L)
        return value.takeIf { it > 0 }
    }

    /** true si hay una fecha de fin de trial guardada y ya ha pasado —
     * distinto de [isLikelyValid], que solo mira si la última verificación
     * fue positiva dentro del periodo de gracia. Un trial recién expirado
     * puede seguir dando isLikelyValid=true unos días más por el periodo de
     * gracia, así que esto es lo que hay que mirar para decidir si mostrar
     * el aviso de "tu prueba ha terminado". */
    fun isTrialExpired(context: Context): Boolean {
        val endsAt = getTrialEndsAt(context) ?: return false
        return System.currentTimeMillis() >= endsAt
    }

    /** "lifetime", "active" o "trialing" según la última verificación con
     * éxito, o null si todavía no se ha verificado nunca. */
    fun getLicenseStatus(context: Context): String? =
        prefs(context).getString(KEY_LICENSE_STATUS, null)

    /**
     * Verificación SÍNCRONA y bloqueante contra el backend. Llamar siempre
     * desde un hilo de fondo.
     */
    fun verifyNow(context: Context, email: String): VerifyResult {
        val apiUrl = BuildConfig.LICENSE_API_URL
        if (apiUrl.isBlank()) {
            Log.w(TAG, "LICENSE_API_URL vacía: añade LICENSE_API_URL en local.properties")
            return VerifyResult.NetworkError
        }

        val deviceId = getDeviceId(context)
        val body = JSONObject().apply {
            put("email", email)
            put("deviceId", deviceId)
            put("deviceName", getDeviceName())
        }.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("${apiUrl.trimEnd('/')}/api/verify")
            .post(body)
            .build()

        val result = try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (responseBody == null) {
                    VerifyResult.NetworkError
                } else {
                    val json = JSONObject(responseBody)
                    if (json.optBoolean("ok", false)) {
                        val trialEndsAt = if (json.isNull("trialEndsAt")) null else json.optLong("trialEndsAt")
                        val status = if (json.isNull("status")) null else json.optString("status")
                        VerifyResult.Valid(trialEndsAt = trialEndsAt?.takeIf { it > 0 }, status = status)
                    } else {
                        VerifyResult.Invalid(
                            reason = json.optString("reason", "unknown"),
                            retryInDays = json.optInt("retryInDays", -1).takeIf { it >= 0 }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error verificando suscripción", e)
            VerifyResult.NetworkError
        }

        persistResult(context, email, result)
        return result
    }

    private fun persistResult(context: Context, email: String, result: VerifyResult) {
        val editor = prefs(context).edit()
        editor.putString(KEY_EMAIL, email)
        when (result) {
            is VerifyResult.Valid -> {
                editor.putBoolean(KEY_LAST_OK, true)
                editor.putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis())
                if (result.status != null) {
                    editor.putString(KEY_LICENSE_STATUS, result.status)
                } else {
                    editor.remove(KEY_LICENSE_STATUS)
                }
                if (result.trialEndsAt != null) {
                    editor.putLong(KEY_TRIAL_ENDS_AT, result.trialEndsAt)
                    TrialReminderScheduler.scheduleFor(context, result.trialEndsAt)
                } else {
                    // No es un trial (o ya se convirtió a lifetime) — no
                    // dejamos una fecha vieja que dispare avisos de más, ni
                    // avisos ya programados de cuando sí lo era.
                    editor.remove(KEY_TRIAL_ENDS_AT)
                    TrialReminderScheduler.cancelAll(context)
                }
            }
            is VerifyResult.Invalid -> {
                editor.putBoolean(KEY_LAST_OK, false)
                editor.putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis())
            }
            is VerifyResult.NetworkError -> {
                // No tocamos KEY_LAST_OK/KEY_LAST_CHECK_AT: un fallo de red no
                // debe invalidar una verificación previa válida antes de que
                // expire su periodo de gracia.
            }
        }
        editor.apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
