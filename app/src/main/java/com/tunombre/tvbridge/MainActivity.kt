package com.tunombre.tvbridge

import android.app.Activity
import android.os.Bundle

/**
 * Activity mínima, obligatoria porque Android exige al menos un componente
 * de lanzamiento en el manifest. No se usa para nada: se cierra al instante.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
