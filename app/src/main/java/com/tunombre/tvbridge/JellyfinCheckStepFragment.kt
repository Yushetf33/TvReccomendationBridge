package com.tunombre.tvbridge

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction

/** Comprobación opcional contra el servidor Jellyfin personal del usuario
 * (ver JellyfinApiClient/StremioLauncher.tryOpenInPersonalJellyfin) —
 * checkbox + dos campos editables (URL, API key) + acción de guardar,
 * porque GuidedAction sí soporta edición de texto en línea. */
class JellyfinCheckStepFragment : GuidedStepSupportFragment() {

    override fun onProvideTheme(): Int = R.style.Theme_TvRecommendationBridge_GuidedStep

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            getString(R.string.main_jellyfin_check_title),
            getString(R.string.main_jellyfin_check_explainer),
            getString(R.string.app_name),
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()

        actions.add(
            GuidedAction.Builder(context)
                .id(ID_ENABLE)
                .title(R.string.main_jellyfin_check_enable)
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(Preferences.isJellyfinCheckEnabled(context))
                .build()
        )
        actions.add(
            GuidedAction.Builder(context)
                .id(ID_URL)
                .title(Preferences.getJellyfinServerUrl(context).orEmpty())
                .description(R.string.main_jellyfin_server_url_hint)
                .editable(true)
                .inputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
                .build()
        )
        actions.add(
            GuidedAction.Builder(context)
                .id(ID_API_KEY)
                .title(Preferences.getJellyfinApiKey(context).orEmpty())
                .description(R.string.main_jellyfin_api_key_hint)
                .editable(true)
                .inputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                .build()
        )
        actions.add(
            GuidedAction.Builder(context)
                .id(ID_SAVE)
                .title(R.string.main_jellyfin_check_save)
                .build()
        )
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        // No hace falta reaccionar aquí — se lee el valor actual de cada
        // campo directamente de la lista de acciones al pulsar "Guardar".
        return GuidedAction.ACTION_ID_NEXT
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id != ID_SAVE) return

        val context = requireContext()
        val list = actions ?: return
        val enabled = list.find { it.id == ID_ENABLE }?.isChecked ?: false
        val serverUrl = list.find { it.id == ID_URL }?.title?.toString()?.trim().orEmpty()
        val apiKey = list.find { it.id == ID_API_KEY }?.title?.toString()?.trim().orEmpty()

        if (enabled && (serverUrl.isBlank() || apiKey.isBlank())) {
            Toast.makeText(context, R.string.main_jellyfin_check_missing_fields, Toast.LENGTH_LONG).show()
            return
        }

        if (serverUrl.isNotBlank() && apiKey.isNotBlank()) {
            Preferences.setJellyfinServerConfig(context, serverUrl, apiKey)
        }
        Preferences.setJellyfinCheckEnabled(context, enabled)
        Toast.makeText(context, R.string.main_jellyfin_check_saved, Toast.LENGTH_SHORT).show()
        fragmentManager?.popBackStack()
    }

    companion object {
        private const val ID_ENABLE = 1L
        private const val ID_URL = 2L
        private const val ID_API_KEY = 3L
        private const val ID_SAVE = 4L
    }
}
