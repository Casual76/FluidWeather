package dev.pampa.fluidweather.core.model

/** La scala EAQI europea, con soglie e ordine: le parole e il colore li decide la UI. */
enum class AqiBand(val maxInclusive: Int) {
  GOOD(20),
  FAIR(40),
  MODERATE(60),
  POOR(80),
  VERY_POOR(100),
  EXTREMELY_POOR(Int.MAX_VALUE);

  companion object {
    fun of(europeanAqi: Int): AqiBand = entries.first { europeanAqi <= it.maxInclusive }
  }
}

data class PollenLevels(
  val alder: Double?,
  val birch: Double?,
  val grass: Double?,
  val olive: Double?,
  val ragweed: Double?,
) {
  val any: Boolean get() = listOfNotNull(alder, birch, grass, olive, ragweed).any { it > 0 }
}

/** Un inquinante adesso: concentrazione e sotto-indice EAQI sulla SUA scala. */
data class Pollutant(
  val name: String,
  val valueUgm3: Double,
  val subIndex: Double,
) {
  val band: AqiBand get() = AqiBand.of(subIndex.toInt())
}

/** Un punto della previsione dell'indice. */
data class AqiPoint(val timestampMillis: Long, val europeanAqi: Int)

/** L'ora corrente della qualita' dell'aria, gia' interpretata. */
data class AirQualityNow(
  val europeanAqi: Int,
  val band: AqiBand,
  /** L'inquinante che comanda l'indice adesso, col suo valore in ug/m3. */
  val dominantPollutant: String?,
  val dominantValue: Double?,
  /** Solo Europa: fuori, il modello CAMS non serve pollini e il campo resta null. */
  val pollen: PollenLevels?,
  /** Tutti gli inquinanti misurati, col loro sotto-indice: la pagina li elenca uno a uno. */
  val pollutants: List<Pollutant> = emptyList(),
  /** La previsione dell'indice, ora per ora, da adesso in avanti. */
  val forecast: List<AqiPoint> = emptyList(),
)
