package dev.pampa.fluidweather.testbench.replay

import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import dev.pampa.fluidweather.nowcast.verdict.ObservationFloors
import dev.pampa.fluidweather.nowcast.verdict.TieredNowcastModel
import dev.pampa.fluidweather.testbench.baselines.HonestBaselines
import dev.pampa.fluidweather.testbench.tiers.LocationInputs
import dev.pampa.fluidweather.testbench.tiers.ScenarioMode
import dev.pampa.fluidweather.testbench.tiers.TierKind
import dev.pampa.fluidweather.testbench.train.v3.V3FeatureAssembly

/**
 * Il v3 come colonna del replay: la pipeline del telefono appena installato con "Il tuo barometro" v3.
 *
 * Per ogni emissione: le quarantadue feature dal **solo** punto di montaggio del banco
 * ([V3FeatureAssembly], lo stesso delle righe di addestramento) con le tabelle del periodo
 * (`honest.priors(livello)`, cioe' le stesse delle baseline del gate) e la semantica del gate (il telefono
 * ha tutto cio' che il suo livello puo' avere); poi il modello del livello ([TieredNowcastModel.verdict]);
 * poi i pavimenti dell'osservazione [floors] (di default quelli del modello), applicati come fa il motore
 * (`NowcastEngine`): alzano e basta, la banda bassa a 0,8 del pavimento. L'apprendimento e' vuoto: e' cio'
 * che il gate giudica.
 *
 * "Piove adesso" per i pavimenti e' la pioggia dello slot chiuso del contesto (r0), la stessa che il
 * modello vede nella feature `piove-adesso`: il v3 non usa i quarti d'ora (precondizione del contratto).
 */
class V3Pipeline(
  val column: String,
  val model: TieredNowcastModel,
  /** Null = i pavimenti del modello; una mappa vuota per livello = nessun pavimento. */
  private val floors: Map<ContextTier, ObservationFloors>? = null,
  /** true = la probabilita' grezza del modello, senza pavimenti. */
  private val raw: Boolean = false,
) : CasePredictor {

  override val columns: List<String> = listOf(column)

  override fun session(input: LocationInputs, kind: TierKind): CaseSession =
    error("il v3 ha bisogno delle baseline del periodo: si gioca solo con un replay che le costruisce")

  override fun session(input: LocationInputs, kind: TierKind, ctx: SessionContext): CaseSession {
    val honest = ctx.honest ?: error("il v3 ha bisogno delle baseline del periodo (replay con withBaselines)")
    return CaseSession { view -> arrayOf(probabilities(view, honest)) }
  }

  /** Le tre probabilita' finali di un'emissione (NaN se il v3 non ha feature: niente barometro o niente tabelle). */
  fun probabilities(view: IssueView, honest: HonestBaselines): DoubleArray {
    val verdict = verdictOf(view, honest) ?: return DoubleArray(RainWindows.ALL.size) { Double.NaN }
    return DoubleArray(RainWindows.ALL.size) { w -> verdict.windows[w].probability }
  }

  /** Il verdetto finale (dopo i pavimenti) di un'emissione, o null. */
  fun verdictOf(view: IssueView, honest: HonestBaselines): NowcastVerdict? {
    val cleaning = view.cleaning ?: return null
    val assembled = V3FeatureAssembly.assemble(
      view.input.location.name, view.kind, view.anchorMillis, view.issueMillis, cleaning, view.normalHpa,
      view.asOf, honest, ScenarioMode.EVALUATION,
    ) ?: return null
    val tier = view.kind.tier
    val verdict = model.verdict(tier, assembled.features)
    if (raw) return verdict
    val policy = (floors ?: model.floors)[tier] ?: ObservationFloors(tier)
    val rainNow = rainNowOf(view)
    return applyFloors(verdict, policy.floorsFor(rainNow))
  }

  companion object {
    /** La pioggia dell'ultimo slot chiuso del contesto: quella che il modello vede come `piove-adesso`. */
    fun rainNowOf(view: IssueView): Double? {
      if (!view.kind.hasContext) return null
      return view.asOf?.context?.rainSlotsMm?.getOrNull(0) ?: view.contextLastHourMm
    }

    /** I pavimenti come li applica il motore: alzano la probabilita' e l'alto della banda, il basso a 0,8 del pavimento. */
    fun applyFloors(verdict: NowcastVerdict, floors: Map<String, Double>): NowcastVerdict {
      if (floors.isEmpty()) return verdict
      val windows = verdict.windows.map { window ->
        val floor = floors[window.window] ?: 0.0
        if (floor <= window.probability) {
          window
        } else {
          window.copy(
            probability = floor,
            probabilityHigh = maxOf(window.probabilityHigh, floor),
            probabilityLow = maxOf(window.probabilityLow, floor * LOW_BAND_SHARE),
          )
        }
      }
      return verdict.copy(windows = windows)
    }

    /** Come `NowcastEngine.LOW_BAND_SHARE`. */
    const val LOW_BAND_SHARE: Double = 0.8
  }
}
