package dev.pampa.fluidweather.feature.home

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.strings.R

@Composable
internal fun SharedRainHeadline(brief: WeatherBrief?) {
  Text(stringResource(when (WeatherBriefPresentation.headline(brief)) {
    BriefRainHeadline.MODEL_WET -> R.string.home_rain_model_wet
    BriefRainHeadline.MODEL_DRY -> R.string.home_rain_model_dry
    BriefRainHeadline.RADAR_WET -> R.string.home_rain_radar_wet
    BriefRainHeadline.RADAR_NO_ECHO -> R.string.home_rain_radar_no_echo
    BriefRainHeadline.WET -> R.string.home_rain_wet
    BriefRainHeadline.DRY -> R.string.home_rain_dry
    BriefRainHeadline.CONFLICT -> R.string.home_rain_conflict
    BriefRainHeadline.UNCERTAIN -> R.string.home_rain_uncertain
    BriefRainHeadline.UNAVAILABLE -> R.string.home_rain_unavailable
  }))
}
