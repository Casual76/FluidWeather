package dev.pampa.fluidweather.feature.benchmark

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.tutorial.fluidTutorialAnchor
import dev.pampa.fluidweather.core.data.FusionSettingsStore
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.RainEventStore
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
import dev.pampa.fluidweather.core.weather.RainBoard
import dev.pampa.fluidweather.core.weather.RainBoardReport
import dev.pampa.fluidweather.core.weather.RainRowKind
import dev.pampa.fluidweather.core.weather.RainRowScore
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.core.weather.WeatherSnapshotStore
import dev.pampa.fluidweather.nowcast.verdict.ModelVersions
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.pampa.fluidweather.strings.R
import dev.pampa.fluidweather.strings.rainRowLabelRes
import androidx.compose.ui.res.stringResource
import dev.pampa.fluidweather.core.ui.rememberUnitFormatter
import dev.pampa.fluidweather.strings.TimeFormats

/** Tutto quello che il Benchmark tocca; lo costruisce :app dal suo grafo. */
class BenchmarkDependencies(
  val verificationStore: VerificationStore,
  val fusionSettings: FusionSettingsStore,
  val snapshotStore: WeatherSnapshotStore,
  /** I giudizi della pioggia: la classifica nuova li legge da qui, non dalle verifiche generali. */
  val rainEventStore: RainEventStore,
  /** Decide l'ancora della classifica pioggia: il barometro, o la climatologia dove non c'e'. */
  val barometerAvailable: Boolean,
)

private val White = Color.White
private val Dim = Color.White.copy(alpha = 0.7f)
private val Faint = Color.White.copy(alpha = 0.5f)
private val Blue = Color(0xFF8FC7F0)
private val Amber = Color(0xFFF0C060)
private val Green = Color(0xFF6FD58C)

/**
 * La vetrina del motore (fase 13), foglio nero a tutta altezza: la classifica dei provider
 * per la zona dalle verifiche VERE, la pioggia col barometro in classifica alla pari (Brier, sugli
 * stessi giri per tutti, con i giudici fuori classifica), l'errore nel tempo, la ripartizione per
 * variabile, e da ogni riga l'override "usa solo questo".
 * Niente qui e' editoriale: dove mancano verifiche, la pagina lo dice.
 */
@Composable
fun BenchmarkSheet(open: Boolean, deps: BenchmarkDependencies, onDismiss: () -> Unit) {
  if (!open) return
  BlackSheet(title = stringResource(R.string.bench_title), onDismiss = onDismiss) {
    BenchmarkContent(deps)
  }
}

/** Le due pagelle della pagina, pronte insieme: il foglio si disegna una volta sola. */
private class BenchmarkState(val report: BenchmarkReport, val rain: RainBoardReport)

@Composable
private fun BenchmarkContent(deps: BenchmarkDependencies) {
  val scope = rememberCoroutineScope()
  val now = remember { System.currentTimeMillis() }
  val state by produceState<BenchmarkState?>(initialValue = null) {
    // Il ricampionamento del Brier e' calcolo vero (mille ricampionamenti per riga e finestra): non
    // sul thread dell'interfaccia.
    value = withContext(Dispatchers.Default) {
      val verifications = runCatching {
        deps.verificationStore.allVerifications(now - 60L * 86_400_000L)
      }.getOrDefault(emptyList())
      val rainRows = runCatching {
        deps.rainEventStore.verifications(ModelVersions.TAG, now - RainBoard.WINDOW_MILLIS)
      }.getOrDefault(emptyList())
      BenchmarkState(
        report = Benchmark.build(verifications, now),
        rain = RainBoard.build(rainRows, now, deps.barometerAvailable),
      )
    }
  }
  val snapshot by produceState<WeatherSnapshot?>(initialValue = null) {
    value = deps.snapshotStore.read(WeatherSnapshot.GPS_KEY)
  }
  val onlyProvider by deps.fusionSettings.onlyProviderId.collectAsState(initial = null)

  val loaded = state
  if (loaded == null) {
    BlackSheetNote(stringResource(R.string.bench_loading))
    return
  }
  val ready = loaded.report

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
  RainSection(loaded.rain)

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
        SheetChip(label = providerLabel(id), selected = index == providerIndex, onClick = { providerIndex = index })
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      variables.forEachIndexed { index, variable ->
        SheetChip(label = variableLabel(variable), selected = index == variableIndex, onClick = { variableIndex = index })
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

/**
 * La pioggia: il barometro, i provider e due riferimenti, giudicati con lo stesso metro sugli stessi
 * giri. Finche' non e' arrivata una sola verita' (il giudizio aspetta un giorno dopo la finestra) la
 * sezione e' la nota che spiega che cosa si sta aspettando.
 */
@Composable
private fun RainSection(board: RainBoardReport) {
  BlackSheetSectionTitle(stringResource(R.string.bench_rain_title))
  if (board.windows.all { it.rows.isEmpty() }) {
    BlackSheetNote(stringResource(R.string.bench_rain_note))
    return
  }
  var selected by remember { mutableIntStateOf(1) }
  val index = selected.coerceIn(0, board.windows.lastIndex)
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    board.windows.forEachIndexed { i, w ->
      SheetChip(label = w.window, selected = i == index, onClick = { selected = i })
    }
  }
  Spacer(Modifier.height(8.dp))
  val window = board.windows[index]
  if (window.rows.isEmpty()) {
    BlackSheetNote(stringResource(R.string.bench_rain_empty))
  } else {
    Text(
      stringResource(R.string.bench_rain_rounds, window.rounds.toString(), window.days.toString()),
      style = MaterialTheme.typography.bodySmall,
      color = Faint,
    )
    window.rows.forEach { RainRow(it) }
  }
  BlackSheetNote(stringResource(R.string.bench_rain_method))
}

/**
 * Una riga della classifica pioggia: posto, nome, "n casi · MAE", e a destra il Brier col suo "±".
 * Il barometro e' verde; i riferimenti (sempre 0%, climatologia) in corsivo, perche' non sono
 * concorrenti ma il metro con cui leggere gli altri; chi ha pochi dati non ha posto ne' numero.
 */
@Composable
private fun RainRow(row: RainRowScore) {
  val isBarometer = row.kind == RainRowKind.BAROMETER
  val isReference = row.kind == RainRowKind.REFERENCE
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp)
      .semantics(mergeDescendants = true) {},
  ) {
    Text(
      row.rank?.toString() ?: "",
      style = MaterialTheme.typography.titleMedium,
      color = if (row.rank == 1) Amber else Faint,
      modifier = Modifier.width(28.dp),
    )
    Column(Modifier.weight(1f)) {
      Text(
        providerLabel(row.providerId),
        style = MaterialTheme.typography.titleSmall.copy(
          fontWeight = if (isBarometer) FontWeight.SemiBold else FontWeight.Normal,
          fontStyle = if (isReference) FontStyle.Italic else FontStyle.Normal,
        ),
        color = if (isBarometer) Green else White,
      )
      Text(
        stringResource(R.string.bench_rain_cases_mae, row.cases.toString(), fmtDecimals(row.mae, 2)),
        style = MaterialTheme.typography.bodySmall,
        color = Faint,
      )
    }
    if (row.fewData) {
      Text(stringResource(R.string.bench_rain_few_data), style = MaterialTheme.typography.bodySmall, color = Faint)
    } else {
      Text(
        stringResource(R.string.bench_rain_brier, fmtDecimals(row.brier, 3), fmtDecimals(row.halfWidth, 3)),
        style = MaterialTheme.typography.titleMedium,
        color = White,
      )
    }
  }
}

/**
 * La pillola di scelta del foglio nero.
 *
 * `FluidChip` dipinge il non scelto con `onSurface` al 6% e la scritta in `onSurface`: colori del
 * TEMA dell'app, pensati per una superficie del tema. Il foglio nero e' nero qualunque sia il tema,
 * e li' una pillola non scelta spariva (al 6% su nero non c'e' bordo, e nel tema chiaro anche la
 * scritta e' scura su nero). Qui il non scelto e' bianco al 14% con la scritta bianca, sempre
 * leggibile; lo scelto resta come quello dell'engine: l'accento con la sua scritta.
 */
@Composable
private fun SheetChip(label: String, selected: Boolean, onClick: () -> Unit) {
  val scheme = MaterialTheme.colorScheme
  val container by animateColorAsState(
    targetValue = if (selected) scheme.primary else White.copy(alpha = 0.14f),
    animationSpec = FluidMotion.color(200),
    label = "sheet chip container",
  )
  val content by animateColorAsState(
    targetValue = if (selected) scheme.onPrimary else White.copy(alpha = 0.92f),
    animationSpec = FluidMotion.color(200),
    label = "sheet chip content",
  )
  Box(
    modifier = Modifier
      .defaultMinSize(minHeight = 48.dp)
      .clip(FluidCapsuleShape)
      .background(container)
      .semantics { this.selected = selected }
      .fluidPressable(onClick = onClick, role = Role.Button)
      .padding(horizontal = 14.dp, vertical = 6.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
      color = content,
      maxLines = 1,
    )
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
internal fun providerLabel(providerId: String): String =
  rainRowLabelRes(providerId)?.let { stringResource(it) }
    ?: ProviderRegistry.all.firstOrNull { it.id == providerId }?.label
    ?: providerId

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
