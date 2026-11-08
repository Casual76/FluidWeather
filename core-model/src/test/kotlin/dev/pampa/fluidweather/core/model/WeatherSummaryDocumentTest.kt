package dev.pampa.fluidweather.core.model

import org.junit.Assert.*
import org.junit.Test

class WeatherSummaryDocumentTest {
  private val document = WeatherSummaryDocument("key", "revision", "en", UnitPreferences.METRIC, listOf(
    WeatherSummaryFact("place", "Rome", true), WeatherSummaryFact("rain", "Uncertain", true),
    WeatherSummaryFact("limit", "Sources disagree", true), WeatherSummaryFact("current", "Temperature")), listOf("rain"), 100)
  @Test fun `selection cannot drop location assessment or essential caveats`() {
    assertEquals("Rome\nUncertain\nSources disagree\nTemperature", document.render(listOf("current")))
  }
  @Test fun `invalid selection falls back without rendering unknown content`() {
    for (ids in listOf(emptyList(), listOf("invented"), listOf("current", "current"), List(9) { "current" })) {
      assertFalse(document.accepts(ids)); assertEquals(document.render(), document.render(ids))
    }
  }
}
