package dev.pampa.fluidweather.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.foundation.EngineBuild
import dev.antigravity.fluidengine.foundation.EngineCompatibility
import dev.antigravity.fluidengine.foundation.EngineConfig
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.fluidLicensesSection
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.fluidweather.core.weather.ProviderRegistry
import dev.pampa.fluidweather.core.weather.RainViewerClient
import dev.pampa.fluidweather.strings.R

/** Tutto quello che la categoria "Informazioni" tocca. */
class AboutDependencies(
  val appVersion: String,
  val repositoryUrl: String,
  val onReviewOnboarding: () -> Unit,
  val updates: UpdateDependencies,
)

/**
 * Informazioni e aggiornamento (fasi 15 e 18): la versione dell'app e dell'engine, il canale e
 * l'aggiornamento in-app dal manifest del Pampa Store, l'avviso remoto se ce n'e' uno, le fonti
 * dei dati con la loro attribuzione (e' parte del contratto, non una nota a pie' di pagina), le
 * licenze dell'engine, la presentazione da rivedere.
 */
@Composable
fun AboutScreen(deps: AboutDependencies, onBack: () -> Unit) {
  val context = LocalContext.current
  val remote by deps.updates.remoteConfig.config.collectAsState(initial = EngineConfig.Fallback)

  FluidScreen(title = stringResource(R.string.about_title), onBack = onBack) {
    item { FluidSectionHeader(title = "FluidWeather") }
    item {
      FluidListGroup {
        FluidListRow(title = stringResource(R.string.about_version), subtitle = "dev.pampa.fluidweather", meta = deps.appVersion)
        FluidListDivider()
        FluidListRow(title = "Fluid Engine", subtitle = stringResource(R.string.about_engine_desc), meta = EngineBuild.VERSION)
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.about_replay_onboarding),
          subtitle = stringResource(R.string.about_replay_desc),
          onClick = deps.onReviewOnboarding,
        )
      }
    }

    item { FluidSectionHeader(title = stringResource(R.string.about_updates_title)) }
    item {
      FluidListGroup {
        AppUpdateRows(deps.updates)
        val notice = remote.notice
        val compatibility = remote.compatibility()
        if (notice != null || compatibility != EngineCompatibility.OK) {
          FluidListDivider()
          FluidListRow(
            title = stringResource(R.string.about_notice),
            subtitle = notice ?: stringResource(R.string.gate_update_required_text),
          )
        }
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.about_source),
          subtitle = deps.repositoryUrl.removePrefix("https://"),
          onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deps.repositoryUrl))) }
          },
        )
      }
    }

    item { FluidSectionHeader(title = stringResource(R.string.about_sources)) }
    item {
      FluidListGroup {
        ProviderRegistry.all.forEachIndexed { index, descriptor ->
          if (index > 0) FluidListDivider()
          FluidListRow(title = descriptor.label, subtitle = descriptor.attribution)
        }
        FluidListDivider()
        FluidListRow(title = "RainViewer", subtitle = stringResource(R.string.about_rainviewer, RainViewerClient.ATTRIBUTION))
        FluidListDivider()
        FluidListRow(title = "Google Maps", subtitle = stringResource(R.string.about_maps))
        FluidListDivider()
        FluidListRow(title = "Meteoalarm / NWS", subtitle = stringResource(R.string.about_alerts))
        FluidListDivider()
        FluidListRow(title = stringResource(R.string.about_openmeteo_extra), subtitle = stringResource(R.string.about_openmeteo_extra_desc))
        FluidListDivider()
        FluidListRow(title = stringResource(R.string.your_barometer), subtitle = stringResource(R.string.about_barometer_desc))
      }
    }

    fluidLicensesSection()
  }
}
