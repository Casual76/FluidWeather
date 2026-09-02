package dev.pampa.fluidweather.feature.home.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.SunTimes
import dev.pampa.fluidweather.core.ui.Charts
import dev.pampa.fluidweather.feature.home.HomeUiState
import dev.pampa.fluidweather.feature.home.WeatherKindIcon
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import dev.pampa.fluidweather.strings.R
import androidx.compose.ui.res.stringResource
import dev.pampa.fluidweather.core.ui.rememberUnitFormatter
import dev.pampa.fluidweather.strings.labelRes

/**
 * Giorno per giorno, fino all'orizzonte del servizio piu' lungo: condizione prevalente di
 * giorno, escursione sulla barra del periodo, pioggia, vento, alba e tramonto. Un tocco sul
 * giorno apre le sue ore.
 */
@Composable
internal fun DailyPage(state: HomeUiState) {
  val now = System.currentTimeMillis()
  val today = localDate(now)
  val byDay = state.fusedHours
    .groupBy { localDate(it.timestampMillis) }
    .toSortedMap()
    .entries
    .filter { it.key >= today }
    .take(10)
  if (byDay.isEmpty()) {
    PageNote(stringResource(R.string.common_waiting_providers))
    return
  }
  val allTemps = byDay.flatMap { (_, hours) -> hours.mapNotNull { it.values[FusionVariables.TEMPERATURE]?.value } }
  if (allTemps.isEmpty()) {
    PageNote(stringResource(R.string.tile_no_fused_temperature))
    return
  }
  val periodMin = allTemps.min()
  val periodMax = allTemps.max()
  var expanded by remember { mutableStateOf<LocalDate?>(null) }
  val units = rememberUnitFormatter()
  val dayFormatter = DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())

  PageNote(stringResource(R.string.daily_hint))
  Spacer(Modifier.height(6.dp))

  byDay.forEach { (date, hours) ->
    val temps = hours.mapNotNull { it.values[FusionVariables.TEMPERATURE]?.value }
    if (temps.isEmpty()) return@forEach
    val daytime = hours.filter { hourOfDay(it.timestampMillis) in 8..20 }.ifEmpty { hours }
    val kind = daytime.mapNotNull { it.kind }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    val popMax = hours.mapNotNull { it.values[FusionVariables.PRECIP_PROBABILITY]?.value }.maxOrNull()
    val mm = hours.sumOf { it.values[FusionVariables.PRECIPITATION]?.value ?: 0.0 }
    val windMax = hours.mapNotNull { it.values[FusionVariables.WIND_SPEED]?.value }.maxOrNull()
    val sun = remember(date, state.latitude, state.longitude) {
      val latitude = state.latitude
      val longitude = state.longitude
      if (latitude == null || longitude == null) {
        null
      } else {
        SunTimes.forDay(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), latitude, longitude)
      }
    }

    Column(
      Modifier
        .fillMaxWidth()
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
        ) { expanded = if (expanded == date) null else date }
        .padding(vertical = 10.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          if (date == today) stringResource(R.string.common_today) else dayFormatter.format(date).replaceFirstChar { it.uppercase() },
          style = MaterialTheme.typography.titleSmall,
          color = White,
          modifier = Modifier.width(64.dp),
        )
        WeatherKindIcon(kind, size = 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(
          kind?.labelRes()?.let { stringResource(it) } ?: "",
          style = MaterialTheme.typography.bodySmall,
          color = Dim,
          modifier = Modifier.weight(1f),
        )
        Text(units.degrees(temps.min()), style = MaterialTheme.typography.labelMedium, color = Faint, modifier = Modifier.width(30.dp))
        Charts.RangeBar(
          periodMin = periodMin,
          periodMax = periodMax,
          dayMin = temps.min(),
          dayMax = temps.max(),
          nowValue = if (date == today) state.temperatureC else null,
          trackColor = White.copy(alpha = 0.15f),
          barBrushColors = listOf(PageBlue, PageAmber),
          modifier = Modifier
            .width(96.dp)
            .height(6.dp),
        )
        Text(
          units.degrees(temps.max()),
          style = MaterialTheme.typography.labelMedium,
          color = White,
          modifier = Modifier
            .width(34.dp)
            .padding(start = 6.dp),
        )
      }
      Text(
        buildString {
          if (popMax != null) append(stringResource(R.string.daily_rain, popMax.toInt()))
          if (mm >= 0.1) append(stringResource(R.string.daily_mm_suffix, units.precipitation(mm)))
          if (windMax != null) append(stringResource(R.string.daily_wind_suffix, units.wind(windMax)))
          val sunrise = sun?.sunriseMillis
          val sunset = sun?.sunsetMillis
          if (sunrise != null && sunset != null) {
            append(" · ☀ ${fmtTime(sunrise)}–${fmtTime(sunset)}")
          }
        },
        style = MaterialTheme.typography.labelSmall,
        color = Faint,
        modifier = Modifier.padding(top = 3.dp),
      )
      if (expanded == date) {
        Spacer(Modifier.height(6.dp))
        hours.forEach { hour ->
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            Text(fmtHour(hour.timestampMillis), style = MaterialTheme.typography.bodySmall, color = Dim, modifier = Modifier.width(34.dp))
            WeatherKindIcon(hour.kind, size = 14.dp)
            Spacer(Modifier.width(8.dp))
            Text(
              hour.values[FusionVariables.TEMPERATURE]?.value?.let { units.degrees(it) } ?: "—",
              style = MaterialTheme.typography.bodySmall,
              color = White,
              modifier = Modifier.width(40.dp),
            )
            val pop = hour.values[FusionVariables.PRECIP_PROBABILITY]?.value
            Text(
              if (pop != null && pop >= 5) "${pop.toInt()}%" else "",
              style = MaterialTheme.typography.bodySmall,
              color = PageBlue,
              modifier = Modifier.width(48.dp),
            )
            val hourMm = hour.values[FusionVariables.PRECIPITATION]?.value
            Text(
              if (hourMm != null && hourMm >= 0.05) units.precipitation(hourMm) else "",
              style = MaterialTheme.typography.bodySmall,
              color = Dim,
            )
          }
        }
      }
    }
    Box(
      Modifier
        .fillMaxWidth()
        .height(1.dp)
        .background(White.copy(alpha = 0.08f)),
    )
  }
}
