package com.tunombre.tvbridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors

/**
 * Carga de pósters de TMDb a mano (no hay Glide/Picasso en el proyecto) —
 * compartida entre [RecommendationCardPresenter] (miniaturas de las filas) y
 * el panel de ficha ampliada de [RecommendationsHomeFragment] (póster
 * grande). Caché en memoria por tamaño+ruta para no repetir descargas al
 * volver a enfocar una tarjeta ya vista.
 */
object PosterLoader {
    private val cache = LruCache<String, Bitmap>(40)
    private val client = OkHttpClient()
    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** [widthPx] debe ser uno de los tamaños que sirve TMDb (92/154/185/
     * 342/500/780) — se usa tal cual en la URL de la imagen. */
    fun load(posterPath: String, widthPx: Int, onLoaded: (Bitmap?) -> Unit) {
        val cacheKey = "$widthPx:$posterPath"
        cache.get(cacheKey)?.let {
            onLoaded(it)
            return
        }
        executor.execute {
            val bitmap = try {
                val request = Request.Builder()
                    .url("https://image.tmdb.org/t/p/w$widthPx$posterPath")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) null
                    else response.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                }
            } catch (e: Exception) {
                null
            }
            if (bitmap != null) cache.put(cacheKey, bitmap)
            mainHandler.post { onLoaded(bitmap) }
        }
    }
}
