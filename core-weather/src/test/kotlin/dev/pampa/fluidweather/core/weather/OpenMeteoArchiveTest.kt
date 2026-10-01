package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import java.time.LocalDate
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * L'archivio historical-forecast nelle sue due forme: le chiavi con suffisso quando i modelli sono
 * piu' d'uno, la chiave nuda quando e' uno solo (verificate sull'API vera il 2026-09-30).
 */
class OpenMeteoArchiveTest {

  @Test
  fun `l'URL ha esattamente la forma dell'archivio`() {
    val url = OpenMeteoArchive.url(
      43.832,
      11.199,
      LocalDate.of(2026, 9, 1),
      LocalDate.of(2026, 9, 2),
      "precipitation",
      TruthPanel.MODELS,
    )

    assertEquals(
      "https://historical-forecast-api.open-meteo.com/v1/forecast?latitude=43.832&longitude=11.199" +
        "&start_date=2026-09-01&end_date=2026-09-02&hourly=precipitation" +
        "&models=meteofrance_seamless,ukmo_seamless,gem_seamless&timeformat=unixtime&timezone=UTC",
      url,
    )
  }

  @Test
  fun `le coordinate non finiscono mai in notazione scientifica`() {
    val url = OpenMeteoArchive.url(0.0001, -58.375, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), "pressure_msl", listOf("meteofrance_seamless"))

    assertFalse(url.contains("E-"))
    assertEquals(true, url.contains("latitude=0.0001&longitude=-58.375&"))
  }

  @Test
  fun `con piu' modelli ogni serie ha la sua chiave, i buchi restano buchi, i secondi diventano millisecondi`() {
    val times = listOf(MIDNIGHT + HOUR, MIDNIGHT + 2 * HOUR, MIDNIGHT + 3 * HOUR)
    val root = Json.parseToJsonElement(
      archiveJson(
        times,
        mapOf(
          "precipitation_meteofrance_seamless" to listOf(0.0, 0.4, null),
          "precipitation_ukmo_seamless" to listOf(0.1, 0.2, 0.3),
        ),
      ),
    )

    val series = OpenMeteoArchive.hourlySeries(root, "precipitation", listOf("meteofrance_seamless", "ukmo_seamless", "gem_seamless"))

    assertEquals(mapOf(MIDNIGHT + HOUR to 0.0, MIDNIGHT + 2 * HOUR to 0.4), series["meteofrance_seamless"])
    assertEquals(0.3, series.getValue("ukmo_seamless").getValue(MIDNIGHT + 3 * HOUR), 1e-9)
    // Un giudice che non ha risposto non c'e': non una serie vuota spacciata per "zero pioggia".
    assertFalse(series.containsKey("gem_seamless"))
  }

  @Test
  fun `con un modello solo la chiave e' nuda`() {
    val root = Json.parseToJsonElement(archiveJson(listOf(MIDNIGHT), mapOf("pressure_msl" to listOf(1013.2))))

    val series = OpenMeteoArchive.hourlySeries(root, "pressure_msl", listOf("meteofrance_seamless"))

    assertEquals(mapOf(MIDNIGHT to 1013.2), series["meteofrance_seamless"])
  }
}
