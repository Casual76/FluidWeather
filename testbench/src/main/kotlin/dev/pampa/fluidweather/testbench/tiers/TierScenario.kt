package dev.pampa.fluidweather.testbench.tiers

import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.SplittableRandom

/**
 * I tre periodi del banco onesto, in istanti di emissione UTC.
 *
 * TRAIN impara, VALIDATION serve a decidere (quante volte serve), TEST si tocca una volta sola
 * sull'artefatto finale. Le baseline di un periodo non vedono mai il periodo: nascono dai due anni
 * di verita' del pannello che lo precedono, come le scaricherebbe il telefono (vedi
 * [historyFromMillis] e [historyUntilMillis]). Per VALIDATION la storia e' "quella che c'e'": il
 * pannello comincia il 2022-11-24, quindi ne restano ventun mesi.
 */
class TierPeriod(
  val name: String,
  /** La prima emissione: mezzanotte UTC del primo giorno. */
  val firstMillis: Long,
  /** Esclusa: mezzanotte UTC del giorno dopo l'ultimo. Le emissioni sono `first, first+3h, ...` sotto questo istante. */
  val endExclusiveMillis: Long,
  /** Da dove la storia delle baseline: due anni prima dell'inizio, mai prima del pannello. */
  val historyFromMillis: Long,
) {

  init {
    require(endExclusiveMillis > firstMillis) { "periodo vuoto: $name" }
    require(historyFromMillis <= firstMillis) { "la storia non puo' cominciare dopo il periodo: $name" }
  }

  /**
   * L'ultimo slot (estremo incluso) della storia delle baseline: quello che era gia' definitivo alla
   * **prima emissione** del periodo. La prima emissione non e' `firstMillis` ma fino a un'ora prima
   * ([TierScenarios.ISSUE_OFFSET_BOUND_MILLIS]), e a quell'istante la verita' del pannello e'
   * definitiva solo fino a [TruthPanel.FINALITY_MILLIS] prima: lo slot che si chiude a `firstMillis`
   * (e le ventiquattro ore che lo precedono) il telefono non li avrebbe ancora. Prima la storia
   * arrivava fino a `firstMillis` compreso: poche ore su due anni, ma ore del futuro delle prime
   * emissioni.
   */
  val historyUntilMillis: Long
    get() = firstMillis - TierScenarios.ISSUE_OFFSET_BOUND_MILLIS - TruthPanel.FINALITY_MILLIS

  /**
   * Le emissioni "t0" del periodo, ogni [stepMillis]: di default [STEP_MILLIS] (tre ore, il passo dei
   * gate), un'ora per costruire le righe di addestramento.
   */
  fun issueAnchors(stepMillis: Long = STEP_MILLIS): LongArray {
    require(stepMillis > 0) { "passo non positivo: $stepMillis" }
    val count = ((endExclusiveMillis - firstMillis + stepMillis - 1) / stepMillis).toInt()
    return LongArray(count) { firstMillis + it * stepMillis }
  }

  override fun toString(): String = "$name ${day(firstMillis)}..${day(endExclusiveMillis - 1)}"

  companion object {
    /** Come il Replayer di oggi: un'emissione ogni tre ore. */
    const val STEP_MILLIS: Long = 3 * 3_600_000L

    /** I due anni di storia che il telefono scarica. */
    const val HISTORY_YEARS: Long = 2

    private fun day(millis: Long): String = Instant.ofEpochMilli(millis).toString().substring(0, 10)

    /** Un periodo dai giorni (estremi inclusi), con la storia di [HISTORY_YEARS] anni prima, ritagliata al pannello. */
    fun ofDays(name: String, firstDay: LocalDate, lastDay: LocalDate): TierPeriod {
      val first = firstDay.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
      val end = lastDay.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
      val twoYearsBefore = firstDay.minusYears(HISTORY_YEARS).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
      return TierPeriod(name, first, end, maxOf(twoYearsBefore, TruthPanel.AVAILABLE_FROM_MILLIS))
    }
  }
}

/** I periodi del piano: TRAIN 2022-11-24..2024-08-31, VALIDATION 2024-09-01..2025-08-31, TEST 2025-09-01..2026-08-31. */
object TierPeriods {
  val TRAIN: TierPeriod = TierPeriod.ofDays("train", LocalDate.of(2022, 11, 24), LocalDate.of(2024, 8, 31))
  val VALIDATION: TierPeriod = TierPeriod.ofDays("validation", LocalDate.of(2024, 9, 1), LocalDate.of(2025, 8, 31))
  val TEST: TierPeriod = TierPeriod.ofDays("test", LocalDate.of(2025, 9, 1), LocalDate.of(2026, 8, 31))

  fun byName(name: String?): TierPeriod? = when (name?.lowercase()) {
    "validation" -> VALIDATION
    "test" -> TEST
    else -> null
  }

  /**
   * La meta' dell'anno di VALIDATION a cui appartiene un'emissione: A = settimana ISO pari, B = dispari.
   * La scelta degli iperparametri e l'arresto anticipato si fanno su A, la decisione D4 su B: la meta'
   * su cui si sceglie non e' quella su cui si giudica la scelta. Settimane intere e non giorni
   * alternati: le emissioni vicine (la stessa perturbazione) restano dalla stessa parte.
   */
  fun half(t0Millis: Long): TierHalf {
    val day = java.time.LocalDate.ofEpochDay(Math.floorDiv(t0Millis, 86_400_000L))
    return if (day.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR) % 2 == 0) TierHalf.A else TierHalf.B
  }
}

/** Le due meta' di VALIDATION per settimana ISO: [A] (pari) sceglie, [B] (dispari) decide. */
enum class TierHalf { A, B }

/**
 * Per chi sono le estrazioni casuali di [TierScenarios]: [TRAINING] le fa (lo stato dei dati del telefono
 * varia: la normale dei trenta giorni non c'e' sempre, le tabelle locali non sempre sono arrivate) e
 * [EVALUATION] — il gate e ogni misura — le tiene al loro stato normale: a un gate si presenta il telefono
 * com'e' quando tutto e' arrivato, non uno sfortunato per sorteggio.
 */
enum class ScenarioMode { TRAINING, EVALUATION }

/**
 * Gli scenari di contesto che il banco fa correre, uno per ogni modo in cui il telefono puo'
 * trovarsi. [FRESH], [STALE], [NONE] e [NONE_NOCLIMA] sono i quattro livelli di [ContextTier];
 * [STALE_3H], [STALE_6H] e [STALE_12H] sono lo stesso livello STALE a eta' fissa, per vedere
 * quanto costa ogni ora di contesto vecchio invece della media su tutte.
 */
enum class TierKind(val label: String, val tier: ContextTier, val fixedAgeHours: Int? = null) {
  FRESH("FRESH", ContextTier.FRESH),
  STALE("STALE", ContextTier.STALE),
  NONE("NONE", ContextTier.NONE),
  NONE_NOCLIMA("NONE_NOCLIMA", ContextTier.NONE_NOCLIMA),
  STALE_3H("STALE-3h", ContextTier.STALE, 3),
  STALE_6H("STALE-6h", ContextTier.STALE, 6),
  STALE_12H("STALE-12h", ContextTier.STALE, 12),
  ;

  /** Il contesto dei provider c'e' (piu' o meno vecchio)? */
  val hasContext: Boolean get() = tier.hasContext

  /** Uno dei quattro livelli veri, non un'eta' fissa di STALE. */
  val isPrimary: Boolean get() = fixedAgeHours == null

  companion object {
    /** I quattro livelli, nell'ordine del rapporto. */
    val PRIMARY: List<TierKind> = entries.filter { it.isPrimary }

    /** Le tre eta' fisse di STALE. */
    val STALE_BUCKETS: List<TierKind> = entries.filter { it.fixedAgeHours != null }
  }
}

/** Uno scenario per una emissione: il livello e, dove c'e' contesto, quanto e' vecchio (millisecondi). */
data class TierScenario(val kind: TierKind, val contextAgeMillis: Long?) {
  init {
    require((contextAgeMillis != null) == kind.hasContext) { "eta' del contesto incoerente con ${kind.label}" }
  }

  val tier: ContextTier get() = kind.tier
}

/**
 * Da dove il banco tira fuori il minuto di emissione e l'eta' del contesto di ogni emissione.
 *
 * **Il minuto di emissione.** Il telefono non emette all'ora piena: il banco di prima si', ed era
 * un caso particolare (la finestra 0-1h cominciava esattamente dove finiva l'ultimo slot visto).
 * Qui ogni emissione e' a `t0 - U(0, 60) min`: l'ancora di [dev.pampa.fluidweather.nowcast.truth.RainWindows]
 * (emissione arrotondata per eccesso) resta `t0`, ma l'ora di emissione, la storia di 24 ore del
 * barometro e l'ultimo slot chiuso del contesto ballano come sul telefono.
 *
 * **L'eta' del contesto.** FRESH pesca in U(0, 90 min) (il massimo che [ContextTier.of] chiama
 * fresco), STALE in U(1,5 h, 12 h) (oltre il fresco, dentro il vecchio): dopo che un contesto
 * nasce fresco un'ora e mezza, e' vecchio fino a dodici.
 *
 * **Deterministico.** Ogni estrazione ha il seme (localita', t0, scenario): rigiocare il banco, in un
 * altro ordine o su piu' thread, da' gli stessi numeri. Nessun `Random` condiviso fra localita'.
 */
object TierScenarios {

  /** Il minuto di emissione sta in [0, 60) minuti sotto `t0`: il 60 esatto sposterebbe l'ancora all'ora prima. */
  const val ISSUE_OFFSET_BOUND_MILLIS: Long = 3_600_000L

  /** STALE comincia dove FRESH finisce, un millisecondo dopo: a 90 minuti esatti il livello e' ancora FRESH. */
  const val STALE_AGE_MIN_MILLIS: Long = ContextTier.FRESH_MAX_AGE_MILLIS + 1
  const val STALE_AGE_MAX_MILLIS: Long = ContextTier.STALE_MAX_AGE_MILLIS

  private const val SEED_BASE = 0x5DEECE66DL
  private const val SALT_ISSUE = 0x1L
  private const val SALT_AGE = 0x2L
  private const val SALT_HISTORY = 0x100L
  private const val SALT_PRIORS = 0x200L
  private const val SALT_LEVEL_BIAS = 0x300L

  /** In addestramento il telefono ha i trenta giorni di storia (la normale) otto volte su dieci. */
  const val HISTORY_KNOWN_PROBABILITY: Double = 0.8

  /** In addestramento FRESH e STALE hanno le tabelle locali nove volte su dieci; altrimenti le sole di tutti i posti. */
  const val LOCAL_PRIORS_KEPT_PROBABILITY: Double = 0.9

  /** L'errore della quota di riferimento, in hPa (circa dodici metri): normale, sigma 1,5. */
  const val LEVEL_BIAS_SIGMA_HPA: Double = 1.5

  /** Quanto prima di `t0` si emette. */
  fun issueOffsetMillis(locationName: String, t0Millis: Long): Long =
    SplittableRandom(seed(locationName, t0Millis, SALT_ISSUE)).nextLong(0, ISSUE_OFFSET_BOUND_MILLIS)

  /** L'istante di emissione: `t0 - offset`. */
  fun issueMillis(locationName: String, t0Millis: Long): Long = t0Millis - issueOffsetMillis(locationName, t0Millis)

  /** L'eta' del contesto di uno scenario; null dove il contesto non c'e'. */
  fun contextAgeMillis(kind: TierKind, locationName: String, t0Millis: Long): Long? = when {
    !kind.hasContext -> null
    kind.fixedAgeHours != null -> kind.fixedAgeHours * 3_600_000L
    kind == TierKind.FRESH ->
      SplittableRandom(seed(locationName, t0Millis, SALT_AGE + kind.ordinal * 16L))
        .nextLong(0, ContextTier.FRESH_MAX_AGE_MILLIS + 1)

    else ->
      SplittableRandom(seed(locationName, t0Millis, SALT_AGE + kind.ordinal * 16L))
        .nextLong(STALE_AGE_MIN_MILLIS, STALE_AGE_MAX_MILLIS + 1)
  }

  /** Tutti gli scenari di un'emissione, nell'ordine di [TierKind.entries]. */
  fun scenariosFor(locationName: String, t0Millis: Long, kinds: List<TierKind> = TierKind.entries): List<TierScenario> =
    kinds.map { TierScenario(it, contextAgeMillis(it, locationName, t0Millis)) }

  /**
   * Il telefono conosce la normale dei trenta giorni (feature 5 e flag 40)? Non e' una proprieta' del
   * livello di contesto ma dello stato dei dati del telefono, quindi **la stessa estrazione** per tutti i
   * livelli della stessa emissione.
   *
   * - `NONE_NOCLIMA`: mai, in addestramento e nel gate. E' il primo avvio offline: non c'e' un archivio
   *   di trenta giorni (e nemmeno un punto di riferimento). Scelta dichiarata, piu' severa del "20%"
   *   delle prime due settimane che valeva per gli altri.
   * - Gli altri livelli: in addestramento otto volte su dieci ([HISTORY_KNOWN_PROBABILITY], le prime due
   *   settimane di un telefono nuovo non hanno la normale); in valutazione sempre.
   */
  fun historyKnown(kind: TierKind, locationName: String, t0Millis: Long, mode: ScenarioMode): Boolean = when {
    kind.tier == ContextTier.NONE_NOCLIMA -> false
    mode == ScenarioMode.EVALUATION -> true
    else -> SplittableRandom(seed(locationName, t0Millis, SALT_HISTORY)).nextDouble() < HISTORY_KNOWN_PROBABILITY
  }

  /**
   * Le tabelle del verdetto sono quelle del posto? NONE: sempre (e' il livello che le ha per
   * definizione). NONE_NOCLIMA: mai (e' il livello che non le ha). FRESH e STALE: in addestramento nove
   * volte su dieci ([LOCAL_PRIORS_KEPT_PROBABILITY]) — la decima l'archivio di clima non e' ancora
   * arrivato e il telefono ha con se' solo il riferimento di tutti i posti —, in valutazione sempre.
   */
  fun localPriorsKept(kind: TierKind, locationName: String, t0Millis: Long, mode: ScenarioMode): Boolean = when (kind.tier) {
    ContextTier.NONE -> true
    ContextTier.NONE_NOCLIMA -> false
    else ->
      mode == ScenarioMode.EVALUATION ||
        SplittableRandom(seed(locationName, t0Millis, SALT_PRIORS)).nextDouble() < LOCAL_PRIORS_KEPT_PROBABILITY
  }

  /**
   * L'errore (hPa) della quota di riferimento di un telefono in un mese, N(0; 1,5): e' quanto il
   * livello del mare (feature 39) si sbaglia se la quota che l'utente ha dichiarato e' sbagliata di una
   * dozzina di metri. **In addestramento e nel gate**, per localita' e mese; il telefono vero passa zero
   * (non sa di sbagliarsi). Deterministico.
   */
  fun levelBiasHpa(locationName: String, issueMillis: Long): Double {
    val month = java.time.Instant.ofEpochMilli(issueMillis).atOffset(java.time.ZoneOffset.UTC)
    val yearMonth = month.year * 100L + month.monthValue
    val random = SplittableRandom(seed(locationName, yearMonth, SALT_LEVEL_BIAS))
    // Box-Muller: u1 in (0, 1], cosi' il logaritmo e' finito.
    val u1 = 1.0 - random.nextDouble()
    val u2 = random.nextDouble()
    return LEVEL_BIAS_SIGMA_HPA * kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
  }

  /** Il seme di un'estrazione: SplitMix a tre giri su (localita', istante, sale). */
  internal fun seed(locationName: String, t0Millis: Long, salt: Long): Long =
    mix(mix(mix(SEED_BASE + locationName.hashCode().toLong()) xor t0Millis) + salt)

  private fun mix(value: Long): Long {
    var z = value + -0x61c8864680b583ebL
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return z xor (z ushr 31)
  }
}
