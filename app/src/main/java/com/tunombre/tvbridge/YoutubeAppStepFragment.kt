package com.tunombre.tvbridge

import android.os.Bundle
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction

/** App de destino para vídeos de YouTube — independiente de la de
 * películas/series (ver YoutubeLauncher). */
class YoutubeAppStepFragment : GuidedStepSupportFragment() {

    override fun onProvideTheme(): Int = R.style.Theme_TvRecommendationBridge_GuidedStep

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(getString(R.string.main_choose_youtube_app), null, getString(R.string.app_name), null)
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()
        val selected = Preferences.getSelectedYoutubeApp(context)

        actions.add(
            GuidedAction.Builder(context)
                .id(ID_SMARTTUBE)
                .title(R.string.main_smarttube_label)
                .checkSetId(GuidedAction.DEFAULT_CHECK_SET_ID)
                .checked(selected == YoutubeApp.SMARTTUBE)
                .build()
        )
        actions.add(
            GuidedAction.Builder(context)
                .id(ID_TIZENTUBE)
                .title(R.string.main_tizentube_label)
                .checkSetId(GuidedAction.DEFAULT_CHECK_SET_ID)
                .checked(selected == YoutubeApp.TIZENTUBE_COBALT)
                .build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val context = requireContext()
        val selected = when (action.id) {
            ID_TIZENTUBE -> YoutubeApp.TIZENTUBE_COBALT
            else -> YoutubeApp.SMARTTUBE
        }
        Preferences.setSelectedYoutubeApp(context, selected)
    }

    companion object {
        private const val ID_SMARTTUBE = 1L
        private const val ID_TIZENTUBE = 2L
    }
}
