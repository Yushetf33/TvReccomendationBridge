package com.tunombre.tvbridge

import android.os.Bundle
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction

/** App de destino por separado para películas y series (ver
 * Preferences.getAppFor/StremioLauncher.open) — solo se llega aquí si se
 * activó el interruptor en PlayerAppStepFragment; deliberadamente NO tiene
 * su propio interruptor de activación, para no duplicarlo. */
class ContentTypeRulesStepFragment : GuidedStepSupportFragment() {

    override fun onProvideTheme(): Int = R.style.Theme_TvRecommendationBridge_GuidedStep

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            getString(R.string.main_content_type_rules_title), null, getString(R.string.app_name), null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()
        val selectedMovie = Preferences.getMovieApp(context)
        val selectedSeries = Preferences.getSeriesApp(context)

        val apps = listOf(
            Triple(PlayerApp.NUVIO, R.string.main_nuvio_label, ID_MOVIE_NUVIO to ID_SERIES_NUVIO),
            Triple(PlayerApp.STREMIO, R.string.main_stremio_label, ID_MOVIE_STREMIO to ID_SERIES_STREMIO),
            Triple(PlayerApp.PLEX, R.string.main_plex_label, ID_MOVIE_PLEX to ID_SERIES_PLEX),
            Triple(PlayerApp.JELLYFIN, R.string.main_jellyfin_label, ID_MOVIE_JELLYFIN to ID_SERIES_JELLYFIN),
            Triple(PlayerApp.WUPLAY, R.string.main_wuplay_label, ID_MOVIE_WUPLAY to ID_SERIES_WUPLAY),
            Triple(PlayerApp.WHOLPHIN, R.string.main_wholphin_label, ID_MOVIE_WHOLPHIN to ID_SERIES_WHOLPHIN)
        )

        actions.add(
            GuidedAction.Builder(context)
                .title(R.string.main_movies_section)
                .infoOnly(true)
                .focusable(false)
                .build()
        )
        for ((app, labelRes, ids) in apps) {
            actions.add(
                GuidedAction.Builder(context)
                    .id(ids.first)
                    .title(labelRes)
                    .checkSetId(CHECK_SET_MOVIES)
                    .checked(app == selectedMovie)
                    .build()
            )
        }

        actions.add(
            GuidedAction.Builder(context)
                .title(R.string.main_series_section)
                .infoOnly(true)
                .focusable(false)
                .build()
        )
        for ((app, labelRes, ids) in apps) {
            actions.add(
                GuidedAction.Builder(context)
                    .id(ids.second)
                    .title(labelRes)
                    .checkSetId(CHECK_SET_SERIES)
                    .checked(app == selectedSeries)
                    .build()
            )
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val context = requireContext()
        when (action.id) {
            ID_MOVIE_NUVIO -> Preferences.setMovieApp(context, PlayerApp.NUVIO)
            ID_MOVIE_STREMIO -> Preferences.setMovieApp(context, PlayerApp.STREMIO)
            ID_MOVIE_PLEX -> Preferences.setMovieApp(context, PlayerApp.PLEX)
            ID_MOVIE_JELLYFIN -> Preferences.setMovieApp(context, PlayerApp.JELLYFIN)
            ID_MOVIE_WUPLAY -> Preferences.setMovieApp(context, PlayerApp.WUPLAY)
            ID_MOVIE_WHOLPHIN -> Preferences.setMovieApp(context, PlayerApp.WHOLPHIN)
            ID_SERIES_NUVIO -> Preferences.setSeriesApp(context, PlayerApp.NUVIO)
            ID_SERIES_STREMIO -> Preferences.setSeriesApp(context, PlayerApp.STREMIO)
            ID_SERIES_PLEX -> Preferences.setSeriesApp(context, PlayerApp.PLEX)
            ID_SERIES_JELLYFIN -> Preferences.setSeriesApp(context, PlayerApp.JELLYFIN)
            ID_SERIES_WUPLAY -> Preferences.setSeriesApp(context, PlayerApp.WUPLAY)
            ID_SERIES_WHOLPHIN -> Preferences.setSeriesApp(context, PlayerApp.WHOLPHIN)
        }
    }

    companion object {
        private const val CHECK_SET_MOVIES = 1
        private const val CHECK_SET_SERIES = 2

        private const val ID_MOVIE_NUVIO = 101L
        private const val ID_MOVIE_STREMIO = 102L
        private const val ID_MOVIE_PLEX = 103L
        private const val ID_MOVIE_JELLYFIN = 104L
        private const val ID_MOVIE_WUPLAY = 105L
        private const val ID_MOVIE_WHOLPHIN = 106L

        private const val ID_SERIES_NUVIO = 201L
        private const val ID_SERIES_STREMIO = 202L
        private const val ID_SERIES_PLEX = 203L
        private const val ID_SERIES_JELLYFIN = 204L
        private const val ID_SERIES_WUPLAY = 205L
        private const val ID_SERIES_WHOLPHIN = 206L
    }
}
