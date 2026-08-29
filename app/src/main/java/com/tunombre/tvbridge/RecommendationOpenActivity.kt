package com.tunombre.tvbridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Recibe el clic de un título de nuestra propia fila de "Recomendado para
 * ti" en la pantalla de inicio (ver RecommendationChannelManager, que le da
 * a cada tarjeta un intentUri "tvbridge://recommend?..." con el id de TMDb).
 * No hay ficha guardada de antemano — se resuelve al vuelo con la misma
 * ruta que usa cualquier otro flujo (TmdbClient.resolveCandidate), así que
 * respeta el player elegido y "Comprobar mi Jellyfin primero" aunque hayan
 * cambiado desde que se publicó la fila.
 */
class RecommendationOpenActivity : Activity() {

    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fondo simple mientras resuelve — el clic real solo necesita una
        // llamada de red (external_ids), suele ser casi instantáneo.
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@RecommendationOpenActivity, R.color.bg_root))
        })

        if (!LicenseManager.isLikelyValid(this)) {
            startActivity(Intent(this, TrialExpiredActivity::class.java))
            finish()
            return
        }

        val data = intent?.data
        val tmdbId = data?.getQueryParameter("tmdbId")?.toIntOrNull()
        val mediaPath = data?.getQueryParameter("mediaPath")
        val title = data?.getQueryParameter("title")
        if (tmdbId == null || mediaPath.isNullOrBlank() || title.isNullOrBlank()) {
            Log.w(TAG, "Intent de recomendación sin datos válidos: $data")
            finish()
            return
        }

        val candidate = TmdbCandidate(tmdbId, mediaPath, title, year = null)
        backgroundExecutor.execute {
            val match = TmdbClient.resolveCandidate(this, candidate)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (match != null) {
                    StremioLauncher.open(this, match)
                } else {
                    Log.w(TAG, "No se pudo resolver la recomendación: $title ($mediaPath/$tmdbId)")
                }
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdown()
    }

    companion object {
        private const val TAG = "RecommendationOpen"
    }
}
