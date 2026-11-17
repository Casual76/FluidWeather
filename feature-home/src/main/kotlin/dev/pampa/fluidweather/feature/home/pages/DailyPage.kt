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
import dev.pampa.fluidweather.core.model.DailyAggregate
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.precipitationHours
import dev.pampa.fluidweather.core.ui.Charts
import dev.pampa.fluidweather.feature.home.HomeUiState
import dev.pampa.fluidweather.feature.home.WeatherKindIcon
import dev.pampa.fluidweather.feature.home.isNightAt
import java.time.LocalDate
import java.time.ZoneId
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
  val zone = dev.pampa.fluidweather.feature.home.homeWeatherZone(state).zoneId
  val today = localDate(now, zone)
  // L'aggregato condiviso (fase 19): la stessa aritmetica della home la legge anche l'assistente.
  val days = remember(state.fusedHours, state.latitude, state.longitude, zone, today) {
    DailyAggregate.of(state.fusedHours, zone, state.latitude, state.longitude, today, 10)
  }
  val rainByStart = remember(state.fusedHours) {
    state.fusedHours.precipitationHours(Long.MIN_VALUE, Long.MAX_VALUE)
      .associateBy { it.precipitationInterval!!.startMillis }
  }
  if (days.isEmpty()) {
    PageNote(stringResource(if (state.fusedHours.isEmpty()) R.string.common_waiting_providers else R.string.tile_no_fused_temperature))
    return
  }
  val periodMin = days.minOf { it.minC }
  val periodMax = days.maxOf { it.maxC }
  var expanded by remember { mutableStateOf<LocalDate?>(null) }
  val units = rememberUnitFormatter()
  val dayFormatter = DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())

  PageNote(stringResource(R.string.daily_hint))
  Spacer(Modifier.height(6.dp))

  days.forEach { day ->
    val date = day.date
    val hours = day.hours
    val kind = day.kind
    val dayMin = day.minC
    val dayMax = day.maxC
    val popMax = day.precipitationProbabilityMaxPercent
    val mm = day.precipitationMm
    val windMax = day.windMaxKmh

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
        Text(units.degrees(dayMin), style = MaterialTheme.typography.labelMedium, color = Faint, modifier = Modifier.width(30.dp))
        Charts.RangeBar(
          periodMin = periodMin,
          periodMax = periodMax,
          dayMin = dayMin,
          dayMax = dayMax,
          nowValue = if (date == today) state.temperatureC else null,
          trackColor = White.copy(alpha = 0.15f),
          barBrushColors = listOf(PageBlue, PageAmber),
          modifier = Modifier
            .width(96.dp)
            .height(6.dp),
        )
        Text(
          units.degrees(dayMax),
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
          if (mm != null && mm >= 0.1) append(stringResource(R.string.daily_mm_suffix, units.precipitation(mm)))
          if (windMax != null) append(stringResource(R.string.daily_wind_suffix, units.wind(windMax)))
          val sunrise = day.sunriseMillis
          val sunset = day.sunsetMillis
          if (sunrise != null && sunset != null) {
            append(" · ☀ ${fmtTime(sunrise, zone)}–${fmtTime(sunset, zone)}")
          }
        },
        style = MaterialTheme.typography.labelSmall,
        color = Faint,
        modifier = Modifier.padding(top = 3.dp),
      )
      if (mm == null) {
        val sampled = day.precipitationSampleSumMm
        Text((if (sampled != null) stringResource(R.string.precip_quality_sample_sum) + ": " + units.precipitation(sampled) + " · " else "") +
          stringResource(R.string.precip_quality_amount_coverage, day.precipitationSamples, day.precipitationExpectedSamples),
          style = MaterialTheme.typography.labelSmall, color = Faint)
      }
      if (expanded == date) {
        Spacer(Modifier.height(6.dp))
        PageNote(stringResource(R.string.quality_rain_interval_rows))
        hours.forEach { hour ->
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            Text(fmtHour(hour.timestampMillis, zone), style = MaterialTheme.typography.bodySmall, color = Dim, modifier = Modifier.width(34.dp))
            WeatherKindIcon(hour.kind, size = 14.dp, night = isNightAt(hour.timestampMillis, state.latitude, state.longitude))
            Spacer(Modifier.width(8.dp))
            Text(
              hour.values[FusionVariables.TEMPERATURE]?.value?.let { units.degrees(it) } ?: "—",
              style = MaterialTheme.typography.bodySmall,
              color = White,
              modifier = Modifier.width(40.dp),
            )
            val rain = rainByStart[hour.timestampMillis]
            val pop = rain?.values?.get(FusionVariables.PRECIP_PROBABILITY)?.value
            Text(
              precipitationProbability(pop),
              style = MaterialTheme.typography.bodySmall,
              color = PageBlue,
              modifier = Modifier.width(48.dp),
            )
            val hourMm = rain?.values?.get(FusionVariables.PRECIPITATION)?.value
            Text(
              precipitationSampleValue(hourMm, units),
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
