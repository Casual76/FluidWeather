package dev.pampa.fluidweather.feature.home

import androidx.compose.runtime.Composable
import dev.pampa.fluidweather.core.ui.BlackSheet
import dev.pampa.fluidweather.core.ui.HomeWidget
import dev.pampa.fluidweather.feature.home.pages.AirQualityPage
import dev.pampa.fluidweather.feature.home.pages.DailyPage
import dev.pampa.fluidweather.feature.home.pages.DetailsPage
import dev.pampa.fluidweather.feature.home.pages.HourlyPage
import dev.pampa.fluidweather.feature.home.pages.MoonPage
import dev.pampa.fluidweather.feature.home.pages.NowcastPage
import dev.pampa.fluidweather.feature.home.pages.PrecipitationPage
import dev.pampa.fluidweather.feature.home.pages.PressurePage
import dev.pampa.fluidweather.feature.home.pages.SunPage

/**
 * Il foglio NERO del piano ([BlackSheet]): sale dal basso, prende tutta la pagina, si chiude
 * con la X o trascinando giu'. Ogni widget ci porta la sua pagina completa (fase 11b): il
 * contenuto vive in `pages/`, una pagina per file.
 */
@Composable
internal fun WidgetSheetHost(
  selected: HomeWidget?,
  state: HomeUiState,
  deps: HomeDependencies,
  onDismiss: () -> Unit,
) {
  if (selected == null) return
  BlackSheet(title = selected.title, onDismiss = onDismiss) {
    when (selected) {
      HomeWidget.NOWCAST -> NowcastPage(state)
      HomeWidget.HOURLY -> HourlyPage(state)
      HomeWidget.DAILY -> DailyPage(state)
      HomeWidget.PRECIPITATION -> PrecipitationPage(state)
      HomeWidget.PRESSURE -> PressurePage(state, deps)
      HomeWidget.AIR_QUALITY -> AirQualityPage(state)
      HomeWidget.SUN -> SunPage(state)
      HomeWidget.MOON -> MoonPage(state)
      HomeWidget.DETAILS -> DetailsPage(state)
    }
  }
}
