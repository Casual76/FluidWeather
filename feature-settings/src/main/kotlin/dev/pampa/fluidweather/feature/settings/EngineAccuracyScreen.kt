package dev.pampa.fluidweather.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.fluidweather.core.data.CalibrationStore
import dev.pampa.fluidweather.core.data.LearningRepository
import dev.pampa.fluidweather.core.data.LearningStore
import dev.pampa.fluidweather.core.data.PressureRepository
import dev.pampa.fluidweather.core.data.SamplingSettings
import dev.pampa.fluidweather.core.data.SamplingSettingsStore
import dev.pampa.fluidweather.core.model.NowcastReadiness
import dev.pampa.fluidweather.core.model.SamplingMode
import dev.pampa.fluidweather.core.sensor.CalibrationController
import dev.pampa.fluidweather.core.sensor.MaximaAlarm
import dev.pampa.fluidweather.core.sensor.SamplingScheduler
import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.pampa.fluidweather.strings.R
import dev.pampa.fluidweather.strings.messageRes
import androidx.compose.ui.res.stringResource
import dev.pampa.fluidweather.core.ui.rememberUnitFormatter
import dev.pampa.fluidweather.core.ui.stageText
import dev.pampa.fluidweather.core.weather.NowcastUseCase
import dev.pampa.fluidweather.strings.TimeFormats

/** Tutto quello che la categoria "Motore e accuratezza" tocca. */
class EngineAccuracyDependencies(
  val samplingSettings: SamplingSettingsStore,
  val scheduler: SamplingScheduler,
  val calibrationStore: CalibrationStore,
  val calibrationController: CalibrationController,
  val pressureRepository: PressureRepository,
  val nowcast: NowcastUseCase,
  val learningStore: LearningStore,
  val learningRepository: LearningRepository,
)

/**
 * Motore e accuratezza (fase 15): a che punto e' il barometro (la barra unica: raffica, poi
 * storia), la taratura con la sua provenienza e il tasto per rifarla, la modalita' di
 * campionamento con le sue conseguenze (allarmi esatti, esenzione batteria), il monitoraggio
 * continuo.
 */
@Composable
fun EngineAccuracyScreen(deps: EngineAccuracyDependencies, onBack: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val settings by deps.samplingSettings.settings.collectAsState(initial = SamplingSettings())
  val calibration by deps.calibrationStore.record.collectAsState(initial = null)
  val progress by deps.calibrationController.progress.collectAsState()
  val outcome by deps.calibrationController.lastOutcome.collectAsState()
  val pendingBurst by deps.calibrationStore.pendingBurst.collectAsState(initial = null)
  val units = rememberUnitFormatter()
  val historyHours by produceState(initialValue = 0.0) {
    val now = System.currentTimeMillis()
    value = withContext(Dispatchers.Default) {
      deps.nowcast.clean(now - 24 * 3_600_000L)?.historyHours ?: 0.0
    }
  }
  val readiness = NowcastReadiness.of(
    calibration = calibration,
    calibrationProgress = progress?.let { it.completedSeconds to it.totalSeconds },
    historyHours = historyHours,
    requiredHours = FeatureExtractor.MIN_HISTORY_HOURS,
  )

  FluidScreen(title = stringResource(R.string.engine_title), onBack = onBack) {
    item { FluidSectionHeader(title = stringResource(R.string.engine_ready_title)) }
    item {
      FluidListGroup {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
          Text(readiness.stageText(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
          Spacer(Modifier.height(8.dp))
          FluidProgressBar(progress = { readiness.overallFraction })
          Spacer(Modifier.height(6.dp))
          Text(
            if (readiness.ready) {
              stringResource(R.string.engine_ready_desc)
            } else {
              stringResource(R.string.engine_not_ready_desc, FeatureExtractor.MIN_HISTORY_HOURS.toInt())
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    item { FluidSectionHeader(title = stringResource(R.string.engine_calibration_title)) }
    item {
      FluidListGroup {
        val record = calibration
        if (record == null) {
          FluidListRow(
            title = stringResource(R.string.engine_bias_missing),
            subtitle = stringResource(R.string.engine_bias_missing_desc),
          )
        } else {
          FluidListRow(
            title = stringResource(R.string.engine_bias),
            subtitle = stringResource(R.string.engine_bias_desc, (record.confidence * 100).toInt()),
            meta = units.pressureDelta(record.biasHpa, 2),
          )
          FluidListDivider()
          FluidListRow(
            title = stringResource(R.string.engine_bias_how),
            subtitle = stringResource(R.string.engine_bias_how_desc, units.pressure(record.localMslHpa, 1), units.pressure(record.referenceMslHpa, 1), record.sampleCount) + (record.altitudeMeters?.let { stringResource(R.string.engine_altitude_suffix, fmt0(it)) } ?: stringResource(R.string.engine_altitude_unknown)),
            meta = fmtDayTime(record.calibratedAtMillis),
          )
          FluidListDivider()
          // Da quanti tratti fermi indipendenti viene il numero, e quanto sono d'accordo fra loro.
          // E' la parte che rende la taratura discutibile: tre tratti a tre quote che dicono lo
          // stesso bias sono una verifica, dieci minuti in salotto sono una misura sola.
          FluidListRow(
            title = stringResource(R.string.engine_calibration_title),
            subtitle = if (record.segmentCount <= 1) {
              stringResource(R.string.engine_calibration_one_segment)
            } else {
              stringResource(
                R.string.engine_calibration_segments,
                record.segmentCount,
                units.pressureDelta(record.spreadHpa, 2),
              )
            },
          )
        }
        FluidListDivider()
        val running = progress
        if (running == null) {
          FluidListRow(
            title = if (record == null) stringResource(R.string.engine_calibrate) else stringResource(R.string.engine_recalibrate),
            subtitle = outcome?.messageRes()?.let { stringResource(it) } ?: stringResource(R.string.engine_calibrate_desc),
            badge = {
              FluidButton(text = stringResource(R.string.common_start), style = FluidButtonStyle.Tinted, onClick = { deps.calibrationController.start() })
            },
          )
          // La raffica c'e' gia' e le e' mancato solo il riferimento: chiedere altri dieci minuti
          // sarebbe assurdo, e la frase dell'esito lo promette da sempre.
          if (pendingBurst != null) {
            FluidListDivider()
            FluidListRow(
              title = stringResource(R.string.engine_retry_estimate),
              subtitle = stringResource(R.string.engine_retry_estimate_desc),
              badge = {
                FluidButton(
                  text = stringResource(R.string.common_start),
                  style = FluidButtonStyle.Plain,
                  onClick = { scope.launch { deps.calibrationController.retryPendingEstimate() } },
                )
              },
            )
          }
        } else if (running.waitingForStillness) {
          FluidListRow(
            title = stringResource(R.string.engine_calibrating),
            subtitle = stringResource(
              R.string.engine_calibration_waiting,
              running.completedSeconds / 60,
              running.totalSeconds / 60,
            ),
            badge = {
              FluidButton(text = stringResource(R.string.common_cancel), style = FluidButtonStyle.Plain, onClick = { deps.calibrationController.cancel() })
            },
          )
        } else {
          FluidListRow(
            title = stringResource(R.string.engine_calibrating),
            subtitle = stringResource(R.string.engine_calibration_progress, running.completedSeconds / 60, String.format(Locale.ROOT, "%02d", running.completedSeconds % 60), running.totalSeconds / 60),
            badge = {
              FluidButton(text = stringResource(R.string.common_cancel), style = FluidButtonStyle.Plain, onClick = { deps.calibrationController.cancel() })
            },
          )
        }
      }
    }

    item { FluidSectionHeader(title = stringResource(R.string.learning_title)) }
    item {
      val platt by deps.learningStore.platt.collectAsState(initial = emptyMap())
      val outcomes by produceState(initialValue = -1) { value = runCatching { deps.learningRepository.outcomeCount() }.getOrDefault(0) }
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.learning_verifications),
          subtitle = stringResource(R.string.learning_verifications_desc),
          meta = if (outcomes < 0) "…" else outcomes.toString(),
        )
        listOf("0-1h", "1-3h", "3-6h").forEach { window ->
          FluidListDivider()
          val record = platt[window]
          FluidListRow(
            title = stringResource(R.string.learning_recalibration, window),
            subtitle = if (record == null) {
              stringResource(R.string.learning_not_yet)
            } else {
              stringResource(R.string.learning_platt, fmt2(record.a), fmt2(record.b), record.samples)
            },
            meta = record?.let { fmtDayTime(it.fittedAtMillis) } ?: "—",
          )
        }
      }
    }

    item { FluidSectionHeader(title = stringResource(R.string.engine_sampling)) }
    item {
      FluidListGroup {
        SamplingMode.entries.forEachIndexed { index, mode ->
          if (index > 0) FluidListDivider()
          FluidListRow(
            title = mode.label(),
            subtitle = mode.description(),
            badge = if (settings.mode == mode) {
              { Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.common_selected), tint = MaterialTheme.colorScheme.primary) }
            } else {
              null
            },
            onClick = {
              scope.launch {
                deps.samplingSettings.setMode(mode)
                deps.scheduler.apply(mode)
              }
            },
          )
        }
      }
    }

    if (settings.mode == SamplingMode.MASSIMA) {
      item {
        FluidListGroup {
          if (!MaximaAlarm.canSchedule(context)) {
            FluidListRow(
              title = stringResource(R.string.engine_exact_alarms),
              subtitle = stringResource(R.string.engine_exact_alarms_desc),
              onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                  context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                }
              },
            )
            FluidListDivider()
          }
          val powerManager = context.getSystemService(PowerManager::class.java)
          if (powerManager?.isIgnoringBatteryOptimizations(context.packageName) == false) {
            FluidListRow(
              title = stringResource(R.string.engine_battery),
              subtitle = stringResource(R.string.engine_battery_desc),
              onClick = {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
              },
            )
          } else {
            FluidListRow(title = stringResource(R.string.engine_max_active), subtitle = stringResource(R.string.engine_max_active_desc))
          }
        }
      }
    }

    item { FluidSectionHeader(title = stringResource(R.string.engine_while_open)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.engine_continuous),
          subtitle = stringResource(R.string.engine_continuous_desc),
          badge = {
            FluidSwitch(
              checked = settings.continuousWhileOpen,
              onCheckedChange = { enabled -> scope.launch { deps.samplingSettings.setContinuousWhileOpen(enabled) } },
            )
          },
        )
      }
    }
  }
}

private fun fmt2(value: Double) = String.format(Locale.ROOT, "%.2f", value)

private fun fmt0(value: Double) = String.format(Locale.getDefault(), "%.0f", value)

private fun fmtDayTime(millis: Long): String = TimeFormats.dayTime(millis)
