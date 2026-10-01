package dev.pampa.fluidweather.testbench.data

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** Localita' di prova: due bastano a distinguere "la localita' giusta" da "la prima che capita". */
internal val TestLocations = listOf(
  BenchLocation("alfa", 43.83, 11.20, "fixture"),
  BenchLocation("beta", 45.46, 9.19, "fixture"),
)

internal object Fixtures {

  /**
   * Una risposta della Single Runs API a piu' localita': `location_id`, ora 0 nulla in precipitazione
   * (come le corse vere), precipitazione `loc*0.1 + h*0.01`, pressione `1000 + loc + h*0.1`.
   */
  fun runCsv(locations: Int, firstEpochSeconds: Long, hours: Int): String = buildString {
    appendLine("location_id,latitude,longitude,elevation,utc_offset_seconds,timezone,timezone_abbreviation")
    for (loc in 0 until locations) appendLine("$loc,43.83128,11.164902,59.0,0,GMT,GMT")
    appendLine()
    appendLine("location_id,time,precipitation (mm),pressure_msl (hPa)")
    for (loc in 0 until locations) {
      for (h in 0 until hours) {
        val rain = if (h == 0) "NaN" else String.format(Locale.ROOT, "%.2f", loc * 0.1 + h * 0.01)
        appendLine("$loc,${firstEpochSeconds + 3_600L * h},$rain,${String.format(Locale.ROOT, "%.1f", 1000.0 + loc + h * 0.1)}")
      }
    }
  }

  /** Una risposta dell'historical-forecast-api a un modello e una localita', un'ora per riga. */
  fun hfCsv(startDay: LocalDate, endDay: LocalDate, variables: List<String>, stepSeconds: Long = 3_600L): String = buildString {
    appendLine("latitude,longitude,elevation,utc_offset_seconds,timezone,timezone_abbreviation")
    appendLine("43.83,11.199999,59.0,0,GMT,GMT")
    appendLine()
    appendLine("time," + variables.joinToString(",") { "$it (x)" })
    var t = startDay.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    val end = endDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    while (t < end) {
      // Il valore e' una funzione dell'istante: si puo' controllare che i pezzi si riattacchino.
      appendLine("$t," + variables.indices.joinToString(",") { i -> "${(t / 3_600L) % 100 + i}" })
      t += stepSeconds
    }
  }

  /** L'istante (secondi UTC) di una data e ora. */
  fun epoch(day: String, hour: Int = 0): Long =
    Instant.parse("${day}T${"%02d".format(hour)}:00:00Z").epochSecond

  fun queryParam(url: String, name: String): String =
    Regex("[?&]$name=([^&]*)").find(url)?.groupValues?.get(1) ?: error("parametro $name assente in $url")

  /** Scrive un file creando le cartelle. */
  fun write(file: File, text: String) {
    file.parentFile.mkdirs()
    file.writeText(text)
  }
}
