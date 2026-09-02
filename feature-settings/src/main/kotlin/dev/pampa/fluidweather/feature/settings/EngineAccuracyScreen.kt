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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tutto quello che la categoria "Motore e accuratezza" tocca. */
class EngineAccuracyDependencies(
  val samplingSettings: SamplingSettingsStore,
  val scheduler: SamplingScheduler,
  val calibrationStore: CalibrationStore,
  val calibrationController: CalibrationController,
  val pressureRepository: PressureRepository,
  val cleaningPipeline: CleaningPipeline,
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
  val historyHours by produceState(initialValue = 0.0) {
    val now = System.currentTimeMillis()
    val samples = runCatching { deps.pressureRepository.samplesSince(now - 24 * 3_600_000L) }.getOrDefault(emptyList())
    value = withContext(Dispatchers.Default) {
      val filtered = runCatching { deps.cleaningPipeline.process(samples).filtered }.getOrDefault(emptyList())
      if (filtered.size >= 2) (filtered.last().timestampMillis - filtered.first().timestampMillis) / 3_600_000.0 else 0.0
    }
  }
  val readiness = NowcastReadiness.of(
    calibration = calibration,
    calibrationProgress = progress?.let { it.completedSeconds to it.totalSeconds },
    historyHours = historyHours,
    requiredHours = FeatureExtractor.MIN_HISTORY_HOURS,
  )

  FluidScreen(title = "Motore e accuratezza", onBack = onBack) {
    item { FluidSectionHeader(title = "Il barometro e' pronto?") }
    item {
      FluidListGroup {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
          Text(readiness.stageLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
          Spacer(Modifier.height(8.dp))
          FluidProgressBar(progress = { readiness.overallFraction })
          Spacer(Modifier.height(6.dp))
          Text(
            if (readiness.ready) {
              "Il modello ha la storia che chiede: il verdetto e' in home."
            } else {
              "Prima la raffica iniziale (un quinto della barra), poi le ${FeatureExtractor.MIN_HISTORY_HOURS.toInt()} ore " +
                "di segnale pulito che il modello vuole vedere. Il telefono lavora da solo."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    item { FluidSectionHeader(title = "Taratura del dispositivo") }
    item {
      FluidListGroup {
        val record = calibration
        if (record == null) {
          FluidListRow(
            title = "Bias non ancora stimato",
            subtitle = "La raffica iniziale confronta la mediana di dieci minuti con la pressione al mare dei servizi.",
          )
        } else {
          FluidListRow(
            title = "Bias del sensore",
            subtitle = "Da sottrarre a ogni lettura; fiducia ${(record.confidence * 100).toInt()}%",
            meta = String.format(Locale.ROOT, "%+.2f hPa", record.biasHpa),
          )
          FluidListDivider()
          FluidListRow(
            title = "Come e' stato stimato",
            subtitle = "Locale ${fmt1(record.localMslHpa)} vs servizi ${fmt1(record.referenceMslHpa)} hPa al mare · " +
              "${record.sampleCount} letture" + (record.altitudeMeters?.let { " · quota ${fmt0(it)} m" } ?: " · quota ignota"),
            meta = fmtDayTime(record.calibratedAtMillis),
          )
        }
        FluidListDivider()
        val running = progress
        if (running == null) {
          FluidListRow(
            title = if (record == null) "Fai la taratura" else "Rifai la taratura",
            subtitle = outcome ?: "Dieci minuti in sottofondo: puoi usare il telefono normalmente.",
            badge = {
              FluidButton(text = "Avvia", style = FluidButtonStyle.Tinted, onClick = { deps.calibrationController.start() })
            },
          )
        } else {
          FluidListRow(
            title = "Taratura in corso",
            subtitle = "${running.completedSeconds / 60}:${String.format(Locale.ROOT, "%02d", running.completedSeconds % 60)} di ${running.totalSeconds / 60}:00",
            badge = {
              FluidButton(text = "Annulla", style = FluidButtonStyle.Plain, onClick = { deps.calibrationController.cancel() })
            },
          )
        }
      }
    }

    item { FluidSectionHeader(title = "Modalita' di campionamento") }
    item {
      FluidListGroup {
        SamplingMode.entries.forEachIndexed { index, mode ->
          if (index > 0) FluidListDivider()
          FluidListRow(
            title = mode.label(),
            subtitle = mode.description(),
            badge = if (settings.mode == mode) {
              { Icon(Icons.Rounded.Check, contentDescription = "Selezionata", tint = MaterialTheme.colorScheme.primary) }
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
              title = "Allarmi esatti non consentiti",
              subtitle = "Senza, Massima degrada a un giro ogni 15 minuti. Tocca per concederli.",
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
              title = "Esenzione batteria",
              subtitle = "In Doze profondo il ritmo cala: l'esenzione lo limita. Tocca per chiederla.",
              onClick = {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
              },
            )
          } else {
            FluidListRow(title = "Massima attiva", subtitle = "Catena di allarmi esatti ogni 5 minuti, raffica di 30 secondi.")
          }
        }
      }
    }

    item { FluidSectionHeader(title = "Mentre l'app e' aperta") }
    item {
      FluidListGroup {
        FluidListRow(
          title = "Monitoraggio continuo",
          subtitle = "Una lettura ogni 10 s finche' l'app e' davanti agli occhi",
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

private fun fmt1(value: Double) = String.format(Locale.getDefault(), "%.1f", value)

private fun fmt0(value: Double) = String.format(Locale.getDefault(), "%.0f", value)

private fun fmtDayTime(millis: Long): String =
  DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.getDefault()).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
