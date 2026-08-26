package com.tunombre.tvbridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Se muestra cuando TmdbClient.resolve() devuelve Ambiguous: dos o más
 * títulos EXACTOS con años distintos (p.ej. un remake) — no hay forma de
 * adivinar cuál quería el usuario, así que se le pregunta en vez de coger
 * el primero a ciegas (ver Preferences.isAskWhenAmbiguousEnabled).
 *
 * Solo se usa en el flujo de Google TV (ver
 * TvRecommendationAccessibilityService.handleMovieClick) — el de Fire TV
 * ya tiene su propio diálogo de confirmación (ConfirmOpenActivity) y
 * apilar un segundo encima sería confuso, así que ahí se sigue cogiendo el
 * primer candidato automáticamente (ver TmdbClient.findImdbId).
 */
class MatchPickerActivity : Activity() {

    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private var choiceMade = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_match_picker)

        val query = intent.getStringExtra(EXTRA_QUERY) ?: ""
        val tmdbIds = intent.getIntArrayExtra(EXTRA_TMDB_IDS) ?: IntArray(0)
        val mediaPaths = intent.getStringArrayExtra(EXTRA_MEDIA_PATHS) ?: emptyArray()
        val titles = intent.getStringArrayExtra(EXTRA_TITLES) ?: emptyArray()
        val years = intent.getStringArrayExtra(EXTRA_YEARS) ?: emptyArray()

        findViewById<TextView>(R.id.match_picker_title).text =
            getString(R.string.match_picker_title, query)

        val list = findViewById<LinearLayout>(R.id.match_picker_list)
        for (i in tmdbIds.indices) {
            val candidate = TmdbCandidate(
                tmdbId = tmdbIds[i],
                mediaPath = mediaPaths.getOrElse(i) { "movie" },
                title = titles.getOrElse(i) { query },
                year = years.getOrElse(i) { "" }.takeIf { it.isNotBlank() }
            )
            val label = if (candidate.year != null) "${candidate.title} (${candidate.year})" else candidate.title
            val button = Button(this).apply {
                text = label
                setBackgroundResource(R.drawable.bg_button)
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setOnClickListener { onCandidateChosen(candidate) }
            }
            val spacingPx = (12 * resources.displayMetrics.density).toInt()
            list.addView(button, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = spacingPx })
        }

        if (tmdbIds.isNotEmpty()) {
            list.getChildAt(0).requestFocus()
        }
    }

    private fun onCandidateChosen(candidate: TmdbCandidate) {
        if (choiceMade) return
        choiceMade = true
        backgroundExecutor.execute {
            val match = TmdbClient.resolveCandidate(candidate)
            runOnUiThread {
                if (match == null) {
                    Log.w(TAG, "No se pudo resolver el candidato elegido: ${candidate.title}")
                    Toast.makeText(this, R.string.main_subscription_error, Toast.LENGTH_SHORT).show()
                    finish()
                    return@runOnUiThread
                }
                StremioLauncher.open(this, match)
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdown()
    }

    companion object {
        private const val TAG = "MatchPickerActivity"
        private const val EXTRA_QUERY = "query"
        private const val EXTRA_TMDB_IDS = "tmdb_ids"
        private const val EXTRA_MEDIA_PATHS = "media_paths"
        private const val EXTRA_TITLES = "titles"
        private const val EXTRA_YEARS = "years"

        fun launch(context: Context, resolution: TmdbResolution.Ambiguous) {
            val intent = Intent(context, MatchPickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_QUERY, resolution.query)
                putExtra(EXTRA_TMDB_IDS, resolution.candidates.map { it.tmdbId }.toIntArray())
                putExtra(EXTRA_MEDIA_PATHS, resolution.candidates.map { it.mediaPath }.toTypedArray())
                putExtra(EXTRA_TITLES, resolution.candidates.map { it.title }.toTypedArray())
                putExtra(EXTRA_YEARS, resolution.candidates.map { it.year ?: "" }.toTypedArray())
            }
            context.startActivity(intent)
        }
    }
}
