package com.tunombre.tvbridge

import android.content.Intent
import android.os.Bundle
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction

/**
 * Paso raíz de Ajustes: un menú que reparte a cada sección en su propio
 * paso (ver PlayerAppStepFragment/YoutubeAppStepFragment/
 * JellyfinCheckStepFragment) o Activity (Suscripción, Ayuda) — sustituye a
 * la lista plana de toda la pantalla que había antes de v1.0.24.
 */
class MainMenuStepFragment : GuidedStepSupportFragment() {

    override fun onProvideTheme(): Int = R.style.Theme_TvRecommendationBridge_GuidedStep

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            getString(R.string.app_name),
            getString(R.string.main_subtitle),
            null,
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()
        val isFireTv = (activity as? MainActivity)?.isFireTvDevice() == true
        val activateLabel = if (isFireTv) R.string.main_firetv_mode_button else R.string.main_open_accessibility_settings

        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_SUBSCRIPTION)
                .title(R.string.main_subscription_title)
                .build()
        )
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_PLAYER_APP)
                .title(R.string.main_choose_app)
                .description(Preferences.getSelectedApp(context).label)
                .build()
        )
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_YOUTUBE_APP)
                .title(R.string.main_choose_youtube_app)
                .description(youtubeAppLabel(context))
                .build()
        )
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_JELLYFIN_CHECK)
                .title(R.string.main_jellyfin_check_title)
                .description(
                    if (Preferences.isJellyfinCheckEnabled(context)) {
                        getString(R.string.main_jellyfin_check_enable)
                    } else {
                        getString(R.string.main_not_configured)
                    }
                )
                .build()
        )
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_ACTIVATE)
                .title(activateLabel)
                .description(if (isFireTv) getString(R.string.main_firetv_mode_explainer) else null)
                .build()
        )
        if (!isFireTv) {
            actions.add(
                GuidedAction.Builder(context)
                    .id(ACTION_VOICE_SEARCH)
                    .title(R.string.main_voice_search_button)
                    .description(R.string.main_voice_search_explainer)
                    .build()
            )
            actions.add(
                GuidedAction.Builder(context)
                    .id(ACTION_RECOMMENDATIONS)
                    .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                    .checked(Preferences.isRecommendationsEnabled(context))
                    .title(R.string.main_recommendations_button)
                    .description(R.string.main_recommendations_explainer)
                    .build()
            )
            actions.add(
                GuidedAction.Builder(context)
                    .id(ACTION_VIEW_RECOMMENDATIONS)
                    .title(R.string.main_view_recommendations_button)
                    .build()
            )
        }
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_HELP)
                .title(R.string.main_help_button)
                .build()
        )
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_CREDITS)
                .title(R.string.main_credits_title)
                .description(R.string.main_tmdb_attribution)
                .build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val hostActivity = activity as? MainActivity ?: return
        when (action.id) {
            ACTION_SUBSCRIPTION -> startActivity(Intent(hostActivity, SubscriptionActivity::class.java))
            ACTION_PLAYER_APP -> add(parentFragmentManager,PlayerAppStepFragment())
            ACTION_YOUTUBE_APP -> add(parentFragmentManager,YoutubeAppStepFragment())
            ACTION_JELLYFIN_CHECK -> add(parentFragmentManager,JellyfinCheckStepFragment())
            ACTION_ACTIVATE -> hostActivity.performActivateService()
            ACTION_VOICE_SEARCH -> hostActivity.performVoiceSearchSetup()
            ACTION_RECOMMENDATIONS -> {
                Preferences.setRecommendationsEnabled(hostActivity, action.isChecked)
                if (action.isChecked) hostActivity.performActivateRecommendations()
            }
            ACTION_VIEW_RECOMMENDATIONS -> startActivity(Intent(hostActivity, RecommendationsActivity::class.java))
            ACTION_HELP -> startActivity(Intent(hostActivity, HelpActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresca las descripciones (app elegida, Jellyfin activado...) al
        // volver de un sub-paso, sin reconstruir toda la lista de acciones.
        actions?.let { list ->
            val context = requireContext()
            list.find { it.id == ACTION_PLAYER_APP }?.description = Preferences.getSelectedApp(context).label
            list.find { it.id == ACTION_YOUTUBE_APP }?.description = youtubeAppLabel(context)
            list.find { it.id == ACTION_JELLYFIN_CHECK }?.description = if (Preferences.isJellyfinCheckEnabled(context)) {
                getString(R.string.main_jellyfin_check_enable)
            } else {
                getString(R.string.main_not_configured)
            }
            setActions(list)
        }
    }

    private fun youtubeAppLabel(context: android.content.Context): String {
        return when (Preferences.getSelectedYoutubeApp(context)) {
            YoutubeApp.SMARTTUBE -> getString(R.string.main_smarttube_label)
            YoutubeApp.TIZENTUBE_COBALT -> getString(R.string.main_tizentube_label)
        }
    }

    companion object {
        private const val ACTION_SUBSCRIPTION = 1L
        private const val ACTION_PLAYER_APP = 2L
        private const val ACTION_YOUTUBE_APP = 3L
        private const val ACTION_JELLYFIN_CHECK = 4L
        private const val ACTION_ACTIVATE = 5L
        private const val ACTION_VOICE_SEARCH = 6L
        private const val ACTION_HELP = 7L
        private const val ACTION_CREDITS = 8L
        private const val ACTION_RECOMMENDATIONS = 9L
        private const val ACTION_VIEW_RECOMMENDATIONS = 10L
    }
}
