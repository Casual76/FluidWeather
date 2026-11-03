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
import dev.antigravity.fluidengine.ui.fluid.FluidEngineLicenses
import dev.antigravity.fluidengine.ui.fluid.FluidLicense
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.fluidLicensesSection
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.fluidweather.core.ui.LocalTutorialController
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
 * Le opere di terze parti che l'APK porta oltre a quelle dell'engine: la mappa del radar. Il testo
 * delle licenze e' quello che chiedono i loro autori (BSD-2-Clause: l'avviso del copyright resta
 * visibile a chi riceve il programma); i dati della mappa hanno le loro attribuzioni nella riga
 * "OpenFreeMap" delle fonti.
 */
private val MapLicenses = listOf(
  FluidLicense(
    name = "MapLibre Native Android",
    author = "MapLibre contributors",
    licence = "BSD-2-Clause",
    url = "https://github.com/maplibre/maplibre-native",
    role = "The radar base map.",
  ),
  FluidLicense(
    name = "MapLibre Android Gestures",
    author = "MapLibre contributors",
    licence = "BSD-2-Clause",
    url = "https://github.com/maplibre/maplibre-gestures-android",
    role = "Pan, zoom and rotate on the radar map.",
  ),
  FluidLicense(
    name = "OkHttp, Okio",
    author = "Square, Inc.",
    licence = "Apache License 2.0",
    url = "https://square.github.io/okhttp/",
    role = "Network traffic of the map.",
  ),
  FluidLicense(
    name = "Gson",
    author = "Google Inc.",
    licence = "Apache License 2.0",
    url = "https://github.com/google/gson",
    role = "Reads the map style and the GeoJSON of the map.",
  ),
  FluidLicense(
    name = "geojson, turf",
    author = "MapLibre contributors",
    licence = "Apache License 2.0",
    url = "https://github.com/maplibre/maplibre-java",
    role = "Geometry of the map.",
  ),
  FluidLicense(
    name = "OpenFreeMap",
    author = "Zsolt Ero",
    licence = "MIT",
    url = "https://openfreemap.org",
    role = "The free map tiles (data © OpenMapTiles, © OpenStreetMap contributors, ODbL).",
  ),
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
  // La pagina dei crediti nella lingua dell'app: i default dell'engine sono italiani.
  val licensesTitle = stringResource(R.string.licenses_title)
  val licensesFootnote = stringResource(R.string.licenses_footnote, EngineBuild.VERSION)

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
        val tutorials = LocalTutorialController.current
        if (tutorials != null) {
          FluidListDivider()
          FluidListRow(
            title = stringResource(R.string.about_replay_tutorials),
            subtitle = stringResource(R.string.about_replay_tutorials_desc),
            // Rifare la presentazione non li riporta: sono due cose diverse, e chi rivede
            // l'onboarding di solito non vuole rivedersi spiegare anche i gesti.
            onClick = { tutorials.replayAll() },
          )
        }
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
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.about_license),
          subtitle = stringResource(R.string.about_license_desc),
          meta = "GPL-3.0",
          onClick = {
            runCatching {
              context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deps.repositoryUrl.trimEnd('/') + "/blob/main/LICENSE")))
            }
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
        FluidListRow(title = "OpenFreeMap · MapLibre", subtitle = stringResource(R.string.about_maps))
        FluidListDivider()
        FluidListRow(title = stringResource(R.string.about_moon), subtitle = stringResource(R.string.about_moon_desc))
        FluidListDivider()
        FluidListRow(title = "Meteoalarm / NWS", subtitle = stringResource(R.string.about_alerts))
        FluidListDivider()
        FluidListRow(title = stringResource(R.string.about_openmeteo_extra), subtitle = stringResource(R.string.about_openmeteo_extra_desc))
        FluidListDivider()
        FluidListRow(title = stringResource(R.string.your_barometer), subtitle = stringResource(R.string.about_barometer_desc))
      }
    }

    fluidLicensesSection(
      title = licensesTitle,
      footnote = licensesFootnote,
      licences = FluidEngineLicenses + MapLicenses,
    )
  }
}
