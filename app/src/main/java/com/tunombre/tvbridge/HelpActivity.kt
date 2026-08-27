package com.tunombre.tvbridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast

/**
 * Pantalla de ayuda/solución de problemas, accesible desde un botón en
 * MainActivity — para que un usuario atascado en la instalación no tenga
 * que salir de la TV a leer el README en el móvil/PC.
 *
 * El contenido está sacado directamente de los problemas más repetidos que
 * la gente reporta en Reddit con este tipo de apps (el toggle de
 * Accesibilidad que se autodesactiva, con diferencia el más común — visto
 * en al menos 5 usuarios distintos en un solo hilo), no son suposiciones.
 */
class HelpActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)

        findViewById<Button>(R.id.button_help_back).setOnClickListener { finish() }

        findViewById<Button>(R.id.button_help_open_app_info).setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                Toast.makeText(this, R.string.help_app_info_unavailable, Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.button_help_open_accessibility).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, R.string.main_accessibility_settings_unavailable, Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.button_help_open_usage_access).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, R.string.main_usage_access_settings_unavailable, Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.button_help_open_guide).setOnClickListener {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.help_guide_url)))
                )
            } catch (e: Exception) {
                Toast.makeText(this, R.string.help_guide_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }
}
