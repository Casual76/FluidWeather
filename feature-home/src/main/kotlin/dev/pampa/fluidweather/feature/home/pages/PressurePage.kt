package dev.pampa.fluidweather.feature.home.pages

import androidx.compose.foundation.layout.Box
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
import dev.antigravity.fluidengine.ui.tutorial.fluidTutorialAnchor
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.PressureTrend
import dev.pampa.fluidweather.core.ui.TutorialScreen
import dev.pampa.fluidweather.core.ui.TutorialSlot
import dev.pampa.fluidweather.feature.home.HomeDependencies
import dev.pampa.fluidweather.feature.home.HomeUiState
import dev.pampa.fluidweather.nowcast.cleaning.CleaningResult
import dev.pampa.fluidweather.nowcast.cleaning.RejectionReason
import dev.pampa.fluidweather.nowcast.tide.TideSource
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.pampa.fluidweather.strings.R
import androidx.compose.ui.res.stringResource
import dev.pampa.fluidweather.core.ui.rememberUnitFormatter
import dev.pampa.fluidweather.strings.labelRes

private val PeriodHours = listOf(12, 24, 72, 168)

/**
 * Quante ore di previsione mostrare a chi guarda una localita' lontana.
 *
 * Due giorni: e' l'orizzonte su cui la pressione dei modelli dice ancora qualcosa di leggibile a
 * occhio (i passaggi di fronte durano ore, non minuti), e sono abbastanza punti perche' la curva
 * abbia una forma senza diventare una riga di rumore.
 */
private const val ProviderHours = 48

/**
 * La pagina della pressione: il livello adesso e le soglie classiche applicate alle ultime tre
 * ore, la serie grezza e quella pulita sul periodo scelto (dall'archivio del telefono, rigiocato
 * dalla stessa pipeline del banco), la marea sottratta, cosa e' stato scartato e da quale
 * stadio, e lo stato della taratura del dispositivo.
 */
@Composable
internal fun PressurePage(state: HomeUiState, deps: HomeDependencies) {
  // Su una localita' lontana il barometro di questo telefono non parla di quel posto: archivio,
  // marea sottratta e taratura sarebbero una pagina su un altro luogo. Al suo posto la pressione
  // che i provider prevedono qui, dichiarata per quello che e'.
  if (!state.barometerApplies) {
    ProviderPressurePage(state)
    return
  }
  var period by remember { mutableIntStateOf(0) }
  val units = rememberUnitFormatter()
  val periodLabels = listOf(stringResource(R.string.period_12h), stringResource(R.string.period_24h), stringResource(R.string.period_3d), stringResource(R.string.period_7d))
  val series by produceState<CleaningResult?>(initialValue = state.cleaning, period, state.cleaning) {
    value = if (period == 0) {
      state.cleaning
    } else {
      val now = System.currentTimeMillis()
      withContext(Dispatchers.Default) {
        // Dallo stesso posto da cui passa il verdetto: taratura, temperatura, quota di casa.
        // Prima questa pagina ricalcolava con i valori di default, e cambiare periodo spostava
        // il livello di tutta la curva senza che nei dati fosse cambiato niente.
        deps.nowcast.clean(now - PeriodHours[period] * 3_600_000L, state.temperatureC)
      }
    }
  }

  val latest = state.cleaning?.latest
  TutorialSlot(screen = TutorialScreen.WIDGET_PRESSURE)
  Box(Modifier.fluidTutorialAnchor("pressure_values")) {
    PageSection(stringResource(R.string.common_now))
  }
  if (latest == null) {
    PageNote(stringResource(R.string.pressure_no_series))
    state.latestRawPressureHpa?.let { StatRow(stringResource(R.string.pressure_last_raw), units.pressure(it, 1)) }
  } else {
    BigStat(units.pressureValue(latest.levelHpa, 1), units.pressureSymbol(), stringResource(R.string.pressure_msl_caption))
    Spacer(Modifier.height(8.dp))
    val threeHoursAgo = state.cleaning.filtered
      .minByOrNull { abs(it.timestampMillis - (latest.timestampMillis - 3 * 3_600_000L)) }
      ?.takeIf { abs(it.timestampMillis - (latest.timestampMillis - 3 * 3_600_000L)) <= 45 * 60_000L }
    val delta3h = threeHoursAgo?.let { latest.levelHpa - it.levelHpa }
    StatGrid(
      listOf(
        stringResource(R.string.pressure_raw_station) to (state.latestRawPressureHpa?.let { units.pressure(it, 1) } ?: "—"),
        stringResource(R.string.pressure_trend) to units.pressureRate(latest.trendHpaPerHour, 2),
        stringResource(R.string.pressure_uncertainty) to units.pressureSigma(latest.levelSigmaHpa),
        stringResource(R.string.pressure_last_3h) to (delta3h?.let { units.pressureDelta(it) } ?: "—"),
      ),
    )
    if (delta3h != null) {
      Spacer(Modifier.height(8.dp))
      Text(
        text = when {
          abs(delta3h) >= 3.0 -> stringResource(R.string.pressure_storm)
          abs(delta3h) >= 1.6 -> stringResource(R.string.pressure_change)
          else -> stringResource(R.string.pressure_normal)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (abs(delta3h) >= 1.6) PageAmber else Dim,
      )
    }
  }

  PageSection(stringResource(R.string.pressure_period))
  ChipRow(periodLabels, period) { period = it }
  Spacer(Modifier.height(6.dp))

  val shown = series
  if (shown == null || shown.cleaned.size < 2) {
    PageNote(
      if (period == 0) stringResource(R.string.pressure_not_enough_12h) else stringResource(R.string.pressure_computing),
    )
  } else {
    PageSection(stringResource(R.string.pressure_raw_station))
    CurveWithLabels(
      values = units.pressureSeries(shown.cleaned.map { it.stationPressureHpa }),
      labels = timeLabels(shown.cleaned.map { it.timestampMillis }),
      color = PageAmber,
      unit = " " + units.pressureSymbol(),
      decimals = units.pressureChartDecimals() + 1,
    )
    if (shown.filtered.size >= 2) {
      PageSection(stringResource(R.string.pressure_clean_msl))
      CurveWithLabels(
        values = units.pressureSeries(shown.filtered.map { it.levelHpa }),
        labels = timeLabels(shown.filtered.map { it.timestampMillis }),
        color = PageBlue,
        unit = " " + units.pressureSymbol(),
        decimals = units.pressureChartDecimals() + 1,
      )
    }
    StatRow(stringResource(R.string.pressure_points), "${shown.cleaned.size}", stringResource(R.string.pressure_of_aggregates, shown.cleaned.size + shown.rejected.size))
    val altitudes = shown.cleaned.mapNotNull { it.altitudeMeters }
    if (altitudes.isNotEmpty()) {
      StatRow(stringResource(R.string.pressure_altitude_used), stringResource(R.string.pressure_meters, fmt0(altitudes.average())), stringResource(R.string.pressure_altitude_source))
    }

    PageSection(stringResource(R.string.pressure_tide))
    val tide = shown.tide
    StatRow(stringResource(R.string.pressure_tide_model), tideSourceLabel(tide.source))
    StatRow(stringResource(R.string.pressure_tide_amplitudes), "S1 ${units.pressure(tide.s1AmplitudeHpa, 1)} · S2 ${units.pressure(tide.s2AmplitudeHpa, 1)}", stringResource(R.string.pressure_tide_kinds))
    if (tide.source != TideSource.NONE) {
      StatRow(stringResource(R.string.pressure_tide_push), units.pressureDelta(tide.tideAtLatestHpa, 2), stringResource(R.string.pressure_tide_subtracted))
    }
    PageNote(
      stringResource(R.string.pressure_tide_note),
    )

    PageSection(stringResource(R.string.pressure_rejected_title))
    val counts = shown.rejectionCounts()
    if (counts.isEmpty() && shown.discardedBursts.isEmpty()) {
      PageNote(stringResource(R.string.pressure_rejected_none))
    } else {
      counts.forEach { (reason, count) -> StatRow(rejectionLabel(reason), "$count") }
      if (shown.discardedBursts.isNotEmpty()) {
        StatRow(stringResource(R.string.pressure_rejected_variance), "${shown.discardedBursts.size}", stringResource(R.string.pressure_stage_1))
      }
    }
  }

  PageSection(stringResource(R.string.pressure_thresholds))
  PageNote(
    stringResource(R.string.pressure_thresholds_note, fmt1(PressureTrend.SURVEILLANCE_THRESHOLD_HPA_PER_HOUR)),
  )

  PageSection(stringResource(R.string.engine_calibration_title))
  PageNote(
    stringResource(R.string.pressure_bias_note),
  )
}

/**
 * La pressione di una localita' lontana: quella prevista dai provider, ora per ora.
 *
 * Non e' la pagina di sopra con meno righe: gli stadi 1-5 (pulizia, marea, taratura, incertezza)
 * sono il sensore di questo telefono, e il sensore e' dove sei tu. Qui c'e' un'altra cosa — una
 * previsione — e va detto, altrimenti si legge un numero credendolo una misura.
 */
@Composable
private fun ProviderPressurePage(state: HomeUiState) {
  val units = rememberUnitFormatter()
  // L'ora al passo dell'ora, come nella pagina orario: `System.currentTimeMillis()` in
  // composizione cambierebbe la chiave ad ogni fotogramma e rifarebbe tutto.
  val hourStamp = remember(state.fusedHours) { System.currentTimeMillis() / 3_600_000L }
  val series = remember(state.fusedHours, hourStamp) {
    val from = hourStamp * 3_600_000L - 3_600_000L
    state.fusedHours
      .asSequence()
      .filter { it.timestampMillis >= from }
      .mapNotNull { hour ->
        hour.values[FusionVariables.PRESSURE_MSL]?.value?.let { hour.timestampMillis to it }
      }
      .take(ProviderHours)
      .toList()
  }

  PageSection(stringResource(R.string.common_now))
  if (series.isEmpty()) {
    PageNote(stringResource(R.string.common_waiting_providers))
    PageSection(stringResource(R.string.pressure_provider_title))
    PageNote(stringResource(R.string.tile_barometer_here_only))
    return
  }

  val (firstMillis, current) = series.first()
  BigStat(
    units.pressureValue(current, 1),
    units.pressureSymbol(),
    stringResource(R.string.pressure_provider_caption),
  )
  Spacer(Modifier.height(8.dp))
  // "Fra tre ore" e non "nelle ultime tre": di una previsione si legge il seguito. La stessa
  // finestra delle soglie classiche, cosi' le due pagine si confrontano.
  val inThree = series.firstOrNull { it.first >= firstMillis + 3 * 3_600_000L }?.second
  val delta3h = inThree?.let { it - current }
  StatGrid(
    listOf(
      stringResource(R.string.pressure_provider_next3h) to (inThree?.let { units.pressure(it, 1) } ?: "\u2014"),
      stringResource(R.string.pressure_trend) to (delta3h?.let { units.pressureRate(it / 3.0, 2) } ?: "\u2014"),
      stringResource(R.string.pressure_provider_hours) to "${series.size}",
    ),
  )
  if (delta3h != null) {
    Spacer(Modifier.height(8.dp))
    Text(
      text = when {
        abs(delta3h) >= 3.0 -> stringResource(R.string.pressure_storm)
        abs(delta3h) >= 1.6 -> stringResource(R.string.pressure_change)
        else -> stringResource(R.string.pressure_normal)
      },
      style = MaterialTheme.typography.bodyMedium,
      color = if (abs(delta3h) >= 1.6) PageAmber else Dim,
    )
  }

  PageSection(stringResource(R.string.pressure_provider_series))
  CurveWithLabels(
    values = units.pressureSeries(series.map { it.second }),
    labels = timeLabels(series.map { it.first }),
    color = PageBlue,
    unit = " " + units.pressureSymbol(),
    decimals = units.pressureChartDecimals() + 1,
  )

  PageSection(stringResource(R.string.pressure_provider_title))
  PageNote(stringResource(R.string.pressure_provider_note))
}

@Composable
private fun rejectionLabel(reason: RejectionReason): String = stringResource(reason.labelRes())
