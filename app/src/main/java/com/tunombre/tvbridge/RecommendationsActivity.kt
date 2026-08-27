package com.tunombre.tvbridge

import android.os.Bundle
import androidx.fragment.app.FragmentActivity

/** Pantalla con la fila de "Recomendado para ti" (ver
 * RecommendationsRowsFragment) — accesible desde Ajustes, funciona siempre,
 * a diferencia de la fila equivalente en la pantalla de inicio de Android TV
 * (ver RecommendationChannelManager). */
class RecommendationsActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recommendations)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.recommendations_fragment_container, RecommendationsHomeFragment())
                .commit()
        }
    }
}
