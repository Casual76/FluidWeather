package dev.pampa.fluidweather.feature.home

import androidx.compose.runtime.Composable
import dev.pampa.fluidweather.core.ui.BlackSheet
import dev.pampa.fluidweather.core.ui.BlackSheetLazy
import dev.pampa.fluidweather.core.ui.BlackSheetNote
import dev.pampa.fluidweather.core.ui.HomeWidget
import dev.pampa.fluidweather.feature.home.pages.AirQualityPage
import dev.pampa.fluidweather.feature.home.pages.DailyPage
import dev.pampa.fluidweather.feature.home.pages.DetailsPage
import dev.pampa.fluidweather.feature.home.pages.hourlyPage
import dev.pampa.fluidweather.feature.home.pages.rememberHourlyModel
import dev.pampa.fluidweather.feature.home.pages.MoonPage
import dev.pampa.fluidweather.feature.home.pages.NowcastPage
import dev.pampa.fluidweather.feature.home.pages.PrecipitationPage
import dev.pampa.fluidweather.feature.home.pages.PressurePage
import dev.pampa.fluidweather.feature.home.pages.SunPage
import androidx.compose.ui.res.stringResource
import dev.pampa.fluidweather.strings.R

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
  // L'orario e' l'unica pagina lunga (fino a 246 ore): va nel foglio pigro, che compone e
  // posiziona solo le righe che si vedono. Le altre nove sono corte e restano dov'erano.
  if (selected == HomeWidget.HOURLY) {
    val model = rememberHourlyModel(state)
    BlackSheetLazy(title = stringResource(selected.titleRes), onDismiss = onDismiss) {
      hourlyPage(model)
    }
    return
  }
  BlackSheet(title = stringResource(selected.titleRes), onDismiss = onDismiss) {
    when (selected) {
      HomeWidget.NOWCAST -> if (state.barometerApplies) {
        NowcastPage(state)
      } else {
        BlackSheetNote(stringResource(R.string.tile_barometer_here_only))
      }
      // Gestita sopra, nel foglio pigro: qui non ci arriva mai.
      HomeWidget.HOURLY -> Unit
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
