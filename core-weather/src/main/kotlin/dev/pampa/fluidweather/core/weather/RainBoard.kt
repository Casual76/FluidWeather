package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.RainBoardIds
import dev.pampa.fluidweather.core.model.RainEventVerification
import dev.pampa.fluidweather.nowcast.scoring.BootstrapSummary
import dev.pampa.fluidweather.nowcast.scoring.DayBlockBootstrap
import dev.pampa.fluidweather.nowcast.scoring.ForecastCase
import dev.pampa.fluidweather.nowcast.scoring.ProperScores
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.verdict.ModelVersions

/** Che cosa e' una riga della classifica pioggia: decide dove si mostra e come si legge. */
enum class RainRowKind { BAROMETER, PROVIDER, REFERENCE, SHADOW }

/** Il punteggio di una riga su una finestra, sui soli giri del caso comune. */
data class RainRowScore(
  val providerId: String,
  val kind: RainRowKind,
  val cases: Int,
  /** Su quanti giorni distinti: i casi di uno stesso giorno sono quasi un caso solo. */
  val days: Int,
  /** Brier medio: la chiave della classifica, piu' basso e' meglio. */
  val brier: Double,
  /** L'intervallo al 95% del Brier, ricampionando giorni interi. */
  val brierLow: Double,
  val brierHigh: Double,
  /** L'errore assoluto medio: secondario, il numero che la vecchia classifica mostrava. */
  val mae: Double,
  /** Brier(riga) - Brier(ancora) sugli stessi giri, a blocchi di giorni; null per l'ancora. */
  val deltaVsAnchor: BootstrapSummary?,
  /** Troppo pochi casi o giorni per una posizione: si mostra, ma senza numero in classifica. */
  val fewData: Boolean,
  /** 1 = il migliore; null per chi ha pochi dati e per le ombre. */
  val rank: Int?,
) {
  /** Il "±" della pagina: meta' dell'intervallo. */
  val halfWidth: Double get() = (brierHigh - brierLow) / 2
}

/** La classifica di una finestra. */
data class RainWindowBoard(
  val window: String,
  val anchorId: String,
  /** I giri del caso comune: quelli con una riga dell'ancora giudicata. */
  val rounds: Int,
  val days: Int,
  /** Le righe in classifica: prima le classificate, poi quelle con pochi dati. */
  val rows: List<RainRowScore>,
  /** Le ombre (il barometro senza contesto): stessi giri, fuori classifica. */
  val shadows: List<RainRowScore>,
)

data class RainBoardReport(
  val modelVersion: String,
  val anchorId: String,
  val sinceMillis: Long,
  val windows: List<RainWindowBoard>,
) {
  val totalCases: Int get() = windows.sumOf { it.rounds }
}

/**
 * La classifica pioggia: chi dice meglio se piovera' nella finestra, su domande identiche.
 *
 * Le regole, e perche':
 *
 * - **Solo la versione corrente** ([ModelVersions.TAG]). Un Brier del modello di ieri non e' un
 *   Brier di quello di oggi: a ogni versione il barometro riparte da zero, ed e' giusto.
 * - **Gli ultimi [WINDOW_DAYS] giorni, senza decadimento.** Un peso che dimezza ogni due settimane
 *   rende la classifica figlia delle ultime piogge; una finestra fissa e' leggibile e confrontabile.
 * - **Gli stessi giri per tutti.** Il caso comune e' l'insieme dei giri in cui l'ancora (il
 *   barometro; la climatologia sui telefoni senza sensore) ha una riga giudicata, e ogni riga si
 *   misura **solo** su quelli. Prima un provider veniva giudicato anche nelle ore in cui il
 *   barometro non aveva ancora un verdetto: due righe della stessa tabella rispondevano a domande
 *   diverse.
 * - **Brier, non errore medio.** Il Brier e' proprio: non si vince dicendo 0 o 1 quando si sa che
 *   la frequenza vera e' 0,3. La MAE resta, dichiarata e secondaria (spareggio).
 * - **L'incertezza vera.** L'intervallo si fa ricampionando giorni interi ([DayBlockBootstrap]),
 *   e il confronto con l'ancora e' appaiato caso per caso: stessi giri, stesse giornate difficili.
 * - **Pochi dati, niente posizione.** Sotto [MIN_CASES] casi o [MIN_DAYS] giorni una riga si mostra
 *   ma non si classifica: una settimana di sole non dice chi e' bravo con la pioggia.
 * - **I giudici non gareggiano** ([ProviderWindowPop.JUDGE_PROVIDER_IDS]) e **le ombre stanno a
 *   parte**: sono misure del barometro, non concorrenti.
 */
object RainBoard {

  const val WINDOW_DAYS: Int = 60
  const val WINDOW_MILLIS: Long = WINDOW_DAYS * 86_400_000L
  const val MIN_CASES: Int = 30
  const val MIN_DAYS: Int = 5

  fun kindOf(id: String): RainRowKind = when (id) {
    RainBoardIds.BAROMETER -> RainRowKind.BAROMETER
    in RainBoardIds.SHADOWS -> RainRowKind.SHADOW
    in RainBoardIds.REFERENCES -> RainRowKind.REFERENCE
    else -> RainRowKind.PROVIDER
  }

  fun build(
    verifications: List<RainEventVerification>,
    nowMillis: Long,
    hasBarometer: Boolean,
    modelVersion: String = ModelVersions.TAG,
    bootstrap: DayBlockBootstrap = DayBlockBootstrap(),
  ): RainBoardReport {
    val since = nowMillis - WINDOW_MILLIS
    val anchorId = if (hasBarometer) RainBoardIds.BAROMETER else RainBoardIds.CLIMATOLOGY
    val kept = verifications.filter {
      it.prediction.modelVersion == modelVersion &&
        it.prediction.issuedAtMillis >= since &&
        it.prediction.providerId !in ProviderWindowPop.JUDGE_PROVIDER_IDS
    }
    val windows = RainWindows.ALL.map { window ->
      windowBoard(window.label, kept.filter { it.prediction.window == window.label }, anchorId, bootstrap)
    }
    return RainBoardReport(modelVersion, anchorId, since, windows)
  }

  private fun windowBoard(
    window: String,
    rows: List<RainEventVerification>,
    anchorId: String,
    bootstrap: DayBlockBootstrap,
  ): RainWindowBoard {
    val anchor = rows.filter { it.prediction.providerId == anchorId }.associateBy { roundKeyOf(it) }
    val anchorDays = anchor.values.map { DayBlockBootstrap.epochDayOf(it.prediction.issuedAtMillis) }.toSet().size

    val scores = rows
      .filter { roundKeyOf(it) in anchor }
      .groupBy { it.prediction.providerId }
      .map { (providerId, own) -> score(providerId, own, anchor, anchorId, bootstrap) }

    val (shadows, main) = scores.partition { it.kind == RainRowKind.SHADOW }
    val ranked = main.filter { !it.fewData }
      .sortedWith(compareBy({ it.brier }, { it.mae }, { it.providerId }))
      .mapIndexed { index, row -> row.copy(rank = index + 1) }
    val unranked = main.filter { it.fewData }.sortedWith(compareBy({ it.brier }, { it.providerId }))
    return RainWindowBoard(
      window = window,
      anchorId = anchorId,
      rounds = anchor.size,
      days = anchorDays,
      rows = ranked + unranked,
      shadows = shadows.sortedWith(compareBy({ it.brier }, { it.providerId })),
    )
  }

  private fun score(
    providerId: String,
    own: List<RainEventVerification>,
    anchor: Map<Pair<String, Long>, RainEventVerification>,
    anchorId: String,
    bootstrap: DayBlockBootstrap,
  ): RainRowScore {
    // Ordine fisso: il bootstrap e' deterministico solo se lo e' anche la lista.
    val cases = own.sortedWith(compareBy({ it.prediction.issuedAtMillis }, { it.prediction.placeKey }))
    val briers = cases.map { ProperScores.brier(it.prediction.probability, it.rained) }
    val days = cases.map { DayBlockBootstrap.epochDayOf(it.prediction.issuedAtMillis) }
    val summary = bootstrap.summarize(briers, days)
    val delta = if (providerId == anchorId) {
      null
    } else {
      val anchorBriers = cases.map { case ->
        val reference = anchor.getValue(roundKeyOf(case))
        ProperScores.brier(reference.prediction.probability, reference.rained)
      }
      bootstrap.paired(briers, anchorBriers, days)
    }
    return RainRowScore(
      providerId = providerId,
      kind = kindOf(providerId),
      cases = cases.size,
      days = summary.days,
      brier = summary.mean,
      brierLow = summary.low,
      brierHigh = summary.high,
      mae = ProperScores.mae(cases.map { ForecastCase(it.prediction.probability, it.rained) }),
      deltaVsAnchor = delta,
      fewData = cases.size < MIN_CASES || summary.days < MIN_DAYS,
      rank = null,
    )
  }

  private fun roundKeyOf(verification: RainEventVerification): Pair<String, Long> =
    verification.prediction.placeKey to verification.prediction.roundId
}
