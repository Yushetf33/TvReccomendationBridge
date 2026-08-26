package com.tunombre.tvbridge

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Consulta la biblioteca personal del usuario en su propio servidor
 * Jellyfin (ver Preferences.getJellyfinServerUrl/getJellyfinApiKey) para
 * saber si un título ya está disponible ahí — comprobación opcional, solo
 * se activa si el usuario ha configurado servidor + API key en Ajustes (ver
 * StremioLauncher.tryOpenInPersonalJellyfin).
 *
 * La API key se genera desde el propio servidor (Panel de administración →
 * API Keys), no hace falta usuario/contraseña.
 *
 * Endpoint y nombres de campo confirmados contra el código fuente del
 * servidor (SearchController.cs / SearchHint.cs en jellyfin/jellyfin):
 * GET /Search/Hints?searchTerm=...&includeItemTypes=Movie,Series, auth por
 * el parámetro de query "ApiKey" (soportado siempre, a diferencia de
 * "api_key" en minúsculas que depende de un ajuste del servidor).
 */
object JellyfinApiClient {
    private const val TAG = "JellyfinApiClient"
    private val httpClient = OkHttpClient()

    /**
     * Busca [title] en la biblioteca del servidor y devuelve el Id del
     * primer resultado con título EXACTO (mismo criterio que
     * TmdbClient.resolveTitle: evita abrir el ítem equivocado con un match
     * parcial/homónimo), o null si no está en la biblioteca, el servidor no
     * responde, o la configuración es inválida.
     *
     * OJO: red de forma SÍNCRONA (bloqueante) — llamar solo desde donde ya
     * se llama a TmdbClient/PlexClient (nunca desde el hilo principal salvo
     * que ya sea aceptable ahí, como en ConfirmOpenActivity).
     */
    fun findExactItemId(serverUrl: String, apiKey: String, title: String): String? {
        val baseUrl = serverUrl.trim().trimEnd('/')
        val encodedTerm = URLEncoder.encode(title, "UTF-8")
        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        val url = "$baseUrl/Search/Hints?searchTerm=$encodedTerm&includeItemTypes=Movie,Series&limit=10&ApiKey=$encodedKey"
        val request = Request.Builder().url(url).build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Búsqueda en el servidor Jellyfin del usuario falló: ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                val hints = JSONObject(body).optJSONArray("SearchHints") ?: return null
                val normalizedQuery = title.trim().lowercase()
                for (i in 0 until hints.length()) {
                    val hint = hints.getJSONObject(i)
                    val name = hint.optString("Name", "")
                    if (name.trim().lowercase() == normalizedQuery) {
                        val id = hint.optString("Id", "")
                        if (id.isNotBlank()) return id
                    }
                }
                Log.d(TAG, "No está en la biblioteca del usuario: $title")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error consultando el servidor Jellyfin del usuario (¿URL/API key mal configurados?)", e)
            null
        }
    }
}
