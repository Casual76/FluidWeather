package dev.pampa.fluidweather.feature.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.fluidweather.core.data.PressureRepository
import dev.pampa.fluidweather.core.data.SamplingSettings
import dev.pampa.fluidweather.core.data.SamplingSettingsStore
import dev.pampa.fluidweather.core.model.ManualBurst
import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.core.model.SampleSource
import dev.pampa.fluidweather.core.model.SamplingMode
import dev.pampa.fluidweather.core.sensor.ActivityRecognizer
import dev.pampa.fluidweather.core.sensor.Barometer
import dev.pampa.fluidweather.core.sensor.LocationProvider
import dev.pampa.fluidweather.core.sensor.ManualBurstController
import dev.pampa.fluidweather.core.sensor.MaximaAlarm
import dev.pampa.fluidweather.core.sensor.SamplingScheduler
import dev.pampa.fluidweather.core.weather.FusionCoordinator
import dev.pampa.fluidweather.core.weather.WeatherRound
import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import dev.pampa.fluidweather.nowcast.cleaning.CleaningResult
import dev.pampa.fluidweather.nowcast.cleaning.RejectionReason
import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.nowcast.tide.TideSource
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.nowcast.verdict.NowcastModel
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import androidx.compose.runtime.produceState
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.pampa.fluidweather.strings.R
import androidx.compose.ui.res.stringResource
import dev.pampa.fluidweather.strings.TimeFormats
import dev.pampa.fluidweather.strings.featureLabelRes
import dev.pampa.fluidweather.strings.labelRes
import dev.pampa.fluidweather.strings.shortLabelRes

/** Tutto quello che la diagnostica tocca; lo costruisce :app dal suo grafo. */
class DiagnosticsDependencies(
  val barometer: Barometer,
  val settingsStore: SamplingSettingsStore,
  val repository: PressureRepository,
  val burstController: ManualBurstController,
  val scheduler: SamplingScheduler,
  val activityRecognizer: ActivityRecognizer,
  val cleaningPipeline: CleaningPipeline,
  val fusionCoordinator: FusionCoordinator,
  val locationProvider: LocationProvider,
)

/**
 * La verifica di campo della fase 1: il segnale grezzo cosi' com'e', la modalita' di
 * campionamento, la raffica manuale. Crescera' con la pipeline (segnale pulito, stadi, bias);
 * per ora deve dimostrare una cosa sola: il telefono registra pressione e la si vede.
 */
@Composable
fun DiagnosticsScreen(deps: DiagnosticsDependencies, onBack: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  val settings by deps.settingsStore.settings.collectAsState(initial = SamplingSettings())
  val samples by remember { deps.repository.latest(20) }.collectAsState(initial = emptyList())
  val sampleCount by remember { deps.repository.count() }.collectAsState(initial = 0L)
  val live by remember { deps.barometer.readings() }.collectAsState(initial = null)
  val burst by deps.burstController.progress.collectAsState()

  // Il segnale pulito si ricalcola quando l'archivio cresce: 12 ore di storia negli stadi 1-2.
  val cleaning by produceState<CleaningResult?>(initialValue = null, sampleCount) {
    value = withContext(Dispatchers.Default) {
      deps.cleaningPipeline.process(
        deps.repository.samplesSince(System.currentTimeMillis() - 24 * 60 * 60_000L),
      )
    }
  }

  // I permessi non hanno un flow: il contatore forza la rivalutazione dopo ogni risposta.
  var permissionEpoch by remember { mutableIntStateOf(0) }
  val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions(),
  ) {
    permissionEpoch++
    deps.activityRecognizer.start()
  }
  val missingPermissions = remember(permissionEpoch) {
    buildList {
      if (!deps.activityRecognizer.hasPermission()) add(Manifest.permission.ACTIVITY_RECOGNITION)
      if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
      ) {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
      ) {
        add(Manifest.permission.POST_NOTIFICATIONS)
      }
    }
  }

  FluidScreen(title = stringResource(R.string.diag_title), onBack = onBack) {
    item { FluidSectionHeader(title = stringResource(R.string.diag_sensor)) }
    item {
      FluidListGroup {
        if (deps.barometer.isAvailable) {
          FluidListRow(
            title = stringResource(R.string.diag_raw_pressure),
            subtitle = deps.barometer.sensorName ?: stringResource(R.string.common_barometer),
            meta = live?.let { String.format(Locale.getDefault(), "%.2f hPa", it.pressureHpa) } ?: "—",
          )
        } else {
          FluidListRow(
            title = stringResource(R.string.diag_no_barometer),
            subtitle = stringResource(R.string.diag_no_barometer_desc),
          )
        }
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.diag_samples),
          subtitle = stringResource(R.string.diag_samples_desc),
          meta = sampleCount.toString(),
        )
      }
    }

    item { FluidSectionHeader(title = stringResource(R.string.diag_verdict_title)) }
    item {
      FluidListGroup {
        val verdict: NowcastVerdict? = cleaning?.let { result ->
          FeatureExtractor.extract(result, context = null, normalHpa = null, nowMillis = System.currentTimeMillis())
            ?.let { NowcastModel.trained().verdict(it) }
        }
        if (verdict == null) {
          FluidListRow(
            title = stringResource(R.string.diag_waiting_history),
            subtitle = stringResource(R.string.diag_waiting_history_desc),
          )
        } else {
          FluidListRow(
            title = stringResource(R.string.pressure_level),
            subtitle = when (verdict.level) {
              AlertLevel.QUIETE -> stringResource(R.string.diag_level_quiet)
              AlertLevel.SORVEGLIANZA -> stringResource(R.string.diag_level_watch)
              AlertLevel.ALLERTA -> stringResource(R.string.diag_level_alert)
            },
            meta = stringResource(verdict.level.labelRes()).lowercase(),
          )
          verdict.windows.forEach { window ->
            FluidListDivider()
            FluidListRow(
              title = stringResource(R.string.diag_rain_window, window.window),
              subtitle = window.topFactors.map { factor ->
                "${stringResource(featureLabelRes(factor.name))} ${if (factor.contribution > 0) "+" else "−"}"
              }.joinToString(" · ").ifEmpty { stringResource(R.string.diag_no_factors) },
              meta = String.format(
                Locale.getDefault(),
                "%.0f%% (%.0f-%.0f)",
                window.probability * 100,
                window.probabilityLow * 100,
                window.probabilityHigh * 100,
              ),
            )
          }
        }
      }
    }

    item { FluidSectionHeader(title = stringResource(R.string.diag_clean_title)) }
    item {
      FluidListGroup {
        val latest = cleaning?.latest
        if (latest == null) {
          FluidListRow(
            title = stringResource(R.string.diag_waiting_data),
            subtitle = stringResource(R.string.diag_waiting_data_desc),
          )
        } else {
          FluidListRow(
            title = stringResource(R.string.diag_level_msl),
            subtitle = stringResource(R.string.diag_level_msl_desc),
            meta = String.format(
              Locale.getDefault(),
              "%.2f ± %.2f hPa",
              latest.levelHpa,
              latest.levelSigmaHpa,
            ),
          )
          FluidListDivider()
          FluidListRow(
            title = stringResource(R.string.pressure_trend),
            subtitle = stringResource(R.string.diag_trend_desc),
            meta = String.format(
              Locale.getDefault(),
              "%+.2f ± %.2f hPa/h",
              latest.trendHpaPerHour,
              latest.trendSigmaHpaPerHour,
            ),
          )
        }
        val result = cleaning
        if (result != null) {
          FluidListDivider()
          FluidListRow(
            title = stringResource(R.string.pressure_tide),
            subtitle = when (result.tide.source) {
              TideSource.NONE -> stringResource(R.string.diag_tide_none)
              TideSource.CLIMATOLOGICAL -> stringResource(
                R.string.diag_tide_prior,
                String.format(Locale.getDefault(), "%.2f", result.tide.s1AmplitudeHpa),
                String.format(Locale.getDefault(), "%.2f", result.tide.s2AmplitudeHpa),
              )
              TideSource.FITTED -> stringResource(
                R.string.diag_tide_fitted,
                String.format(Locale.getDefault(), "%.2f", result.tide.s1AmplitudeHpa),
                String.format(Locale.getDefault(), "%.2f", result.tide.s2AmplitudeHpa),
              )
            },
            meta = if (result.tide.source == TideSource.NONE) {
              "—"
            } else {
              stringResource(R.string.diag_tide_now, String.format(Locale.getDefault(), "%+.2f", result.tide.tideAtLatestHpa))
            },
          )
          val counts = result.rejectionCounts()
          FluidListDivider()
          FluidListRow(
            title = stringResource(R.string.diag_rejections),
            subtitle = if (counts.isEmpty()) {
              stringResource(R.string.diag_no_rejections)
            } else {
              counts.entries.map { (reason, count) -> "${stringResource(reason.shortLabelRes())}: $count" }.joinToString(" · ")
            },
            meta = stringResource(R.string.diag_kept, result.cleaned.size),
          )
        }
      }
    }

    if (missingPermissions.isNotEmpty()) {
      item {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.diag_grant_permissions),
            subtitle = stringResource(R.string.diag_grant_permissions_desc),
            onClick = { permissionLauncher.launch(missingPermissions.toTypedArray()) },
          )
        }
      }
    }

    item { FluidSectionHeader(title = stringResource(R.string.diag_tools)) }
    item {
      FluidListGroup {
        val burstProgress = burst
        if (burstProgress == null) {
          FluidListRow(
            title = stringResource(R.string.diag_manual_burst),
            subtitle = stringResource(R.string.diag_manual_burst_desc, ManualBurst.DURATION_SECONDS / 60, ManualBurst.HZ),
            badge = {
              FluidButton(
                text = stringResource(R.string.common_start),
                style = FluidButtonStyle.Tinted,
                onClick = { deps.burstController.start() },
              )
            },
          )
        } else {
          FluidListRow(
            title = stringResource(R.string.diag_burst_running),
            subtitle = stringResource(R.string.diag_burst_progress, burstProgress.completedSeconds, burstProgress.totalSeconds),
            badge = {
              FluidButton(
                text = stringResource(R.string.common_cancel),
                style = FluidButtonStyle.Plain,
                onClick = { deps.burstController.cancel() },
              )
            },
          )
        }
      }
    }

    item { FluidSectionHeader(title = stringResource(R.string.diag_providers_title)) }
    item {
      var fetching by remember { androidx.compose.runtime.mutableStateOf(false) }
      var round by remember { androidx.compose.runtime.mutableStateOf<WeatherRound?>(null) }
      var roundFailed by remember { androidx.compose.runtime.mutableStateOf(false) }
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.diag_query),
          subtitle = stringResource(R.string.diag_query_desc),
          badge = {
            FluidButton(
              text = if (fetching) "..." else stringResource(R.string.common_go),
              style = FluidButtonStyle.Tinted,
              enabled = !fetching,
              onClick = {
                fetching = true
                roundFailed = false
                scope.launch {
                  try {
                    val here = deps.locationProvider.snapshot()
                    if (here == null) {
                      roundFailed = true
                      round = null
                    } else {
                      round = deps.fusionCoordinator.refresh(here.latitude, here.longitude)
                    }
                  } finally {
                    fetching = false
                  }
                }
              },
            )
          },
        )
        if (roundFailed) {
          FluidListDivider()
          FluidListRow(
            title = stringResource(R.string.diag_no_position),
            subtitle = stringResource(R.string.diag_no_position_desc),
          )
        }
        val result = round
        if (result != null) {
          result.fetches.forEach { fetch ->
            FluidListDivider()
            val weight = result.fused.providerWeights[fetch.descriptor.id]
            FluidListRow(
              title = fetch.descriptor.label,
              subtitle = stringResource(fetch.descriptor.whyRes),
              meta = when {
                fetch.bundle != null && weight != null ->
                  stringResource(R.string.diag_hours_weight, fetch.bundle!!.hourly.size, (weight * 100).toInt())
                fetch.bundle != null -> stringResource(R.string.common_hours_count, fetch.bundle!!.hourly.size)
                else -> fetch.error ?: stringResource(R.string.common_error_short)
              },
            )
          }
          FluidListDivider()
          FluidListRow(
            title = stringResource(R.string.diag_fused),
            subtitle = stringResource(R.string.diag_fused_desc),
            meta = stringResource(R.string.common_hours_count, result.fused.hours.size),
          )
        }
      }
    }

    if (samples.isNotEmpty()) {
      item { FluidSectionHeader(title = stringResource(R.string.diag_last_readings)) }
      item {
        FluidListGroup {
          samples.forEachIndexed { index, sample ->
            if (index > 0) FluidListDivider()
            SampleRow(sample)
          }
        }
      }
    }
  }
}

@Composable
private fun SampleRow(sample: PressureSample) {
  FluidListRow(
    title = String.format(Locale.getDefault(), "%.2f hPa", sample.pressureHpa),
    subtitle = buildString {
      append(stringResource(sample.source.labelRes()))
      sample.altitudeMeters?.let { append(stringResource(R.string.diag_meters_suffix, it.toInt())) }
      if (sample.activity != dev.pampa.fluidweather.core.model.ActivityKind.UNKNOWN) {
        append(" · ${sample.activity.name.lowercase()}")
      }
    },
    meta = TimeFormats.timeWithSeconds(sample.timestampMillis),
  )
}
