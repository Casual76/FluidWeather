package dev.pampa.fluidweather.core.model

import org.junit.Assert.*
import org.junit.Test

class ForecastIntervalsTest {
  private val q = 900_000L
  private val end = 1_788_220_800_000L
  private fun bundle(vararg points: Pair<Long, Double?>) = ForecastBundle(
    "open-meteo", end, 43.0, 11.0, emptyList(),
    points.map { MinutePoint(it.first, it.second) },
  )
  private fun hour() = bundle(*(-3..0).map { end + it * q to 0.25 }.toTypedArray())

  @Test fun `nonaligned clock uses four unique closed quarters across midnight UTC`() {
    val result = hour().rainLastHour(end + 7 * 60_000L)!!
    assertEquals(1.0, result.amountMm, 0.0)
    assertEquals(WeatherInterval(end - 4 * q, end), result.interval)
    assertEquals(0.25, hour().rainNowMm(end + 7 * 60_000L)!!, 0.0)
  }

  @Test fun `future quarter is excluded and old quarters expire`() {
    val b = hour().copy(minutely = hour().minutely + MinutePoint(end + q, 100.0))
    assertEquals(1.0, b.rainLastHourMm(end + q - 1)!!, 0.0)
    assertNull(b.rainLastHourMm(end + 5 * q))
    assertNull(hour().rainLastHourMm(end + q, maxAgeMillis = q - 1))
  }

  @Test fun `duplicates gaps extra timestamps and invalid amounts cannot make an hour`() {
    val original = hour()
    val variants = listOf(
      original.minutely + original.minutely.last(),
      List(4) { MinutePoint(end, 0.25) },
      original.minutely.drop(1),
      original.minutely.drop(1) + MinutePoint(end - 5 * q, 0.25),
      original.minutely + MinutePoint(end - q / 2, 0.25),
    ) + listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0).map { bad ->
      original.minutely.dropLast(1) + MinutePoint(end, bad)
    }
    variants.forEach { assertNull(original.copy(minutely = it).rainLastHourMm(end)) }
    listOf(null, Double.NaN, -1.0).forEach { assertNull(bundle(end to it).rainNowMm(end)) }
    assertNull(bundle(end to 0.0, end to 0.0).rainNowMm(end))
    assertNull(bundle(end - 1 to 0.0).rainNowMm(end))
  }

  @Test fun `future total needs full coverage while explicit API retains interior coverage`() {
    val b = bundle(*(1..4).map { end + it * q to 0.25 }.toTypedArray())
    assertEquals(1.0, b.rainSoonMm(end, 4 * q)!!, 0.0)
    assertNull(b.copy(minutely = b.minutely.dropLast(1)).rainSoonMm(end, 4 * q))
    assertNull(b.copy(minutely = b.minutely + b.minutely.first()).rainSoon(end, 4 * q))
    val inside = b.rainSoon(end + 60_000, 4 * q)!!
    assertEquals(WeatherInterval(end + q, end + 4 * q), inside.interval)
    assertEquals(0.75, inside.amountMm, 0.0)
    assertNull(b.rainSoonMm(end + 60_000, 4 * q))
    assertNull(b.rainSoon(end, 0))
    assertNull(bundle().rainSoon(end, q))
    assertNull(bundle(end + q to null).rainSoonMm(end, q))
    assertNull(bundle(end + q to -1.0).rainSoonMm(end, q))
  }

  @Test fun `known coarse product cannot become quarter precipitation`() {
    assertNull(hour().copy(minutelyPrecipitation = PrecipitationAccumulation(10_800_000,
      AccumulationAnchor.PRECEDING)).rainNowMm(end))
  }
}
