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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dehaze
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.ApparentTemperature
import dev.pampa.fluidweather.core.model.AqiBand
import dev.pampa.fluidweather.core.model.Moon
import dev.pampa.fluidweather.core.model.MoonPhase
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.core.model.nearestHour
import dev.pampa.fluidweather.core.ui.Charts
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.pampa.fluidweather.core.ui.HomeWidget
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import dev.pampa.fluidweather.strings.R
import androidx.compose.ui.res.stringResource
import dev.pampa.fluidweather.core.ui.rememberUnitFormatter
import dev.pampa.fluidweather.core.ui.stageText
import dev.pampa.fluidweather.strings.TimeFormats
import dev.pampa.fluidweather.strings.compassPoint
import dev.pampa.fluidweather.strings.featureLabelRes
import dev.pampa.fluidweather.strings.labelRes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

/**
 * Il contenuto vero di ogni tessera. Icone: set Material come segnaposto DICHIARATO — le
 * Meteocons piene e colorate scelte dall'utente arrivano con la loro importazione dedicata.
 */
@Composable
internal fun WidgetTileContent(widget: HomeWidget, state: HomeUiState) {
  when (widget) {
    HomeWidget.NOWCAST -> NowcastTile(state)
    HomeWidget.HOURLY -> HourlyTile(state)
    HomeWidget.DAILY -> DailyTile(state)
    HomeWidget.PRECIPITATION -> PrecipitationTile(state)
    HomeWidget.PRESSURE -> PressureTile(state)
    HomeWidget.AIR_QUALITY -> AirQualityTile(state)
    HomeWidget.SUN -> SunTile(state)
    HomeWidget.MOON -> MoonTile(state)
    HomeWidget.DETAILS -> DetailsTile(state)
  }
}

// ------------------------------------------------------------------------------------ nowcast

@Composable
private fun NowcastTile(state: HomeUiState) {
  val verdict = state.verdict
  if (verdict == null) {
    val readiness = state.readiness
    if (readiness == null) {
      EmptyTileBody(stringResource(R.string.tile_nowcast_waiting))
      return
    }
    // La barra unica chiesta sul telefono: raffica iniziale, poi le ore di storia.
    Spacer(Modifier.height(6.dp))
    Text(readiness.stageText(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(8.dp))
    FluidProgressBar(progress = { readiness.overallFraction })
    Spacer(Modifier.height(6.dp))
    Text(
      text = if (readiness.calibrationRunning) {
        stringResource(R.string.tile_calibration_running)
      } else {
        stringResource(R.string.tile_history_needed, readiness.requiredHours.toInt())
      },
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
    )
    return
  }
  // Quello che si vede batte quello che si prevede. Con la pioggia in corso, la tessera lo dice
  // e basta: il verdetto resta sotto, nelle tre finestre, dove serve a sapere quanto durera'.
  val observation = state.nowcastExplanation?.observation?.takeIf { it.rainingNow }
  val rate = observation?.intensityMmPerHour
  Text(
    text = when {
      observation == null -> when (verdict.level) {
        AlertLevel.QUIETE -> stringResource(R.string.tile_level_quiet)
        AlertLevel.SORVEGLIANZA -> stringResource(R.string.tile_level_watch)
        AlertLevel.ALLERTA -> stringResource(R.string.tile_level_alert)
      }
      rate != null -> stringResource(R.string.nowcast_raining_now_rate, String.format("%.1f", rate))
      else -> stringResource(R.string.nowcast_raining_now)
    },
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurface,
  )
  Spacer(Modifier.height(10.dp))
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    verdict.windows.forEach { window ->
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.weight(1f),
      ) {
        Text(
          text = window.window,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Text(
          text = "${(window.probability * 100).toInt()}%",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = "${(window.probabilityLow * 100).toInt()}-${(window.probabilityHigh * 100).toInt()}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        )
      }
    }
  }
  val topFactor = verdict.windows.firstOrNull { it.window == "1-3h" }?.topFactors?.firstOrNull()
  if (observation != null) {
    Spacer(Modifier.height(8.dp))
    Text(
      text = stringResource(R.string.nowcast_seen_by, observation.source),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
    )
  } else if (topFactor != null) {
    Spacer(Modifier.height(8.dp))
    Text(
      text = stringResource(R.string.tile_top_factor, stringResource(featureLabelRes(topFactor.name))),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
    )
  }
}

// ------------------------------------------------------------------------------------- orario

/** Larghezza di una colonna della striscia: ora, icona, gradi e pioggia stanno in 54 dp. */
private val HourColumnWidth = 54.dp

/** Quanto la curva della temperatura puo' salire e scendere fra il minimo e il massimo. */
private val HourCurveHeight = 34.dp

/** L'altezza della riga dei gradi che cavalcano la curva: il centro del testo e' sulla linea. */
private val HourTempTextHeight = 22.dp

/**
 * La striscia oraria: UNO scorrimento solo, con la curva della temperatura dentro — i gradi
 * di ogni ora sono appoggiati sulla curva, cosi' la curva dice da sola cos'e' e scorre con le
 * ore. La prima versione la disegnava fissa sotto la striscia, e sul telefono non si capiva
 * cosa fosse ne' perche' non si muovesse (2026-09-02).
 */
@Composable
private fun HourlyTile(state: HomeUiState) {
  val now = System.currentTimeMillis()
  val hours = state.fusedHours
    .filter {
      it.timestampMillis >= now - 30 * 60_000L && it.values[FusionVariables.TEMPERATURE] != null
    }
    .take(24)
  if (hours.isEmpty()) {
    EmptyTileBody(stringResource(R.string.common_waiting_providers))
    return
  }
  val zone = ZoneId.systemDefault()
  val units = rememberUnitFormatter()
  val temps = hours.map { it.values.getValue(FusionVariables.TEMPERATURE).value }
  val min = temps.min()
  val max = temps.max()
  val span = (max - min).takeIf { it > 1e-9 } ?: 1.0
  val sunrise = state.sunTimesToday?.sunriseMillis
  val sunset = state.sunTimesToday?.sunsetMillis
  val onSurface = MaterialTheme.colorScheme.onSurface
  val textShadow = Shadow(color = Color.Black.copy(alpha = 0.55f), blurRadius = 6f)
  // Con la scala del testo di sistema i gradi crescono: la riga e le colonne crescono con loro.
  val density = LocalDensity.current
  val tempTextHeight = with(density) { MaterialTheme.typography.titleSmall.lineHeight.toDp() }.coerceAtLeast(HourTempTextHeight)
  val hourColumnWidth = HourColumnWidth * density.fontScale.coerceIn(1f, 1.6f)
  val insetPx = with(density) { tempTextHeight.toPx() / 2f }

  Column(Modifier.horizontalScroll(rememberScrollState())) {
    // Riga 1: l'ora e l'icona (alba e tramonto prendono il posto dell'icona nella loro ora).
    Row {
      hours.forEach { hour ->
        Column(Modifier.width(hourColumnWidth), horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            text = TimeFormats.hour(hour.timestampMillis, zone),
            style = MaterialTheme.typography.labelSmall,
            color = onSurface.copy(alpha = 0.6f),
          )
          Spacer(Modifier.height(4.dp))
          val isSunEdge = listOfNotNull(sunrise, sunset)
            .any { abs(it - hour.timestampMillis) < 30 * 60_000L }
          if (isSunEdge) {
            Icon(
              imageVector = Icons.Rounded.WbTwilight,
              contentDescription = null,
              tint = Color(0xFFF0A860),
              modifier = Modifier.size(20.dp),
            )
          } else {
            WeatherKindIcon(hour.kind, size = 20.dp)
          }
        }
      }
    }
    Spacer(Modifier.height(4.dp))

    // Riga 2: la curva, con i gradi sopra. Il margine della curva e' meta' altezza del testo,
    // cosi' il punto di ogni ora coincide col centro del suo numero.
    Box(
      Modifier
        .width(hourColumnWidth * hours.size)
        .height(HourCurveHeight + tempTextHeight),
    ) {
      Charts.SmoothLine(
        values = temps,
        color = MaterialTheme.colorScheme.primary,
        insetPx = insetPx,
        modifier = Modifier
          .matchParentSize()
          .padding(horizontal = hourColumnWidth / 2),
      )
      Row {
        temps.forEach { temperature ->
          val normalized = ((temperature - min) / span).toFloat()
          Column(
            modifier = Modifier
              .width(hourColumnWidth)
              .padding(top = HourCurveHeight * (1f - normalized)),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text(
              text = units.degrees(temperature),
              style = MaterialTheme.typography.titleSmall.copy(shadow = textShadow),
              color = onSurface,
              modifier = Modifier.height(tempTextHeight),
            )
          }
        }
      }
    }

    // Riga 3: la probabilita' di pioggia, solo dove conta.
    Row {
      hours.forEach { hour ->
        val pop = hour.values[FusionVariables.PRECIP_PROBABILITY]?.value
        Column(Modifier.width(hourColumnWidth), horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            text = if (pop != null && pop >= 5) "${pop.toInt()}%" else " ",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF8FC7F0),
          )
        }
      }
    }
  }
}

// --------------------------------------------------------------------------------- giornaliero

/** Il giornaliero della home, precalcolato: raggruppare 246 ore non e' lavoro da composizione. */
private class DailyTileModel(
  val days: List<Triple<java.time.LocalDate, List<Double>, dev.pampa.fluidweather.core.model.WeatherKind?>>,
  val periodMin: Double,
  val periodMax: Double,
  val today: java.time.LocalDate,
) {
  companion object {
    fun of(hours: List<dev.pampa.fluidweather.core.model.FusedHour>, zone: ZoneId, nowMillis: Long): DailyTileModel? {
      val byDay = hours
        .groupBy { Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDate() }
        .toSortedMap()
        .entries
        .take(10)
      if (byDay.isEmpty()) return null
      val days = byDay.mapNotNull { (date, list) ->
        val temps = list.mapNotNull { it.values[FusionVariables.TEMPERATURE]?.value }
        if (temps.isEmpty()) {
          null
        } else {
          val kind = list.mapNotNull { it.kind }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
          Triple(date, temps, kind)
        }
      }
      val all = days.flatMap { it.second }
      if (all.isEmpty()) return null
      return DailyTileModel(days, all.min(), all.max(), Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate())
    }
  }
}

@Composable
private fun DailyTile(state: HomeUiState) {
  val zone = remember { ZoneId.systemDefault() }
  // L'ora al passo dell'ora, non del fotogramma: `System.currentTimeMillis()` in composizione
  // rendeva la chiave sempre diversa, e con lei si rifacevano raggruppamento e ordinamento.
  val hourStamp = remember(state.fusedHours) { System.currentTimeMillis() / 3_600_000L }
  val model = remember(state.fusedHours, zone, hourStamp) {
    DailyTileModel.of(state.fusedHours, zone, hourStamp * 3_600_000L)
  }
  if (model == null) {
    EmptyTileBody(stringResource(R.string.common_waiting_providers))
    return
  }
  val periodMin = model.periodMin
  val periodMax = model.periodMax
  val units = rememberUnitFormatter()
  val today = model.today
  val dayFormatter = remember { DateTimeFormatter.ofPattern("EEE", Locale.getDefault()) }

  Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
    model.days.forEach { (date, temps, kind) ->
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = if (date == today) stringResource(R.string.common_today) else dayFormatter.format(date).replaceFirstChar { it.uppercase() },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.width(38.dp),
        )
        WeatherKindIcon(kind, size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Text(
          text = units.degrees(temps.min()),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
          modifier = Modifier.width(26.dp),
        )
        Charts.RangeBar(
          periodMin = periodMin,
          periodMax = periodMax,
          dayMin = temps.min(),
          dayMax = temps.max(),
          nowValue = if (date == today) state.temperatureC else null,
          trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
          barBrushColors = listOf(Color(0xFF6FAAF0), Color(0xFFF0C060)),
          modifier = Modifier
            .weight(1f)
            .height(5.dp),
        )
        Text(
          text = units.degrees(temps.max()),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier
            .width(30.dp)
            .padding(start = 6.dp),
        )
      }
    }
  }
}

// ------------------------------------------------------------------------------ precipitazioni

@Composable
private fun PrecipitationTile(state: HomeUiState) {
  val now = System.currentTimeMillis()
  val next6 = state.fusedHours.filter { it.timestampMillis >= now }.take(6)
  if (next6.isEmpty()) {
    EmptyTileBody(stringResource(R.string.common_waiting_providers))
    return
  }
  val pops = next6.map { it.values[FusionVariables.PRECIP_PROBABILITY]?.value ?: 0.0 }
  val totalMm = next6.sumOf { it.values[FusionVariables.PRECIPITATION]?.value ?: 0.0 }
  val zone = ZoneId.systemDefault()
  val units = rememberUnitFormatter()

  Text(
    text = if (totalMm >= 0.1) {
      stringResource(R.string.tile_rain_6h, units.precipitation(totalMm))
    } else {
      stringResource(R.string.tile_no_rain_6h)
    },
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurface,
  )
  Spacer(Modifier.height(10.dp))
  val barsDescription = stringResource(
    R.string.a11y_chart_probability,
    next6.zip(pops).joinToString(", ") { (hour, pop) -> "${TimeFormats.hour(hour.timestampMillis, zone)} ${pop.toInt()}%" },
  )
  Charts.ProbabilityBars(
    percentages = pops,
    color = Color(0xFF8FC7F0),
    modifier = Modifier
      .fillMaxWidth()
      .height(52.dp)
      .semantics { contentDescription = barsDescription },
  )
  Spacer(Modifier.height(4.dp))
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    next6.forEach { hour ->
      Text(
        text = TimeFormats.hour(hour.timestampMillis, zone),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
      )
    }
  }
}

// ---------------------------------------------------------------------- pressione (compatto)

@Composable
private fun PressureTile(state: HomeUiState) {
  // Fuori casa il sensore non c'entra: la pressione al livello del mare e' una variabile che i
  // provider danno ora per ora, e la si dichiara per quello che e'.
  if (!state.barometerApplies) {
    ProviderPressureBody(state)
    return
  }
  val raw = state.latestRawPressureHpa
  if (raw == null) {
    EmptyTileBody(stringResource(R.string.tile_no_barometer_reading))
    return
  }
  val units = rememberUnitFormatter()
  Spacer(Modifier.height(6.dp))
  Text(
    text = units.pressureValue(raw, 1),
    fontSize = 34.sp,
    fontWeight = FontWeight.Light,
    color = MaterialTheme.colorScheme.onSurface,
  )
  Text(
    text = stringResource(R.string.tile_pressure_raw, units.pressureSymbol()),
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
  )
  val trend = state.cleaning?.latest?.trendHpaPerHour
  if (trend != null) {
    Spacer(Modifier.height(8.dp))
    Text(
      text = when {
        trend <= -0.5 -> stringResource(R.string.tile_trend_falling, units.pressureRate(trend))
        trend >= 0.5 -> stringResource(R.string.tile_trend_rising, units.pressureRate(trend))
        else -> stringResource(R.string.tile_trend_steady)
      },
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
    )
  }
}

// ------------------------------------------------------------------- qualita' aria (compatto)

internal fun aqiColor(band: AqiBand): Color = when (band) {
  AqiBand.GOOD -> Color(0xFF6FD58C)
  AqiBand.FAIR -> Color(0xFFB6D96A)
  AqiBand.MODERATE -> Color(0xFFF0C060)
  AqiBand.POOR -> Color(0xFFF09060)
  AqiBand.VERY_POOR -> Color(0xFFE06060)
  AqiBand.EXTREMELY_POOR -> Color(0xFFB05070)
}

@Composable
private fun AirQualityTile(state: HomeUiState) {
  val air = state.airQuality
  if (air == null) {
    EmptyTileBody(stringResource(R.string.tile_air_unavailable))
    return
  }
  Spacer(Modifier.height(6.dp))
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = "${air.europeanAqi}",
      fontSize = 34.sp,
      fontWeight = FontWeight.Light,
      color = aqiColor(air.band),
    )
    Spacer(Modifier.width(8.dp))
    Column {
      Text(
        text = stringResource(air.band.labelRes()),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = "EAQI",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
      )
    }
  }
  val dominant = air.dominantPollutant
  if (dominant != null) {
    Spacer(Modifier.height(8.dp))
    Text(
      text = stringResource(R.string.tile_air_dominant, dominant),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    )
  }
}

// --------------------------------------------------------------------------------------- sole

@Composable
private fun SunTile(state: HomeUiState) {
  val times = state.sunTimesToday
  if (times?.sunriseMillis == null || times.sunsetMillis == null) {
    EmptyTileBody(
      if (state.hasLocation) stringResource(R.string.tile_sun_no_crossing) else stringResource(R.string.tile_needs_location),
    )
    return
  }
  val zone = ZoneId.systemDefault()
  val now = System.currentTimeMillis()
  val progress = ((now - times.sunriseMillis!!).toFloat() /
    (times.sunsetMillis!! - times.sunriseMillis!!).toFloat()).takeIf { it in 0f..1f }

  val arcDescription = stringResource(
    R.string.a11y_sun_arc,
    TimeFormats.time(times.sunriseMillis!!, zone),
    TimeFormats.time(times.sunsetMillis!!, zone),
  )
  Charts.SunArc(
    dayProgress = progress,
    arcColor = MaterialTheme.colorScheme.onSurface,
    sunColor = Color(0xFFF6C750),
    modifier = Modifier
      .fillMaxWidth()
      .height(88.dp)
      .semantics { contentDescription = arcDescription },
  )
  Spacer(Modifier.height(6.dp))
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Column {
      Text(stringResource(R.string.sun_rise), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
      Text(
        TimeFormats.time(times.sunriseMillis!!, zone),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      val length = state.dayLengthTodayMillis
      val delta = if (length != null && state.dayLengthYesterdayMillis != null) {
        (length - state.dayLengthYesterdayMillis!!) / 60_000L
      } else {
        null
      }
      Text(stringResource(R.string.sun_length), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
      Text(
        text = length?.let { stringResource(R.string.duration_hm, it / 3_600_000L, (it / 60_000L) % 60) } ?: "—",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (delta != null && delta != 0L) {
        Text(
          text = if (delta > 0) stringResource(R.string.sun_delta_plus, delta) else stringResource(R.string.sun_delta, delta),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
      }
    }
    Column(horizontalAlignment = Alignment.End) {
      Text(stringResource(R.string.sun_set), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
      Text(
        TimeFormats.time(times.sunsetMillis!!, zone),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

// --------------------------------------------------------------------------------------- luna

@Composable
internal fun moonPhaseLabel(phase: MoonPhase): String = stringResource(phase.labelRes())

@Composable
private fun MoonTile(state: HomeUiState) {
  val now = System.currentTimeMillis()
  val phase = Moon.phase(now)
  val illumination = Moon.illuminatedFraction(now)
  val waxing = Moon.ageDays(now) < Moon.SYNODIC_MONTH_DAYS / 2
  val nextFull = Moon.nextFullMoonMillis(now)
  val zone = ZoneId.systemDefault()

  val moonDescription = stringResource(R.string.a11y_moon, moonPhaseLabel(phase))
  Row(verticalAlignment = Alignment.CenterVertically) {
    Charts.MoonDisc(
      illuminatedFraction = illumination,
      waxing = waxing,
      modifier = Modifier
        .size(64.dp)
        .semantics { contentDescription = moonDescription },
    )
    Spacer(Modifier.width(14.dp))
    Column {
      Text(
        text = moonPhaseLabel(phase),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = stringResource(R.string.moon_illuminated, (illumination * 100).toInt()),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
      )
      Text(
        text = stringResource(R.string.moon_full_in, TimeFormats.shortDate(nextFull, zone)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
      )
    }
  }
}

// ----------------------------------------------------------------------------------- dettagli

@Composable
private fun DetailsTile(state: HomeUiState) {
  val now = System.currentTimeMillis()
  val hour = state.fusedHours.nearestHour(now)?.first
  if (hour == null) {
    EmptyTileBody(stringResource(R.string.common_waiting_providers))
    return
  }
  fun value(variable: String): Double? = hour.values[variable]?.value
  val units = rememberUnitFormatter()
  val context = LocalContext.current

  val wind = value(FusionVariables.WIND_SPEED)
  val humidity = value(FusionVariables.HUMIDITY)
  val apparent = if (state.temperatureC != null && humidity != null && wind != null) {
    ApparentTemperature.celsius(state.temperatureC, humidity, wind)
  } else {
    null
  }

  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row {
      DetailStat(stringResource(R.string.common_wind), wind?.let { units.wind(it) }, Modifier.weight(1f)) {
        val direction = hour.windDirectionDeg
        if (direction != null) {
          Icon(
            imageVector = Icons.Rounded.Navigation,
            // "da NE", non "NE": la freccia punta DOVE VA il vento, il nome cardinale dice
            // DA DOVE VIENE. Senza la preposizione grafica e voce si contraddicono.
            contentDescription = context.getString(R.string.a11y_wind_from, compassPoint(context.resources, direction)),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            // L'icona punta in su; il vento VIENE da direction: la freccia indica dove va.
            modifier = Modifier
              .size(14.dp)
              .rotate((direction + 180).toFloat()),
          )
        }
      }
      DetailStat(stringResource(R.string.common_humidity), humidity?.let { "${it.toInt()}%" }, Modifier.weight(1f))
      DetailStat("UV", value(FusionVariables.UV_INDEX)?.let { String.format(Locale.ROOT, "%.0f", it) }, Modifier.weight(1f))
    }
    Row {
      DetailStat(
        stringResource(R.string.common_visibility),
        value(FusionVariables.VISIBILITY)?.let { units.visibilityMeters(it) },
        Modifier.weight(1f),
      )
      DetailStat(stringResource(R.string.details_dew_short), value(FusionVariables.DEW_POINT)?.let { units.degrees(it) }, Modifier.weight(1f))
      DetailStat(stringResource(R.string.common_feels_like), apparent?.let { units.degrees(it) }, Modifier.weight(1f))
    }
  }
}

@Composable
private fun DetailStat(
  label: String,
  value: String?,
  modifier: Modifier = Modifier,
  trailing: (@Composable () -> Unit)? = null,
) {
  // Un solo nodo per TalkBack: "Vento, 12 km/h" invece di due frammenti.
  Column(modifier.semantics(mergeDescendants = true) {}) {
    Text(
      text = label.uppercase(),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = value ?: "—",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (trailing != null) {
        Spacer(Modifier.width(4.dp))
        trailing()
      }
    }
  }
}

// -------------------------------------------------------------------------------------- utili

@Composable
private fun EmptyTileBody(message: String) {
  Spacer(Modifier.height(8.dp))
  Text(
    text = message,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
  )
}

/** Segnaposto Material dichiarato: le Meteocons colorate arrivano con la loro importazione. */
@Composable
internal fun WeatherKindIcon(kind: WeatherKind?, size: androidx.compose.ui.unit.Dp) {
  val (icon, tint) = kindIconAndTint(kind)
  Icon(
    imageVector = icon,
    contentDescription = kind?.labelRes()?.let { stringResource(it) },
    tint = tint,
    modifier = Modifier.size(size),
  )
}

private fun kindIconAndTint(kind: WeatherKind?): Pair<ImageVector, Color> = when (kind) {
  WeatherKind.CLEAR -> Icons.Rounded.WbSunny to Color(0xFFF6C750)
  WeatherKind.MOSTLY_CLEAR -> Icons.Rounded.WbSunny to Color(0xFFE8C878)
  WeatherKind.PARTLY_CLOUDY -> Icons.Rounded.WbCloudy to Color(0xFFD8DEE8)
  WeatherKind.CLOUDY -> Icons.Rounded.Cloud to Color(0xFFB8C2CE)
  WeatherKind.FOG -> Icons.Rounded.Dehaze to Color(0xFFAEB6C2)
  WeatherKind.DRIZZLE -> Icons.Rounded.Grain to Color(0xFF8FC7F0)
  WeatherKind.RAIN -> Icons.Rounded.WaterDrop to Color(0xFF6FAAE8)
  WeatherKind.HEAVY_RAIN -> Icons.Rounded.WaterDrop to Color(0xFF4A88CC)
  WeatherKind.SLEET -> Icons.Rounded.Grain to Color(0xFF9FBADD)
  WeatherKind.SNOW -> Icons.Rounded.AcUnit to Color(0xFFDCE8F4)
  WeatherKind.HEAVY_SNOW -> Icons.Rounded.AcUnit to Color(0xFFC8DCF0)
  WeatherKind.THUNDERSTORM -> Icons.Rounded.Thunderstorm to Color(0xFFB99AE8)
  else -> Icons.Rounded.HelpOutline to Color(0xFF9AA4B0)
}

/** La pressione di una localita' lontana: quella fusa dei provider, col nome della fonte. */
@Composable
private fun ProviderPressureBody(state: HomeUiState) {
  val nowMillis = remember(state.fusedHours) { System.currentTimeMillis() }
  val hour = remember(state.fusedHours, nowMillis) {
    state.fusedHours.minByOrNull { kotlin.math.abs(it.timestampMillis - nowMillis) }
  }
  val pressure = hour?.values?.get(FusionVariables.PRESSURE_MSL)?.value
  if (pressure == null) {
    EmptyTileBody(stringResource(R.string.common_waiting_providers))
    return
  }
  val units = rememberUnitFormatter()
  Spacer(Modifier.height(6.dp))
  Text(
    text = units.pressureValue(pressure, 1),
    fontSize = 34.sp,
    fontWeight = FontWeight.Light,
    color = MaterialTheme.colorScheme.onSurface,
  )
  Text(
    text = stringResource(R.string.tile_pressure_provider, units.pressureSymbol()),
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
  )
  Spacer(Modifier.height(8.dp))
  Text(
    text = stringResource(R.string.tile_barometer_here_only),
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
  )
}
