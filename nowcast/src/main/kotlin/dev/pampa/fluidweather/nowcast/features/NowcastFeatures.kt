package dev.pampa.fluidweather.nowcast.features

import dev.pampa.fluidweather.nowcast.cleaning.CleaningResult
import dev.pampa.fluidweather.nowcast.cleaning.FilteredPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Il contesto sinottico che i provider portano e che il banco pesca dagli archivi.
 * Tutto nullable: il motore deve funzionare anche col solo barometro, e un null diventa
 * "valore neutro" dentro al modello — mai un'invenzione.
 */
data class NowcastContext(
  val relativeHumidityPercent: Double? = null,
  val dewPointSpreadC: Double? = null,
  val cloudCoverPercent: Double? = null,
  val windSpeedKmh: Double? = null,
  val windDirectionDeg: Double? = null,
  val windDirectionDeg3hAgo: Double? = null,
  val rainLastHourMm: Double? = null,
  val rainLast3hMm: Double? = null,
  /**
   * La pressione al mare del provider adesso e tre ore fa: da qui esce la tendenza *sinottica*,
   * quella del modello, da mettere accanto alla tendenza *locale* che misura il barometro. Il
   * valore aggiunto di un barometro in tasca non e' sapere che la pressione scende — lo sa anche
   * il modello — e' vedere quando scende piu' in fretta di quanto il modello si aspetti.
   */
  val pressureMslHpa: Double? = null,
  val pressureMsl3hAgoHpa: Double? = null,
)

/**
 * Stadio 4: dalle serie pulite alle feature, con nomi che un essere umano puo' leggere in un
 * verdetto. L'ordine di [names] E' il contratto: coefficienti, medie e deviazioni del modello
 * addestrato sono indicizzati su di esso, e lo e' anche la colonna delle feature nell'archivio
 * dell'apprendimento — cambiarlo vuol dire riaddestrare e ignorare i vettori vecchi.
 *
 * Le feature mancanti valgono NaN qui e vengono imputate al neutro (media di addestramento)
 * dentro al modello: cosi' un telefono senza provider produce comunque un verdetto, e ogni
 * pezzo di contesto in piu' lo affina invece di cambiarne la natura.
 *
 * **Cosa e' cambiato, e perche'.** Fuori la vecchia incertezza-tendenza: a banco era costante
 * (campioni orari regolari, filtro che converge sempre allo stesso sigma), quindi la sua
 * deviazione finiva sul pavimento di 1e-6 e sul telefono — dove costante non e' — il rapporto
 * fra peso e deviazione la trasformava in un moltiplicatore diretto sui log-odds, con segno
 * positivo: piu' il filtro era incerto, piu' pioggia. Non era una grandezza meteorologica, era
 * un baco con un nome.
 *
 * Dentro la tendenza del provider sulle stesse tre ore. Da sola non aggiunge niente al modello
 * globale che l'ha prodotta; accanto alla tendenza locale dice l'unica cosa che un barometro in
 * tasca sa e un modello no — se qui sta succedendo prima. (Cape sarebbe stata la scelta ovvia,
 * ed e' rimasta fuori per una ragione precisa: l'archivio ERA5 offre la colonna e non la riempie,
 * e addestrare su una colonna vuota e' esattamente il baco appena tolto.)
 */
object FeatureExtractor {

  val names: List<String> = listOf(
    "tendenza-1h",
    "tendenza-3h",
    "tendenza-6h",
    "tendenza-12h",
    "accelerazione-3h",
    "anomalia-livello",
    "caduta-3h",
    "caduta-con-aria-umida",
    "umidita",
    "spread-rugiada",
    "copertura",
    "cielo-coperto",
    "aria-satura",
    "vento",
    "rotazione-vento-3h",
    "pioggia-ultima-ora",
    "pioggia-ultime-3h",
    "tendenza-provider-3h",
    "ora-sin",
    "ora-cos",
  )

  const val MIN_HISTORY_HOURS = 13.0

  /** Umidita, copertura, vento: se c'e' uno di questi, il contesto dei provider e' arrivato. */
  private val CONTEXT_MARKERS = intArrayOf(8, 10, 13)

  /**
   * Questo vettore ha avuto il contesto meteo dei provider, o e' un verdetto del solo barometro?
   *
   * Serve a non mescolare due popolazioni nella taratura. Senza contesto otto feature su sedici
   * sono NaN e il modello le imputa alla media: e' una modalita' di funzionamento vera e voluta
   * (un telefono senza rete un verdetto lo da' lo stesso), ma la probabilita' grezza che ne esce
   * ha una distribuzione diversa. Tararci sopra una mappa sola vuol dire tararla su un ingresso
   * bimodale. Si guardano tre marcatori e non tutte le feature del contesto: la rotazione del
   * vento e' NaN anche col contesto, quando manca il punto di tre ore fa.
   */
  fun hasContext(features: DoubleArray): Boolean =
    CONTEXT_MARKERS.any { it < features.size && !features[it].isNaN() }

  /**
   * Null quando la storia filtrata non copre nemmeno le 13 ore che servono alla tendenza piu'
   * lunga: meglio nessun verdetto che un verdetto costruito sul vuoto.
   */
  fun extract(
    cleaning: CleaningResult,
    context: NowcastContext?,
    /** La normale del punto: media del livello sui 30 giorni precedenti; null = ignota. */
    normalHpa: Double?,
    nowMillis: Long,
  ): DoubleArray? {
    val filtered = cleaning.filtered
    val latest = filtered.lastOrNull() ?: return null
    val spanHours = (latest.timestampMillis - filtered.first().timestampMillis) / 3_600_000.0
    if (spanHours < MIN_HISTORY_HOURS) return null

    val trend1 = slope(filtered, nowMillis, 1.0)
    val trend3 = slope(filtered, nowMillis, 3.0)
    val trend6 = slope(filtered, nowMillis, 6.0)
    val trend12 = slope(filtered, nowMillis, 12.0)

    // Accelerazione: la tendenza a 3 ore di adesso contro quella di 3 ore fa. Un fronte che
    // arriva non e' solo "scende": e' "scende sempre piu' in fretta".
    val previous3 = slopeBetween(filtered, nowMillis - 3 * 3_600_000L, 3.0)
    val acceleration = if (trend3.isNaN() || previous3.isNaN()) Double.NaN else trend3 - previous3

    val anomaly = if (normalHpa == null) Double.NaN else latest.levelHpa - normalHpa

    val rotation = wrapDegrees(context?.windDirectionDeg, context?.windDirectionDeg3hAgo)

    // La tendenza del modello sulle stesse tre ore. Non e' ridondante con tendenza-3h: e' il suo
    // metro di paragone, e la differenza fra le due e' l'unica cosa che un telefono sa e un
    // modello globale no.
    val now = context?.pressureMslHpa
    val before = context?.pressureMsl3hAgoHpa
    val providerTrend = if (now == null || before == null) Double.NaN else (now - before) / 3.0

    // Ora solare media del posto quando le coordinate ci sono (la convezione pomeridiana e' un
    // fatto solare, non di fuso); UTC come ripiego dichiarato.
    val longitude = cleaning.cleaned.mapNotNull { it.longitude }.sorted().let { sorted ->
      if (sorted.isEmpty()) 0.0 else sorted[sorted.size / 2]
    }
    val utcHours = Math.floorMod(nowMillis, 86_400_000L) / 3_600_000.0
    val solarHours = ((utcHours + longitude / 15.0) % 24.0 + 24.0) % 24.0
    val solarAngle = 2 * PI * solarHours / 24.0

    val humidity = context?.relativeHumidityPercent
    val cloud = context?.cloudCoverPercent

    // La caduta, separata dalla salita. La pressione che scende annuncia pioggia; la pressione
    // che sale non annuncia il contrario con la stessa forza, e un coefficiente solo era
    // costretto a fare la media fra i due mestieri.
    val fall = if (trend3.isNaN()) Double.NaN else minOf(trend3, 0.0)
    // E la caduta conta quando c'e' acqua da far cadere: la stessa caduta con aria secca non
    // porta niente. E' l'unica interazione che il barometro chiede davvero.
    val fallInMoistAir = if (fall.isNaN() || humidity == null) Double.NaN else fall * humidity / 100.0

    // Il cielo non e' lineare: fra il 10 e il 40 per cento di copertura non cambia quasi niente,
    // fra il 70 e il 100 cambia tutto. La rampa dice dove sta la differenza.
    val overcast = if (cloud == null) Double.NaN else maxOf(0.0, cloud - OVERCAST_FROM_PERCENT) / (100.0 - OVERCAST_FROM_PERCENT)
    val saturated = if (humidity == null || cloud == null) Double.NaN else (humidity / 100.0) * (cloud / 100.0)

    return doubleArrayOf(
      trend1,
      trend3,
      trend6,
      trend12,
      acceleration,
      anomaly,
      fall,
      fallInMoistAir,
      humidity ?: Double.NaN,
      context?.dewPointSpreadC ?: Double.NaN,
      cloud ?: Double.NaN,
      overcast,
      saturated,
      context?.windSpeedKmh ?: Double.NaN,
      rotation,
      // Millimetri in scala logaritmica: fra zero e mezzo millimetro c'e' tutta la differenza
      // fra asciutto e bagnato, fra dieci e dieci e mezzo non c'e' niente. La feature si chiama
      // ancora "pioggia dell'ultima ora" perche' e' quello che e' — cambia il righello.
      logMillimetres(context?.rainLastHourMm),
      logMillimetres(context?.rainLast3hMm),
      providerTrend,
      sin(solarAngle),
      cos(solarAngle),
    )
  }

  /** Sopra questa copertura il cielo comincia davvero a pesare. */
  private const val OVERCAST_FROM_PERCENT = 70.0

  private fun logMillimetres(value: Double?): Double =
    if (value == null) Double.NaN else kotlin.math.ln(1.0 + maxOf(0.0, value))

  /** Pendenza (hPa/h) fra il livello filtrato "adesso" e quello [lookbackHours] fa. */
  private fun slope(filtered: List<FilteredPoint>, nowMillis: Long, lookbackHours: Double): Double =
    slopeBetween(filtered, nowMillis, lookbackHours)

  private fun slopeBetween(
    filtered: List<FilteredPoint>,
    endMillis: Long,
    lookbackHours: Double,
  ): Double {
    val end = nearest(filtered, endMillis, toleranceHours = lookbackHours / 3) ?: return Double.NaN
    val startMillis = endMillis - (lookbackHours * 3_600_000L).toLong()
    val start = nearest(filtered, startMillis, toleranceHours = lookbackHours / 3) ?: return Double.NaN
    val dtHours = (end.timestampMillis - start.timestampMillis) / 3_600_000.0
    if (dtHours < lookbackHours / 2) return Double.NaN
    return (end.levelHpa - start.levelHpa) / dtHours
  }

  private fun nearest(
    filtered: List<FilteredPoint>,
    targetMillis: Long,
    toleranceHours: Double,
  ): FilteredPoint? = filtered
    .minByOrNull { abs(it.timestampMillis - targetMillis) }
    ?.takeIf { abs(it.timestampMillis - targetMillis) <= toleranceHours * 3_600_000L }

  /** Differenza angolare avvolta fra meno e piu' 180: da 350 a 10 gradi vale piu' 20. */
  private fun wrapDegrees(now: Double?, before: Double?): Double {
    if (now == null || before == null) return Double.NaN
    var delta = now - before
    while (delta > 180) delta -= 360
    while (delta < -180) delta += 360
    return delta
  }
}
