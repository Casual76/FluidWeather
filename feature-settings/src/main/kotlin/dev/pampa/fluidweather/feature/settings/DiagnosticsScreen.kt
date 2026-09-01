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
import dev.pampa.fluidweather.core.sensor.ManualBurstController
import dev.pampa.fluidweather.core.sensor.MaximaAlarm
import dev.pampa.fluidweather.core.sensor.SamplingScheduler
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
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tutto quello che la diagnostica tocca; lo costruisce :app dal suo grafo. */
class DiagnosticsDependencies(
  val barometer: Barometer,
  val settingsStore: SamplingSettingsStore,
  val repository: PressureRepository,
  val burstController: ManualBurstController,
  val scheduler: SamplingScheduler,
  val activityRecognizer: ActivityRecognizer,
  val cleaningPipeline: CleaningPipeline,
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
        deps.repository.samplesSince(System.currentTimeMillis() - 12 * 60 * 60_000L),
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

  FluidScreen(title = "Diagnostica barometro", onBack = onBack) {
    item { FluidSectionHeader(title = "Sensore") }
    item {
      FluidListGroup {
        if (deps.barometer.isAvailable) {
          FluidListRow(
            title = "Pressione grezza",
            subtitle = deps.barometer.sensorName ?: "Barometro",
            meta = live?.let { String.format(Locale.getDefault(), "%.2f hPa", it.pressureHpa) } ?: "—",
          )
        } else {
          FluidListRow(
            title = "Barometro assente",
            subtitle = "Questo dispositivo non ha il sensore: la sezione locale non e' disponibile.",
          )
        }
        FluidListDivider()
        FluidListRow(
          title = "Campioni in archivio",
          subtitle = "Tutte le letture registrate finora",
          meta = sampleCount.toString(),
        )
      }
    }

    item { FluidSectionHeader(title = "Verdetto nowcast — v1, solo barometro") }
    item {
      FluidListGroup {
        val verdict: NowcastVerdict? = cleaning?.let { result ->
          FeatureExtractor.extract(result, context = null, normalHpa = null, nowMillis = System.currentTimeMillis())
            ?.let { NowcastModel.trained().verdict(it) }
        }
        if (verdict == null) {
          FluidListRow(
            title = "In attesa di storia",
            subtitle = "Il verdetto compare dopo ~13 ore di campionamento; " +
              "il contesto dei provider (fase 6) lo affinera'",
          )
        } else {
          FluidListRow(
            title = "Livello",
            subtitle = when (verdict.level) {
              AlertLevel.QUIETE -> "Nessun segnale fuori dalla climatologia"
              AlertLevel.SORVEGLIANZA -> "Qualcosa si muove: finestre sopra il 35%"
              AlertLevel.ALLERTA -> "Precipitazione piu' probabile che no a breve"
            },
            meta = verdict.level.name.lowercase(),
          )
          verdict.windows.forEach { window ->
            FluidListDivider()
            FluidListRow(
              title = "Pioggia ${window.window}",
              subtitle = window.topFactors.joinToString(" · ") { factor ->
                "${factor.name} ${if (factor.contribution > 0) "+" else "−"}"
              }.ifEmpty { "Nessun fattore fuori dal neutro" },
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

    item { FluidSectionHeader(title = "Segnale pulito — stadi 1-2") }
    item {
      FluidListGroup {
        val latest = cleaning?.latest
        if (latest == null) {
          FluidListRow(
            title = "In attesa di dati",
            subtitle = "Il segnale pulito compare quando l'archivio ha qualche lettura",
          )
        } else {
          FluidListRow(
            title = "Livello (mare)",
            subtitle = "Ridotto con la quota, filtrato, con la sua incertezza",
            meta = String.format(
              Locale.getDefault(),
              "%.2f ± %.2f hPa",
              latest.levelHpa,
              latest.levelSigmaHpa,
            ),
          )
          FluidListDivider()
          FluidListRow(
            title = "Tendenza",
            subtitle = "Quiete sotto 1,0 · sorveglianza oltre",
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
            title = "Marea atmosferica",
            subtitle = when (result.tide.source) {
              TideSource.NONE -> "Nessuna posizione nota: non si sottrae niente"
              TideSource.CLIMATOLOGICAL -> String.format(
                Locale.getDefault(),
                "Prior climatologico · S1 %.2f · S2 %.2f hPa",
                result.tide.s1AmplitudeHpa,
                result.tide.s2AmplitudeHpa,
              )
              TideSource.FITTED -> String.format(
                Locale.getDefault(),
                "Adattata al posto · S1 %.2f · S2 %.2f hPa",
                result.tide.s1AmplitudeHpa,
                result.tide.s2AmplitudeHpa,
              )
            },
            meta = if (result.tide.source == TideSource.NONE) {
              "—"
            } else {
              String.format(Locale.getDefault(), "adesso %+.2f hPa", result.tide.tideAtLatestHpa)
            },
          )
          val counts = result.rejectionCounts()
          FluidListDivider()
          FluidListRow(
            title = "Scarti della pulizia",
            subtitle = if (counts.isEmpty()) {
              "Nessun punto scartato nelle ultime 12 ore"
            } else {
              counts.entries.joinToString(" · ") { (reason, count) -> "${reason.label()}: $count" }
            },
            meta = "${result.cleaned.size} tenuti",
          )
        }
      }
    }

    if (missingPermissions.isNotEmpty()) {
      item {
        FluidListGroup {
          FluidListRow(
            title = "Concedi i permessi",
            subtitle = "Posizione, attivita' e notifiche arricchiscono ogni campione",
            onClick = { permissionLauncher.launch(missingPermissions.toTypedArray()) },
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
              {
                Icon(
                  imageVector = Icons.Rounded.Check,
                  contentDescription = "Selezionata",
                  tint = MaterialTheme.colorScheme.primary,
                )
              }
            } else {
              null
            },
            onClick = {
              scope.launch {
                deps.settingsStore.setMode(mode)
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
                  context.startActivity(
                    Intent(
                      Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                      Uri.parse("package:${context.packageName}"),
                    ),
                  )
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
                context.startActivity(
                  Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                  ),
                )
              },
            )
          } else {
            FluidListRow(
              title = "Massima attiva",
              subtitle = "Catena di allarmi esatti ogni 5 minuti, raffica di 30 secondi.",
            )
          }
        }
      }
    }

    item { FluidSectionHeader(title = "Strumenti") }
    item {
      FluidListGroup {
        FluidListRow(
          title = "Monitoraggio continuo",
          subtitle = "Una lettura ogni 10 s mentre l'app e' aperta",
          badge = {
            FluidSwitch(
              checked = settings.continuousWhileOpen,
              onCheckedChange = { enabled ->
                scope.launch { deps.settingsStore.setContinuousWhileOpen(enabled) }
              },
            )
          },
        )
        FluidListDivider()
        val burstProgress = burst
        if (burstProgress == null) {
          FluidListRow(
            title = "Raffica manuale",
            subtitle = "${ManualBurst.DURATION_SECONDS / 60} minuti a ${ManualBurst.HZ} Hz: " +
              "abbastanza campioni da separare una caduta vera dal rumore",
            badge = {
              FluidButton(
                text = "Avvia",
                style = FluidButtonStyle.Tinted,
                onClick = { deps.burstController.start() },
              )
            },
          )
        } else {
          FluidListRow(
            title = "Raffica in corso",
            subtitle = "${burstProgress.completedSeconds} / ${burstProgress.totalSeconds} s",
            badge = {
              FluidButton(
                text = "Annulla",
                style = FluidButtonStyle.Plain,
                onClick = { deps.burstController.cancel() },
              )
            },
          )
        }
      }
    }

    if (samples.isNotEmpty()) {
      item { FluidSectionHeader(title = "Ultime letture") }
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
      append(sample.source.label())
      sample.altitudeMeters?.let { append(" · ${it.toInt()} m") }
      if (sample.activity != dev.pampa.fluidweather.core.model.ActivityKind.UNKNOWN) {
        append(" · ${sample.activity.name.lowercase()}")
      }
    },
    meta = TimeFormatter.format(Instant.ofEpochMilli(sample.timestampMillis)),
  )
}

private val TimeFormatter: DateTimeFormatter =
  DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun SamplingMode.label(): String = when (this) {
  SamplingMode.MASSIMA -> "Massima"
  SamplingMode.BILANCIATA -> "Bilanciata"
  SamplingMode.RISPARMIO -> "Risparmio"
  SamplingMode.MINIMA -> "Minima"
}

private fun SamplingMode.description(): String = when (this) {
  SamplingMode.MASSIMA -> "Ogni 5 min, raffica 30 s — allarmi esatti, piu' batteria"
  SamplingMode.BILANCIATA -> "Ogni 15 min, raffica 30 s — il compromesso suggerito"
  SamplingMode.RISPARMIO -> "Ogni 30 min, raffica 15 s — qualita' in calo dichiarata"
  SamplingMode.MINIMA -> "Ogni 20 min, lettura secca — consumo ~nullo, accuratezza scarsa"
}

private fun SampleSource.label(): String = when (this) {
  SampleSource.PERIODIC -> "giro periodico"
  SampleSource.SURVEILLANCE -> "sorveglianza"
  SampleSource.MANUAL_BURST -> "raffica manuale"
  SampleSource.CONTINUOUS -> "continuo"
}

private fun RejectionReason.label(): String = when (this) {
  RejectionReason.ANOMALOUS_VARIANCE -> "varianza anomala"
  RejectionReason.VEHICLE -> "veicolo"
  RejectionReason.ALTITUDE_CHANGE -> "cambio di quota"
  RejectionReason.NON_WEATHER_JUMP -> "salto non-meteo"
}
