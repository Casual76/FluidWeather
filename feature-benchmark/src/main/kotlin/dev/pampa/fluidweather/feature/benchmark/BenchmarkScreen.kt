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
import dev.pampa.fluidweather.core.data.FusionSettingsStore
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.VerificationStore
import dev.pampa.fluidweather.core.ui.BlackSheet
import dev.pampa.fluidweather.core.ui.BlackSheetNote
import dev.pampa.fluidweather.core.ui.BlackSheetSectionTitle
import dev.pampa.fluidweather.core.ui.Charts
import dev.pampa.fluidweather.core.weather.Benchmark
import dev.pampa.fluidweather.core.weather.BenchmarkReport
import dev.pampa.fluidweather.core.weather.ProviderRegistry
import dev.pampa.fluidweather.core.weather.ProviderScore
import dev.pampa.fluidweather.core.weather.RainEvent
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.core.weather.WeatherSnapshotStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

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
  BlackSheet(title = "Benchmark", onDismiss = onDismiss) {
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
    BlackSheetNote("Leggo le verifiche…")
    return
  }

  // ------------------------------------------------------------------------- lo stato
  BlackSheetSectionTitle("La raccolta")
  val first = ready.firstVerificationMillis
  if (ready.totalVerifications == 0) {
    BlackSheetNote(
      "Nessuna verifica ancora. Ogni giro dei provider semina previsioni a +1, +3, +6, +12 e +24 ore " +
        "e le giudica quando quell'ora e' passata, contro la mediana delle analisi: torna dopo " +
        "qualche ora di uso, la classifica si costruisce da sola.",
    )
  } else {
    StatRow("Verifiche raccolte", "${ready.totalVerifications}", first?.let { "dal ${fmtDay(it)}" })
    StatRow("Soglia per pesare", "${Benchmark.MIN_VERIFICATIONS} per variabile", "sotto, valgono i priori regionali")
    val current = snapshot
    if (current != null) {
      StatRow("Ultimo giro", "${current.providersResponding}/${current.fetches.size} provider", fmtTime(current.fetchedAtMillis))
    }
  }
  if (onlyProvider != null) {
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        "Override attivo: solo ${providerLabel(onlyProvider!!)} dove arriva.",
        style = MaterialTheme.typography.bodyMedium,
        color = Amber,
        modifier = Modifier.weight(1f),
      )
      FluidButton(
        text = "Torna alla fusione",
        style = FluidButtonStyle.Tinted,
        onClick = { scope.launch { deps.fusionSettings.setOnlyProvider(null) } },
      )
    }
  }

  // ------------------------------------------------------------------------ la classifica
  BlackSheetSectionTitle("Classifica per la zona")
  if (ready.ranking.isEmpty()) {
    BlackSheetNote("La classifica compare con le prime verifiche.")
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
                append("${rank.verifications} verifiche")
                if (rank.bestAt.isNotEmpty()) append(" · migliore su ${rank.bestAt.joinToString { variableLabel(it) }}")
                if (rank.providerId == onlyProvider) append(" · override")
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
              "MAE ${fmtMae(variable, score.decayedMae)}",
              if (score.learned) "${score.count} verifiche · pesa" else "${score.count} verifiche · sotto soglia",
            )
          }
          Spacer(Modifier.height(6.dp))
          val isOverride = rank.providerId == onlyProvider
          FluidButton(
            text = if (isOverride) "Torna alla fusione" else "Usa solo questo dove e' supportato",
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
      "La quota e' il peso appreso (1/(MAE+0,3)², MAE decaduto con dimezzamento a 14 giorni) " +
        "normalizzato fra chi ha verifiche, mediato sulle variabili: e' la stessa matematica che " +
        "comanda la fusione.",
    )
  }

  // ------------------------------------------------------------------------ la pioggia
  BlackSheetSectionTitle("La pioggia arriva? Il barometro alla pari")
  if (ready.rainEvent.isEmpty()) {
    BlackSheetNote(
      "Qui il barometro del telefono e i provider rispondono alla stessa domanda — piove nella " +
        "finestra? — e vengono giudicati con lo stesso metro (|probabilita' − esito|). Le prime " +
        "verifiche arrivano dopo qualche ora di uso.",
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
      BlackSheetNote("Nessuna verifica ancora su questa finestra.")
    } else {
      scores.forEachIndexed { index, score -> RainRow(index + 1, score) }
    }
    BlackSheetNote(
      "Errore medio |p − esito|: 0 e' perfetto, 0,5 e' tirare a indovinare. I provider parlano per " +
        "ore: la loro probabilita' sulla finestra e' il massimo delle orarie, la convenzione delle app meteo.",
    )
  }

  // ------------------------------------------------------------------- errore nel tempo
  BlackSheetSectionTitle("Errore nel tempo, ${Benchmark.DAYS} giorni")
  if (ready.dailyError.isEmpty()) {
    BlackSheetNote("La curva compare con le verifiche dei prossimi giorni.")
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
      BlackSheetNote("Servono almeno due giorni di verifiche per una curva.")
    } else {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("max ${fmtMae(variables[variableIndex], series.maxOf { it.mae })}", style = MaterialTheme.typography.labelSmall, color = Faint)
        Text("min ${fmtMae(variables[variableIndex], series.minOf { it.mae })}", style = MaterialTheme.typography.labelSmall, color = Faint)
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
        "Errore medio del giorno (fascia 0-6 ore), ${series.sumOf { it.count }} verifiche.",
        style = MaterialTheme.typography.bodySmall,
        color = Faint,
      )
    }
  }

  // ------------------------------------------------------------- ripartizione per variabile
  BlackSheetSectionTitle("Ripartizione per variabile")
  if (ready.byVariable.isEmpty()) {
    BlackSheetNote("Compare con le prime verifiche.")
  } else {
    ready.byVariable.forEach { (variable, scores) ->
      val best = scores.first()
      StatRow(
        variableLabel(variable),
        "${providerLabel(best.providerId)} · ${fmtMae(variable, best.decayedMae)}",
        "${scores.size} in gara",
      )
    }
    BlackSheetNote("Il migliore di ogni variabile nella fascia 0-6 ore, col suo errore medio decaduto.")
  }

  BlackSheetSectionTitle("Come funziona")
  BlackSheetNote(
    "Ogni giro semina previsioni e le giudica contro la mediana delle analisi dei provider " +
      "(quorum di tre): nessun giudice unico. I pesi sono appresi per variabile e fascia di " +
      "orizzonte; sotto la soglia comandano i priori per macro-regione, e nessun provider scende " +
      "mai sotto il pavimento. L'override \"usa solo questo\" vale dove il provider arriva: dove " +
      "non copre, la cascata riprende il volante.",
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
      Text("${score.count} verifiche", style = MaterialTheme.typography.bodySmall, color = Faint)
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

internal fun providerLabel(providerId: String): String = when (providerId) {
  RainEvent.LOCAL_BAROMETER_ID -> "Il tuo barometro"
  else -> ProviderRegistry.all.firstOrNull { it.id == providerId }?.label ?: providerId
}

internal fun variableLabel(variable: String): String = when (variable) {
  FusionVariables.TEMPERATURE -> "Temperatura"
  FusionVariables.PRESSURE_MSL -> "Pressione"
  FusionVariables.PRECIPITATION -> "Pioggia (mm)"
  FusionVariables.CLOUD_COVER -> "Nuvole"
  FusionVariables.WIND_SPEED -> "Vento"
  else -> variable
}

/** L'unita' del MAE per variabile: gradi, hPa, mm, punti percentuali, km/h. */
internal fun fmtMae(variable: String, mae: Double): String = when (variable) {
  FusionVariables.TEMPERATURE -> String.format(Locale.getDefault(), "%.1f°", mae)
  FusionVariables.PRESSURE_MSL -> String.format(Locale.getDefault(), "%.1f hPa", mae)
  FusionVariables.PRECIPITATION -> String.format(Locale.getDefault(), "%.2f mm", mae)
  FusionVariables.CLOUD_COVER -> String.format(Locale.getDefault(), "%.0f pt", mae)
  FusionVariables.WIND_SPEED -> String.format(Locale.getDefault(), "%.1f km/h", mae)
  else -> String.format(Locale.getDefault(), "%.2f", mae)
}

private fun fmtDay(millis: Long): String =
  DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

private fun fmtTime(millis: Long): String =
  DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

private fun fmtEpochDay(epochDay: Long): String =
  DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(LocalDate.ofEpochDay(epochDay))
