package dev.pampa.fluidweather.feature.home.pages

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pampa.fluidweather.core.model.PressureTrend
import dev.pampa.fluidweather.feature.home.HomeDependencies
import dev.pampa.fluidweather.feature.home.HomeUiState
import dev.pampa.fluidweather.nowcast.cleaning.CleaningResult
import dev.pampa.fluidweather.nowcast.cleaning.RejectionReason
import dev.pampa.fluidweather.nowcast.tide.TideSource
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val PeriodLabels = listOf("12 ore", "24 ore", "3 giorni", "7 giorni")
private val PeriodHours = listOf(12, 24, 72, 168)

/**
 * La pagina della pressione: il livello adesso e le soglie classiche applicate alle ultime tre
 * ore, la serie grezza e quella pulita sul periodo scelto (dall'archivio del telefono, rigiocato
 * dalla stessa pipeline del banco), la marea sottratta, cosa e' stato scartato e da quale
 * stadio, e lo stato della taratura del dispositivo.
 */
@Composable
internal fun PressurePage(state: HomeUiState, deps: HomeDependencies) {
  var period by remember { mutableIntStateOf(0) }
  val series by produceState<CleaningResult?>(initialValue = state.cleaning, period, state.cleaning) {
    value = if (period == 0) {
      state.cleaning
    } else {
      val now = System.currentTimeMillis()
      val samples = runCatching {
        deps.pressureRepository.samplesSince(now - PeriodHours[period] * 3_600_000L)
      }.getOrDefault(emptyList())
      withContext(Dispatchers.Default) {
        runCatching { deps.cleaningPipeline.process(samples, temperatureCelsius = state.temperatureC) }.getOrNull()
      }
    }
  }

  val latest = state.cleaning?.latest
  PageSection("Adesso")
  if (latest == null) {
    PageNote("Nessuna serie barometrica pulita: il campionamento sta ancora accumulando.")
    state.latestRawPressureHpa?.let { StatRow("Ultima lettura grezza", "${fmt1(it)} hPa") }
  } else {
    BigStat(fmt1(latest.levelHpa), "hPa", "al livello del mare, pulita e de-tidalizzata")
    Spacer(Modifier.height(8.dp))
    val threeHoursAgo = state.cleaning.filtered
      .minByOrNull { abs(it.timestampMillis - (latest.timestampMillis - 3 * 3_600_000L)) }
      ?.takeIf { abs(it.timestampMillis - (latest.timestampMillis - 3 * 3_600_000L)) <= 45 * 60_000L }
    val delta3h = threeHoursAgo?.let { latest.levelHpa - it.levelHpa }
    StatGrid(
      listOf(
        "Grezza alla stazione" to (state.latestRawPressureHpa?.let { "${fmt1(it)} hPa" } ?: "—"),
        "Tendenza" to String.format(Locale.ROOT, "%+.2f hPa/h", latest.trendHpaPerHour),
        "Incertezza" to "±${fmt1(latest.levelSigmaHpa)} hPa",
        "Ultime 3 ore" to (delta3h?.let { String.format(Locale.ROOT, "%+.1f hPa", it) } ?: "—"),
      ),
    )
    if (delta3h != null) {
      Spacer(Modifier.height(8.dp))
      Text(
        text = when {
          abs(delta3h) >= 3.0 -> "Aria di tempesta: oltre 3 hPa in tre ore e' la soglia delle stazioni da polso."
          abs(delta3h) >= 1.6 -> "Cambio di tempo in vista: 1,6 hPa in tre ore e' la soglia di Zambretti."
          else -> "Dentro la normale variabilita': meno di 1,6 hPa in tre ore."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (abs(delta3h) >= 1.6) PageAmber else Dim,
      )
    }
  }

  PageSection("Periodo")
  ChipRow(PeriodLabels, period) { period = it }
  Spacer(Modifier.height(6.dp))

  val shown = series
  if (shown == null || shown.cleaned.size < 2) {
    PageNote(
      if (period == 0) "Non ci sono abbastanza punti nelle ultime 12 ore." else "Calcolo sull'archivio, o troppo pochi punti nel periodo.",
    )
  } else {
    PageSection("Grezza alla stazione")
    CurveWithLabels(
      values = shown.cleaned.map { it.stationPressureHpa },
      labels = timeLabels(shown.cleaned.map { it.timestampMillis }),
      color = PageAmber,
      unit = " hPa",
      decimals = 1,
    )
    if (shown.filtered.size >= 2) {
      PageSection("Pulita e ridotta al mare")
      CurveWithLabels(
        values = shown.filtered.map { it.levelHpa },
        labels = timeLabels(shown.filtered.map { it.timestampMillis }),
        color = PageBlue,
        unit = " hPa",
        decimals = 1,
      )
    }
    StatRow("Punti nel periodo", "${shown.cleaned.size}", "su ${shown.cleaned.size + shown.rejected.size} aggregati")
    val altitudes = shown.cleaned.mapNotNull { it.altitudeMeters }
    if (altitudes.isNotEmpty()) {
      StatRow("Quota usata per la riduzione", "${fmt0(altitudes.average())} m", "dal GPS, media del periodo")
    }

    PageSection("Marea atmosferica")
    val tide = shown.tide
    StatRow("Modello", tideSourceLabel(tide.source))
    StatRow("Ampiezze", "S1 ${fmt1(tide.s1AmplitudeHpa)} · S2 ${fmt1(tide.s2AmplitudeHpa)} hPa", "diurna · semidiurna")
    if (tide.source != TideSource.NONE) {
      StatRow("Spinta adesso", String.format(Locale.ROOT, "%+.2f hPa", tide.tideAtLatestHpa), "sottratta dal segnale")
    }
    PageNote(
      "La marea atmosferica S1/S2 vale da sola fino a 1-1,5 hPa e culmina alle 10 e alle 22 solari: " +
        "senza sottrarla, il calo pomeridiano fisiologico diventa un falso fronte.",
    )

    PageSection("Cosa e' stato scartato")
    val counts = shown.rejectionCounts()
    if (counts.isEmpty() && shown.discardedBursts.isEmpty()) {
      PageNote("Niente: ogni punto del periodo e' passato dagli stadi 1 e 2.")
    } else {
      counts.forEach { (reason, count) -> StatRow(rejectionLabel(reason), "$count") }
      if (shown.discardedBursts.isNotEmpty()) {
        StatRow("Raffiche con varianza anomala", "${shown.discardedBursts.size}", "stadio 1")
      }
    }
  }

  PageSection("Le soglie")
  PageNote(
    "1,6 hPa in tre ore e' il \"cambio di tempo\" di Zambretti; 3-4 hPa in tre ore e' aria di " +
      "tempesta. La marcia di sorveglianza del sensore si accende a " +
      "${fmt1(PressureTrend.SURVEILLANCE_THRESHOLD_HPA_PER_HOUR)} hPa/h sulla tendenza pulita.",
  )

  PageSection("Taratura del dispositivo")
  PageNote(
    "Bias del sensore non ancora stimato: la taratura iniziale (prima raffica confrontata con la " +
      "stazione piu' vicina) arriva con l'onboarding, fase 15. Finche' non c'e', per il nowcast " +
      "conta la tendenza, e un offset costante non la tocca. Lo scarto fra barometro e provider " +
      "e' gia' visibile nella pagina Dettagli.",
  )
}

private fun rejectionLabel(reason: RejectionReason): String = when (reason) {
  RejectionReason.ANOMALOUS_VARIANCE -> "Varianza anomala nella raffica"
  RejectionReason.VEHICLE -> "In veicolo (auto, bici, aereo)"
  RejectionReason.ALTITUDE_CHANGE -> "Cambio di quota (scale, ascensore)"
  else -> "Salto non meteorologico"
}
