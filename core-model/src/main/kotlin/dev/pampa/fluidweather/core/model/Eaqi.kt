package dev.pampa.fluidweather.core.model

/**
 * L'indice europeo della qualita' dell'aria (EAQI, EEA), inquinante per inquinante: ogni
 * inquinante ha le SUE soglie di banda, e il sotto-indice e' l'interpolazione lineare fra le
 * soglie sulla scala 0-20-40-60-80-100. L'indice del punto e' il peggiore dei sotto-indici;
 * l'inquinante dominante e' quello che lo produce. Numeri della scala ufficiale EEA
 * (concentrazioni in ug/m3; PM su media 24h, gas su media oraria).
 */
object Eaqi {

  /** Le soglie superiori delle sei bande, per inquinante (Buona, Discreta, Moderata, Scarsa, Molto scarsa, Pessima). */
  private val thresholds: Map<String, DoubleArray> = mapOf(
    "PM2.5" to doubleArrayOf(10.0, 20.0, 25.0, 50.0, 75.0, 800.0),
    "PM10" to doubleArrayOf(20.0, 40.0, 50.0, 100.0, 150.0, 1200.0),
    "NO2" to doubleArrayOf(40.0, 90.0, 120.0, 230.0, 340.0, 1000.0),
    "Ozono" to doubleArrayOf(50.0, 100.0, 130.0, 240.0, 380.0, 800.0),
    "SO2" to doubleArrayOf(100.0, 200.0, 350.0, 500.0, 750.0, 1250.0),
  )

  val pollutants: List<String> get() = thresholds.keys.toList()

  /** Il sotto-indice EAQI (0..100+) di una concentrazione; null per un inquinante sconosciuto. */
  fun subIndex(pollutant: String, valueUgm3: Double): Double? {
    val bands = thresholds[pollutant] ?: return null
    val value = valueUgm3.coerceAtLeast(0.0)
    var lower = 0.0
    for (i in bands.indices) {
      val upper = bands[i]
      if (value <= upper) {
        val fraction = if (upper > lower) (value - lower) / (upper - lower) else 0.0
        return 20.0 * i + 20.0 * fraction
      }
      lower = upper
    }
    // Oltre l'ultima soglia: fuori scala, e lo si dice con un numero sopra il 100.
    return 100.0 + (value - bands.last()) / bands.last() * 20.0
  }

  fun bandOf(pollutant: String, valueUgm3: Double): AqiBand? =
    subIndex(pollutant, valueUgm3)?.let { AqiBand.of(it.toInt()) }
}
