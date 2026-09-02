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
      EmptyTileBody("Il barometro sta ancora accumulando storia (~13 ore).")
      return
    }
    // La barra unica chiesta sul telefono: raffica iniziale, poi le ore di storia.
    Spacer(Modifier.height(6.dp))
    Text(readiness.stageLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(8.dp))
    FluidProgressBar(progress = { readiness.overallFraction })
    Spacer(Modifier.height(6.dp))
    Text(
      text = if (readiness.calibrationRunning) {
        "La raffica di taratura gira in sottofondo: puoi chiudere l'app."
      } else {
        "Il modello vuole ${readiness.requiredHours.toInt()} ore di segnale pulito prima del primo verdetto."
      },
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
    )
    return
  }
  Text(
    text = when (verdict.level) {
      AlertLevel.QUIETE -> "Quiete: nessun segnale fuori dalla climatologia"
      AlertLevel.SORVEGLIANZA -> "Sorveglianza: qualcosa si muove"
      AlertLevel.ALLERTA -> "Allerta: precipitazione probabile a breve"
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
  if (topFactor != null) {
    Spacer(Modifier.height(8.dp))
    Text(
      text = "Fattore principale: ${topFactor.name}",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
    )
  }
}

// ------------------------------------------------------------------------------------- orario

private val HourFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH")

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
    EmptyTileBody("In attesa dei provider…")
    return
  }
  val zone = ZoneId.systemDefault()
  val temps = hours.map { it.values.getValue(FusionVariables.TEMPERATURE).value }
  val min = temps.min()
  val max = temps.max()
  val span = (max - min).takeIf { it > 1e-9 } ?: 1.0
  val sunrise = state.sunTimesToday?.sunriseMillis
  val sunset = state.sunTimesToday?.sunsetMillis
  val onSurface = MaterialTheme.colorScheme.onSurface
  val textShadow = Shadow(color = Color.Black.copy(alpha = 0.55f), blurRadius = 6f)
  val insetPx = with(LocalDensity.current) { HourTempTextHeight.toPx() / 2f }

  Column(Modifier.horizontalScroll(rememberScrollState())) {
    // Riga 1: l'ora e l'icona (alba e tramonto prendono il posto dell'icona nella loro ora).
    Row {
      hours.forEach { hour ->
        Column(Modifier.width(HourColumnWidth), horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            text = HourFormatter.format(Instant.ofEpochMilli(hour.timestampMillis).atZone(zone)),
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
        .width(HourColumnWidth * hours.size)
        .height(HourCurveHeight + HourTempTextHeight),
    ) {
      Charts.SmoothLine(
        values = temps,
        color = MaterialTheme.colorScheme.primary,
        insetPx = insetPx,
        modifier = Modifier
          .matchParentSize()
          .padding(horizontal = HourColumnWidth / 2),
      )
      Row {
        temps.forEach { temperature ->
          val normalized = ((temperature - min) / span).toFloat()
          Column(
            modifier = Modifier
              .width(HourColumnWidth)
              .padding(top = HourCurveHeight * (1f - normalized)),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text(
              text = "${temperature.toInt()}°",
              style = MaterialTheme.typography.titleSmall.copy(shadow = textShadow),
              color = onSurface,
              modifier = Modifier.height(HourTempTextHeight),
            )
          }
        }
      }
    }

    // Riga 3: la probabilita' di pioggia, solo dove conta.
    Row {
      hours.forEach { hour ->
        val pop = hour.values[FusionVariables.PRECIP_PROBABILITY]?.value
        Column(Modifier.width(HourColumnWidth), horizontalAlignment = Alignment.CenterHorizontally) {
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

@Composable
private fun DailyTile(state: HomeUiState) {
  val zone = ZoneId.systemDefault()
  val byDay = state.fusedHours
    .groupBy { Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDate() }
    .toSortedMap()
    .entries
    .take(10)
  if (byDay.isEmpty()) {
    EmptyTileBody("In attesa dei provider…")
    return
  }

  val allTemps = byDay.flatMap { (_, hours) ->
    hours.mapNotNull { it.values[FusionVariables.TEMPERATURE]?.value }
  }
  if (allTemps.isEmpty()) {
    EmptyTileBody("Nessuna temperatura fusa disponibile.")
    return
  }
  val periodMin = allTemps.min()
  val periodMax = allTemps.max()
  val today = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone).toLocalDate()
  val dayFormatter = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())

  Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
    byDay.forEach { (date, hours) ->
      val temps = hours.mapNotNull { it.values[FusionVariables.TEMPERATURE]?.value }
      if (temps.isEmpty()) return@forEach
      val kind = hours.mapNotNull { it.kind }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = if (date == today) "Oggi" else dayFormatter.format(date).replaceFirstChar { it.uppercase() },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.width(38.dp),
        )
        WeatherKindIcon(kind, size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Text(
          text = "${temps.min().toInt()}°",
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
          text = "${temps.max().toInt()}°",
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
    EmptyTileBody("In attesa dei provider…")
    return
  }
  val pops = next6.map { it.values[FusionVariables.PRECIP_PROBABILITY]?.value ?: 0.0 }
  val totalMm = next6.sumOf { it.values[FusionVariables.PRECIPITATION]?.value ?: 0.0 }
  val zone = ZoneId.systemDefault()

  Text(
    text = if (totalMm >= 0.1) {
      "~${String.format(Locale.getDefault(), "%.1f", totalMm)} mm nelle prossime 6 ore"
    } else {
      "Niente pioggia attesa nelle prossime 6 ore"
    },
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurface,
  )
  Spacer(Modifier.height(10.dp))
  Charts.ProbabilityBars(
    percentages = pops,
    color = Color(0xFF8FC7F0),
    modifier = Modifier
      .fillMaxWidth()
      .height(52.dp),
  )
  Spacer(Modifier.height(4.dp))
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    next6.forEach { hour ->
      Text(
        text = HourFormatter.format(Instant.ofEpochMilli(hour.timestampMillis).atZone(zone)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
      )
    }
  }
}

// ---------------------------------------------------------------------- pressione (compatto)

@Composable
private fun PressureTile(state: HomeUiState) {
  val raw = state.latestRawPressureHpa
  if (raw == null) {
    EmptyTileBody("Nessuna lettura del barometro.")
    return
  }
  Spacer(Modifier.height(6.dp))
  Text(
    text = String.format(Locale.getDefault(), "%.1f", raw),
    fontSize = 34.sp,
    fontWeight = FontWeight.Light,
    color = MaterialTheme.colorScheme.onSurface,
  )
  Text(
    text = "hPa · grezza",
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
  )
  val trend = state.cleaning?.latest?.trendHpaPerHour
  if (trend != null) {
    Spacer(Modifier.height(8.dp))
    Text(
      text = when {
        trend <= -0.5 -> "↓ in discesa (${String.format(Locale.ROOT, "%+.1f", trend)}/h)"
        trend >= 0.5 -> "↑ in salita (${String.format(Locale.ROOT, "%+.1f", trend)}/h)"
        else -> "→ stabile"
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
    EmptyTileBody("Qualita' dell'aria non disponibile.")
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
        text = air.band.label,
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
  if (air.dominantPollutant != null) {
    Spacer(Modifier.height(8.dp))
    Text(
      text = "Domina: ${air.dominantPollutant}",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    )
  }
}

// --------------------------------------------------------------------------------------- sole

private val TimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun SunTile(state: HomeUiState) {
  val times = state.sunTimesToday
  if (times?.sunriseMillis == null || times.sunsetMillis == null) {
    EmptyTileBody(
      if (state.hasLocation) "Oggi il sole non attraversa l'orizzonte qui." else "Serve la posizione.",
    )
    return
  }
  val zone = ZoneId.systemDefault()
  val now = System.currentTimeMillis()
  val progress = ((now - times.sunriseMillis!!).toFloat() /
    (times.sunsetMillis!! - times.sunriseMillis!!).toFloat()).takeIf { it in 0f..1f }

  Charts.SunArc(
    dayProgress = progress,
    arcColor = MaterialTheme.colorScheme.onSurface,
    sunColor = Color(0xFFF6C750),
    modifier = Modifier
      .fillMaxWidth()
      .height(88.dp),
  )
  Spacer(Modifier.height(6.dp))
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Column {
      Text("Alba", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
      Text(
        TimeFormatter.format(Instant.ofEpochMilli(times.sunriseMillis!!).atZone(zone)),
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
      Text("Durata", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
      Text(
        text = length?.let { "${it / 3_600_000L}h ${(it / 60_000L) % 60}m" } ?: "—",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (delta != null && delta != 0L) {
        Text(
          text = if (delta > 0) "+$delta min su ieri" else "$delta min su ieri",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
      }
    }
    Column(horizontalAlignment = Alignment.End) {
      Text("Tramonto", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
      Text(
        TimeFormatter.format(Instant.ofEpochMilli(times.sunsetMillis!!).atZone(zone)),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

// --------------------------------------------------------------------------------------- luna

internal fun moonPhaseLabel(phase: MoonPhase): String = when (phase) {
  MoonPhase.NEW -> "Luna nuova"
  MoonPhase.WAXING_CRESCENT -> "Falce crescente"
  MoonPhase.FIRST_QUARTER -> "Primo quarto"
  MoonPhase.WAXING_GIBBOUS -> "Gibbosa crescente"
  MoonPhase.FULL -> "Luna piena"
  MoonPhase.WANING_GIBBOUS -> "Gibbosa calante"
  MoonPhase.LAST_QUARTER -> "Ultimo quarto"
  MoonPhase.WANING_CRESCENT -> "Falce calante"
}

@Composable
private fun MoonTile(state: HomeUiState) {
  val now = System.currentTimeMillis()
  val phase = Moon.phase(now)
  val illumination = Moon.illuminatedFraction(now)
  val waxing = Moon.ageDays(now) < Moon.SYNODIC_MONTH_DAYS / 2
  val nextFull = Moon.nextFullMoonMillis(now)
  val zone = ZoneId.systemDefault()

  Row(verticalAlignment = Alignment.CenterVertically) {
    Charts.MoonDisc(
      illuminatedFraction = illumination,
      waxing = waxing,
      modifier = Modifier.size(64.dp),
    )
    Spacer(Modifier.width(14.dp))
    Column {
      Text(
        text = moonPhaseLabel(phase),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = "Illuminata al ${(illumination * 100).toInt()}%",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
      )
      Text(
        text = "Piena " + DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault())
          .format(Instant.ofEpochMilli(nextFull).atZone(zone)),
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
  val hour = state.fusedHours.minByOrNull { abs(it.timestampMillis - now) }
  if (hour == null) {
    EmptyTileBody("In attesa dei provider…")
    return
  }
  fun value(variable: String): Double? = hour.values[variable]?.value

  val wind = value(FusionVariables.WIND_SPEED)
  val humidity = value(FusionVariables.HUMIDITY)
  val apparent = if (state.temperatureC != null && humidity != null && wind != null) {
    ApparentTemperature.celsius(state.temperatureC, humidity, wind)
  } else {
    null
  }

  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row {
      DetailStat("Vento", wind?.let { "${it.toInt()} km/h" }, Modifier.weight(1f)) {
        val direction = hour.windDirectionDeg
        if (direction != null) {
          Icon(
            imageVector = Icons.Rounded.Navigation,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            // L'icona punta in su; il vento VIENE da direction: la freccia indica dove va.
            modifier = Modifier
              .size(14.dp)
              .rotate((direction + 180).toFloat()),
          )
        }
      }
      DetailStat("Umidita'", humidity?.let { "${it.toInt()}%" }, Modifier.weight(1f))
      DetailStat("UV", value(FusionVariables.UV_INDEX)?.let { String.format(Locale.ROOT, "%.0f", it) }, Modifier.weight(1f))
    }
    Row {
      DetailStat(
        "Visibilita'",
        value(FusionVariables.VISIBILITY)?.let { String.format(Locale.ROOT, "%.0f km", it / 1000) },
        Modifier.weight(1f),
      )
      DetailStat("Rugiada", value(FusionVariables.DEW_POINT)?.let { "${it.toInt()}°" }, Modifier.weight(1f))
      DetailStat("Percepita", apparent?.let { "${it.toInt()}°" }, Modifier.weight(1f))
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
  Column(modifier) {
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
    contentDescription = null,
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
