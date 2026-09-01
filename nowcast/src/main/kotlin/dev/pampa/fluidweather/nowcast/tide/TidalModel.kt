package dev.pampa.fluidweather.nowcast.tide

/** Da dove viene il modello di marea in uso: la diagnostica lo dichiara sempre. */
enum class TideSource {
  /** Nessuna posizione nota: non si sottrae niente, e lo si dice. */
  NONE,

  /** Priori dalla letteratura, scalati per latitudine e stagione: il cold-start. */
  CLIMATOLOGICAL,

  /** Ampiezza e fase stimate dall'archivio locale: il modello che diventa tuo. */
  FITTED,
}

/**
 * La componente di marea atmosferica (S1 diurna + S2 semidiurna) da sottrarre al segnale.
 *
 * S1/S2 valgono fino a ~1-1,5 hPa alle basse latitudini: un algoritmo che non le sottrae
 * scambia il calo pomeridiano fisiologico per un fronte in arrivo. Alle basse latitudini e' la
 * differenza fra funzionare e non funzionare.
 */
interface TidalModel {
  val source: TideSource
  val s1AmplitudeHpa: Double
  val s2AmplitudeHpa: Double

  /** Il valore della marea a quell'istante, in hPa, da sottrarre alla serie ridotta al mare. */
  fun tideAt(timestampMillis: Long): Double
}

/** Il modello onesto quando non si sa dove ci si trova: zero, dichiarato. */
object ZeroTide : TidalModel {
  override val source = TideSource.NONE
  override val s1AmplitudeHpa = 0.0
  override val s2AmplitudeHpa = 0.0
  override fun tideAt(timestampMillis: Long): Double = 0.0
}

/** Il riassunto che la pipeline espone: cosa e' stato sottratto, quanto, e da quale modello. */
data class TidalSummary(
  val source: TideSource,
  val s1AmplitudeHpa: Double,
  val s2AmplitudeHpa: Double,
  /** La marea all'istante dell'ultimo punto: quanto sta "spingendo" adesso. */
  val tideAtLatestHpa: Double,
)
