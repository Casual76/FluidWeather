package dev.pampa.fluidweather.testbench.train.v3

import dev.pampa.fluidweather.nowcast.climatology.LocalBaselines
import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.learning.RainObservation
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import dev.pampa.fluidweather.nowcast.verdict.ObservationFloors
import dev.pampa.fluidweather.nowcast.verdict.RadarFloor
import dev.pampa.fluidweather.nowcast.verdict.RainNowFloor
import dev.pampa.fluidweather.testbench.gate.IndependenceGate
import dev.pampa.fluidweather.testbench.metrics.Fmt
import dev.pampa.fluidweather.testbench.replay.TierBench
import dev.pampa.fluidweather.testbench.replay.TierGroups
import dev.pampa.fluidweather.testbench.replay.TierReplayResult
import dev.pampa.fluidweather.testbench.replay.TierReplayer
import dev.pampa.fluidweather.testbench.replay.TierStats
import dev.pampa.fluidweather.testbench.replay.TruthKind
import dev.pampa.fluidweather.testbench.replay.V3Pipeline
import dev.pampa.fluidweather.testbench.tiers.TierKind
import dev.pampa.fluidweather.testbench.tiers.TierPeriod
import java.io.File
import java.util.Locale

/** Una variante della politica dei pavimenti del v3. */
class FloorVariant(val name: String, val description: String, val policies: Map<ContextTier, ObservationFloors>)

/** La decisione di una cella (livello, finestra) con i suoi numeri. */
class FloorCellDecision(
  val tier: ContextTier,
  val window: Int,
  /** La variante adottata, o null (nessun pavimento). */
  val adopted: FloorVariant?,
  val lines: List<String>,
)

/**
 * `floors-v3 validation [--emit]`: la politica dei pavimenti dell'osservazione del v3, decisa su VALIDATION
 * con la regola preregistrata, livello per livello e finestra per finestra.
 *
 * Varianti: F0 nessun pavimento; F1 i pavimenti di oggi (0,65/0,60/0,50 con l'ultimo slot chiuso >= 0,2 mm) in
 * FRESH e STALE; F2 gli stessi solo in FRESH; F3 una tabella per intensita' in FRESH (0,5-2 mm: 0,45/0,40/-,
 * >= 2 mm: 0,70/0,60/0,45). Una variante si adotta per una cella **solo se** la differenza appaiata
 * (finale - grezzo) in EUROPA ha l'estremo alto dell'IC95% sotto zero, **e** ogni localita' dura ha la differenza
 * puntuale <= 0, **e** il gate di sviluppo con quella variante passa ancora. Altrimenti niente pavimento. Fra
 * piu' varianti adottabili vince quella con la differenza media piu' negativa in EUROPA.
 *
 * Il quarto d'ora non si puo' rigiocare (il banco non ha dati hf15 per tutti): nel v3 non ci sono pavimenti
 * dal quarto d'ora. Il radar nemmeno: il suo pavimento esiste come dato (spento) e accenderlo e' una
 * decisione dell'utente, perche' il gate non certificherebbe piu' esattamente cio' che il telefono mostra.
 */
object FloorsV3Command {

  private val TODAY_FLOORS = RainObservation.FLOORS_WHEN_RAINING

  fun variants(): List<FloorVariant> {
    val f1Step = listOf(RainNowFloor(RainObservation.RAINING_FROM_MM_PER_HOUR, TODAY_FLOORS))
    val f3Steps = listOf(
      RainNowFloor(0.5, mapOf("0-1h" to 0.45, "1-3h" to 0.40)),
      RainNowFloor(2.0, mapOf("0-1h" to 0.70, "1-3h" to 0.60, "3-6h" to 0.45)),
    )
    fun policy(fresh: List<RainNowFloor>, stale: List<RainNowFloor>) = ContextTier.entries.associateWith { tier ->
      when (tier) {
        ContextTier.FRESH -> ObservationFloors(tier, fresh)
        ContextTier.STALE -> ObservationFloors(tier, stale)
        else -> ObservationFloors(tier)
      }
    }
    return listOf(
      FloorVariant("F1", "pavimenti di oggi in FRESH e STALE", policy(f1Step, f1Step)),
      FloorVariant("F2", "pavimenti di oggi solo in FRESH", policy(f1Step, emptyList())),
      FloorVariant("F3", "tabella per intensita' in FRESH", policy(f3Steps, emptyList())),
    )
  }

  fun run(period: TierPeriod, emit: Boolean, dataRoot: File = File("data"), log: (String) -> Unit = ::println): String {
    val candidate = CandidateArtefact.read(TrainV3Command.CANDIDATE_DIR)
    val model = candidate.toModel()
    val loaded = TierBench.loadInputs(dataRoot, log)
    val rawColumn = "v3 grezzo"
    val variants = variants()
    val extras = listOf(V3Pipeline(rawColumn, model, raw = true)) + variants.map { V3Pipeline("v3 ${it.name}", model, floors = it.policies) }
    log("gioco $period con ${variants.size} varianti di pavimenti (${TierReplayer.defaultThreads()} thread)...")
    val result = TierReplayer(period, kinds = TierKind.PRIMARY, log = log, extras = extras).replay(loaded.inputs)
    val stats = TierStats(result)
    val raw = result.indexOf(rawColumn)
    val gates = variants.associate { it.name to IndependenceGate.evaluate(result, "v3 ${it.name}", candidate.version) }
    val rawGate = IndependenceGate.evaluate(result, rawColumn, candidate.version)
    val decisions = ArrayList<FloorCellDecision>()
    for (kind in listOf(TierKind.FRESH, TierKind.STALE)) {
      for (w in RainWindows.ALL.indices) {
        val lines = ArrayList<String>()
        var chosen: FloorVariant? = null
        var chosenMean = 0.0
        for (variant in variants) {
          val column = result.indexOf("v3 ${variant.name}")
          val europe = stats.cases(TierGroups.EUROPA, kind, w, TruthKind.PANEL)
          val delta = stats.paired(europe, TruthKind.PANEL, column, raw) ?: continue
          val locations = TierGroups.EUROPA.map { location ->
            val cases = stats.cases(listOf(location), kind, w, TruthKind.PANEL)
            location to (stats.row(cases, column, TruthKind.PANEL)!!.brier - stats.row(cases, raw, TruthKind.PANEL)!!.brier)
          }
          val touches = variant.policies.getValue(kind.tier).whenRainingNow.any { RainWindows.ALL[w].label in it.floors }
          val gatePasses = gates.getValue(variant.name).passed
          val adoptable = touches && delta.high < 0 && locations.all { it.second <= 0 } && gatePasses
          lines += String.format(
            Locale.ROOT, "    %-3s %-40s delta EUROPA %s %s; peggiore localita' %s %s; gate %s -> %s",
            variant.name, variant.description, Fmt.sgn4(delta.mean), Fmt.interval(delta),
            locations.maxBy { it.second }.first, Fmt.sgn4(locations.maxOf { it.second }), if (gatePasses) "passa" else "NON passa",
            when {
              !touches -> "non tocca la cella"
              adoptable -> "adottabile"
              else -> "no"
            },
          )
          if (adoptable && (chosen == null || delta.mean < chosenMean)) {
            chosen = variant
            chosenMean = delta.mean
          }
        }
        decisions += FloorCellDecision(kind.tier, w, chosen, lines)
      }
    }
    val policy = merge(decisions)
    val radarValue = radarCandidate(result)
    if (emit) {
      writeKotlin(policy, radarValue, decisions)
      candidate.copy(floors = policy).write(TrainV3Command.CANDIDATE_DIR)
    }
    return buildString {
      appendLine("=== floors-v3 ${period.name} — i pavimenti dell'osservazione del v3 ===")
      appendLine()
      appendLine("Generato da `floors-v3 ${period.name}${if (emit) " --emit" else ""}` sul candidato ${candidate.version} (famiglia ${model.activeFamily}). ${TruthPanel.ATTRIBUTION}.")
      appendLine("Regola preregistrata: una variante si adotta per (livello, finestra) solo se delta(finale - grezzo) in EUROPA ha IC95% tutto sotto zero,")
      appendLine("ogni localita' dura ha delta puntuale <= 0 e il gate di sviluppo con quella variante passa; altrimenti nessun pavimento.")
      appendLine("'Piove adesso' = l'ultimo slot orario chiuso del contesto (r0). Quarto d'ora e radar: non rigiocabili, nessun pavimento certificato.")
      appendLine()
      appendLine("Gate di sviluppo (celle dure che non passano): grezzo ${rawGate.failures.size}; " + variants.joinToString("; ") { "${it.name} ${gates.getValue(it.name).failures.size}" })
      appendLine()
      for (d in decisions) {
        appendLine("  ${d.tier.name} ${RainWindows.ALL[d.window].label}: ${d.adopted?.let { "ADOTTATA ${it.name}" } ?: "nessun pavimento"}")
        d.lines.forEach { appendLine(it) }
      }
      appendLine()
      appendLine("Politica risultante:")
      for (tier in ContextTier.entries) appendLine("  ${policy.getValue(tier).encode()}")
      appendLine()
      appendLine(String.format(Locale.ROOT, "Radar (come dato, SPENTO): pavimento 0-1h = persistenza di tutti i posti (0-1h, fascia 0, classe 1) - 0,05 = %.3f.", radarValue.floor))
      appendLine("  Accenderlo e' una decisione dell'utente: il banco non rigioca il radar e il gate non lo certificherebbe. Proposta per P2: registrare")
      appendLine("  nel record d'emissione il verdetto con e senza radar e decidere dopo almeno 30 giorni.")
      if (emit) appendLine("\nScritti: nowcast/.../verdict/FloorPoliciesV3.kt e build/v3/candidate/floors.txt.")
    }
  }

  /** Le decisioni per cella diventano una politica per livello: gradini = unione delle soglie, valori della variante scelta per finestra. */
  fun merge(decisions: List<FloorCellDecision>): Map<ContextTier, ObservationFloors> = ContextTier.entries.associateWith { tier ->
    val cells = decisions.filter { it.tier == tier && it.adopted != null }
    if (cells.isEmpty()) return@associateWith ObservationFloors(tier)
    val thresholds = cells.flatMap { d -> d.adopted!!.policies.getValue(tier).whenRainingNow.map { it.fromMm } }.distinct().sorted()
    val steps = thresholds.map { t ->
      val floors = LinkedHashMap<String, Double>()
      for (d in cells) {
        val label = RainWindows.ALL[d.window].label
        d.adopted!!.policies.getValue(tier).floorsFor(t)[label]?.let { floors[label] = it }
      }
      RainNowFloor(t, floors)
    }.filter { it.floors.isNotEmpty() }
    ObservationFloors(tier, steps)
  }

  private fun radarCandidate(result: TierReplayResult): RadarFloor {
    val pooled = result.baselines.values.first().pooledOnly()!!.pooled
    val value = (pooled.persistence("0-1h", 0, 1) ?: 0.5) - 0.05
    check(LocalBaselines.RAIN_NOW_CLASSES > 1)
    return RadarFloor(floor = value)
  }

  private fun writeKotlin(policy: Map<ContextTier, ObservationFloors>, radar: RadarFloor, decisions: List<FloorCellDecision>) {
    val target = File(ArtefactWriterV3.SOURCE_DIR, "FloorPoliciesV3.kt")
    val adopted = decisions.filter { it.adopted != null }
    val version = "floors-v3-" + if (adopted.isEmpty()) "none" else adopted.joinToString("-") { "${it.tier.name.lowercase()}${it.window}${it.adopted!!.name.lowercase()}" }.take(60)
    target.writeText(
      buildString {
        appendLine("package dev.pampa.fluidweather.nowcast.verdict")
        appendLine()
        appendLine("import dev.pampa.fluidweather.nowcast.features.ContextTier")
        appendLine()
        appendLine("/**")
        appendLine(" * GENERATO da `gradlew :testbench:run --args=\"floors-v3 validation --emit\"` — non modificare a mano.")
        appendLine(" * I pavimenti dell'osservazione del v3, decisi su VALIDATION con la regola preregistrata (reports/floors-v3-validation.txt):")
        for (d in decisions) appendLine(" * - ${d.tier.name} ${RainWindows.ALL[d.window].label}: ${d.adopted?.let { "${it.name} (${it.description})" } ?: "nessun pavimento"}")
        appendLine(" * Nessun pavimento dal quarto d'ora (non rigiocabile). Il radar e' un dato, spento: accenderlo e' una decisione dell'utente.")
        appendLine(" * P2 li passa al motore come [dev.pampa.fluidweather.nowcast.learning.RainObservation] ([ObservationFloors.observation]).")
        appendLine(" */")
        appendLine("object FloorPoliciesV3 {")
        appendLine("  const val VERSION: String = \"$version\"")
        appendLine()
        appendLine("  val BY_TIER: Map<ContextTier, ObservationFloors> = mapOf(")
        for (tier in ContextTier.entries) {
          val p = policy.getValue(tier)
          if (p.whenRainingNow.isEmpty()) {
            appendLine("    ContextTier.${tier.name} to ObservationFloors(ContextTier.${tier.name}),")
          } else {
            appendLine("    ContextTier.${tier.name} to ObservationFloors(")
            appendLine("      ContextTier.${tier.name},")
            appendLine("      listOf(")
            for (step in p.whenRainingNow) {
              appendLine("        RainNowFloor(${step.fromMm}, mapOf(${step.floors.entries.joinToString(", ") { "\"${it.key}\" to ${it.value}" }})),")
            }
            appendLine("      ),")
            appendLine("    ),")
          }
        }
        appendLine("  )")
        appendLine()
        appendLine("  /** Il pavimento del radar come dato (SPENTO: il banco non lo puo' certificare). */")
        appendLine("  val RADAR_CANDIDATE: RadarFloor? = RadarFloor(window = \"${radar.window}\", minDbz = ${radar.minDbz}, minConfidence = ${radar.minConfidence}, floor = ${radar.floor})")
        appendLine("}")
      },
    )
  }
}
