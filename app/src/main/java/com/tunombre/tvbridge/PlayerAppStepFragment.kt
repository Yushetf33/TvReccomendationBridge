package com.tunombre.tvbridge

import android.os.Bundle
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction

/** App de destino para películas/series, más las dos opciones de
 * confirmación que dependen de ella (ver StremioLauncher.open y
 * TvRecommendationAccessibilityService). */
class PlayerAppStepFragment : GuidedStepSupportFragment() {

    override fun onProvideTheme(): Int = R.style.Theme_TvRecommendationBridge_GuidedStep

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(getString(R.string.main_choose_app), null, getString(R.string.app_name), null)
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()
        val selected = Preferences.getSelectedApp(context)

        val apps = listOf(
            Triple(ID_NUVIO, PlayerApp.NUVIO, R.string.main_nuvio_label),
            Triple(ID_STREMIO, PlayerApp.STREMIO, R.string.main_stremio_label),
            Triple(ID_PLEX, PlayerApp.PLEX, R.string.main_plex_label),
            Triple(ID_JELLYFIN, PlayerApp.JELLYFIN, R.string.main_jellyfin_label),
            Triple(ID_WUPLAY, PlayerApp.WUPLAY, R.string.main_wuplay_label),
            Triple(ID_WHOLPHIN, PlayerApp.WHOLPHIN, R.string.main_wholphin_label)
        )
        for ((id, app, labelRes) in apps) {
            actions.add(
                GuidedAction.Builder(context)
                    .id(id)
                    .title(labelRes)
                    .checkSetId(GuidedAction.DEFAULT_CHECK_SET_ID)
                    .checked(app == selected)
                    .build()
            )
        }

        actions.add(
            GuidedAction.Builder(context)
                .id(ID_ASK_AMBIGUOUS)
                .title(R.string.main_ask_when_ambiguous)
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(Preferences.isAskWhenAmbiguousEnabled(context))
                .build()
        )
        actions.add(
            GuidedAction.Builder(context)
                .id(ID_WATCH_NOW)
                .title(R.string.main_watch_now_confirm)
                .description(R.string.main_watch_now_confirm_explainer)
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(Preferences.isWatchNowConfirmEnabled(context))
                .build()
        )
        actions.add(
            GuidedAction.Builder(context)
                .id(ID_PER_TYPE_ROUTING)
                .title(R.string.main_per_type_routing)
                .description(R.string.main_per_type_routing_explainer)
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(Preferences.isPerTypeRoutingEnabled(context))
                .build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val context = requireContext()
        when (action.id) {
            ID_NUVIO -> Preferences.setSelectedApp(context, PlayerApp.NUVIO)
            ID_STREMIO -> Preferences.setSelectedApp(context, PlayerApp.STREMIO)
            ID_PLEX -> Preferences.setSelectedApp(context, PlayerApp.PLEX)
            ID_JELLYFIN -> Preferences.setSelectedApp(context, PlayerApp.JELLYFIN)
            ID_WUPLAY -> Preferences.setSelectedApp(context, PlayerApp.WUPLAY)
            ID_WHOLPHIN -> Preferences.setSelectedApp(context, PlayerApp.WHOLPHIN)
            ID_ASK_AMBIGUOUS -> Preferences.setAskWhenAmbiguousEnabled(context, action.isChecked)
            ID_WATCH_NOW -> Preferences.setWatchNowConfirmEnabled(context, action.isChecked)
            ID_PER_TYPE_ROUTING -> {
                Preferences.setPerTypeRoutingEnabled(context, action.isChecked)
                // Solo tiene sentido elegir las apps de película/serie una
                // vez activado -- si se acaba de desactivar, no hace falta
                // ir a esa pantalla (getAppFor ya vuelve a ignorarlas).
                if (action.isChecked) {
                    add(parentFragmentManager, ContentTypeRulesStepFragment())
                }
            }
        }
    }

    companion object {
        private const val ID_NUVIO = 1L
        private const val ID_STREMIO = 2L
        private const val ID_PLEX = 3L
        private const val ID_JELLYFIN = 4L
        private const val ID_WUPLAY = 5L
        private const val ID_WHOLPHIN = 8L
        private const val ID_ASK_AMBIGUOUS = 6L
        private const val ID_WATCH_NOW = 7L
        private const val ID_PER_TYPE_ROUTING = 9L
    }
}
