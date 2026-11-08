package dev.pampa.fluidweather.feature.home.pages

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
import dev.pampa.fluidweather.strings.TimeFormats

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
fun SunPage(state: HomeUiState) {
  val latitude = state.latitude
  val longitude = state.longitude
  if (latitude == null || longitude == null) {
    PageNote(stringResource(R.string.sun_needs_location))
    return
  }
  val now = System.currentTimeMillis()
  val locale = LocalConfiguration.current.locales[0]
  val time: (Long) -> String = { TimeFormats.time(it, zone(), locale) }
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
      stringResource(R.string.sun_rise) to (day.sun.sunriseMillis?.let(time) ?: stringResource(R.string.sun_no_rise)),
      stringResource(R.string.sun_set) to (day.sun.sunsetMillis?.let(time) ?: stringResource(R.string.sun_no_set)),
      stringResource(R.string.sun_day_length) to (length?.let { fmtDuration(it) } ?: "—"),
      stringResource(R.string.sun_vs_yesterday) to (delta?.let { if (it > 0) stringResource(R.string.sun_plus_min, it) else stringResource(R.string.sun_min, it) } ?: "—"),
      stringResource(R.string.sun_solar_noon) to time(day.noonMillis),
      stringResource(R.string.sun_max_elevation) to stringResource(R.string.sun_above_horizon, fmt0(day.noonElevation)),
    ),
  )

  PageSection(stringResource(R.string.sun_twilights))
  TwilightRow(stringResource(R.string.sun_civil), day.civil.sunriseMillis, day.sun.sunriseMillis, day.sun.sunsetMillis, day.civil.sunsetMillis, time)
  TwilightRow(stringResource(R.string.sun_nautical), day.nautical.sunriseMillis, day.civil.sunriseMillis, day.civil.sunsetMillis, day.nautical.sunsetMillis, time)
  TwilightRow(stringResource(R.string.sun_astronomical), day.astronomical.sunriseMillis, day.nautical.sunriseMillis, day.nautical.sunsetMillis, day.astronomical.sunsetMillis, time)

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
      labels = Month.entries.map { it.getDisplayName(TextStyle.NARROW, locale) },
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
      AnnualDateRow("sun_longest", stringResource(R.string.sun_longest), TimeFormats.longDate(longest.date, locale), fmtDuration(longest.lengthMillis!!))
      AnnualDateRow("sun_shortest", stringResource(R.string.sun_shortest), TimeFormats.longDate(shortest.date, locale), fmtDuration(shortest.lengthMillis!!))
      val twelve = 12 * 3_600_000L
      val firstHalf = withLength.filter { it.date.dayOfYear < 183 }.minByOrNull { abs(it.lengthMillis!! - twelve) }
      val secondHalf = withLength.filter { it.date.dayOfYear >= 183 }.minByOrNull { abs(it.lengthMillis!! - twelve) }
      if (firstHalf != null && secondHalf != null) {
        // These dates minimize local sunrise-to-sunset duration around twelve hours;
        // they are not the astronomical equinox instants.
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("sun_twelve_dates")) {
          Text(stringResource(R.string.quality_daylight_twelve), color = Dim,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("sun_twelve_label"))
          Text(TimeFormats.longDate(firstHalf.date, locale), color = White, style = MaterialTheme.typography.titleSmall)
          Text(TimeFormats.longDate(secondHalf.date, locale), color = White, style = MaterialTheme.typography.titleSmall)
        }
      }
    }
  }
  PageDisclosure {
    PageNote(stringResource(R.string.sun_twilight_note))
    PageNote(stringResource(R.string.sun_year_note))
  }
}

@Composable
private fun TwilightRow(label: String, morningFrom: Long?, morningTo: Long?, eveningFrom: Long?, eveningTo: Long?, time: (Long) -> String) {
  BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
    val stacked = maxWidth < 480.dp && LocalDensity.current.fontScale > 1.3f
    Column {
      Text(label, style = MaterialTheme.typography.bodyMedium, color = Dim)
      Row(Modifier.fillMaxWidth()) {
        listOf(Triple(R.string.sun_morning, morningFrom, morningTo), Triple(R.string.sun_evening, eveningFrom, eveningTo)).forEach { (title, from, to) ->
          Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.labelSmall, color = Faint)
            Text(if (from != null && to != null) time(from) + (if (stacked) "\n– " else "–") + time(to) else "—",
              style = MaterialTheme.typography.bodyMedium, color = White)
          }
        }
      }
    }
  }
}

@Composable
private fun AnnualDateRow(tag: String, label: String, date: String, duration: String) {
  Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
    Text(label, color = Dim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("${tag}_label"))
    Text(date, color = White, style = MaterialTheme.typography.titleSmall)
    Text(duration, color = Faint, style = MaterialTheme.typography.bodySmall)
  }
}
