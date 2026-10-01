package dev.pampa.fluidweather.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
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
import dev.pampa.fluidweather.core.model.BarometerReadiness
import dev.pampa.fluidweather.core.model.NowcastReadiness
import dev.pampa.fluidweather.core.model.PlattMapRecord
import dev.pampa.fluidweather.core.model.PlattMaps
import dev.pampa.fluidweather.core.model.ReadinessStage
import dev.pampa.fluidweather.core.model.SamplingMode
import dev.pampa.fluidweather.core.sensor.CalibrationController
import dev.pampa.fluidweather.core.sensor.MaximaAlarm
import dev.pampa.fluidweather.core.sensor.SamplingScheduler
import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.nowcast.learning.PlattRefitPolicy
import dev.pampa.fluidweather.nowcast.learning.PlattStatus
import dev.pampa.fluidweather.nowcast.learning.PlattVariant
import dev.pampa.fluidweather.nowcast.verdict.ModelVersions
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.pampa.fluidweather.strings.R
import dev.pampa.fluidweather.strings.labelRes
import dev.pampa.fluidweather.strings.messageRes
import dev.pampa.fluidweather.strings.plattStatusLabelRes
import androidx.compose.ui.res.stringResource
import dev.pampa.fluidweather.core.ui.rememberUnitFormatter
import dev.pampa.fluidweather.core.ui.stageText
import dev.pampa.fluidweather.core.ui.BackgroundAccessIntents
import dev.pampa.fluidweather.core.ui.rememberBackgroundAccess
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
  /** Il livello di contesto dell'ultimo giro del telefono, per la riga "contesto adesso". */
  val currentTier: suspend () -> ContextTier? = { null },
  /** Senza barometro non c'e' niente da tarare ne' da campionare: la schermata lo dice e basta. */
  val barometerAvailable: Boolean = true,
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
  // La storia e il campionamento fermo si leggono dall'archivio una volta; la taratura, che puo'
  // girare mentre si guarda, resta viva sopra.
  val base by produceState<BarometerReadiness?>(initialValue = null) {
    value = withContext(Dispatchers.Default) {
      runCatching { deps.nowcast.readiness(System.currentTimeMillis(), calibrationProgress = null) }.getOrNull()
    }
  }
  val readiness = NowcastReadiness.of(
    calibration = calibration,
    calibrationProgress = progress?.let { it.completedSeconds to it.totalSeconds },
    historyHours = base?.historyHours ?: 0.0,
    requiredHours = FeatureExtractor.MIN_HISTORY_HOURS,
    sensorAvailable = deps.barometerAvailable,
    samplingBlocked = base?.samplingBlocked == true,
  )
  val access = rememberBackgroundAccess()

  FluidScreen(title = stringResource(R.string.engine_title), onBack = onBack) {
    item { FluidSectionHeader(title = stringResource(R.string.engine_ready_title)) }
    item {
      FluidListGroup {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
          Text(readiness.stageText(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
          if (deps.barometerAvailable) {
            Spacer(Modifier.height(8.dp))
            FluidProgressBar(progress = { readiness.overallFraction })
          }
          Spacer(Modifier.height(6.dp))
          Text(
            when (readiness.stage) {
              ReadinessStage.READY -> stringResource(R.string.engine_ready_desc)
              ReadinessStage.NO_SENSOR -> stringResource(R.string.no_sensor_desc)
              ReadinessStage.BLOCKED -> stringResource(R.string.bg_access_desc)
              else -> stringResource(R.string.engine_not_ready_desc, FeatureExtractor.MIN_HISTORY_HOURS.toInt())
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    // Senza sensore quello che segue (taratura, apprendimento, campionamento) non ha oggetto.
    if (!deps.barometerAvailable) return@FluidScreen

    // Il campionamento vive in background, e sui telefoni che mettono le app a dormire muore li'.
    // Prima l'esenzione si offriva solo in MASSIMA: i due Samsung dei genitori, in BILANCIATA,
    // non l'avevano mai vista (2026-09-26). Ora si offre sempre, finche' non e' fatta.
    if (access.fixable || readiness.stage == ReadinessStage.BLOCKED) {
      item { FluidSectionHeader(title = stringResource(R.string.bg_access_title)) }
      item {
        FluidListGroup {
          if (access.unrestrictedBattery) {
            FluidListRow(title = stringResource(R.string.bg_access_battery_ok), subtitle = stringResource(R.string.bg_access_battery_ok_desc))
          } else {
            FluidListRow(
              title = stringResource(R.string.bg_access_battery),
              subtitle = stringResource(R.string.bg_access_battery_desc),
              onClick = { BackgroundAccessIntents.requestUnrestrictedBattery(context) },
            )
          }
          if (access.backgroundRestricted) {
            FluidListDivider()
            FluidListRow(
              title = stringResource(R.string.bg_access_restricted),
              subtitle = stringResource(R.string.bg_access_restricted_desc),
              onClick = { BackgroundAccessIntents.openAppDetails(context) },
            )
          }
          if (access.samsung) {
            FluidListDivider()
            FluidListRow(
              title = stringResource(R.string.bg_access_samsung),
              subtitle = stringResource(R.string.bg_access_samsung_desc),
              onClick = { BackgroundAccessIntents.openSamsungSleepingApps(context) },
            )
          }
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
      val maps by deps.learningStore.maps.collectAsState(initial = PlattMaps.EMPTY)
      val outcomes by produceState(initialValue = -1) { value = runCatching { deps.learningRepository.outcomeCount() }.getOrDefault(0) }
      // Il livello del posto del telefono dall'ultima istantanea, non da un fix nuovo: per questo
      // l'etichetta dice "ultimo giro".
      val tier by produceState<ContextTier?>(initialValue = null) {
        value = withContext(Dispatchers.Default) { runCatching { deps.currentTier() }.getOrNull() }
      }
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.learning_model_version),
          subtitle = "${ModelVersions.TAG} · ${PlattRefitPolicy.RULES_VERSION}",
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.learning_tier_now),
          subtitle = tier?.let { stringResource(it.labelRes()) } ?: "…",
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.learning_verifications),
          subtitle = stringResource(R.string.learning_verifications_desc),
          meta = if (outcomes < 0) "…" else outcomes.toString(),
        )
        // Una riga per variante di contesto e finestra: i numeri della mappa e, sotto, perche'
        // e' attiva o no. Una mappa esiste sempre come riga anche quando non si applica.
        PlattVariant.FITTED.forEach { variant ->
          PlattRefitPolicy.WINDOWS.forEach { window ->
            FluidListDivider()
            val record = maps.records.firstOrNull { it.variant == variant.key && it.window == window }
            FluidListRow(
              title = stringResource(
                R.string.learning_recalibration,
                "${stringResource(variant.labelRes())} · $window",
              ),
              subtitle = mapSubtitle(record),
              meta = record?.let { fmtDayTime(it.fittedAtMillis) } ?: "—",
            )
          }
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
          // L'esenzione batteria ora sta nella sezione del background, per ogni modalita'.
          FluidListRow(title = stringResource(R.string.engine_max_active), subtitle = stringResource(R.string.engine_max_active_desc))
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

/**
 * Le righe di una mappa di Platt: i parametri quando ci sono, i conteggi con bagnati e asciutti, e
 * l'esito: attiva con il suo guadagno fuori campione, o il motivo per cui non si applica.
 */
@Composable
private fun mapSubtitle(record: PlattMapRecord?): String {
  if (record == null) return stringResource(R.string.learning_not_yet)
  val lines = mutableListOf<String>()
  val a = record.a
  val b = record.b
  if (a != null && b != null) lines += stringResource(R.string.learning_platt, fmt2(a), fmt2(b), record.samples)
  lines += stringResource(R.string.learning_map_counts, record.samples, record.wet, record.dry)
  val reason = plattStatusLabelRes(record.status)
  lines += when {
    record.active -> stringResource(R.string.learning_active_gain, record.guardDeltaBrier?.let(::fmtDelta) ?: "—")
    reason != null && record.status != PlattStatus.ACTIVE.name -> stringResource(reason)
    else -> stringResource(R.string.learning_reason_no_gain)
  }
  // Senza contesto la regola barometrica del posto puo' aver battuto il modello sui giri di questo
  // telefono: allora e' lei a parlare, e la pagina lo dice.
  if (record.useRule) lines += stringResource(R.string.learning_rule_fallback, record.ruleDeltaBrier?.let(::fmtDelta) ?: "—")
  return lines.joinToString("\n")
}

private fun fmtDelta(value: Double) = String.format(Locale.ROOT, "%+.4f", value)

private fun fmt2(value: Double) = String.format(Locale.ROOT, "%.2f", value)

private fun fmt0(value: Double) = String.format(Locale.getDefault(), "%.0f", value)

private fun fmtDayTime(millis: Long): String = TimeFormats.dayTime(millis)
