package dev.pampa.fluidweather.testbench.train.v3

import dev.pampa.fluidweather.nowcast.cleaning.CleaningResult
import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.testbench.baselines.HonestBaselines
import dev.pampa.fluidweather.testbench.replay.TierReplayer
import dev.pampa.fluidweather.testbench.tiers.AsOfContext
import dev.pampa.fluidweather.testbench.tiers.IssueSimulator
import dev.pampa.fluidweather.testbench.tiers.LocationInputs
import dev.pampa.fluidweather.testbench.tiers.ScenarioMode
import dev.pampa.fluidweather.testbench.tiers.SimulatedIssue
import dev.pampa.fluidweather.testbench.tiers.TierKind
import dev.pampa.fluidweather.testbench.tiers.TierPeriod
import dev.pampa.fluidweather.testbench.tiers.TierScenarios
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Il vettore delle quarantadue feature di un (emissione, livello): **l'unico posto** in cui il banco
 * mette insieme barometro, contesto, tabelle, normale e livello del mare per il v3. Lo chiamano il
 * costruttore delle righe di addestramento e, nel gate, la pipeline del v3: se le due strade
 * sommassero le cose in modo diverso, il modello si addestrerebbe su un telefono che il gate non giudica.
 *
 * Le estrazioni casuali dello scenario ([TierScenarios], [ScenarioMode]) decidono tre cose:
 * - se la normale dei trenta giorni c'e' ([TierScenarios.historyKnown]): altrimenti `normalHpa = null`;
 * - se le tabelle sono quelle del posto o il riferimento di tutti i posti ([TierScenarios.localPriorsKept]);
 * - l'errore della quota di riferimento, sul solo livello del mare ([TierScenarios.levelBiasHpa]).
 * In [ScenarioMode.EVALUATION] il telefono ha tutto quello che il suo livello puo' avere.
 */
object V3FeatureAssembly {

  /** Le feature e cosa e' stato estratto: per i conti sui tassi di caduta dei dati e per la diagnosi. */
  class Result(
    val features: DoubleArray,
    /** L'estrazione "la normale dei trenta giorni c'e'" (non: "la normale e' nota al banco"). */
    val historyKnown: Boolean,
    /** Le tabelle usate sono quelle del posto. */
    val localPriors: Boolean,
  )

  /**
   * Le feature di [kind] per l'emissione simulata [sim]. [asOf] e' il contesto del livello (null nei livelli
   * senza contesto); [honest] le baseline della localita' per l'anno della riga. Null quando non si puo'
   * costruire: il livello vuole tabelle locali e la localita' non ne ha per quell'anno (NONE), o non c'e'
   * nemmeno il riferimento di tutti i posti.
   */
  fun assemble(
    locationName: String,
    kind: TierKind,
    sim: SimulatedIssue,
    asOf: AsOfContext?,
    honest: HonestBaselines,
    mode: ScenarioMode,
  ): Result? = assemble(locationName, kind, sim.t0Millis, sim.issueMillis, sim.cleaning, sim.normalHpa, asOf, honest, mode)

  /**
   * Lo stesso con gli ingredienti sciolti: e' la forma che serve a un predittore del replay, che riceve
   * dall'`IssueView` l'ancora, l'emissione, il barometro pulito, la normale, il contesto e le baseline.
   */
  fun assemble(
    locationName: String,
    kind: TierKind,
    t0Millis: Long,
    issueMillis: Long,
    cleaning: CleaningResult,
    normalHpa: Double?,
    asOf: AsOfContext?,
    honest: HonestBaselines,
    mode: ScenarioMode,
  ): Result? {
    val tier = kind.tier
    val historyKnown = TierScenarios.historyKnown(kind, locationName, t0Millis, mode)
    val wantsLocal = TierScenarios.localPriorsKept(kind, locationName, t0Millis, mode)
    val priors = when {
      tier == ContextTier.NONE_NOCLIMA -> honest.pooledOnly()
      !wantsLocal -> honest.pooledOnly()
      else -> honest.priors(tier) ?: if (tier.hasContext) honest.pooledOnly() else null
    } ?: return null
    val features = FeatureExtractorV3.extract(
      cleaning = cleaning,
      context = asOf?.context,
      normalHpa = if (historyKnown) normalHpa else null,
      nowMillis = issueMillis,
      priors = priors,
      referenceAltitudeKnown = true,
      levelBiasHpa = TierScenarios.levelBiasHpa(locationName, issueMillis),
    ) ?: return null
    return Result(features, historyKnown, priors.isLocal)
  }
}

/**
 * Da dove si costruiscono le righe: il periodo (le emissioni `t0` ogni [stepMillis]), se lo scenario
 * estrae a caso ([ScenarioMode.TRAINING]) o tiene il telefono normale ([ScenarioMode.EVALUATION]), e la
 * fase ([V3Stage]) che decide le tabelle e il limite delle etichette.
 */
class V3RowSpec(
  val period: TierPeriod,
  val stepMillis: Long,
  val mode: ScenarioMode,
  val stage: V3Stage,
  val kinds: List<TierKind> = TierKind.PRIMARY,
  /**
   * Si scartano le emissioni senza nessuna etichetta del pannello (in addestramento non servono: fuori
   * Europa prima del 2023-12-27 c'e' solo ERA5). In valutazione si tengono, come fa il replay, finche' una
   * delle due verita' c'e'.
   */
  val requirePanel: Boolean = mode == ScenarioMode.TRAINING,
)

/** Quanto e' entrato e perche' il resto no, per livello: niente sparisce in silenzio. */
class V3BuildStats(
  val rowsByTier: MutableMap<ContextTier, Int> = ContextTier.entries.associateWith { 0 }.toMutableMap(),
  val historyKnownByTier: MutableMap<ContextTier, Int> = ContextTier.entries.associateWith { 0 }.toMutableMap(),
  val localPriorsByTier: MutableMap<ContextTier, Int> = ContextTier.entries.associateWith { 0 }.toMutableMap(),
  var anchors: Int = 0,
  var noTruth: Int = 0,
  var noBarometer: Int = 0,
  val droppedNoContext: MutableMap<ContextTier, Int> = ContextTier.entries.associateWith { 0 }.toMutableMap(),
  val droppedNoTables: MutableMap<ContextTier, Int> = ContextTier.entries.associateWith { 0 }.toMutableMap(),
  /** Finestre sull'etichetta cui si e' tolta per il limite della fase (mai usare il futuro del periodo di prova). */
  var labelsCutByPhase: Int = 0,
) {
  fun add(other: V3BuildStats) {
    for (tier in ContextTier.entries) {
      rowsByTier[tier] = rowsByTier.getValue(tier) + other.rowsByTier.getValue(tier)
      historyKnownByTier[tier] = historyKnownByTier.getValue(tier) + other.historyKnownByTier.getValue(tier)
      localPriorsByTier[tier] = localPriorsByTier.getValue(tier) + other.localPriorsByTier.getValue(tier)
      droppedNoContext[tier] = droppedNoContext.getValue(tier) + other.droppedNoContext.getValue(tier)
      droppedNoTables[tier] = droppedNoTables.getValue(tier) + other.droppedNoTables.getValue(tier)
    }
    anchors += other.anchors
    noTruth += other.noTruth
    noBarometer += other.noBarometer
    labelsCutByPhase += other.labelsCutByPhase
  }
}

/**
 * Il costruttore delle righe del v3: per ogni localita', ogni `t0` del periodo e ogni livello, il telefono
 * finto ([IssueSimulator], lo stesso del replay), il contesto com'era al fetch, le tabelle **degli altri
 * anni** ([JackknifeSlices]: mai quelle che contengono l'anno della riga) e le etichette del pannello
 * e di ERA5 ([dev.pampa.fluidweather.nowcast.truth.RainWindows], la stessa definizione dell'evento).
 *
 * - **Una emissione, quattro righe.** Il barometro si pulisce una volta e serve FRESH, STALE, NONE e
 *   NONE_NOCLIMA; cambiano contesto, tabelle e stato dei dati.
 * - **Scarti come nel replay:** meno di 24 campioni o di 13 ore di storia pulita (niente barometro), contesto
 *   senza la riga "adesso" o senza la pioggia dell'ultima ora (FRESH e STALE), nessuna tabella (NONE di una
 *   localita' senza pannello in quell'anno). Un'etichetta di finestra che si chiude dopo il limite della fase
 *   ([JackknifePlan.labelCutoffMillis]) diventa "non giudicabile" per quella finestra soltanto: e' piu' fine di
 *   scartare l'intera riga e non fa passare nemmeno un'ora di TEST (o di VALIDATION) nell'addestramento.
 * - **Deterministico e parallelo.** Le localita' si spezzano in blocchi di emissioni contigue che corrono su
 *   un pool; ogni blocco ha il suo [IssueSimulator] (la pulizia non e' condivisa fra thread) e i blocchi
 *   si ricompongono nell'ordine dei dati: stesso risultato con un thread o con dodici.
 */
class V3RowBuilder(
  private val inputs: List<LocationInputs>,
  private val slices: JackknifeSlices,
  private val threads: Int = TierReplayer.defaultThreads(),
  private val log: (String) -> Unit = {},
) {

  /** Le statistiche dell'ultimo [build]. */
  var lastStats: V3BuildStats = V3BuildStats()
    private set

  fun build(spec: V3RowSpec): V3Rows {
    val locations = inputs.map { it.location.name }
    val anchors = spec.period.issueAnchors(spec.stepMillis)
    val chunks = chunksOf(anchors.size)
    // Le tabelle di ogni anno presente si costruiscono prima, in fila: i thread le trovano pronte.
    anchors.asSequence().mapNotNull { slices.plan.regionOf(it) }.distinct().toList().forEach { slices.honest(spec.stage, it) }
    val pool = Executors.newFixedThreadPool(threads.coerceIn(1, maxOf(1, inputs.size * chunks.size)))
    val results = try {
      val futures = inputs.withIndex().flatMap { (index, input) ->
        chunks.map { range ->
          pool.submit(Callable { buildChunk(index, input, anchors, range, spec) })
        }
      }
      futures.map { future ->
        try {
          future.get()
        } catch (e: ExecutionException) {
          throw e.cause ?: e
        }
      }
    } finally {
      pool.shutdown()
    }
    val stats = V3BuildStats()
    results.forEach { stats.add(it.second) }
    lastStats = stats
    val rows = V3Rows.concat(locations, results.map { it.first })
    log("  ${spec.period.name}: ${rows.size} righe da ${stats.anchors} emissioni")
    return rows
  }

  private fun chunksOf(count: Int): List<IntRange> {
    if (count == 0) return emptyList()
    val parts = minOf(CHUNKS_PER_LOCATION, count)
    return (0 until parts).map { i -> (i * count / parts) until ((i + 1) * count / parts) }
  }

  private fun buildChunk(
    locationIndex: Int,
    input: LocationInputs,
    anchors: LongArray,
    range: IntRange,
    spec: V3RowSpec,
  ): Pair<V3Rows, V3BuildStats> {
    val name = input.location.name
    val simulator = IssueSimulator(input)
    val writer = V3RowsWriter(inputs.map { it.location.name })
    val stats = V3BuildStats()
    val windows = RainWindows.ALL

    for (i in range) {
      val t0 = anchors[i]
      val region = slices.plan.regionOf(t0) ?: continue
      stats.anchors++
      val issue = simulator.issueMillis(t0)
      val cutoff = slices.plan.labelCutoffMillis(spec.stage, region)

      val panel = IntArray(windows.size)
      val era5 = IntArray(windows.size)
      for ((w, window) in windows.withIndex()) {
        val inside = RainWindows.lastSlotEnd(issue, window) <= cutoff
        panel[w] = if (inside) encode(input.panelTruth.outcome(issue, window)) else -1
        era5[w] = if (inside) encode(input.era5Truth.outcome(issue, window)) else -1
        if (!inside && (input.panelTruth.outcome(issue, window) != null)) stats.labelsCutByPhase++
      }
      val noPanel = panel.all { it < 0 }
      if (noPanel && (spec.requirePanel || era5.all { it < 0 })) {
        stats.noTruth++
        continue
      }

      val sim = simulator.simulate(t0)
      if (sim == null) {
        stats.noBarometer++
        continue
      }
      val honest = slices.honest(spec.stage, region).getValue(name)

      for (kind in spec.kinds) {
        val asOf = simulator.context(kind, t0, issue)
        if (kind.hasContext && (asOf == null || asOf.context.rainLastHourMm == null)) {
          stats.droppedNoContext[kind.tier] = stats.droppedNoContext.getValue(kind.tier) + 1
          continue
        }
        val assembled = V3FeatureAssembly.assemble(name, kind, sim, asOf, honest, spec.mode)
        if (assembled == null) {
          stats.droppedNoTables[kind.tier] = stats.droppedNoTables.getValue(kind.tier) + 1
          continue
        }
        val flags = (if (assembled.historyKnown) V3Rows.FLAG_HISTORY_KNOWN else 0) or
          (if (assembled.localPriors) V3Rows.FLAG_LOCAL_PRIORS else 0)
        writer.add(locationIndex, kind.tier.ordinal, t0, issue, assembled.features, panel, era5, flags)
        stats.rowsByTier[kind.tier] = stats.rowsByTier.getValue(kind.tier) + 1
        if (assembled.historyKnown) stats.historyKnownByTier[kind.tier] = stats.historyKnownByTier.getValue(kind.tier) + 1
        if (assembled.localPriors) stats.localPriorsByTier[kind.tier] = stats.localPriorsByTier.getValue(kind.tier) + 1
      }
    }
    return writer.build() to stats
  }

  private fun encode(outcome: Boolean?): Int = when (outcome) {
    null -> -1
    false -> 0
    true -> 1
  }

  companion object {
    /** Quanti blocchi di emissioni contigue per localita': abbastanza per tenere occupati dodici thread. */
    const val CHUNKS_PER_LOCATION: Int = 8
  }
}
