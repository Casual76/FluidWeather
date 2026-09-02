package dev.pampa.fluidweather.feature.home.pages

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pampa.fluidweather.core.model.SunCalendar
import dev.pampa.fluidweather.core.model.SunTimes
import dev.pampa.fluidweather.core.model.Twilight
import dev.pampa.fluidweather.feature.home.HomeUiState
import java.time.ZoneOffset
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.pampa.fluidweather.strings.R
import androidx.compose.ui.res.stringResource
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

private class SunDay(
  val sun: SunTimes.Times,
  val civil: SunTimes.Times,
  val nautical: SunTimes.Times,
  val astronomical: SunTimes.Times,
  val noonMillis: Long,
  val noonElevation: Double,
)

/**
 * La pagina del sole: alba, tramonto, mezzogiorno solare e altezza massima; i tre crepuscoli
 * mattina e sera; la durata del giorno lungo tutto l'anno per questa latitudine, coi solstizi e
 * gli equinozi. Tutto calcolato sul posto, dalle stesse effemeridi che colorano il cielo.
 */
@Composable
internal fun SunPage(state: HomeUiState) {
  val latitude = state.latitude
  val longitude = state.longitude
  if (latitude == null || longitude == null) {
    PageNote(stringResource(R.string.sun_needs_location))
    return
  }
  val now = System.currentTimeMillis()
  val today = localDate(now)
  val dayStart = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
  val day = remember(today, latitude, longitude) {
    val (noon, elevation) = SunTimes.solarNoon(dayStart, latitude, longitude)
    SunDay(
      sun = SunTimes.forDay(dayStart, latitude, longitude),
      civil = SunTimes.forDay(dayStart, latitude, longitude, Twilight.CIVIL),
      nautical = SunTimes.forDay(dayStart, latitude, longitude, Twilight.NAUTICAL),
      astronomical = SunTimes.forDay(dayStart, latitude, longitude, Twilight.ASTRONOMICAL),
      noonMillis = noon,
      noonElevation = elevation,
    )
  }

  PageSection(stringResource(R.string.common_today))
  val length = state.dayLengthTodayMillis
  val delta = if (length != null && state.dayLengthYesterdayMillis != null) (length - state.dayLengthYesterdayMillis) / 60_000L else null
  StatGrid(
    listOf(
      stringResource(R.string.sun_rise) to (day.sun.sunriseMillis?.let { fmtTime(it) } ?: stringResource(R.string.sun_no_rise)),
      stringResource(R.string.sun_set) to (day.sun.sunsetMillis?.let { fmtTime(it) } ?: stringResource(R.string.sun_no_set)),
      stringResource(R.string.sun_day_length) to (length?.let { fmtDuration(it) } ?: "—"),
      stringResource(R.string.sun_vs_yesterday) to (delta?.let { if (it > 0) stringResource(R.string.sun_plus_min, it) else stringResource(R.string.sun_min, it) } ?: "—"),
      stringResource(R.string.sun_solar_noon) to fmtTime(day.noonMillis),
      stringResource(R.string.sun_max_elevation) to stringResource(R.string.sun_above_horizon, fmt0(day.noonElevation)),
    ),
  )

  PageSection(stringResource(R.string.sun_twilights))
  Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
    Text("", modifier = Modifier.weight(1.3f))
    Text(stringResource(R.string.sun_morning), style = MaterialTheme.typography.labelSmall, color = Faint, modifier = Modifier.weight(1f))
    Text(stringResource(R.string.sun_evening), style = MaterialTheme.typography.labelSmall, color = Faint, modifier = Modifier.weight(1f))
  }
  TwilightRow(stringResource(R.string.sun_civil), day.civil.sunriseMillis, day.sun.sunriseMillis, day.sun.sunsetMillis, day.civil.sunsetMillis)
  TwilightRow(stringResource(R.string.sun_nautical), day.nautical.sunriseMillis, day.civil.sunriseMillis, day.civil.sunsetMillis, day.nautical.sunsetMillis)
  TwilightRow(stringResource(R.string.sun_astronomical), day.astronomical.sunriseMillis, day.nautical.sunriseMillis, day.nautical.sunsetMillis, day.astronomical.sunsetMillis)
  PageNote(
    stringResource(R.string.sun_twilight_note),
  )

  PageSection(stringResource(R.string.sun_year_title))
  val year by produceState<List<SunCalendar.DayLength>?>(initialValue = null, today.year, latitude, longitude) {
    value = withContext(Dispatchers.Default) { SunCalendar.year(today.year, latitude, longitude) }
  }
  val lengths = year
  if (lengths == null) {
    PageNote(stringResource(R.string.sun_year_computing))
  } else {
    CurveWithLabels(
      values = lengths.map { (it.lengthMillis ?: 0L) / 3_600_000.0 },
      labels = Month.entries.map { it.getDisplayName(TextStyle.NARROW, Locale.getDefault()) },
      color = PageAmber,
      unit = " h",
      decimals = 1,
      markerIndex = today.dayOfYear - 1,
    )
    Spacer(Modifier.height(4.dp))
    val withLength = lengths.filter { it.lengthMillis != null }
    val longest = withLength.maxByOrNull { it.lengthMillis!! }
    val shortest = withLength.minByOrNull { it.lengthMillis!! }
    if (longest != null && shortest != null) {
      StatRow(stringResource(R.string.sun_longest), fmtDate(longest.date), fmtDuration(longest.lengthMillis!!))
      StatRow(stringResource(R.string.sun_shortest), fmtDate(shortest.date), fmtDuration(shortest.lengthMillis!!))
      val twelve = 12 * 3_600_000L
      val firstHalf = withLength.filter { it.date.dayOfYear < 183 }.minByOrNull { abs(it.lengthMillis!! - twelve) }
      val secondHalf = withLength.filter { it.date.dayOfYear >= 183 }.minByOrNull { abs(it.lengthMillis!! - twelve) }
      if (firstHalf != null && secondHalf != null) {
        StatRow(stringResource(R.string.sun_equinoxes), "${fmtDate(firstHalf.date)} · ${fmtDate(secondHalf.date)}", stringResource(R.string.sun_equinox_note))
      }
    }
    PageNote(stringResource(R.string.sun_year_note))
  }
}

@Composable
private fun TwilightRow(label: String, morningFrom: Long?, morningTo: Long?, eveningFrom: Long?, eveningTo: Long?) {
  Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
    Text(label, style = MaterialTheme.typography.bodyMedium, color = Dim, modifier = Modifier.weight(1.3f))
    Text(
      if (morningFrom != null && morningTo != null) "${fmtTime(morningFrom)}–${fmtTime(morningTo)}" else "—",
      style = MaterialTheme.typography.bodyMedium,
      color = White,
      modifier = Modifier.weight(1f),
    )
    Text(
      if (eveningFrom != null && eveningTo != null) "${fmtTime(eveningFrom)}–${fmtTime(eveningTo)}" else "—",
      style = MaterialTheme.typography.bodyMedium,
      color = White,
      modifier = Modifier.weight(1f),
    )
  }
}
