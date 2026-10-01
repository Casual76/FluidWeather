package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.data.DataWipeGuard
import dev.pampa.fluidweather.core.model.Observation
import dev.pampa.fluidweather.core.model.RainBoardIds
import dev.pampa.fluidweather.core.model.RainEventPending
import dev.pampa.fluidweather.core.model.RainEventStore
import dev.pampa.fluidweather.core.model.RainEventVerification
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.truth.Sighting
import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException

/** Una richiesta di verita': un punto, un intervallo di date UTC, e le righe che giudichera'. */
data class TruthRequest(
  val latitude: Double,
  val longitude: Double,
  val startDate: LocalDate,
  val endDate: LocalDate,
  val rows: List<RainEventPending>,
)

/**
 * Come si raggruppano le righe da giudicare in richieste all'archivio.
 *
 * La verita' di una riga si scarica **nel suo posto**, quello salvato quando e' stata registrata, e
 * non dove l'utente si trova il giorno dopo. Ma una richiesta per riga sarebbe uno spreco: le righe
 * di uno stesso giro stanno tutte nello stesso punto, e i giri di una giornata a casa a pochi metri.
 * Si raggruppano quindi per cella da 0,1 gradi e, dentro la cella, attorno a un seme (la riga piu'
 * vecchia) tutte quelle entro [SAME_PLACE_KM]: e' lo stesso raggio con cui l'app dice "stesso
 * posto" (vedi [WeatherSnapshotRefresher.MAX_DISTANCE_KM]), piu' fine della griglia di qualunque
 * giudice. La richiesta va al punto del seme.
 */
object TruthRequests {
  const val CELL_DEGREES: Double = 0.1
  const val SAME_PLACE_KM: Double = WeatherSnapshotRefresher.MAX_DISTANCE_KM

  /**
   * Le richieste per le righe [due], dalla piu' urgente (quella col seme piu' vecchio). Righe con
   * una finestra sconosciuta non entrano: non c'e' niente da chiedere per loro.
   *
   * startDate e' la data UTC della prima fine-slot fra le righe del grappolo, endDate quella
   * dell'ultima: uno slot che si chiude a mezzanotte appartiene al giorno che comincia.
   */
  fun plan(due: List<RainEventPending>): List<TruthRequest> {
    val judgeable = due.filter { RainWindows.byLabel(it.window) != null }
    val byCell = judgeable.groupBy { cellOf(it) }
    val requests = mutableListOf<TruthRequest>()
    for (rows in byCell.values) {
      val left = rows.sortedWith(compareBy({ it.issuedAtMillis }, { it.providerId }, { it.window })).toMutableList()
      while (left.isNotEmpty()) {
        val seed = left.first()
        val cluster = left.filter { haversineKm(seed.latitude, seed.longitude, it.latitude, it.longitude) <= SAME_PLACE_KM }
        left.removeAll(cluster.toSet())
        requests += requestFor(seed, cluster)
      }
    }
    return requests.sortedBy { request -> request.rows.minOf { it.issuedAtMillis } }
  }

  fun url(request: TruthRequest): String = OpenMeteoArchive.url(
    request.latitude,
    request.longitude,
    request.startDate,
    request.endDate,
    PRECIPITATION,
    TruthPanel.MODELS,
  )

  internal const val PRECIPITATION = "precipitation"

  private fun requestFor(seed: RainEventPending, rows: List<RainEventPending>): TruthRequest {
    val firstSlotEnd = rows.minOf { row ->
      val window = RainWindows.byLabel(row.window)!!
      RainWindows.slotEnds(RainWindows.anchorOf(row.issuedAtMillis), window).first()
    }
    val lastSlotEnd = rows.maxOf { RainWindows.lastSlotEnd(it.issuedAtMillis, RainWindows.byLabel(it.window)!!) }
    return TruthRequest(seed.latitude, seed.longitude, utcDateOf(firstSlotEnd), utcDateOf(lastSlotEnd), rows)
  }

  /**
   * La cella in millesimi di grado interi: 11,2 / 0,1 in virgola mobile fa 111,99999999999999, e un
   * `floor` sul double metterebbe 11,2 nella cella sbagliata. Le righe sono gia' al millesimo.
   */
  private fun cellOf(row: RainEventPending): Pair<Long, Long> =
    cellIndex(row.latitude, CELL_MILLIDEGREES) to cellIndex(row.longitude, CELL_MILLIDEGREES)

  private const val CELL_MILLIDEGREES = 100L

  internal fun utcDateOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
}

/** L'indice di cella di una coordinata, a celle di [cellMillidegrees] millesimi di grado: aritmetica intera, esatta. */
internal fun cellIndex(degrees: Double, cellMillidegrees: Long): Long =
  Math.floorDiv(Math.round(degrees * 1000.0), cellMillidegrees)

/** Com'e' andato un giro di giudizi: per la Diagnostica e per i test. */
data class SettleSummary(
  /** Le righe la cui finestra era definitiva. */
  val due: Int,
  val settled: Int,
  val requests: Int,
  val failedRequests: Int,
  val expired: Int,
)

/**
 * Il giudice della classifica pioggia: scarica la verita' del [TruthPanel] e salda le righe in
 * attesa.
 *
 * Prima i giudizi si davano dentro il giro dei provider, con i bundle appena scaricati: la verita'
 * erano gli stessi provider in classifica, la loro ora "passata" era spesso ancora la corsa che
 * aveva prodotto la PoP da giudicare, e la riga si giudicava solo se l'utente era ancora li'. Qui
 * la verita' e' una richiesta a parte, all'archivio historical-forecast, con i tre giudici fuori
 * classifica, **nel posto della riga** e solo quando quelle ore non cambiano piu'.
 *
 * - **Quando.** Una riga e' matura quando la sua finestra si e' chiusa da [TruthPanel.FINALITY_MILLIS].
 *   Non basta: l'archivio deve essere stato scaricato *dopo* quell'istante, e per questo la
 *   richiesta non passa dalla cache — una risposta di un'ora fa poteva contenere quelle ore quando
 *   erano ancora previsioni.
 * - **Chi decide.** La mediana del pannello, slot per slot, solo con tutti e tre i giudici; poi
 *   l'occhio dell'utente entro 3 km, con le regole di [RainWindows]: "bagnato" rende bagnata la
 *   finestra, "asciutto" azzera il suo slot. Nessun provider vota mai.
 * - **Cosa si aspetta.** Una riga senza verita' (un giudice che manca, la rete giu') resta in attesa
 *   fino a [PENDING_MAX_AGE_MILLIS]; poi si butta, senza inventarle un esito.
 *
 * Gli esiti del barometro tornano anche all'apprendimento ([onBarometerOutcome]): la ricalibrazione
 * personale impara dalla stessa verita' della classifica, non da una sua parente.
 */
class TruthPanelSettler(
  private val http: ProviderHttp,
  private val store: RainEventStore,
  /** Le osservazioni dell'utente da un istante in poi. */
  private val observations: suspend (sinceMillis: Long) -> List<Observation>,
  /** (giro, finestra, e' piovuto?) per ogni riga del barometro giudicata. */
  private val onBarometerOutcome: suspend (roundId: Long, window: String, rained: Boolean) -> Unit = { _, _, _ -> },
  private val clock: () -> Long = System::currentTimeMillis,
  /**
   * La mutua esclusione con "cancella tutti i dati": il giudizio scarica (rete, lenta) e poi
   * scrive; se l'utente cancella nel mezzo, le scritture dopo la rete non devono resuscitare i
   * giudizi e gli esiti che ha chiesto di perdere.
   */
  private val wipeGuard: DataWipeGuard = DataWipeGuard(),
) {

  suspend fun settle(): SettleSummary {
    val generation = wipeGuard.generation()
    val now = clock()
    // Una finestra 0-1h si chiude almeno un'ora dopo l'emissione: prima di adesso meno finalita' e
    // un'ora non c'e' niente di maturo, e la query resta piccola.
    val candidates = store.pendingIssuedBefore(now - TruthPanel.FINALITY_MILLIS - RainWindows.HOUR_MILLIS)
    val due = candidates.filter { row ->
      val window = RainWindows.byLabel(row.window) ?: return@filter false
      TruthPanel.isFinal(RainWindows.lastSlotEnd(row.issuedAtMillis, window), now)
    }

    val verifications = mutableListOf<RainEventVerification>()
    var requests = 0
    var failed = 0
    if (due.isNotEmpty()) {
      val since = due.minOf { it.issuedAtMillis } - RainWindows.HOUR_MILLIS
      val seen = runCatching { observations(since) }.getOrDefault(emptyList())
        .filter { it.latitude != null && it.longitude != null }

      for (request in TruthRequests.plan(due).take(MAX_REQUESTS_PER_RUN)) {
        requests++
        val judged = runCatching { judge(request, seen, now) }
        // La rete o l'archivio: le righe restano in attesa, riprovera' il prossimo giro.
        judged.onSuccess { verifications += it }.onFailure { failed++ }
      }
    }

    // Tutte le scritture insieme sotto il lucchetto: una cancellazione passata nel frattempo le
    // salta, e niente di quanto si e' scaricato torna a galla.
    val expired = wipeGuard.writeIfUnchanged(generation) {
      // Prima l'apprendimento, poi il magazzino. Nell'ordine opposto un processo ucciso (o il tetto di
      // tempo del ciclo in background) fra le due scritture perdeva l'esito per sempre: la riga non
      // era piu' in attesa, e nessuno l'avrebbe rigiudicata. Cosi' invece la riga resta in attesa
      // finche' il suo giudizio non e' scritto, e un esito ripetuto non costa niente: l'archivio
      // dell'apprendimento lo chiava su (giro, finestra) e lo sostituisce.
      for (verification in verifications) {
        val row = verification.prediction
        if (row.providerId != RainBoardIds.BAROMETER) continue
        try {
          onBarometerOutcome(row.roundId, row.window, verification.rained)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          // Un esito non scritto non ferma i giudizi: la classifica non dipende dall'apprendimento.
        }
      }
      store.settle(verifications)
      val expired = store.expirePending(now - PENDING_MAX_AGE_MILLIS)
      // La ritenzione dei giudizi, qui e non solo nel ciclo in background: sui telefoni senza barometro
      // il ciclo non parte, ma il giudice si' (all'apertura dell'app), e una tabella senza potatura
      // crescerebbe per sempre.
      runCatching { store.prune(now) }
      expired
    } ?: return SettleSummary(due.size, 0, requests, failed, 0)
    return SettleSummary(due.size, verifications.size, requests, failed, expired)
  }

  private suspend fun judge(request: TruthRequest, seen: List<Observation>, now: Long): List<RainEventVerification> {
    val (root, downloadedAt) = http.readJsonTimed(TruthRequests.url(request), maxAgeMillis = 0L, useCache = false)
    val series = OpenMeteoArchive.hourlySeries(root, TruthRequests.PRECIPITATION, TruthPanel.MODELS)
    val truth = TruthPanel.combine(series)

    return request.rows.mapNotNull { row ->
      val window = RainWindows.byLabel(row.window) ?: return@mapNotNull null
      // Un archivio scaricato prima della finalita' puo' contenere quelle ore da previsione.
      if (downloadedAt < RainWindows.lastSlotEnd(row.issuedAtMillis, window) + TruthPanel.FINALITY_MILLIS) {
        return@mapNotNull null
      }
      val sightings = seen
        .filter { haversineKm(row.latitude, row.longitude, it.latitude!!, it.longitude!!) <= OBSERVATION_RADIUS_KM }
        .map { Sighting.of(it) }
      val outcome = RainWindows.evaluate(row.issuedAtMillis, window, { truth[it] }, sightings)
        ?: return@mapNotNull null
      val slotEnds = RainWindows.slotEnds(RainWindows.anchorOf(row.issuedAtMillis), window)
      RainEventVerification(
        prediction = row,
        rained = outcome.wet,
        truthSumMm = outcome.sumMm,
        truthVoters = slotEnds.minOf { end -> TruthPanel.MODELS.count { series[it]?.get(end) != null } },
        truthSource = if (outcome.sightings > 0) OBSERVATION_SOURCE else TruthPanel.VERSION,
        settledAtMillis = now,
      )
    }
  }

  companion object {
    /** Una settimana: oltre, una verita' che non arriva non arrivera' piu' (o arrivera' troppo tardi per contare). */
    const val PENDING_MAX_AGE_MILLIS: Long = 7L * 24 * 3_600_000L

    /** Otto richieste per giro bastano a smaltire una settimana di arretrato in poche ore, senza raffiche. */
    const val MAX_REQUESTS_PER_RUN: Int = 8

    /** Il valore di `truthSource` quando nella finestra c'era un'osservazione dell'utente. */
    const val OBSERVATION_SOURCE: String = "osservazione"

    /** Un'osservazione vale per le righe entro lo stesso raggio di "stesso posto". */
    const val OBSERVATION_RADIUS_KM: Double = TruthRequests.SAME_PLACE_KM
  }
}
