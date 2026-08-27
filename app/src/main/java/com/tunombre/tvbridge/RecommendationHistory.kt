package com.tunombre.tvbridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Historial local de títulos que el usuario ha abierto de verdad a través
 * de la app — la base para la fila de "Recomendado para ti" que publicamos
 * en la pantalla de inicio (ver RecommendationChannelWorker). Vive solo en
 * este dispositivo, no se manda a ningún sitio.
 */
data class OpenedTitle(val tmdbId: Int, val mediaPath: String, val title: String)

object RecommendationHistory {
    private const val PREFS_NAME = "tvbridge_prefs"
    private const val KEY_HISTORY = "opened_titles_history"

    // Suficiente para variar las recomendaciones sin pedirle a TMDb una
    // llamada por cada título que el usuario haya abierto en su vida.
    private const val MAX_ENTRIES = 20

    /** Añade un título recién abierto al principio del historial (lo más
     * reciente primero), sin duplicados — si ya estaba, se mueve arriba en
     * vez de dejar una entrada repetida. */
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
    }

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
}
