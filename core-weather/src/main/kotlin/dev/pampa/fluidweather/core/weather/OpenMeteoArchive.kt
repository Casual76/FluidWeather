package dev.pampa.fluidweather.core.weather

import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.serialization.json.JsonElement

/**
 * L'archivio historical-forecast di Open-Meteo: le prime ore di ogni corsa, incollate. E' la
 * stessa fonte da cui il banco di prova prende le etichette, ed e' per questo che il telefono ci
 * va a prendere la verita' della pioggia e la climatologia del posto: giudicare il telefono con
 * un dato diverso da quello su cui il modello e' stato addestrato vorrebbe dire fargli una domanda
 * nuova.
 *
 * Due forme di risposta, verificate il 2026-09-30: con piu' modelli le chiavi sono
 * `<variabile>_<modello>`, con un modello solo la chiave e' la variabile nuda. Il tempo e' in
 * secondi Unix (con `timeformat=unixtime`) e marca la **fine** dell'ora: la precipitazione delle
 * 10:00 e' quella caduta fra le 9:00 e le 10:00, la stessa convenzione degli slot di
 * [dev.pampa.fluidweather.nowcast.truth.RainWindows]. La risposta arriva fino alla fine del
 * giorno UTC corrente, e le ore recenti sono ancora previsioni: chi giudica controlla la finalita'.
 *
 * Dati: Weather data by Open-Meteo.com (CC BY 4.0).
 */
object OpenMeteoArchive {

  const val BASE_URL: String = "https://historical-forecast-api.open-meteo.com/v1/forecast"

  /**
   * `...?latitude=<lat>&longitude=<lon>&start_date=<yyyy-MM-dd>&end_date=<yyyy-MM-dd>&hourly=<variabile>&models=<m1,m2..>&timeformat=unixtime&timezone=UTC`.
   * Le coordinate si scrivono senza notazione scientifica e senza zeri in coda: 43.832, non
   * 43.832000000000001 ne' 4.3832E1.
   */
  fun url(
    latitude: Double,
    longitude: Double,
    startDate: LocalDate,
    endDate: LocalDate,
    variable: String,
    models: List<String>,
  ): String = buildString {
    append(BASE_URL)
    append("?latitude=").append(coordinate(latitude))
    append("&longitude=").append(coordinate(longitude))
    append("&start_date=").append(startDate)
    append("&end_date=").append(endDate)
    append("&hourly=").append(variable)
    append("&models=").append(models.joinToString(","))
    append("&timeformat=unixtime&timezone=UTC")
  }

  /**
   * modello -> (fine dello slot in ms -> valore). Un modello senza serie nella risposta non
   * compare; valori null o NaN non compaiono (un buco e' un buco, non uno zero).
   */
  fun hourlySeries(root: JsonElement, variable: String, models: List<String>): Map<String, Map<Long, Double>> {
    val hourly = root["hourly"]
    val times = hourly["time"].asArray().map { it.double()?.toLong()?.times(1_000L) }
    val series = LinkedHashMap<String, Map<Long, Double>>()
    for (model in models) {
      val values = hourly["${variable}_$model"]
        ?: (if (models.size == 1) hourly[variable] else null)
        ?: continue
      val points = LinkedHashMap<Long, Double>()
      values.asArray().forEachIndexed { index, element ->
        val time = times.getOrNull(index) ?: return@forEachIndexed
        val value = element.double()?.takeUnless { it.isNaN() } ?: return@forEachIndexed
        points[time] = value
      }
      series[model] = points
    }
    return series
  }

  private fun coordinate(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}
