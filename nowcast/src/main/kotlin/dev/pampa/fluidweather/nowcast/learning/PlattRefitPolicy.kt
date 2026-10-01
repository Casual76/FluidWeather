package dev.pampa.fluidweather.nowcast.learning

import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import dev.pampa.fluidweather.core.model.NowcastIssueRecord
import dev.pampa.fluidweather.core.model.NowcastOutcomeRecord
import dev.pampa.fluidweather.core.model.PlattMapRecord
import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.nowcast.scoring.DayBlockBootstrap
import dev.pampa.fluidweather.nowcast.scoring.ProperScores
import dev.pampa.fluidweather.nowcast.verdict.ModelVersions

/** Una probabilita' grezza, se e' piovuto, e quando: il corpo di una ricalibrazione, in ordine di tempo. */
data class TimedCalibrationSample(
  val atMillis: Long,
  val probability: Double,
  val rained: Boolean,
  /**
   * La probabilita' della regola barometrica locale per la stessa finestra, quando il vettore e' del v3
   * (la colonna `regola-barometrica-<finestra>` ne porta il logit): serve alla prova "modello o regola"
   * dei livelli senza contesto ([PlattRefitPolicy.ruleGuard]).
   */
  val ruleProbability: Double? = null,
)

/**
 * Perche' una mappa c'e' o non c'e', attiva o no. Il nome finisce su disco ([PlattMapRecord.status])
 * e la pagina della precisione lo traduce: per questo non si rinominano i valori.
 */
enum class PlattStatus(val active: Boolean) {
  /** La stima e' valida e, sugli ultimi dati, ha abbassato il Brier: si applica. */
  ACTIVE(true),

  /** La stima c'e', ma sul 30% piu' recente non ha battuto il grezzo con certezza: non si applica. */
  NO_OUT_OF_SAMPLE_GAIN(false),
  TOO_FEW_SAMPLES(false),
  TOO_FEW_WET(false),
  TOO_FEW_DRY(false),

  /** Troppo pochi casi o giorni recenti per giudicare la mappa: per prudenza non si applica. */
  GUARD_TOO_FEW_TEST(false),

  /** Il 70% piu' vecchio non basta a stimare una mappa da mettere alla prova. */
  GUARD_TRAIN_TOO_SMALL(false),
  NOT_CONVERGED(false),
}

/** Una mappa stimata: i parametri (null se la stima e' stata rifiutata), i conteggi e il verdetto del controllo. */
/** L'esito della prova "modello o regola" di una finestra senza contesto. */
data class RuleGuardResult(val useRule: Boolean, val deltaBrier: Double?, val upperBound: Double?)

data class PlattMapFit(
  val variant: PlattVariant,
  val window: String,
  val params: PlattParams?,
  val samples: Int,
  val wet: Int,
  val dry: Int,
  val status: PlattStatus,
  /** Media, sugli ultimi dati, di Brier(mappa) - Brier(grezzo): negativa = la mappa migliora. */
  val guardDeltaBrier: Double?,
  /** Estremo alto dell'intervallo al 95% di quella media (bootstrap per giorni): deve stare sotto zero. */
  val guardUpperBound: Double?,
  val guardTestDays: Int,
  /**
   * Solo variante "none": sui giri verificati di questo telefono la regola barometrica locale ha
   * battuto il modello (gia' ricalibrato) fuori campione, con certezza. Il motore allora dice la regola.
   */
  val useRule: Boolean = false,
  /** Media, sugli ultimi dati, di Brier(regola) - Brier(modello): negativa = la regola fa meglio. */
  val ruleDeltaBrier: Double? = null,
  val ruleUpperBound: Double? = null,
)

/** Una ristima completa: tutte le varianti per tutte le finestre, e le regole con cui e' stata fatta. */
data class PlattRefit(
  val version: String,
  val fittedAtMillis: Long,
  val maps: List<PlattMapFit>,
) {
  fun records(): List<PlattMapRecord> = maps.map { fit ->
    PlattMapRecord(
      variant = fit.variant.key,
      window = fit.window,
      a = fit.params?.a,
      b = fit.params?.b,
      samples = fit.samples,
      wet = fit.wet,
      dry = fit.dry,
      status = fit.status.name,
      active = fit.status.active && fit.params != null,
      guardDeltaBrier = fit.guardDeltaBrier,
      guardUpperBound = fit.guardUpperBound,
      guardTestDays = fit.guardTestDays,
      useRule = fit.useRule,
      ruleDeltaBrier = fit.ruleDeltaBrier,
      ruleUpperBound = fit.ruleUpperBound,
      fittedAtMillis = fittedAtMillis,
    )
  }
}

/** L'esito del solo controllo sugli ultimi dati. */
data class PlattGuardResult(
  val status: PlattStatus,
  val deltaBrier: Double?,
  val upperBound: Double?,
  val testDays: Int,
)

/**
 * Le regole con cui il telefono ristima e mette alla prova le sue mappe di Platt.
 *
 * Prima la ristima viveva nel ciclo in background e accettava qualunque mappa a trenta
 * verifiche: una curva su pochi giorni di pioggia che, applicata, poteva peggiorare il verdetto
 * senza che nessuno se ne accorgesse. Qui una mappa e' **attiva solo se ha gia' dimostrato di
 * servire fuori campione**: si stima sul 70% piu' vecchio dei casi (in ordine di tempo, senza mai
 * spezzare un giorno), si misura sul 30% piu' recente, e si applica solo se il Brier migliora con
 * un bootstrap per giorni il cui estremo alto dell'intervallo sta sotto zero. Se i dati recenti non
 * lo confermano la mappa resta salvata ma spenta, e la pagina dice perche'.
 *
 * Il controllo e' severo di proposito: servono almeno cinque giorni di prova, quindi per settimane
 * le mappe restano spente. E' il comportamento voluto: meglio il grezzo onesto di una correzione
 * che non sa di non servire.
 *
 * Puro e deterministico (bootstrap con seme fisso): stessi casi, stesso esito, sul telefono e nei test.
 */
object PlattRefitPolicy {

  /** La ristima si ripete ogni sei ore: i dati nuovi arrivano al ritmo dei giri, non dei minuti. */
  const val REFIT_INTERVAL_MILLIS: Long = 6 * 3_600_000L

  /** Le regole di stima e di controllo: cambiarle vuol dire ricominciare, come per il modello. */
  const val RULES_VERSION: String = "platt-2"

  /** La quota piu' vecchia dei casi su cui si stima la mappa da mettere alla prova. */
  const val TRAIN_FRACTION: Double = 0.7

  /** Sotto questi numeri la prova non dice niente: pochi casi, o tutti negli stessi giorni. */
  const val MIN_TEST_CASES: Int = 30
  const val MIN_TEST_DAYS: Int = 5

  /** Quel che sta su disco come `platt_version`: il modello E le regole. */
  const val VERSION: String = ModelVersions.TAG + "#" + RULES_VERSION

  val WINDOWS: List<String> = listOf("0-1h", "1-3h", "3-6h")

  /** Tocca ristimare? Per versione diversa, per tempo trascorso, o perche' l'orologio e' tornato indietro. */
  fun isDue(nowMillis: Long, lastFitMillis: Long, storedVersion: String?, version: String = VERSION): Boolean =
    storedVersion != version || lastFitMillis > nowMillis || nowMillis - lastFitMillis >= REFIT_INTERVAL_MILLIS

  /**
   * Le coppie (probabilita' grezza, esito) di una variante e di una finestra, in ordine di tempo.
   *
   * Entra solo chi ha la versione del modello corrente, il vettore della lunghezza giusta e un livello
   * che appartiene a [variant]: un'emissione senza livello (le righe di prima) non sa a quale
   * regime appartiene e non si usa. L'esito si aggancia con [NowcastIssueRecord.outcomeKey], cosi'
   * anche l'emissione ombra trova la sua verita'. L'istante e' quello della chiave: il giorno in
   * cui il giro e' stato giudicato.
   */
  fun corpus(
    variant: PlattVariant,
    window: String,
    issues: List<NowcastIssueRecord>,
    outcomes: List<NowcastOutcomeRecord>,
    modelVersion: String = ModelVersions.TAG,
    featureCount: Int = ModelVersions.CURRENT_FEATURE_COUNT,
  ): List<TimedCalibrationSample> {
    val rained = outcomes.filter { it.window == window }.associate { it.issuedAtMillis to it.rained }
    return issues.asSequence()
      .filter { it.modelVersion == modelVersion && it.features.size == featureCount }
      .filter { PlattVariant.ofTierName(it.tier) == variant }
      // Un giro conta una volta sola per variante. Quando il verdetto vero e' gia' senza contesto
      // (livello NONE) l'ombra del solo barometro e' la stessa riga — stesse feature, stesso grezzo,
      // stesso esito — e contarle entrambe raddoppierebbe quel giro nella mappa "none", dando
      // peso doppio proprio ai giri senza rete. Vince l'emissione vera; l'ombra resta per i giri
      // in cui il verdetto vero aveva il contesto.
      .sortedBy { if (it.isShadow) 1 else 0 }
      .distinctBy { it.outcomeKey }
      .mapNotNull { issue ->
        val probability = when (window) {
          "0-1h" -> issue.rawProbability01
          "1-3h" -> issue.rawProbability13
          "3-6h" -> issue.rawProbability36
          else -> return@mapNotNull null
        }
        val outcome = rained[issue.outcomeKey] ?: return@mapNotNull null
        TimedCalibrationSample(issue.outcomeKey, probability, outcome, ruleProbabilityOf(issue.features, window))
      }
      .sortedBy { it.atMillis }
      .toList()
  }

  /**
   * La prova fuori campione di una mappa: stima sul 70% piu' vecchio, misura sul 30% piu' recente.
   *
   * Il taglio e' sul giorno del campione all'indice 70%: i giorni non si spezzano, perche' i casi di
   * un giorno sono quasi un caso solo e metterne meta' da una parte e meta' dall'altra
   * farebbe trapelare la risposta. Per ogni caso di prova si confronta il Brier della mappa con
   * quello del grezzo; attiva solo se l'intervallo al 95% della differenza media sta sotto zero.
   */
  fun guard(
    samples: List<TimedCalibrationSample>,
    rules: PlattFitRules = PlattFitRules.DEFAULT,
    bootstrap: DayBlockBootstrap = DayBlockBootstrap(),
  ): PlattGuardResult {
    val sorted = samples.sortedBy { it.atMillis }
    if (sorted.isEmpty()) return PlattGuardResult(PlattStatus.GUARD_TOO_FEW_TEST, null, null, 0)
    val cutIndex = (sorted.size * TRAIN_FRACTION).toInt().coerceIn(0, sorted.size - 1)
    val cutDay = DayBlockBootstrap.epochDayOf(sorted[cutIndex].atMillis)
    val (train, test) = sorted.partition { DayBlockBootstrap.epochDayOf(it.atMillis) < cutDay }
    val testDays = test.map { DayBlockBootstrap.epochDayOf(it.atMillis) }.distinct().size
    if (test.size < MIN_TEST_CASES || testDays < MIN_TEST_DAYS) {
      return PlattGuardResult(PlattStatus.GUARD_TOO_FEW_TEST, null, null, testDays)
    }
    val trainMap = PlattCalibration.fitWithRules(
      train.map { CalibrationSample(it.probability, it.rained) },
      rules.copy(minSamples = 0),
    ).params ?: return PlattGuardResult(PlattStatus.GUARD_TRAIN_TOO_SMALL, null, null, testDays)

    val deltas = test.map {
      ProperScores.brier(trainMap.apply(it.probability), it.rained) - ProperScores.brier(it.probability, it.rained)
    }
    val summary = bootstrap.summarize(deltas, test.map { DayBlockBootstrap.epochDayOf(it.atMillis) })
    val status = if (summary.high < 0.0) PlattStatus.ACTIVE else PlattStatus.NO_OUT_OF_SAMPLE_GAIN
    return PlattGuardResult(status, summary.mean, summary.high, testDays)
  }

  /**
   * La rete di sicurezza della garanzia "meglio che a occhio", sul telefono vero.
   *
   * Il gate del banco certifica il v3 su dieci localita'; nei livelli senza contesto, in climi come
   * Reykjavik o Innsbruck, la regola barometrica tarata sul posto a volte resta davanti. Qui la
   * stessa domanda si fa sui giri verificati di **questo** telefono: sul 30% piu' recente (giorni
   * interi, come [guard]) Brier(regola) - Brier(modello come lo si applicherebbe, cioe' con la mappa
   * attiva se c'e'); se l'intervallo al 95% sta tutto sotto zero la regola vince e la finestra la usa.
   * Nel dubbio resta il modello: la regola deve dimostrare di essere meglio, non il contrario.
   */
  fun ruleGuard(
    samples: List<TimedCalibrationSample>,
    activeMap: PlattParams?,
    bootstrap: DayBlockBootstrap = DayBlockBootstrap(),
  ): RuleGuardResult {
    val sorted = samples.filter { it.ruleProbability != null }.sortedBy { it.atMillis }
    if (sorted.isEmpty()) return RuleGuardResult(false, null, null)
    val cutIndex = (sorted.size * TRAIN_FRACTION).toInt().coerceIn(0, sorted.size - 1)
    val cutDay = DayBlockBootstrap.epochDayOf(sorted[cutIndex].atMillis)
    val test = sorted.filter { DayBlockBootstrap.epochDayOf(it.atMillis) >= cutDay }
    val testDays = test.map { DayBlockBootstrap.epochDayOf(it.atMillis) }.distinct().size
    if (test.size < MIN_TEST_CASES || testDays < MIN_TEST_DAYS) return RuleGuardResult(false, null, null)
    val deltas = test.map {
      val model = activeMap?.apply(it.probability) ?: it.probability
      ProperScores.brier(it.ruleProbability!!, it.rained) - ProperScores.brier(model, it.rained)
    }
    val summary = bootstrap.summarize(deltas, test.map { DayBlockBootstrap.epochDayOf(it.atMillis) })
    return RuleGuardResult(summary.high < 0.0, summary.mean, summary.high)
  }

  /** La probabilita' della regola dal vettore v3 della emissione; null per i vettori del v2. */
  internal fun ruleProbabilityOf(features: List<Double>, window: String): Double? {
    if (features.size != FeatureExtractorV3.COUNT) return null
    val index = WINDOWS.indexOf(window).takeIf { it >= 0 } ?: return null
    val logit = features[FeatureExtractorV3.BAROMETRIC_RULE + index]
    if (logit.isNaN()) return null
    return 1.0 / (1.0 + kotlin.math.exp(-logit))
  }

  /**
   * La ristima completa: per ogni variante e finestra, stima sul corpo intero e prova fuori campione.
   * Restituisce sempre una voce per coppia (varianti x tre finestre): anche quelle fallite, con
   * il loro stato e senza mappa, perche' chi ne aveva una vecchia deve perderla ("salvare SOSTITUISCE
   * tutto") e la pagina deve poter spiegare "non ancora".
   *
   * La mappa salvata e' sempre quella stimata sul corpo intero; la prova serve solo a decidere se
   * applicarla.
   */
  fun refit(
    issues: List<NowcastIssueRecord>,
    outcomes: List<NowcastOutcomeRecord>,
    nowMillis: Long,
    rules: PlattFitRules = PlattFitRules.DEFAULT,
    variants: List<PlattVariant> = PlattVariant.FITTED,
    bootstrap: DayBlockBootstrap = DayBlockBootstrap(),
    modelVersion: String = ModelVersions.TAG,
    featureCount: Int = ModelVersions.CURRENT_FEATURE_COUNT,
    version: String = VERSION,
  ): PlattRefit {
    val maps = variants.flatMap { variant ->
      WINDOWS.map { window ->
        val corpus = corpus(variant, window, issues, outcomes, modelVersion, featureCount)
        val fit = PlattCalibration.fitWithRules(corpus.map { CalibrationSample(it.probability, it.rained) }, rules)
        if (fit.params == null) {
          PlattMapFit(variant, window, null, fit.samples, fit.wet, fit.dry, fit.status, null, null, 0)
        } else {
          val guard = guard(corpus, rules, bootstrap)
          PlattMapFit(
            variant, window, fit.params, fit.samples, fit.wet, fit.dry, guard.status,
            guard.deltaBrier, guard.upperBound, guard.testDays,
          )
        }.let { mapFit ->
          if (variant != PlattVariant.NONE) return@let mapFit
          val active = mapFit.params?.takeIf { mapFit.status.active }
          val rule = ruleGuard(corpus, active, bootstrap)
          mapFit.copy(useRule = rule.useRule, ruleDeltaBrier = rule.deltaBrier, ruleUpperBound = rule.upperBound)
        }
      }
    }
    return PlattRefit(version, nowMillis, maps)
  }
}
