package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.ForecastVerification
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.HorizonBucket
import dev.pampa.fluidweather.core.model.Observation
import dev.pampa.fluidweather.core.model.PendingPrediction
import dev.pampa.fluidweather.core.model.VerificationStore
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import kotlin.math.abs

/**
 * L'evento "piove?" sulle tre finestre del nowcast: e' la variabile su cui il barometro del
 * telefono e i provider si confrontano ALLA PARI (piano, pagina Benchmark). La previsione e' una
 * probabilita' [0,1], la verita' e' 0/1 (almeno un'ora della finestra con pioggia misurabile
 * nelle analisi), l'errore e' |p - y|: e' il Brier "in valore assoluto", e vale per chiunque.
 *
 * I provider parlano per ore, il barometro per finestre: la probabilita' di un provider sulla
 * finestra e' il MASSIMO delle sue probabilita' orarie dentro la finestra (la convenzione delle
 * app meteo per la "probabilita' di pioggia nel periodo"), e lo si dichiara.
 */
object RainEvent {
  const val PREFIX = "rain_event_"

  /** Il barometro del telefono, come provider fra i provider. */
  const val LOCAL_BAROMETER_ID = "barometro"

  data class Window(val variable: String, val nowcastLabel: String, val fromHour: Int, val toHour: Int)

  val windows: List<Window> = listOf(
    Window("${PREFIX}0_1", "0-1h", 0, 1),
    Window("${PREFIX}1_3", "1-3h", 1, 3),
    Window("${PREFIX}3_6", "3-6h", 3, 6),
  )

  fun isRainEvent(variable: String): Boolean = variable.startsWith(PREFIX)

  fun windowOf(variable: String): Window? = windows.firstOrNull { it.variable == variable }

  /** Pioggia "misurabile": sotto 0,1 mm/h le analisi dicono umido, non piovoso. */
  const val WET_MM: Double = 0.1
}

/**
 * La verifica automatica: ogni fetch semina previsioni "in attesa" a +1/+3/+6/+12/+24 ore, e
 * quando quelle ore sono passate le giudica contro la verita' di riferimento.
 *
 * La verita' automatica e' la MEDIANA delle analisi dei provider per quell'ora (l'ora appena
 * passata di un fetch fresco e' analisi, non previsione): robusta, senza un giudice unico che
 * possa portare acqua al suo mulino. Serve il quorum di [minTruthOpinions] opinioni — sotto,
 * meglio nessun giudizio che un giudizio di parte. Il barometro locale entra in classifica
 * alla pari sull'evento pioggia ([RainEvent]), con [registerBarometer].
 */
class ForecastVerifier(
  private val store: VerificationStore,
  private val minTruthOpinions: Int = 3,
  private val clock: () -> Long = System::currentTimeMillis,
  /** Chiamato per ogni giudizio emesso (previsione, verita'): l'apprendimento on-device ascolta qui. */
  private val onJudged: suspend (PendingPrediction, Double) -> Unit = { _, _ -> },
) {

  /** Le previsioni dei bundle appena arrivati entrano in attesa di giudizio. */
  suspend fun registerPending(fetches: List<ProviderFetch>) {
    val now = clock()
    val pending = mutableListOf<PendingPrediction>()
    for (fetch in fetches) {
      val bundle = fetch.bundle ?: continue
      for (horizonHours in HORIZONS) {
        val target = now + horizonHours * 3_600_000L
        val point = bundle.at(target) ?: continue
        for (variable in FusionVariables.verified) {
          val value = FusionVariables.of(point, variable) ?: continue
          pending += PendingPrediction(
            providerId = bundle.providerId,
            variable = variable,
            targetTimestampMillis = point.timestampMillis,
            predictedValue = value,
            issuedAtMillis = now,
          )
        }
      }
    }
    store.addPending(pending)
  }

  /** L'evento pioggia dei provider sulle tre finestre: il massimo delle probabilita' orarie. */
  suspend fun registerRainEvents(fetches: List<ProviderFetch>) {
    val now = clock()
    val pending = mutableListOf<PendingPrediction>()
    for (fetch in fetches) {
      val bundle = fetch.bundle ?: continue
      for (window in RainEvent.windows) {
        val probabilities = ((window.fromHour + 1)..window.toHour).mapNotNull { hour ->
          bundle.at(now + hour * 3_600_000L)?.precipitationProbabilityPercent
        }
        if (probabilities.isEmpty()) continue
        pending += PendingPrediction(
          providerId = bundle.providerId,
          variable = window.variable,
          targetTimestampMillis = now + window.toHour * 3_600_000L,
          predictedValue = (probabilities.max() / 100.0).coerceIn(0.0, 1.0),
          issuedAtMillis = now,
        )
      }
    }
    store.addPending(pending)
  }

  /** Il verdetto del barometro, finestra per finestra, in attesa dello stesso giudizio. */
  suspend fun registerBarometer(verdict: NowcastVerdict, nowMillis: Long = clock()) {
    val pending = RainEvent.windows.mapNotNull { window ->
      val probability = verdict.forWindow(window.nowcastLabel)?.probability ?: return@mapNotNull null
      PendingPrediction(
        providerId = RainEvent.LOCAL_BAROMETER_ID,
        variable = window.variable,
        targetTimestampMillis = nowMillis + window.toHour * 3_600_000L,
        predictedValue = probability.coerceIn(0.0, 1.0),
        issuedAtMillis = nowMillis,
      )
    }
    store.addPending(pending)
  }

  /**
   * Giudica tutte le previsioni scadute usando i bundle freschi come fonte di verita'. Le
   * [observations] dell'utente (fase 14) valgono piu' della mediana per l'ora in cui sono
   * state fatte: chi ha guardato fuori sa se piove.
   */
  suspend fun settle(fetches: List<ProviderFetch>, observations: List<Observation> = emptyList()) {
    val now = clock()
    val due = store.duePending(now)
    if (due.isEmpty()) return

    val bundles = fetches.mapNotNull { it.bundle }
    val verifications = mutableListOf<ForecastVerification>()
    val settled = mutableListOf<PendingPrediction>()

    for (prediction in due) {
      val truth = if (RainEvent.isRainEvent(prediction.variable)) {
        rainEventTruth(bundles, prediction, observations)
      } else {
        medianTruth(bundles, prediction.variable, prediction.targetTimestampMillis)
      }
      if (truth == null) {
        // Nessun quorum (offline, ora troppo vecchia per le analisi): la si lascia decadere
        // quando e' piu' vecchia dell'orizzonte delle analisi, senza inventare un giudizio.
        if (now - prediction.targetTimestampMillis > TRUTH_WINDOW_MILLIS) settled += prediction
        continue
      }
      runCatching { onJudged(prediction, truth) }
      verifications += ForecastVerification(
        providerId = prediction.providerId,
        variable = prediction.variable,
        horizonBucket = HorizonBucket.of(prediction.horizonHours),
        absoluteError = abs(prediction.predictedValue - truth),
        verifiedAtMillis = now,
      )
      settled += prediction
    }

    store.addVerifications(verifications)
    store.removePending(settled)
  }

  private fun medianTruth(bundles: List<ForecastBundle>, variable: String, timestampMillis: Long): Double? {
    val opinions = bundles.mapNotNull { bundle ->
      bundle.at(timestampMillis)?.let { FusionVariables.of(it, variable) }
    }
    if (opinions.size < minTruthOpinions) return null
    val sorted = opinions.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
  }

  /**
   * 1 se almeno un'ora della finestra ha pioggia misurabile nella mediana delle analisi, 0 se
   * nessuna; null se anche una sola ora e' senza quorum — un "no" costruito su un buco sarebbe
   * un giudizio inventato.
   */
  private fun rainEventTruth(
    bundles: List<ForecastBundle>,
    prediction: PendingPrediction,
    observations: List<Observation>,
  ): Double? {
    val window = RainEvent.windowOf(prediction.variable) ?: return null
    var wet = false
    for (hour in (window.fromHour + 1)..window.toHour) {
      val timestamp = prediction.issuedAtMillis + hour * 3_600_000L
      val seen = observations.filter { abs(it.timestampMillis - timestamp) <= OBSERVATION_WINDOW_MILLIS }
      val hourWet = if (seen.isNotEmpty()) {
        seen.any { it.condition.wet }
      } else {
        (medianTruth(bundles, FusionVariables.PRECIPITATION, timestamp) ?: return null) >= RainEvent.WET_MM
      }
      if (hourWet) wet = true
    }
    return if (wet) 1.0 else 0.0
  }

  private companion object {
    val HORIZONS = listOf(1, 3, 6, 12, 24)

    /** Oltre sei ore nel passato le analisi dei bundle freschi non arrivano piu'. */
    const val TRUTH_WINDOW_MILLIS = 6 * 3_600_000L

    /** Un'osservazione vale per l'ora a cui e' piu' vicina: mezz'ora di qua e di la'. */
    const val OBSERVATION_WINDOW_MILLIS = 30 * 60_000L
  }
}
