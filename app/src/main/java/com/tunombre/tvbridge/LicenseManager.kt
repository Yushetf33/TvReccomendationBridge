package com.tunombre.tvbridge

import android.content.Context
import android.provider.Settings
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Resultado de una verificación (en vivo o desde caché) de la suscripción. */
sealed class VerifyResult {
    object Valid : VerifyResult()
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

    private val GRACE_PERIOD_MS = 3 * 24 * 60 * 60 * 1000L // 3 días

    private val httpClient = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun getSavedEmail(context: Context): String? =
        prefs(context).getString(KEY_EMAIL, null)

    fun getDeviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"

    /** true si la última verificación con éxito sigue dentro del periodo de gracia. */
    fun isLikelyValid(context: Context): Boolean {
        val prefs = prefs(context)
        val lastOk = prefs.getBoolean(KEY_LAST_OK, false)
        if (!lastOk) return false
        val lastCheckAt = prefs.getLong(KEY_LAST_CHECK_AT, 0L)
        return (System.currentTimeMillis() - lastCheckAt) < GRACE_PERIOD_MS
    }

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
                        VerifyResult.Valid
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
