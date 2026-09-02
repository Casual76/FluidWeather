package dev.pampa.fluidweather.feature.home.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pampa.fluidweather.core.model.Moon
import dev.pampa.fluidweather.core.model.MoonEphemeris
import dev.pampa.fluidweather.core.ui.Charts
import dev.pampa.fluidweather.feature.home.HomeUiState
import dev.pampa.fluidweather.feature.home.moonPhaseLabel
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import dev.pampa.fluidweather.strings.R
import androidx.compose.ui.res.stringResource
import dev.pampa.fluidweather.core.ui.rememberUnitFormatter
import java.time.DayOfWeek
import java.time.format.TextStyle

/**
 * La pagina della luna: lo scrubber dei giorni (trascina per andare avanti e indietro), il
 * disco col terminatore, fase, illuminazione, eta', distanza; sorgere e tramonto dalle
 * effemeridi; le prossime quattro fasi; il calendario del mese con la faccia di ogni giorno.
 */
@Composable
internal fun MoonPage(state: HomeUiState) {
  var offsetDays by remember { mutableIntStateOf(0) }
  val units = rememberUnitFormatter()
  val now = System.currentTimeMillis()
  val selected = now + offsetDays * 86_400_000L
  val date = localDate(selected)

  PageSection(if (offsetDays == 0) stringResource(R.string.common_now) else fmtDate(date))
  DayScrubber(offsetDays = offsetDays, onOffsetChange = { offsetDays = it })
  val quick = listOf(-1, 0, 1, 7)
  ChipRow(listOf(stringResource(R.string.common_yesterday), stringResource(R.string.common_today), stringResource(R.string.common_tomorrow), stringResource(R.string.moon_plus_7)), quick.indexOf(offsetDays)) { offsetDays = quick[it] }
  Spacer(Modifier.height(12.dp))

  val age = Moon.ageDays(selected)
  val illumination = Moon.illuminatedFraction(selected)
  val waxing = age < Moon.SYNODIC_MONTH_DAYS / 2
  val distance = MoonEphemeris.distanceKm(selected)
  Row(verticalAlignment = Alignment.CenterVertically) {
    Charts.MoonDisc(illuminatedFraction = illumination, waxing = waxing, modifier = Modifier.size(110.dp))
    Spacer(Modifier.width(18.dp))
    Column {
      Text(moonPhaseLabel(Moon.phase(selected)), style = MaterialTheme.typography.titleMedium, color = White)
      Text(stringResource(R.string.moon_illuminated, (illumination * 100).toInt()), style = MaterialTheme.typography.bodySmall, color = Dim)
      Text(stringResource(R.string.moon_age, fmt1(age)), style = MaterialTheme.typography.bodySmall, color = Dim)
      val distanceNote = when {
        distance < MoonEphemeris.PERIGEE_KM + 6_000 -> stringResource(R.string.moon_near_perigee)
        distance > MoonEphemeris.APOGEE_KM - 6_000 -> stringResource(R.string.moon_near_apogee)
        else -> ""
      }
      Text(stringResource(R.string.moon_distance, units.distanceGrouped(distance), distanceNote), style = MaterialTheme.typography.bodySmall, color = Dim)
    }
  }

  PageSection(stringResource(R.string.moon_rise_set))
  val latitude = state.latitude
  val longitude = state.longitude
  if (latitude == null || longitude == null) {
    PageNote(stringResource(R.string.moon_needs_location))
  } else {
    val times = remember(date, latitude, longitude) {
      MoonEphemeris.riseSet(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), latitude, longitude)
    }
    StatGrid(
      listOf(
        stringResource(R.string.moon_rises) to (times.riseMillis?.let { fmtTime(it) } ?: stringResource(R.string.moon_no_rise)),
        stringResource(R.string.moon_sets) to (times.setMillis?.let { fmtTime(it) } ?: stringResource(R.string.moon_no_set)),
      ),
    )
    PageNote(stringResource(R.string.moon_skip_note))
  }

  PageSection(stringResource(R.string.moon_next_phases))
  nextPhases(now).forEach { (label, millis) ->
    StatRow(label, fmtDay(millis), fmtTime(millis))
  }

  val month = YearMonth.from(date)
  PageSection(stringResource(R.string.moon_calendar_of) + DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()).format(month).replaceFirstChar { it.uppercase() })
  MonthCalendar(month = month, selected = date, today = localDate(now))
  PageNote(
    stringResource(R.string.moon_method_note),
  )
}

/** Le quattro fasi principali che verranno, in ordine di arrivo. */
@Composable
private fun nextPhases(nowMillis: Long): List<Pair<String, Long>> {
  val age = Moon.ageDays(nowMillis)
  val synodic = Moon.SYNODIC_MONTH_DAYS
  val targets = listOf(
    stringResource(R.string.moon_new) to 0.0,
    stringResource(R.string.moon_first_quarter) to synodic / 4,
    stringResource(R.string.moon_full) to synodic / 2,
    stringResource(R.string.moon_last_quarter) to synodic * 3 / 4,
  )
  return targets.map { (label, targetAge) ->
    val daysTo = ((targetAge - age) % synodic + synodic) % synodic
    label to nowMillis + (daysTo * 86_400_000).toLong()
  }.sortedBy { it.second }
}

/** Lo scrubber: trascina a destra per andare indietro nei giorni, a sinistra per andare avanti. */
@Composable
private fun DayScrubber(offsetDays: Int, onOffsetChange: (Int) -> Unit) {
  val current = rememberUpdatedState(offsetDays)
  val stepPx = with(LocalDensity.current) { 22.dp.toPx() }
  var accumulated by remember { mutableFloatStateOf(0f) }
  val tick = White.copy(alpha = 0.35f)
  val strong = White
  Canvas(
    Modifier
      .fillMaxWidth()
      .height(44.dp)
      .pointerInput(Unit) {
        detectHorizontalDragGestures(
          onDragStart = { accumulated = 0f },
          onHorizontalDrag = { change, delta ->
            change.consume()
            accumulated += delta
            val steps = (accumulated / stepPx).toInt()
            if (steps != 0) {
              onOffsetChange((current.value - steps).coerceIn(-30, 30))
              accumulated -= steps * stepPx
            }
          },
        )
      },
  ) {
    val centerX = size.width / 2f
    val baseline = size.height - 8f
    for (day in -30..30) {
      val x = centerX + (day - current.value) * stepPx
      if (x < 0f || x > size.width) continue
      val weekly = day % 7 == 0
      val height = if (day == current.value) 26f else if (weekly) 18f else 10f
      drawLine(
        color = if (day == current.value) strong else tick,
        start = Offset(x, baseline),
        end = Offset(x, baseline - height),
        strokeWidth = if (day == current.value) 4f else 2f,
        cap = StrokeCap.Round,
      )
    }
  }
}

@Composable
private fun MonthCalendar(month: YearMonth, selected: LocalDate, today: LocalDate) {
  val first = month.atDay(1)
  val leading = first.dayOfWeek.value - 1 // lunedi' = 0
  val cells = List(leading) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
  Row(Modifier.fillMaxWidth()) {
    // Le iniziali dei giorni nella lingua del telefono, da lunedi'.
    DayOfWeek.entries.forEach { dayOfWeek ->
      val it = dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
      Text(it, style = MaterialTheme.typography.labelSmall, color = Faint, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
    }
  }
  cells.chunked(7).forEach { week ->
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
      week.forEach { day ->
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
          if (day != null) {
            val noon = day.atTime(12, 0).atZone(zone()).toInstant().toEpochMilli()
            val age = Moon.ageDays(noon)
            Charts.MoonDisc(
              illuminatedFraction = Moon.illuminatedFraction(noon),
              waxing = age < Moon.SYNODIC_MONTH_DAYS / 2,
              modifier = Modifier.size(18.dp),
            )
            Text(
              "${day.dayOfMonth}",
              style = MaterialTheme.typography.labelSmall,
              color = when (day) {
                selected -> MaterialTheme.colorScheme.primary
                today -> White
                else -> Faint
              },
            )
          } else {
            Box(Modifier.size(18.dp))
          }
        }
      }
      repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
    }
  }
}
