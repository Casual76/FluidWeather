package dev.pampa.fluidweather.feature.settings

import androidx.compose.runtime.Composable
import dev.antigravity.fluidengine.foundation.EngineBuild
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.fluidLicensesSection
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.fluidweather.core.weather.ProviderRegistry
import dev.pampa.fluidweather.core.weather.RainViewerClient

/** Tutto quello che la categoria "Informazioni" tocca. */
class AboutDependencies(
  val appVersion: String,
  val onReviewOnboarding: () -> Unit,
)

/**
 * Informazioni e aggiornamento (fase 15): la versione dell'app e dell'engine, le fonti dei
 * dati con la loro attribuzione (e' parte del contratto, non una nota a pie' di pagina), le
 * licenze dell'engine, la presentazione da rivedere. L'aggiornamento in-app e' della fase 18.
 */
@Composable
fun AboutScreen(deps: AboutDependencies, onBack: () -> Unit) {
  FluidScreen(title = "Informazioni", onBack = onBack) {
    item { FluidSectionHeader(title = "FluidWeather") }
    item {
      FluidListGroup {
        FluidListRow(title = "Versione", subtitle = "dev.pampa.fluidweather", meta = deps.appVersion)
        FluidListDivider()
        FluidListRow(title = "Fluid Engine", subtitle = "Le fondamenta condivise delle app Pampa", meta = EngineBuild.VERSION)
        FluidListDivider()
        FluidListRow(
          title = "Aggiornamento in-app",
          subtitle = "Canali stable e beta via Pampa Store: arriva con la fase 18 (rilascio).",
        )
        FluidListDivider()
        FluidListRow(
          title = "Rivedi la presentazione",
          subtitle = "Le pagine del primo avvio, permessi e scelte comprese",
          onClick = deps.onReviewOnboarding,
        )
      }
    }

    item { FluidSectionHeader(title = "Fonti dei dati") }
    item {
      FluidListGroup {
        ProviderRegistry.all.forEachIndexed { index, descriptor ->
          if (index > 0) FluidListDivider()
          FluidListRow(title = descriptor.label, subtitle = descriptor.attribution)
        }
        FluidListDivider()
        FluidListRow(title = "RainViewer", subtitle = "${RainViewerClient.ATTRIBUTION} · radar composito, uso non commerciale")
        FluidListDivider()
        FluidListRow(title = "Google Maps", subtitle = "Base cartografica del radar, Google Maps Platform")
        FluidListDivider()
        FluidListRow(title = "Meteoalarm / NWS", subtitle = "Allerte ufficiali dei servizi meteorologici nazionali, riportate senza reinterpretazione")
        FluidListDivider()
        FluidListRow(title = "Open-Meteo Air Quality e Geocoding", subtitle = "Qualita' dell'aria (CAMS) e ricerca delle localita', CC BY 4.0")
        FluidListDivider()
        FluidListRow(title = "Il tuo barometro", subtitle = "Il sensore del telefono, con le formule di Mass & Madaus, Meeus e Montenbruck")
      }
    }

    fluidLicensesSection()
  }
}
