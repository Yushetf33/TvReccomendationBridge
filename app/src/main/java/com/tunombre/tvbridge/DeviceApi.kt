package com.tunombre.tvbridge

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Un dispositivo vinculado a la suscripción, tal y como lo devuelve /api/devices. */
data class BoundDevice(
    val deviceId: String,
    val deviceName: String?,
    val boundAt: Long,
    val isThisDevice: Boolean
)

sealed class DevicesResult {
    data class Loaded(val devices: List<BoundDevice>) : DevicesResult()
    data class Error(val reason: String) : DevicesResult()
}

/**
 * Llama a /api/devices (listar/eliminar dispositivos vinculados a un
 * email). El backend exige que ESTE dispositivo (por su deviceId) ya esté
 * vinculado a ese email como prueba de propiedad — ver
 * TvRecommendationBridge-backend/api/devices.js.
 */
object DeviceApi {

    private const val TAG = "DeviceApi"
    private val httpClient = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun listDevices(context: Context, email: String): DevicesResult {
        val body = JSONObject().apply {
            put("email", email)
            put("deviceId", LicenseManager.getDeviceId(context))
            put("action", "list")
        }
        return runCatching { call(body) }.fold(
            onSuccess = { json ->
                if (json.optBoolean("ok", false)) {
                    DevicesResult.Loaded(parseDevices(json))
                } else {
                    DevicesResult.Error(json.optString("reason", "unknown"))
                }
            },
            onFailure = {
                Log.e(TAG, "Error listando dispositivos", it)
                DevicesResult.Error("network_error")
            }
        )
    }

    /** true si se pudo eliminar. */
    fun removeDevice(context: Context, email: String, targetDeviceId: String): Boolean {
        val body = JSONObject().apply {
            put("email", email)
            put("deviceId", LicenseManager.getDeviceId(context))
            put("action", "remove")
            put("targetDeviceId", targetDeviceId)
        }
        return runCatching { call(body) }
            .onFailure { Log.e(TAG, "Error eliminando dispositivo", it) }
            .getOrNull()
            ?.optBoolean("ok", false) ?: false
    }

    private fun parseDevices(json: JSONObject): List<BoundDevice> {
        val devices = mutableListOf<BoundDevice>()
        val arr = json.optJSONArray("devices") ?: return devices
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            devices.add(
                BoundDevice(
                    deviceId = d.getString("deviceId"),
                    deviceName = if (d.has("deviceName") && !d.isNull("deviceName")) d.getString("deviceName") else null,
                    boundAt = d.optLong("boundAt", 0L),
                    isThisDevice = d.optBoolean("isThisDevice", false)
                )
            )
        }
        return devices
    }

    private fun call(body: JSONObject): JSONObject {
        val apiUrl = BuildConfig.LICENSE_API_URL
        val request = Request.Builder()
            .url("${apiUrl.trimEnd('/')}/api/devices")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: throw IllegalStateException("empty response body")
            return JSONObject(responseBody)
        }
    }
}
