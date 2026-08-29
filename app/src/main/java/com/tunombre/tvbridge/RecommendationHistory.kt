package com.tunombre.tvbridge

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Historial de títulos que el usuario ha abierto de verdad a través de la
 * app — la base para la fila de "Recomendado para ti" que publicamos en la
 * pantalla de inicio (ver RecommendationChannelWorker). Vive en este
 * dispositivo, y además se sincroniza con el backend propio (por email, no
 * solo este TV) para que [fetchMerged] pueda tener en cuenta lo visto en
 * cualquier otro dispositivo vinculado al mismo email — ver
 * TvRecommendationBridge-backend/api/history-record.js y history-list.js.
 */
data class OpenedTitle(val tmdbId: Int, val mediaPath: String, val title: String)

object RecommendationHistory {
    private const val TAG = "RecommendationHistory"
    private const val PREFS_NAME = "tvbridge_prefs"
    private const val KEY_HISTORY = "opened_titles_history"

    // Suficiente para variar las recomendaciones sin pedirle a TMDb una
    // llamada por cada título que el usuario haya abierto en su vida.
    private const val MAX_ENTRIES = 20

    private val httpClient = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** Añade un título recién abierto al principio del historial local (lo
     * más reciente primero), sin duplicados — si ya estaba, se mueve arriba
     * en vez de dejar una entrada repetida. También lo manda al backend en
     * segundo plano (mejor esfuerzo: si falla por lo que sea, el historial
     * local ya se guardó igual). */
    fun record(context: Context, tmdbId: Int, mediaPath: String, title: String) {
        val current = getAll(context).toMutableList()
        current.removeAll { it.tmdbId == tmdbId && it.mediaPath == mediaPath }
        current.add(0, OpenedTitle(tmdbId, mediaPath, title))
        val trimmed = current.take(MAX_ENTRIES)

        val array = JSONArray()
        for (item in trimmed) {
            array.put(JSONObject().apply {
                put("tmdbId", item.tmdbId)
                put("mediaPath", item.mediaPath)
                put("title", item.title)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY, array.toString())
            .apply()

        syncRecordToBackend(context, tmdbId, mediaPath, title)
    }

    /** Solo el historial de este dispositivo — ver [fetchMerged] para el
     * fusionado con otros TVs del mismo email. Rápido y local, sin red: es
     * el que se usa para decisiones síncronas en el hilo principal (p.ej.
     * MainActivity decidiendo a qué pantalla ir al abrir la app). */
    fun getAll(context: Context): List<OpenedTitle> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                OpenedTitle(obj.getInt("tmdbId"), obj.getString("mediaPath"), obj.getString("title"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Historial local fusionado con el que el backend tiene guardado para
     * este email (lo visto en cualquier otro dispositivo vinculado) — lo
     * local va primero, así que en caso de solape gana el orden de este
     * TV. Si la llamada de red falla (sin conexión, sin suscripción...) se
     * cae al historial solo local, nunca deja la fila de recomendaciones
     * vacía por un fallo de red.
     *
     * Bloqueante (red) — llamar siempre desde un hilo de fondo. */
    fun fetchMerged(context: Context): List<OpenedTitle> {
        val local = getAll(context)
        val remote = fetchRemote(context) ?: return local
        return (local + remote)
            .distinctBy { it.tmdbId to it.mediaPath }
            .take(MAX_ENTRIES)
    }

    private fun fetchRemote(context: Context): List<OpenedTitle>? {
        val email = LicenseManager.getSavedEmail(context) ?: return null
        val body = JSONObject().apply {
            put("email", email)
            put("deviceId", LicenseManager.getDeviceId(context))
        }
        val json = postJson("/api/history-list", body) ?: return null
        if (!json.optBoolean("ok", false)) return null
        val array = json.optJSONArray("history") ?: return emptyList()
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            OpenedTitle(obj.getInt("tmdbId"), obj.getString("mediaPath"), obj.optString("title", ""))
        }
    }

    private fun syncRecordToBackend(context: Context, tmdbId: Int, mediaPath: String, title: String) {
        val email = LicenseManager.getSavedEmail(context) ?: return
        val deviceId = LicenseManager.getDeviceId(context)
        Thread {
            val body = JSONObject().apply {
                put("email", email)
                put("deviceId", deviceId)
                put("tmdbId", tmdbId)
                put("mediaPath", mediaPath)
                put("title", title)
            }
            postJson("/api/history-record", body)
        }.start()
    }

    private fun postJson(path: String, body: JSONObject): JSONObject? {
        val apiUrl = BuildConfig.LICENSE_API_URL
        if (apiUrl.isBlank()) return null
        val request = Request.Builder()
            .url("${apiUrl.trimEnd('/')}$path")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: return null
                JSONObject(responseBody)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error llamando a $path", e)
            null
        }
    }
}
