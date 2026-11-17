package dev.pampa.fluidweather.core.cycle

import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusedValue
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.WeatherKind
import org.junit.Assert.*
import org.junit.Test

class PrecipitationReliabilityTest {
  private val noon = 1_781_517_600_000L
  private val hour = 3_600_000L
  private fun row(offset: Double, probability: Double? = null, amount: Double? = null) = FusedHour(
    noon + (offset * hour).toLong() + hour, buildMap {
      put(FusionVariables.TEMPERATURE, FusedValue(20.0, emptyList()))
      probability?.let { put(FusionVariables.PRECIP_PROBABILITY, FusedValue(it, emptyList())) }
      amount?.let { put(FusionVariables.PRECIPITATION, FusedValue(it, emptyList())) }
    }, WeatherKind.RAIN,
    precipitationInterval = dev.pampa.fluidweather.core.model.WeatherInterval(
      noon + (offset * hour).toLong(), noon + (offset * hour).toLong() + hour),
  )

  @Test fun `missing precipitation cannot announce rain ending`() {
    assertNull(PrecipitationTransitions.next(listOf(row(0.0, 90.0), row(1.0)), noon))
  }

  @Test fun `missing current precipitation cannot announce rain onset`() {
    assertNull(PrecipitationTransitions.next(listOf(row(0.0), row(1.0, 90.0)), noon))
  }

  @Test fun `unknown intermediate hour cannot be skipped to infer an onset`() {
    assertNull(PrecipitationTransitions.next(
      listOf(row(0.0, 0.0), row(1.0), row(2.0, 90.0)), noon + 50 * 60_000L,
    ))
  }

  @Test fun `missing intervening hour cannot establish a transition`() {
    assertNull(PrecipitationTransitions.next(
      listOf(row(0.0, 90.0), row(2.0, 0.0)), noon + 50 * 60_000L,
    ))
  }

  @Test fun `future row is not a known current state`() {
    assertNull(PrecipitationTransitions.next(listOf(row(0.5, 0.0), row(1.0, 90.0)), noon))
  }

  @Test fun `nonfinite and invalid precipitation cannot announce an onset`() {
    for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 101.0)) {
      assertNull("invalid probability $invalid", PrecipitationTransitions.next(
        listOf(row(0.0, invalid), row(1.0, 90.0)), noon,
      ))
    }
    assertNull(PrecipitationTransitions.next(listOf(row(0.0, 0.0), row(1.0, amount = Double.POSITIVE_INFINITY)), noon))
  }

  @Test fun `known consecutive states still announce onset and end`() {
    assertEquals(TransitionKind.ONSET,
      PrecipitationTransitions.next(listOf(row(0.0, 0.0), row(1.0, 60.0)), noon)!!.kind)
    assertEquals(TransitionKind.END,
      PrecipitationTransitions.next(listOf(row(0.0, amount = 0.2), row(1.0, amount = 0.0)), noon)!!.kind)
  }

  @Test fun `normalized hour-end products announce the interval start`() {
    val current = row(0.0, 0.0).copy(timestampMillis = noon + hour)
    val next = row(1.0, 90.0).copy(timestampMillis = noon + 2 * hour)
    assertEquals(noon + hour, PrecipitationTransitions.next(listOf(current, next), noon + 30 * 60_000L)!!.atMillis)
  }

  @Test fun `legacy values without a rain interval cannot establish timing`() {
    assertNull(PrecipitationTransitions.next(listOf(row(0.0, 0.0).copy(precipitationInterval = null), row(1.0, 90.0)), noon))
  }
}
