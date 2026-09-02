package dev.pampa.fluidweather.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.Moon
import dev.pampa.fluidweather.core.ui.BlackSheet
import dev.pampa.fluidweather.core.ui.Charts
import dev.pampa.fluidweather.core.ui.HomeWidget
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Il foglio NERO del piano ([BlackSheet]): sale dal basso, prende tutta la pagina, si chiude
 * con la X o trascinando giu'. Ogni widget ci porta il suo capitolo; le parti future dicono la
 * loro fase — il contenuto ricco a tutta pagina e' la fase 11b.
 */
@Composable
internal fun WidgetSheetHost(
  selected: HomeWidget?,
  state: HomeUiState,
  onDismiss: () -> Unit,
) {
  if (selected == null) return
  BlackSheet(title = selected.title, onDismiss = onDismiss) {
    when (selected) {
      HomeWidget.NOWCAST -> NowcastSheet(state)
      HomeWidget.HOURLY -> HourlySheet(state)
      HomeWidget.DAILY -> SheetNote("Il giorno per giorno esteso arriva con la fase 11b; intanto la tessera mostra i 10 giorni fusi.")
      HomeWidget.PRECIPITATION -> PrecipitationSheet(state)
      HomeWidget.PRESSURE -> PressureSheet(state)
      HomeWidget.AIR_QUALITY -> AirQualitySheet(state)
      HomeWidget.SUN -> SheetNote("Crepuscoli e calendario solare arrivano con la fase 11b; alba, tramonto e durata sono sulla tessera.")
      HomeWidget.MOON -> MoonSheet(state)
      HomeWidget.DETAILS -> SheetNote("Ogni misura estesa con la sua spiegazione arriva con la fase 11b; i valori del momento sono sulla tessera.")
    }
  }
}

// ------------------------------------------------------------------------------------- sezioni

@Composable
private fun SheetSectionTitle(text: String) {
  Text(
    text = text.uppercase(),
    style = MaterialTheme.typography.labelMedium,
    color = Color.White.copy(alpha = 0.55f),
    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
  )
}

@Composable
private fun SheetNote(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = Color.White.copy(alpha = 0.7f),
  )
}

@Composable
private fun NowcastSheet(state: HomeUiState) {
  val verdict = state.verdict
  if (verdict == null) {
    SheetNote("Il verdetto compare dopo ~13 ore di campionamento barometrico.")
    return
  }
  SheetSectionTitle("Probabilita' di precipitazione, 0-6 ore")
  verdict.windows.forEach { window ->
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 6.dp),
    ) {
      Text(window.window, Modifier.width(52.dp), color = Color.White.copy(alpha = 0.7f))
      Text(
        text = "${(window.probability * 100).toInt()}%",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.width(74.dp),
      )
      Text(
        text = "banda ${(window.probabilityLow * 100).toInt()}-${(window.probabilityHigh * 100).toInt()}%",
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.5f),
      )
    }
  }

  SheetSectionTitle("I fattori del verdetto (1-3h)")
  val factors = verdict.forWindow("1-3h")?.topFactors.orEmpty()
  if (factors.isEmpty()) {
    SheetNote("Nessun fattore fuori dal neutro: il verdetto e' la climatologia.")
  } else {
    factors.forEach { factor ->
      Row(Modifier.padding(vertical = 3.dp)) {
        Text(
          text = if (factor.contribution > 0) "▲" else "▼",
          color = if (factor.contribution > 0) Color(0xFF8FC7F0) else Color(0xFFF0C060),
          modifier = Modifier.width(24.dp),
        )
        Text(factor.name, color = Color.White.copy(alpha = 0.85f))
      }
    }
  }

  val cleaning = state.cleaning
  if (cleaning != null && cleaning.filtered.size >= 2) {
    SheetSectionTitle("Il segnale pulito (12 ore, livello del mare)")
    Charts.SmoothLine(
      values = cleaning.filtered.map { it.levelHpa },
      color = Color(0xFF8FC7F0),
      modifier = Modifier
        .fillMaxWidth()
        .height(90.dp),
    )
  }
  SheetSectionTitle("Storico delle verifiche")
  SheetNote("La pagella del nowcast contro cio' che e' successo davvero arriva col Benchmark (fase 13).")
}

@Composable
private fun HourlySheet(state: HomeUiState) {
  val zone = ZoneId.systemDefault()
  val hourFormatter = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())
  val now = System.currentTimeMillis()
  SheetSectionTitle("Ora per ora, tutto l'orizzonte fuso")
  state.fusedHours.filter { it.timestampMillis >= now }.take(48).forEach { hour ->
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp),
    ) {
      Text(
        text = hourFormatter.format(Instant.ofEpochMilli(hour.timestampMillis).atZone(zone)),
        modifier = Modifier.width(92.dp),
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.7f),
      )
      WeatherKindIcon(hour.kind, size = 18.dp)
      Spacer(Modifier.width(10.dp))
      Text(
        text = hour.values[FusionVariables.TEMPERATURE]?.value?.let { "${it.toInt()}°" } ?: "—",
        modifier = Modifier.width(44.dp),
      )
      val pop = hour.values[FusionVariables.PRECIP_PROBABILITY]?.value
      Text(
        text = if (pop != null && pop >= 5) "${pop.toInt()}%" else "",
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF8FC7F0),
      )
    }
  }
}

@Composable
private fun PrecipitationSheet(state: HomeUiState) {
  val now = System.currentTimeMillis()
  val hours = state.fusedHours.filter { it.timestampMillis >= now }
  SheetSectionTitle("Probabilita' fino all'orizzonte massimo dei servizi")
  Charts.ProbabilityBars(
    percentages = hours.take(48).map { it.values[FusionVariables.PRECIP_PROBABILITY]?.value ?: 0.0 },
    color = Color(0xFF8FC7F0),
    modifier = Modifier
      .fillMaxWidth()
      .height(90.dp),
  )
  Spacer(Modifier.height(10.dp))
  val totalMm = hours.sumOf { it.values[FusionVariables.PRECIPITATION]?.value ?: 0.0 }
  SheetNote(
    "Accumulo atteso sull'intero orizzonte: ${String.format(Locale.getDefault(), "%.1f", totalMm)} mm. " +
      "Ogni valore e' una fusione pesata dei provider che coprono questo punto.",
  )
}

@Composable
private fun PressureSheet(state: HomeUiState) {
  val cleaning = state.cleaning
  if (cleaning == null || cleaning.cleaned.isEmpty()) {
    SheetNote("Nessuna serie barometrica: il campionamento sta ancora accumulando.")
    return
  }
  SheetSectionTitle("Grezza alla stazione (12 ore)")
  Charts.SmoothLine(
    values = cleaning.cleaned.map { it.stationPressureHpa },
    color = Color(0xFFF0C060),
    modifier = Modifier
      .fillMaxWidth()
      .height(80.dp),
  )
  SheetSectionTitle("Pulita e ridotta al mare")
  Charts.SmoothLine(
    values = cleaning.filtered.map { it.levelHpa },
    color = Color(0xFF8FC7F0),
    modifier = Modifier
      .fillMaxWidth()
      .height(80.dp),
  )
  Spacer(Modifier.height(10.dp))
  val latest = cleaning.latest
  if (latest != null) {
    SheetNote(
      "Adesso: ${String.format(Locale.getDefault(), "%.1f", latest.levelHpa)} hPa al mare, " +
        "tendenza ${String.format(Locale.ROOT, "%+.2f", latest.trendHpaPerHour)} hPa/h " +
        "(±${String.format(Locale.ROOT, "%.2f", latest.trendSigmaHpaPerHour)}). " +
        "Le soglie classiche: 1,6 hPa/3h cambia il tempo, 3-4 hPa/3h aria di tempesta.",
    )
  }
}

@Composable
private fun AirQualitySheet(state: HomeUiState) {
  val air = state.airQuality
  if (air == null) {
    SheetNote("Qualita' dell'aria non disponibile per questo punto.")
    return
  }
  SheetSectionTitle("Indice europeo (EAQI)")
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = "${air.europeanAqi}",
      style = MaterialTheme.typography.displaySmall,
      color = aqiColor(air.band),
    )
    Spacer(Modifier.width(12.dp))
    Text(air.band.label, style = MaterialTheme.typography.titleMedium)
  }
  if (air.dominantPollutant != null && air.dominantValue != null) {
    Spacer(Modifier.height(6.dp))
    SheetNote(
      "Inquinante dominante: ${air.dominantPollutant} " +
        "(${String.format(Locale.getDefault(), "%.1f", air.dominantValue)} µg/m³).",
    )
  }
  val pollen = air.pollen
  SheetSectionTitle("Pollini")
  if (pollen == null) {
    SheetNote("I pollini sono serviti solo in Europa dal modello CAMS.")
  } else if (!pollen.any) {
    SheetNote("Nessun polline rilevante in questo momento.")
  } else {
    listOf(
      "Ontano" to pollen.alder,
      "Betulla" to pollen.birch,
      "Graminacee" to pollen.grass,
      "Olivo" to pollen.olive,
      "Ambrosia" to pollen.ragweed,
    ).forEach { (name, value) ->
      if (value != null && value > 0) {
        Row(Modifier.padding(vertical = 3.dp)) {
          Text(name, Modifier.weight(1f), color = Color.White.copy(alpha = 0.85f))
          Text("${value.toInt()} gr/m³", color = Color.White.copy(alpha = 0.6f))
        }
      }
    }
  }
}

@Composable
private fun MoonSheet(state: HomeUiState) {
  val now = System.currentTimeMillis()
  SheetSectionTitle("Adesso")
  Row(verticalAlignment = Alignment.CenterVertically) {
    Charts.MoonDisc(
      illuminatedFraction = Moon.illuminatedFraction(now),
      waxing = Moon.ageDays(now) < Moon.SYNODIC_MONTH_DAYS / 2,
      modifier = Modifier.size(96.dp),
    )
    Spacer(Modifier.width(16.dp))
    Column {
      Text(moonPhaseLabel(Moon.phase(now)), style = MaterialTheme.typography.titleMedium)
      Text(
        "Eta' del ciclo: ${String.format(Locale.getDefault(), "%.1f", Moon.ageDays(now))} giorni",
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.7f),
      )
      Text(
        "Illuminata al ${(Moon.illuminatedFraction(now) * 100).toInt()}%",
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.7f),
      )
    }
  }
  SheetSectionTitle("A seguire")
  SheetNote(
    "Sorgere della luna, scrubber dei giorni, distanza e calendario mensile arrivano con la " +
      "rifinitura: chiedono la posizione topocentrica vera, e meritano di essere fatti bene.",
  )
}
