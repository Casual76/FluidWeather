package dev.pampa.fluidweather.testbench.data

import java.io.File

/**
 * Un'ora di verita' storica per una stazione: il segnale che il telefono avrebbe misurato
 * (pressione di stazione), la riduzione ufficiale del provider (msl, che fa da riferimento per
 * il banco della riduzione), e le variabili che diventeranno feature negli stadi 4-5.
 */
data class HourlyRecord(
  val timestampMillis: Long,
  val temperatureC: Double?,
  val relativeHumidityPercent: Double?,
  val dewPointC: Double?,
  val surfacePressureHpa: Double?,
  val pressureMslHpa: Double?,
  val precipitationMm: Double?,
  val cloudCoverPercent: Double?,
  val windSpeedKmh: Double?,
  val windDirectionDeg: Double?,
  /**
   * Energia potenziale convettiva. **Sempre null in pratica**: l'archivio ERA5 accetta la
   * variabile, restituisce la colonna, e la riempie di NaN per tutte e dieci le localita' su
   * quattro anni (verificato 2026-09-10). Resta chiesta e parsata perche' il giorno in cui
   * l'archivio la riempira' bastera' rimetterla fra le feature — ma finche' e' vuota, addestrarci
   * sopra vorrebbe dire spedire un coefficiente stimato sul nulla.
   */
  val capeJkg: Double? = null,
)

/** Una localita' del banco: nome, coordinate richieste, e il file su cui vive. */
data class BenchLocation(
  val name: String,
  val latitude: Double,
  val longitude: Double,
  /** Perche' sta nel banco: la diversita' delle localita' e' meta' del suo valore. */
  val why: String,
)

/**
 * La costellazione del banco: latitudini da -34 a 64, equatore e poli barici, coste e valli
 * alpine, quota e pianura. Un motore tarato su un solo clima e' un motore tarato per sbagliare
 * altrove; ogni numero del banco si legge anche per-localita' proprio per scoprirlo.
 */
val BenchLocations: List<BenchLocation> = listOf(
  BenchLocation("sesto-fiorentino", 43.83, 11.20, "casa: il microclima che l'app abitera'"),
  BenchLocation("milano", 45.46, 9.19, "pianura padana: nebbie, inversioni, temporali estivi"),
  BenchLocation("genova", 44.41, 8.93, "costa ligure: alluvioni lampo, venti di caduta"),
  BenchLocation("innsbruck", 47.27, 11.39, "valle alpina: foehn e orografia difficile"),
  BenchLocation("bergen", 60.39, 5.32, "alta latitudine atlantica: fronti in fila indiana"),
  BenchLocation("reykjavik", 64.15, -21.94, "il laboratorio delle tempeste: cicloni profondi"),
  BenchLocation("singapore", 1.35, 103.82, "equatore: marea S2 massima, convezione quotidiana"),
  BenchLocation("tokyo", 35.68, 139.69, "stagione dei tifoni: cadute baromentriche estreme"),
  BenchLocation("denver", 39.74, -104.98, "continentale a 1600 m: la riduzione conta davvero"),
  BenchLocation("buenos-aires", -34.60, -58.38, "emisfero sud: stagioni invertite per i fattori"),
)

/**
 * Un dataset caricato: i record orari piu' i metadati che l'archivio dichiara (quota compresa,
 * che e' quella con cui si costruiscono i campioni sintetici del telefono).
 */
class StationDataset(
  val location: BenchLocation,
  val elevationMeters: Double,
  val records: List<HourlyRecord>,
) {

  init {
    require(records.isNotEmpty()) { "dataset vuoto per ${location.name}" }
  }

  val spanDays: Double
    get() = (records.last().timestampMillis - records.first().timestampMillis) / 86_400_000.0

  companion object {

    fun dataFile(location: BenchLocation): File = File("data/${location.name}.csv")

    fun isFetched(location: BenchLocation): Boolean = dataFile(location).exists()

    /**
     * Il formato e' quello che l'API restituisce, salvato cosi' com'e': due righe di metadati,
     * una vuota, l'intestazione oraria, le righe. Nessuna trasformazione fra il download e il
     * disco — quello che si rigioca e' quello che il provider ha detto.
     */
    fun load(location: BenchLocation): StationDataset =
      parse(dataFile(location).readLines(), location)

    fun parse(lines: List<String>, location: BenchLocation): StationDataset {
      require(lines.size > 4) { "file troppo corto per ${location.name}" }
      val meta = lines[1].split(",")
      val elevation = meta[2].toDouble()

      val headerIndex = lines.indexOfFirst { it.startsWith("time,") }
      require(headerIndex > 0) { "intestazione oraria mancante per ${location.name}" }

      val records = lines.drop(headerIndex + 1)
        .filter { it.isNotBlank() }
        .map { line ->
          val cells = line.split(",")
          HourlyRecord(
            timestampMillis = cells[0].toLong() * 1_000L,
            temperatureC = cells.numberAt(1),
            relativeHumidityPercent = cells.numberAt(2),
            dewPointC = cells.numberAt(3),
            surfacePressureHpa = cells.numberAt(4),
            pressureMslHpa = cells.numberAt(5),
            precipitationMm = cells.numberAt(6),
            cloudCoverPercent = cells.numberAt(7),
            windSpeedKmh = cells.numberAt(8),
            windDirectionDeg = cells.numberAt(9),
            capeJkg = cells.numberAt(10),
          )
        }
      return StationDataset(location, elevation, records)
    }

    private fun List<String>.numberAt(index: Int): Double? {
      val raw = getOrNull(index) ?: return null
      val value = raw.toDoubleOrNull() ?: return null
      return if (value.isNaN()) null else value
    }
  }
}
