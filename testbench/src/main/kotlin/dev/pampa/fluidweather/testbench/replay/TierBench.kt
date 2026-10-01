package dev.pampa.fluidweather.testbench.replay

import dev.pampa.fluidweather.nowcast.verdict.NowcastModel
import dev.pampa.fluidweather.testbench.baselines.PredictorNames
import dev.pampa.fluidweather.testbench.data.BenchLocations
import dev.pampa.fluidweather.testbench.metrics.Fmt
import dev.pampa.fluidweather.testbench.tiers.LocationInputs
import dev.pampa.fluidweather.testbench.tiers.TierPeriod
import dev.pampa.fluidweather.testbench.tiers.TierPeriods
import dev.pampa.fluidweather.testbench.train.TrainCommand
import java.io.File

/**
 * I due comandi del banco onesto:
 *
 *   --args="baselines <validation|test>"      solo le baseline, tutti i livelli -> reports/baselines-<periodo>.txt
 *   --args="replay-tiers <validation|test>"   baseline + il nowcast spedito (verdetto grezzo) + le PoP dei
 *                                             provider con fuga (solo FRESH) -> reports/replay-tiers-<periodo>.txt
 *
 * Il periodo e' obbligatorio e non ha default: TEST si tocca una volta sola sull'artefatto finale,
 * e un comando che lo scegliesse da solo lo consumerebbe per distrazione.
 */
object TierBench {

  /** Le localita' lette e quelle che mancano (localita' -> perche'). */
  class LoadedInputs(val inputs: List<LocationInputs>, val missing: Map<String, String>)

  /** Il nome del file di rapporto di un comando su un periodo. */
  fun reportName(command: String, period: TierPeriod): String = "$command-${period.name}.txt"

  /** Legge tutte le localita' del banco da [dataRoot], nell'ordine di [BenchLocations]; quelle incomplete le salta e lo dice. */
  fun loadInputs(dataRoot: File = File("data"), log: (String) -> Unit = ::println): LoadedInputs {
    val missing = LinkedHashMap<String, String>()
    val inputs = BenchLocations.mapNotNull { location ->
      val absent = LocationInputs.missingFor(location, dataRoot)
      if (absent.isNotEmpty()) {
        missing[location.name] = "mancano ${absent.joinToString(", ")}"
        log("  salto ${location.name}: mancano ${absent.joinToString(", ")}")
        null
      } else {
        log("  leggo ${location.name}...")
        LocationInputs.load(location, dataRoot)
      }
    }
    return LoadedInputs(inputs, missing)
  }

  /** Il risultato di un replay con le localita' mancanti aggiunte a quelle saltate, per il rapporto. */
  fun withMissing(result: TierReplayResult, missing: Map<String, String>): TierReplayResult = TierReplayResult(
    period = result.period,
    predictorNames = result.predictorNames,
    records = result.records,
    coverage = result.coverage,
    baselines = result.baselines,
    skipped = missing + result.skipped,
  )

  /**
   * Gioca il periodo e ritorna il testo del rapporto. [withModel] aggiunge il nowcast spedito e le
   * PoP dei provider (con fuga, solo FRESH). Le localita' con dati mancanti non si giocano e il rapporto lo dice.
   */
  fun run(
    period: TierPeriod,
    withModel: Boolean,
    dataRoot: File = File("data"),
    log: (String) -> Unit = ::println,
    /** La misura di sensibilita' del barometro causale (vedi [SampleSynthesizer]): stesso banco, pressione senza anticipo. */
    causalPressure: Boolean = false,
    /** Il v3 da mettere accanto (`--model v3|v3-candidate`, solo con il modello): nome e modello. */
    v3: Pair<String, dev.pampa.fluidweather.nowcast.verdict.TieredNowcastModel>? = null,
  ): String {
    val command = if (withModel) "replay-tiers" else "baselines"
    val loaded = loadInputs(dataRoot, log)
    if (loaded.inputs.isEmpty()) return "$command: nessuna localita' con i dati completi in ${dataRoot.path} - prima `fetch` e `fetch-hf all`"

    log("gioco $period su ${loaded.inputs.size} localita' (${TierReplayer.defaultThreads()} thread)...")
    val replayer = TierReplayer(
      period,
      model = if (withModel) NowcastModel.trained() else null,
      log = log,
      extras = if (withModel) listOf(LeakyProviderPop()) + (v3?.let { (name, model) -> listOf(V3Pipeline("$name grezzo", model, raw = true)) } ?: emptyList()) else emptyList(),
      causalPressure = causalPressure,
    )
    val result = replayer.replay(loaded.inputs)
    val notes = if (!causalPressure) {
      emptyList()
    } else {
      listOf(
        "SENSIBILITA': barometro CAUSALE. Fra un'ora e l'altra la pressione sintetica prosegue la pendenza delle due ore chiuse",
        "invece di interpolare verso l'ora dopo: nessun campione dipende dall'ERA5 di un'ora che si chiude dopo di lui.",
        "Da confrontare con $command-${period.name}.txt (stesse emissioni, stessi contesti, stesse verita').",
      )
    }
    val invocation = "$command ${period.name}" + if (causalPressure) " $CAUSAL_FLAG" else ""
    val report = TierReportWriter(withMissing(result, loaded.missing), invocation + (v3?.let { " --model ${it.first}" } ?: ""), notes).render()
    if (v3 == null) return report
    return report + "\n" + V3Report.section(
      result,
      "IL V3 GREZZO ACCANTO AL V2 GREZZO (${v3.first}, ${v3.second.version}, famiglia ${v3.second.activeFamily})",
      listOf("${v3.first} grezzo" to "v3", PredictorNames.MODELLO to "v2"),
    )
  }

  /** Il flag della misura di sensibilita' del barometro causale. */
  const val CAUSAL_FLAG: String = "--barometro-causale"

  /** Il nome del rapporto di `replay-tiers`/`baselines`, con il suffisso della misura di sensibilita'. */
  fun reportName(command: String, period: TierPeriod, causalPressure: Boolean): String =
    if (causalPressure) "$command-${period.name}-causale.txt" else reportName(command, period)

  /** `baselines`/`replay-tiers` con l'argomento della riga di comando; null (e un messaggio) se il periodo non e' valido. */
  fun parsePeriod(argument: String?): TierPeriod? = TierPeriods.byName(argument)

  /**
   * L'avvertenza per i periodi che il v2 spedito ha visto in addestramento ([TrainCommand.CUTOFF_MILLIS]:
   * archivio orario fino al 2025-08-31, etichetta ERA5, contesto ERA5): li' i suoi pesi sono in
   * campione, e ogni confronto del modello con le baseline e' ottimista per il modello. VALIDATION ci
   * cade tutta; TEST no. Null se il periodo e' fuori.
   */
  fun v2InSampleNote(period: TierPeriod): String? =
    if (period.firstMillis >= TrainCommand.CUTOFF_MILLIS) {
      null
    } else {
      "ATTENZIONE: il v2 spedito e' addestrato sull'archivio fino al ${Fmt.date(TrainCommand.CUTOFF_MILLIS - 1)} (etichetta e contesto ERA5): " +
        "su ${period.name.uppercase()} i suoi pesi sono IN CAMPIONE, e il confronto del modello con le baseline e' ottimista per il modello."
    }
}
