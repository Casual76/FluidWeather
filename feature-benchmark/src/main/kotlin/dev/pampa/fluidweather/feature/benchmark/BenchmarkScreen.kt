package dev.pampa.fluidweather.feature.benchmark

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.tutorial.fluidTutorialAnchor
import dev.pampa.fluidweather.core.data.FusionSettingsStore
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.VerificationStore
import dev.pampa.fluidweather.core.ui.BlackSheet
import dev.pampa.fluidweather.core.ui.BlackSheetNote
import dev.pampa.fluidweather.core.ui.BlackSheetSectionTitle
import dev.pampa.fluidweather.core.ui.Charts
import dev.pampa.fluidweather.core.ui.TutorialScreen
import dev.pampa.fluidweather.core.ui.TutorialSlot
import dev.pampa.fluidweather.core.weather.Benchmark
import dev.pampa.fluidweather.core.weather.BenchmarkReport
import dev.pampa.fluidweather.core.weather.ProviderRegistry
import dev.pampa.fluidweather.core.weather.ProviderScore
import dev.pampa.fluidweather.core.weather.RainEvent
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.core.weather.WeatherSnapshotStore
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.launch
import dev.pampa.fluidweather.strings.R
import androidx.compose.ui.res.stringResource
import dev.pampa.fluidweather.core.ui.rememberUnitFormatter
import dev.pampa.fluidweather.strings.TimeFormats

/** Tutto quello che il Benchmark tocca; lo costruisce :app dal suo grafo. */
class BenchmarkDependencies(
  val verificationStore: VerificationStore,
  val fusionSettings: FusionSettingsStore,
  val snapshotStore: WeatherSnapshotStore,
)

private val White = Color.White
private val Dim = Color.White.copy(alpha = 0.7f)
private val Faint = Color.White.copy(alpha = 0.5f)
private val Blue = Color(0xFF8FC7F0)
private val Amber = Color(0xFFF0C060)
private val Green = Color(0xFF6FD58C)

/**
 * La vetrina del motore (fase 13), foglio nero a tutta altezza: la classifica dei provider
 * per la zona dalle verifiche VERE, la pioggia col barometro in classifica alla pari, l'errore
 * nel tempo, la ripartizione per variabile, e da ogni riga l'override "usa solo questo".
 * Niente qui e' editoriale: dove mancano verifiche, la pagina lo dice.
 */
@Composable
fun BenchmarkSheet(open: Boolean, deps: BenchmarkDependencies, onDismiss: () -> Unit) {
  if (!open) return
  BlackSheet(title = stringResource(R.string.bench_title), onDismiss = onDismiss) {
    BenchmarkContent(deps)
  }
}

@Composable
private fun BenchmarkContent(deps: BenchmarkDependencies) {
  val scope = rememberCoroutineScope()
  val now = remember { System.currentTimeMillis() }
  val report by produceState<BenchmarkReport?>(initialValue = null) {
    val verifications = runCatching {
      deps.verificationStore.allVerifications(now - 60L * 86_400_000L)
    }.getOrDefault(emptyList())
    value = Benchmark.build(verifications, now)
  }
  val snapshot by produceState<WeatherSnapshot?>(initialValue = null) {
    value = deps.snapshotStore.read(WeatherSnapshot.GPS_KEY)
  }
  val onlyProvider by deps.fusionSettings.onlyProviderId.collectAsState(initial = null)

  val ready = report
  if (ready == null) {
    BlackSheetNote(stringResource(R.string.bench_loading))
    return
  }

  // ------------------------------------------------------------------------- lo stato
  BlackSheetSectionTitle(stringResource(R.string.bench_collection))
  val first = ready.firstVerificationMillis
  if (ready.totalVerifications == 0) {
    BlackSheetNote(
      stringResource(R.string.bench_empty),
    )
  } else {
    StatRow(stringResource(R.string.bench_collected), "${ready.totalVerifications}", first?.let { stringResource(R.string.bench_since, fmtDay(it)) })
    StatRow(stringResource(R.string.bench_threshold), stringResource(R.string.bench_per_variable, Benchmark.MIN_VERIFICATIONS), stringResource(R.string.bench_below_threshold))
    val current = snapshot
    if (current != null) {
      StatRow(stringResource(R.string.bench_last_round), stringResource(R.string.bench_providers_count, current.providersResponding, current.fetches.size), fmtTime(current.fetchedAtMillis))
    }
  }
  if (onlyProvider != null) {
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        stringResource(R.string.bench_override_active, providerLabel(onlyProvider!!)),
        style = MaterialTheme.typography.bodyMedium,
        color = Amber,
        modifier = Modifier.weight(1f),
      )
      FluidButton(
        text = stringResource(R.string.prov_back_to_fusion),
        style = FluidButtonStyle.Tinted,
        onClick = { scope.launch { deps.fusionSettings.setOnlyProvider(null) } },
      )
    }
  }

  // ------------------------------------------------------------------------ la classifica
  TutorialSlot(screen = TutorialScreen.BENCHMARK)
  Box(Modifier.fluidTutorialAnchor("benchmark_ranking_list")) {
    BlackSheetSectionTitle(stringResource(R.string.bench_ranking))
  }
  if (ready.ranking.isEmpty()) {
    BlackSheetNote(stringResource(R.string.bench_ranking_empty))
  } else {
    var expanded by remember { mutableStateOf<String?>(null) }
    ready.ranking.forEachIndexed { index, rank ->
      val isExpanded = expanded == rank.providerId
      Column(
        Modifier
          .fillMaxWidth()
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
          ) { expanded = if (isExpanded) null else rank.providerId }
          .padding(vertical = 8.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            "${index + 1}",
            style = MaterialTheme.typography.titleMedium,
            color = if (index == 0) Amber else Faint,
            modifier = Modifier.width(28.dp),
          )
          Column(Modifier.weight(1f)) {
            Text(providerLabel(rank.providerId), style = MaterialTheme.typography.titleSmall, color = White)
            Text(
              buildString {
                append(stringResource(R.string.common_verifications_count, rank.verifications))
                if (rank.bestAt.isNotEmpty()) append(stringResource(R.string.bench_best_at, rank.bestAt.map { variableLabel(it) }.joinToString()))
                if (rank.providerId == onlyProvider) append(stringResource(R.string.bench_override_suffix))
              },
              style = MaterialTheme.typography.bodySmall,
              color = Faint,
            )
          }
          Text(
            "${(rank.share * 100).toInt()}%",
            style = MaterialTheme.typography.titleMedium,
            color = White,
          )
        }
        Spacer(Modifier.height(4.dp))
        ShareBar(rank.share)
        if (isExpanded) {
          Spacer(Modifier.height(8.dp))
          rank.maeByVariable.forEach { (variable, score) ->
            StatRow(
              variableLabel(variable),
              stringResource(R.string.bench_mae, fmtMae(variable, score.decayedMae)),
              if (score.learned) stringResource(R.string.bench_count_weighs, score.count) else stringResource(R.string.bench_count_below, score.count),
            )
          }
          Spacer(Modifier.height(6.dp))
          val isOverride = rank.providerId == onlyProvider
          FluidButton(
            text = if (isOverride) stringResource(R.string.prov_back_to_fusion) else stringResource(R.string.bench_use_only),
            style = FluidButtonStyle.Tinted,
            onClick = {
              scope.launch { deps.fusionSettings.setOnlyProvider(if (isOverride) null else rank.providerId) }
            },
          )
        }
      }
      Divider()
    }
    BlackSheetNote(
      stringResource(R.string.bench_weight_note),
    )
  }

  // ------------------------------------------------------------------------ la pioggia
  BlackSheetSectionTitle(stringResource(R.string.bench_rain_title))
  if (ready.rainEvent.isEmpty()) {
    BlackSheetNote(
      stringResource(R.string.bench_rain_note),
    )
  } else {
    var window by remember { mutableIntStateOf(1) }
    val windows = RainEvent.windows
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      windows.forEachIndexed { index, w ->
        FluidChip(label = w.nowcastLabel, selected = index == window, onClick = { window = index })
      }
    }
    Spacer(Modifier.height(8.dp))
    val scores = ready.rainEvent[windows[window].variable].orEmpty()
    if (scores.isEmpty()) {
      BlackSheetNote(stringResource(R.string.bench_rain_empty))
    } else {
      scores.forEachIndexed { index, score -> RainRow(index + 1, score) }
    }
    BlackSheetNote(
      stringResource(R.string.bench_rain_method),
    )
  }

  // ------------------------------------------------------------------- errore nel tempo
  BlackSheetSectionTitle(stringResource(R.string.bench_error_time, Benchmark.DAYS))
  if (ready.dailyError.isEmpty()) {
    BlackSheetNote(stringResource(R.string.bench_curve_empty))
  } else {
    val providers = ready.ranking.map { it.providerId }.ifEmpty { ready.dailyError.keys.toList() }
    var providerIndex by remember { mutableIntStateOf(0) }
    var variableIndex by remember { mutableIntStateOf(0) }
    val variables = FusionVariables.verified
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
      providers.take(4).forEachIndexed { index, id ->
        FluidChip(label = providerLabel(id), selected = index == providerIndex, onClick = { providerIndex = index })
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      variables.forEachIndexed { index, variable ->
        FluidChip(label = variableLabel(variable), selected = index == variableIndex, onClick = { variableIndex = index })
      }
    }
    Spacer(Modifier.height(8.dp))
    val series = providers.getOrNull(providerIndex)?.let { ready.dailyError[it]?.get(variables[variableIndex]) }.orEmpty()
    if (series.size < 2) {
      BlackSheetNote(stringResource(R.string.bench_curve_two_days))
    } else {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.bench_max, fmtMae(variables[variableIndex], series.maxOf { it.mae })), style = MaterialTheme.typography.labelSmall, color = Faint)
        Text(stringResource(R.string.bench_min, fmtMae(variables[variableIndex], series.minOf { it.mae })), style = MaterialTheme.typography.labelSmall, color = Faint)
      }
      Charts.SmoothLine(
        values = series.map { it.mae },
        color = Blue,
        modifier = Modifier
          .fillMaxWidth()
          .height(90.dp),
      )
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(fmtEpochDay(series.first().epochDay), style = MaterialTheme.typography.labelSmall, color = Faint)
        Text(fmtEpochDay(series.last().epochDay), style = MaterialTheme.typography.labelSmall, color = Faint)
      }
      Text(
        stringResource(R.string.bench_daily_error, series.sumOf { it.count }),
        style = MaterialTheme.typography.bodySmall,
        color = Faint,
      )
    }
  }

  // ------------------------------------------------------------- ripartizione per variabile
  BlackSheetSectionTitle(stringResource(R.string.bench_by_variable))
  if (ready.byVariable.isEmpty()) {
    BlackSheetNote(stringResource(R.string.bench_appears_first))
  } else {
    ready.byVariable.forEach { (variable, scores) ->
      val best = scores.first()
      StatRow(
        variableLabel(variable),
        "${providerLabel(best.providerId)} · ${fmtMae(variable, best.decayedMae)}",
        stringResource(R.string.bench_competing, scores.size),
      )
    }
    BlackSheetNote(stringResource(R.string.bench_best_note))
  }

  BlackSheetSectionTitle(stringResource(R.string.bench_how))
  BlackSheetNote(
    stringResource(R.string.bench_how_note),
  )
}

@Composable
private fun RainRow(position: Int, score: ProviderScore) {
  val isBarometer = score.providerId == RainEvent.LOCAL_BAROMETER_ID
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp),
  ) {
    Text("$position", style = MaterialTheme.typography.titleMedium, color = if (position == 1) Amber else Faint, modifier = Modifier.width(28.dp))
    Column(Modifier.weight(1f)) {
      Text(
        providerLabel(score.providerId),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (isBarometer) FontWeight.SemiBold else FontWeight.Normal),
        color = if (isBarometer) Green else White,
      )
      Text(stringResource(R.string.common_verifications_count, score.count), style = MaterialTheme.typography.bodySmall, color = Faint)
    }
    Text(String.format(Locale.getDefault(), "%.2f", score.decayedMae), style = MaterialTheme.typography.titleMedium, color = White)
  }
}

@Composable
private fun ShareBar(share: Double) {
  Box(
    Modifier
      .fillMaxWidth()
      .height(6.dp)
      .background(White.copy(alpha = 0.10f), ContinuousCornerShape(3.dp)),
  ) {
    Box(
      Modifier
        .fillMaxWidth(share.toFloat().coerceIn(0.02f, 1f))
        .height(6.dp)
        .background(Blue, ContinuousCornerShape(3.dp)),
    )
  }
}

@Composable
private fun Divider() {
  Box(
    Modifier
      .fillMaxWidth()
      .height(1.dp)
      .background(White.copy(alpha = 0.08f)),
  )
}

@Composable
private fun StatRow(label: String, value: String, detail: String? = null) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 5.dp),
  ) {
    Text(label, style = MaterialTheme.typography.bodyMedium, color = Dim, modifier = Modifier.weight(1f))
    Column(horizontalAlignment = Alignment.End) {
      Text(value, style = MaterialTheme.typography.titleSmall, color = White)
      if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = Faint)
    }
  }
}

@Composable
internal fun providerLabel(providerId: String): String = when (providerId) {
  RainEvent.LOCAL_BAROMETER_ID -> stringResource(R.string.your_barometer)
  else -> ProviderRegistry.all.firstOrNull { it.id == providerId }?.label ?: providerId
}

@Composable
internal fun variableLabel(variable: String): String = when (variable) {
  FusionVariables.TEMPERATURE -> stringResource(R.string.var_temperature)
  FusionVariables.PRESSURE_MSL -> stringResource(R.string.var_pressure)
  FusionVariables.PRECIPITATION -> stringResource(R.string.var_precipitation)
  FusionVariables.CLOUD_COVER -> stringResource(R.string.var_clouds)
  FusionVariables.WIND_SPEED -> stringResource(R.string.var_wind)
  else -> variable
}

/** L'unita' del MAE per variabile, nelle unita' dell'utente: gradi, pressione, pioggia, punti, vento. */
@Composable
internal fun fmtMae(variable: String, mae: Double): String {
  val units = rememberUnitFormatter()
  return when (variable) {
    FusionVariables.TEMPERATURE -> units.temperatureSpan(mae, 1)
    FusionVariables.PRESSURE_MSL -> units.pressure(mae, 1)
    FusionVariables.PRECIPITATION -> units.precipitation(mae, 2)
    FusionVariables.CLOUD_COVER -> stringResource(R.string.mae_points, units.num(mae, 0))
    FusionVariables.WIND_SPEED -> units.wind(mae, 1)
    else -> units.num(mae, 2)
  }
}

private fun fmtDay(millis: Long): String = TimeFormats.shortDate(millis)

private fun fmtTime(millis: Long): String = TimeFormats.time(millis)

private fun fmtEpochDay(epochDay: Long): String = TimeFormats.shortDate(LocalDate.ofEpochDay(epochDay))
