package dev.pampa.fluidweather.nowcast.features

import dev.pampa.fluidweather.nowcast.climatology.LocalPriors
import dev.pampa.fluidweather.nowcast.climatology.WindowClimatology
import dev.pampa.fluidweather.nowcast.cleaning.CleaningResult
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Le feature del v3, "Il tuo barometro" senza previsioni dei provider: quarantadue colonne, le
 * venti del v2 **invariate** (indici 0-19, bit per bit: [FeatureExtractor.extract] le produce e qui
 * si copiano) e ventidue in coda.
 *
 * L'ordine di [names] E' il contratto, come per il v2: medie, deviazioni e coefficienti di ogni
 * modello sono indicizzati su di esso, e cosi' [FeatureSubsets] (quali colonne vede ogni livello) e
 * gli indici di `Analogs`/`CONTEXT_MARKERS`, che si reggono sui primi venti. Cambiarlo vuol dire
 * riaddestrare.
 *
 * **Cosa c'e' in piu', e perche'.**
 * - *Contesto "adesso"* (20-26, NaN senza contesto): quanto e' vecchio il contesto, se sta piovendo
 *   (l'ultima ora chiusa, soglia dell'evento), quanta pioggia nelle ultime sei ore, da quante ore non
 *   piove, e le tendenze a tre ore di nuvole, punto di rugiada e temperatura. Sono fatti del
 *   presente: nessuna previsione, solo slot chiusi ([ContextSlots]).
 * - *Tempo* (27-29, sempre presenti): giorno dell'anno (seno e coseno, spostato di mezzo anno
 *   nell'emisfero sud) e un termine di convezione pomeridiana, il prodotto di "e' estate" per
 *   "e' pomeriggio" — la stagione e l'ora dei temporali, che la tendenza da sola non separa.
 * - *Le baseline come feature* (30-38, mai NaN): i logit della climatologia locale (una per
 *   finestra), della persistenza tarata sul ritardo del contesto e della regola barometrica sulla
 *   tendenza misurata. Sono **lo stesso codice** che il gate usa come avversarie ([LocalPriors]): dando
 *   coefficiente uno a una di loro il modello la riproduce esattamente, in ogni posto, e la garanzia
 *   D1 ("batte la migliore baseline") smette di dipendere da cio' che un modello condiviso riesce a
 *   imparare sul singolo posto. Il modello deve solo aggiungere, non riscoprire.
 * - *Livello e stato dei dati* (39-41): il livello del mare rispetto allo standard (con la quota di
 *   riferimento nota; NaN se non lo e'), se la normale dei 30 giorni manca, e se le tabelle sono
 *   quelle del posto o il riferimento di tutti i posti.
 *
 * **Rifiutate.** La variabilita' a 30 giorni (sul telefono la sua deviazione standard sarebbe
 * dominata dai cambi di quota dell'utente, e senza storia e' NaN per costruzione) e il dropout
 * casuale per feature (il contesto del telefono e' tutto-o-niente: un pacchetto best_match; un
 * regime a pezzi non esiste e addestrarci sopra costerebbe Brier).
 *
 * Le feature 0-19 vedono il contesto del v2 ([NowcastContext.rainLastHourMm], ecc.); le 20-26 le
 * rileggono dagli slot ([NowcastContext.rainSlotsMm], i campi "3 h fa"): la pioggia di adesso e'
 * sempre quella dello slot chiuso S, mai un quarto d'ora di un radar o del minutely (precondizione del v3).
 */
object FeatureExtractorV3 {

  const val VERSION: String = "features-v3"

  /** Le ventidue feature in coda, nell'ordine degli indici 20-41. */
  val TAIL: List<String> = listOf(
    "eta-contesto-ore",
    "piove-adesso",
    "pioggia-ultime-6h",
    "ore-da-ultima-pioggia",
    "tendenza-nuvole-3h",
    "tendenza-rugiada-3h",
    "tendenza-temperatura-3h",
    "giorno-sin",
    "giorno-cos",
    "convezione-pomeridiana",
    "clima-0-1h",
    "clima-1-3h",
    "clima-3-6h",
    "persistenza-0-1h",
    "persistenza-1-3h",
    "persistenza-3-6h",
    "regola-barometrica-0-1h",
    "regola-barometrica-1-3h",
    "regola-barometrica-3-6h",
    "livello-mare",
    "normale-assente",
    "clima-locale",
  )

  val names: List<String> = FeatureExtractor.names + TAIL

  const val COUNT: Int = 42

  // Gli indici delle feature in coda: i nomi che il resto del codice usa al posto dei numeri.
  const val CONTEXT_AGE: Int = 20
  const val RAINING_NOW: Int = 21
  const val RAIN_LAST_6H: Int = 22
  const val HOURS_SINCE_RAIN: Int = 23
  const val CLOUD_TREND: Int = 24
  const val DEW_POINT_TREND: Int = 25
  const val TEMPERATURE_TREND: Int = 26
  const val DAY_SIN: Int = 27
  const val DAY_COS: Int = 28
  const val CONVECTION: Int = 29

  /** Prima delle tre colonne di ogni blocco di baseline: +0, +1, +2 per finestra. */
  const val CLIMATOLOGY: Int = 30
  const val PERSISTENCE: Int = 33
  const val BAROMETRIC_RULE: Int = 36
  const val SEA_LEVEL: Int = 39
  const val NORMAL_MISSING: Int = 40
  const val LOCAL_TABLES: Int = 41

  /** La tendenza misurata a 3 ore (hPa/h) che legge la regola barometrica: e' la feature 1 del v2. */
  private const val TREND_3H: Int = 1

  /** Il livello standard contro cui si misura [SEA_LEVEL]. */
  const val STANDARD_SEA_LEVEL_HPA: Double = 1013.25

  /** L'eta' del contesto si ritaglia a [0, 14] ore: STALE arriva a 12 h piu' l'ora dello slot e il minuto di emissione. */
  const val MAX_CONTEXT_AGE_HOURS: Double = 14.0

  /** Lo spostamento di stagione nell'emisfero sud: mezzo anno in giorni. */
  private const val SOUTHERN_SHIFT_DAYS = 182.62
  private const val YEAR_DAYS = 365.2425

  /** Il giorno dell'anno del massimo della convezione: meta' luglio. */
  private const val CONVECTION_PEAK_DAY = 196.0

  /** L'ora solare del massimo della convezione. */
  private const val CONVECTION_PEAK_HOUR = 15.0

  /**
   * Le quarantadue feature del verdetto emesso a [nowMillis]; null dove il v2 darebbe null (meno di
   * 13 ore di storia filtrata).
   *
   * [priors] sono le tabelle delle baseline per quel posto e livello ([LocalPriors.local] o
   * [LocalPriors.pooledOnly]). [referenceAltitudeKnown] dice se il telefono conosce la quota di
   * riferimento (senza, il livello del mare e' un numero che non vuol dire niente: NaN).
   * [levelBiasHpa] e' **solo del banco**: simula l'errore della quota di riferimento; il telefono
   * passa sempre zero.
   */
  fun extract(
    cleaning: CleaningResult,
    context: NowcastContext?,
    normalHpa: Double?,
    nowMillis: Long,
    priors: LocalPriors,
    referenceAltitudeKnown: Boolean = true,
    levelBiasHpa: Double = 0.0,
  ): DoubleArray? {
    val base = FeatureExtractor.extract(cleaning, context, normalHpa, nowMillis) ?: return null
    val latest = cleaning.filtered.last()
    val features = DoubleArray(COUNT) { Double.NaN }
    base.copyInto(features)

    // --- Contesto: solo slot chiusi, NaN dove il dato manca.
    val slotEnd = context?.slotEndMillis
    if (context != null && slotEnd != null) {
      features[CONTEXT_AGE] = ((nowMillis - slotEnd) / RainWindows.HOUR_MILLIS.toDouble()).coerceIn(0.0, MAX_CONTEXT_AGE_HOURS)
    }
    val slots = context?.rainSlotsMm
    val rainNow: Double? = if (slots != null) slots.getOrNull(0) else context?.rainLastHourMm
    if (rainNow != null) features[RAINING_NOW] = if (RainWindows.isWet(rainNow)) 1.0 else 0.0
    if (slots != null && slots.size >= ContextSlots.RAIN_6H_SLOTS) {
      val lastSix = slots.subList(0, ContextSlots.RAIN_6H_SLOTS)
      if (lastSix.all { it != null }) features[RAIN_LAST_6H] = ln(1.0 + lastSix.sumOf { max(0.0, it!!) })
      features[HOURS_SINCE_RAIN] = hoursSinceRain(lastSix)
    }
    features[CLOUD_TREND] = difference(context?.cloudCoverPercent, context?.cloudCover3hAgoPercent)
    features[DEW_POINT_TREND] = difference(context?.dewPointC, context?.dewPoint3hAgoC)
    features[TEMPERATURE_TREND] = difference(context?.temperatureC, context?.temperature3hAgoC)

    // --- Tempo: stagione e ora solare del posto.
    val latitude = medianOf(cleaning.cleaned.mapNotNull { it.latitude })
    val longitude = medianOf(cleaning.cleaned.mapNotNull { it.longitude })
    val dayOfYear = java.time.LocalDate.ofEpochDay(Math.floorDiv(nowMillis, DAY_MILLIS)).dayOfYear
    val secondOfDay = Math.floorMod(nowMillis, DAY_MILLIS) / 1_000.0
    val day = (dayOfYear - 1) + secondOfDay / 86_400.0 + if (latitude < 0) SOUTHERN_SHIFT_DAYS else 0.0
    val seasonAngle = 2 * PI * day / YEAR_DAYS
    features[DAY_SIN] = sin(seasonAngle)
    features[DAY_COS] = cos(seasonAngle)
    val utcHours = Math.floorMod(nowMillis, DAY_MILLIS) / 3_600_000.0
    val solarHours = ((utcHours + longitude / 15.0) % 24.0 + 24.0) % 24.0
    features[CONVECTION] = max(0.0, cos(2 * PI * (day - CONVECTION_PEAK_DAY) / YEAR_DAYS)) *
      max(0.0, cos(2 * PI * (solarHours - CONVECTION_PEAK_HOUR) / 24.0))

    // --- Le baseline come feature: lo stesso LocalPriors che le giudica.
    val trend = features[TREND_3H].takeUnless { it.isNaN() }
    for ((w, window) in RainWindows.ALL.withIndex()) {
      val clima = priors.climatology(window, nowMillis)
      features[CLIMATOLOGY + w] = WindowClimatology.logitOf(clima)
      if (context != null) {
        // Con il contesto ma senza "piove adesso" (o senza slot da cui leggere il ritardo) la
        // persistenza non sa niente e dice la climatologia, come la regola barometrica senza tendenza.
        val persistence = if (rainNow != null && slotEnd != null) priors.persistence(window, nowMillis, rainNow, slotEnd) else null
        features[PERSISTENCE + w] = WindowClimatology.logitOf(persistence ?: clima)
      }
      features[BAROMETRIC_RULE + w] = WindowClimatology.logitOf(priors.barometric(window, nowMillis, trend))
    }

    // --- Livello e stato dei dati.
    if (referenceAltitudeKnown) features[SEA_LEVEL] = latest.levelHpa - STANDARD_SEA_LEVEL_HPA + levelBiasHpa
    features[NORMAL_MISSING] = if (normalHpa == null) 1.0 else 0.0
    features[LOCAL_TABLES] = if (priors.isLocal) 1.0 else 0.0
    return features
  }

  /**
   * Da quante ore non piove: scorre gli slot dal piu' recente; un buco prima di aver trovato
   * pioggia rende il numero sconosciuto (NaN), nessuna pioggia negli slot vale sei.
   */
  private fun hoursSinceRain(lastSix: List<Double?>): Double {
    for ((k, mm) in lastSix.withIndex()) {
      if (mm == null) return Double.NaN
      if (RainWindows.isWet(mm)) return k.toDouble()
    }
    return ContextSlots.RAIN_6H_SLOTS.toDouble()
  }

  private fun difference(now: Double?, before: Double?): Double =
    if (now == null || before == null) Double.NaN else now - before

  /** La mediana come la prende [FeatureExtractor] per la longitudine: l'elemento di mezzo dell'ordinata; zero se non ce ne sono. */
  private fun medianOf(values: List<Double>): Double =
    if (values.isEmpty()) 0.0 else values.sorted()[values.size / 2]

  private const val DAY_MILLIS = 86_400_000L
}
