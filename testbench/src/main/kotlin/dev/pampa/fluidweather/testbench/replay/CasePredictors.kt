package dev.pampa.fluidweather.testbench.replay

import dev.pampa.fluidweather.nowcast.cleaning.CleaningResult
import dev.pampa.fluidweather.nowcast.features.NowcastContext
import dev.pampa.fluidweather.nowcast.truth.RainWindow
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import dev.pampa.fluidweather.testbench.baselines.HonestBaselines
import dev.pampa.fluidweather.testbench.tiers.AsOfContext
import dev.pampa.fluidweather.testbench.tiers.LocationInputs
import dev.pampa.fluidweather.testbench.tiers.TierKind
import java.util.PriorityQueue

/**
 * Cio' che il telefono sa a un'emissione, per un livello: la stessa cosa che vede il modello, e
 * niente di piu'. Non c'e' nessun esito qui dentro: il futuro arriva ai predittori solo attraverso
 * [CaseSession.onFinalOutcome], e solo quando il pannello lo considera definitivo.
 */
class IssueView(
  val input: LocationInputs,
  val kind: TierKind,
  /** L'ancora delle finestre (`t0`). */
  val anchorMillis: Long,
  /** L'istante di emissione del telefono (`t0` meno il minuto casuale). */
  val issueMillis: Long,
  /** Le feature del verdetto con il contesto del livello (NONE e NONE_NOCLIMA: solo barometro). */
  val features: DoubleArray,
  /** Il contesto "com'era al fetch"; null nei livelli senza contesto. */
  val context: NowcastContext?,
  /** La pioggia dell'ultimo slot chiuso del contesto (il "piove adesso" orario); null senza contesto. */
  val contextLastHourMm: Double?,
  /**
   * Il barometro pulito del telefono all'emissione: c'e' per ogni emissione giocata. Serve ai predittori
   * che ricalcolano le feature (il v3 le rifa' con le sue tabelle), e si puo' passare a
   * `FeatureExtractorV3.extract` com'e'.
   */
  val cleaning: CleaningResult? = null,
  /** La normale dei trenta giorni del banco a questa emissione (sempre nota al banco dopo i primi quindici giorni; null prima). */
  val normalHpa: Double? = null,
  /** Il contesto con lo slot da cui viene (il ritardo della persistenza si legge da qui); null senza contesto. */
  val asOf: AsOfContext? = null,
  /** Le baseline oneste del periodo per questa localita': da qui un predittore prende le tabelle (`honest.priors(tier)`). */
  val honest: HonestBaselines? = null,
)

/**
 * Cio' che un predittore in piu' riceve alla nascita di una sessione, oltre alla localita' e al livello:
 * per ora le baseline oneste del periodo (null quando il replay non le costruisce, cioe' senza
 * `withBaselines`).
 */
class SessionContext(val honest: HonestBaselines?)

/** Un esito del pannello diventato definitivo: l'emissione e la finestra a cui appartiene, e quando lo e' diventato. */
data class FinalOutcome(
  val issueMillis: Long,
  val window: RainWindow,
  val rained: Boolean,
  /** Fine dell'ultimo slot della finestra piu' la finalita' del pannello. */
  val finalAtMillis: Long,
)

/**
 * Un predittore in piu' per il [TierReplayer]: una o piu' colonne che non sono baseline ne' il
 * verdetto grezzo — la pipeline del telefono con i suoi pavimenti e il suo apprendimento, le PoP
 * dei provider. Ogni (localita', livello) ha la sua [CaseSession], come se ci fosse un telefono per
 * scenario: le sessioni vedono le emissioni in ordine di tempo, una localita' alla volta.
 */
interface CasePredictor {

  /** Le colonne che riempie, nell'ordine in cui [CaseSession.predict] le restituisce. */
  val columns: List<String>

  /** Un telefono nuovo per [input] nel livello [kind]. */
  fun session(input: LocationInputs, kind: TierKind): CaseSession

  /**
   * Lo stesso con il [SessionContext] del replay. Il default ignora il contesto e chiama la forma di
   * sempre: chi non ne ha bisogno non cambia; il v3, che vuole le tabelle, la ridefinisce.
   */
  fun session(input: LocationInputs, kind: TierKind, ctx: SessionContext): CaseSession = session(input, kind)
}

/** Il telefono di uno scenario: vede le emissioni una dopo l'altra, e gli esiti solo quando sono definitivi. */
fun interface CaseSession {

  /**
   * Le probabilita' all'emissione [view]: `[colonna][finestra]` nell'ordine di [RainWindows.ALL],
   * NaN dove la colonna non risponde. Si chiama per ogni emissione giocata del livello, anche se poi
   * qualche finestra non entra nel conto: chi impara deve vedere tutto cio' che il telefono vedrebbe.
   */
  fun predict(view: IssueView): Array<DoubleArray>

  /**
   * Un esito del pannello, consegnato alla prima emissione a cui e' definitivo (mai prima): e' la
   * sola porta da cui il futuro entra in un predittore.
   */
  fun onFinalOutcome(outcome: FinalOutcome) {}
}

/**
 * La coda degli esiti in attesa di diventare definitivi, per una (localita', livello).
 *
 * Un esito di una finestra si conosce solo [TruthPanel.FINALITY_MILLIS] dopo la fine del suo
 * ultimo slot ([RainWindows.isFinal]): prima l'archivio puo' ancora riscriverlo, e sul telefono
 * nessuno lo ha. La coda e' ordinata per (istante di finalita', emissione, finestra), cosi' la
 * consegna e' deterministica.
 */
class FinalityQueue(private val finalityMillis: Long = TruthPanel.FINALITY_MILLIS) {

  private val pending = PriorityQueue(
    compareBy<FinalOutcome>({ it.finalAtMillis }, { it.issueMillis }, { it.window.fromHours }),
  )

  val size: Int get() = pending.size

  /** Mette in coda gli esiti noti (>= 0) di un'emissione: `outcomes[w]` e' -1, 0 o 1 per [RainWindows.ALL]. */
  fun enqueue(issueMillis: Long, outcomes: IntArray) {
    for ((w, window) in RainWindows.ALL.withIndex()) {
      val outcome = outcomes[w]
      if (outcome < 0) continue
      val finalAt = RainWindows.lastSlotEnd(issueMillis, window) + finalityMillis
      pending += FinalOutcome(issueMillis, window, outcome == 1, finalAt)
    }
  }

  /** Consegna a [deliver] tutti gli esiti definitivi a [nowMillis], nell'ordine della coda. */
  fun release(nowMillis: Long, deliver: (FinalOutcome) -> Unit) {
    while (true) {
      val head = pending.peek() ?: return
      if (!RainWindows.isFinal(head.finalAtMillis - finalityMillis, nowMillis, finalityMillis)) return
      deliver(pending.poll())
    }
  }
}
