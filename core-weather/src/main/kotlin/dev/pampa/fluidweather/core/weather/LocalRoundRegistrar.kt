package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.data.DataWipeGuard
import dev.pampa.fluidweather.core.model.NowcastIssueRecord
import dev.pampa.fluidweather.core.model.RainBoardIds
import dev.pampa.fluidweather.core.model.RainEventPending
import dev.pampa.fluidweather.core.model.RainEventStore
import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.verdict.ModelVersions
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Cio' che il barometro dice su un giro: il verdetto finale (quello che l'utente vede), le
 * probabilita' grezze e le feature da cui il telefono impara, e l'ombra "solo barometro".
 */
data class LocalEvaluation(
  val verdict: NowcastVerdict?,
  val rawVerdict: NowcastVerdict?,
  val features: DoubleArray?,
  val soloVerdict: NowcastVerdict?,
  /** Il livello con cui il verdetto e' stato calcolato: il registratore lo iscrive, non lo ricalcola. */
  val tier: ContextTier? = null,
  /** Le probabilita' grezze dell'ombra: la riga d'archivio da cui impara la mappa "none". */
  val soloRawVerdict: NowcastVerdict? = null,
  val soloFeatures: DoubleArray? = null,
  /** Il livello dell'ombra: NONE o NONE_NOCLIMA, mai quello del verdetto vero. */
  val soloTier: ContextTier? = null,
)

/** Chi sa valutare il barometro su un giro: il caso d'uso vero, o un finto nei test. */
fun interface LocalEvaluator {
  suspend fun evaluate(snapshot: WeatherSnapshot, nowMillis: Long): LocalEvaluation
}

/**
 * Il caso d'uso del nowcast come valutatore del registratore: senza scrivere nello storico dei
 * verdetti (lo fanno la home e il ciclo, al loro passo) e con l'ombra "solo barometro".
 */
fun NowcastUseCase.asLocalEvaluator(): LocalEvaluator = LocalEvaluator { snapshot, nowMillis ->
  val result = evaluate(snapshot, nowMillis, calibrationProgress = null, record = false, withSoloShadow = true)
  LocalEvaluation(
    verdict = result.explanation?.verdict,
    rawVerdict = result.explanation?.rawVerdict,
    features = result.features,
    soloVerdict = result.soloExplanation?.verdict,
    tier = result.tier,
    soloRawVerdict = result.soloExplanation?.rawVerdict,
    soloFeatures = result.soloFeatures,
    soloTier = result.soloTier,
  )
}

/** Cosa ha iscritto un giro: per i log e per i test. */
data class RoundRegistration(
  val roundId: Long,
  val rows: Int,
  /** Il verdetto del barometro e' entrato: il giro conta per la classifica. */
  val barometer: Boolean,
  val tier: ContextTier?,
)

/**
 * L'iscrizione di un giro alla classifica pioggia: tutti insieme, sullo stesso giro.
 *
 * Prima il barometro si iscriveva nel ciclo in background e i provider dentro il giro dei provider,
 * con due orologi diversi, e la home poteva seminare lo stesso giro una seconda volta: due righe
 * della classifica non rispondevano mai alla stessa domanda nello stesso istante. Qui un giro del
 * posto del telefono appena scritto (il gancio di [WeatherSnapshotRefresher]) iscrive, con lo
 * **stesso** [RegisteredRound.roundId] come istante di emissione:
 *
 * - **ogni provider** con la sua PoP sugli slot esatti della finestra ([ProviderWindowPop]); i
 *   giudici del pannello no, e chi non ha PoP nemmeno;
 * - **"sempre 0%"**: il riferimento che chiunque batte se sa qualcosa;
 * - **"climatologia"**: quanto piove di solito li', quando la climatologia locale e' pronta;
 * - **"barometro"**: il verdetto finale, quello che l'utente vede (taratura, analoghi, pavimenti);
 * - **"barometro-solo"**: l'ombra senza contesto, fuori dalla classifica principale.
 *
 * Il barometro entra solo se il telefono ce l'ha: sui dispositivi senza sensore restano provider e
 * riferimenti, e la classifica si ancora alla climatologia. Nello stesso momento si scrive la riga
 * dell'archivio da cui il telefono impara (feature e probabilita' grezze), con lo stesso giro, la
 * stessa versione e lo stesso livello di contesto: l'esito le arrivera' dal giudice della pioggia.
 *
 * Solo giri del posto del telefono, e solo giri di adesso: un giro di un'altra citta' non parla del
 * barometro, e un giro di un'ora fa iscritto adesso avrebbe un verdetto calcolato su un altro
 * segnale.
 */
class LocalRoundRegistrar(
  private val evaluator: LocalEvaluator,
  private val store: RainEventStore,
  private val recordIssue: suspend (NowcastIssueRecord) -> Unit,
  private val climatology: suspend (latitude: Double, longitude: Double) -> LocalClimate?,
  private val sensorAvailable: () -> Boolean,
  private val clock: () -> Long = System::currentTimeMillis,
  /**
   * Dove gira il lavoro. Il gancio parte dentro `refresh`, e la home chiama `refresh` dal thread
   * dell'interfaccia: qui c'e' una valutazione intera del barometro (la pulizia di ventiquattro
   * ore di campioni, il motore due volte con l'ombra), che sul thread dell'interfaccia sarebbe un
   * fotogramma perso a ogni giro. Si sposta qui dentro, cosi' vale per ogni chiamante.
   */
  private val computeContext: CoroutineContext = Dispatchers.Default,
  /**
   * La mutua esclusione con "cancella tutti i dati": la valutazione e la climatologia sono lavoro
   * lungo, e una cancellazione nel mezzo non deve essere seguita da righe e archivio del giro.
   */
  private val wipeGuard: DataWipeGuard = DataWipeGuard(),
) {

  /** Un giro alla volta: due iscrizioni intrecciate scriverebbero righe e archivio in ordine sparso. */
  private val mutex = Mutex()

  suspend fun register(round: RegisteredRound): RoundRegistration? = mutex.withLock {
    withContext(computeContext) { registerLocked(round) }
  }

  private suspend fun registerLocked(round: RegisteredRound): RoundRegistration? {
    // La generazione PRIMA di qualunque lavoro: una cancellazione dopo questo punto ferma le scritture.
    val generation = wipeGuard.generation()
    val snapshot = round.snapshot
    if (snapshot.placeKey != WeatherSnapshot.GPS_KEY) return null
    val roundId = round.roundId
    if (abs(clock() - roundId) > MAX_ROUND_LAG_MILLIS) return null

    // Le coordinate a cento metri, come le vedono i provider: la verita' si scarichera' li'.
    val (latitude, longitude) = WeatherPoint.round(snapshot.latitude, snapshot.longitude)
    val climate = runCatching { climatology(latitude, longitude) }.getOrNull()
    val hasBarometer = runCatching { sensorAvailable() }.getOrDefault(false)
    val evaluation = if (hasBarometer) runCatching { evaluator.evaluate(snapshot, roundId) }.getOrNull() else null
    // Il livello lo dichiara chi ha calcolato il verdetto: ricalcolarlo qui poteva dare un livello
    // diverso da quello con cui il verdetto e' stato corretto (e la riga imparerebbe sulla mappa sbagliata).
    val tier = if (hasBarometer) evaluation?.tier ?: tierOf(snapshot, roundId, hasClimatology = climate != null) else null
    val soloTier = evaluation?.soloTier
      ?: if (climate != null) ContextTier.NONE else ContextTier.NONE_NOCLIMA

    fun row(providerId: String, window: String, probability: Double, rowTier: ContextTier? = tier) = RainEventPending(
      placeKey = WeatherSnapshot.GPS_KEY,
      latitude = latitude,
      longitude = longitude,
      roundId = roundId,
      providerId = providerId,
      window = window,
      issuedAtMillis = roundId,
      probability = probability.coerceIn(0.0, 1.0),
      modelVersion = ModelVersions.TAG,
      tier = rowTier?.name,
    )

    val rows = mutableListOf<RainEventPending>()
    for (fetch in round.fetches) {
      val bundle = fetch.bundle ?: continue
      val providerId = fetch.descriptor.id
      if (providerId in ProviderWindowPop.JUDGE_PROVIDER_IDS) continue
      val convention = ProviderWindowPop.conventionOf(providerId)
      for (window in RainWindows.ALL) {
        ProviderWindowPop.of(bundle, convention, roundId, window)?.let { rows += row(providerId, window.label, it) }
      }
    }
    for (window in RainWindows.ALL) {
      val label = window.label
      rows += row(RainBoardIds.ALWAYS_ZERO, label, 0.0)
      climate?.climatology?.rate(label, roundId)?.let { rows += row(RainBoardIds.CLIMATOLOGY, label, it) }
      evaluation?.verdict?.forWindow(label)?.probability?.let { rows += row(RainBoardIds.BAROMETER, label, it) }
      evaluation?.soloVerdict?.forWindow(label)?.probability?.let { rows += row(RainBoardIds.BAROMETER_SOLO, label, it, soloTier) }
    }

    // L'archivio da cui si impara: feature e probabilita' GREZZE del verdetto iscritto, sullo
    // stesso giro delle righe — l'esito del "barometro" tornera' con questo roundId. Accanto, la
    // riga ombra del solo barometro (a roundId + 1 ms, stesso giro): e' la sola che riempie la mappa
    // "none", perche' un giro vero e' quasi sempre FRESH.
    val verdict = evaluation?.verdict
    val raw = evaluation?.rawVerdict
    val features = evaluation?.features
    val issues = mutableListOf<NowcastIssueRecord>()
    if (verdict != null && raw != null && features != null) {
      issues += issueOf(roundId, roundId, features, raw, tier)
    }
    val soloRaw = evaluation?.soloRawVerdict
    val soloFeatures = evaluation?.soloFeatures
    if (evaluation?.soloVerdict != null && soloRaw != null && soloFeatures != null) {
      issues += issueOf(roundId + NowcastIssueRecord.SHADOW_OFFSET_MILLIS, roundId, soloFeatures, soloRaw, soloTier)
    }

    // Tutte le scritture insieme sotto il lucchetto: se l'utente ha cancellato i dati mentre si
    // valutava, del giro non resta niente.
    val written = wipeGuard.writeIfUnchanged(generation) {
      store.addPending(rows)
      for (issue in issues) runCatching { recordIssue(issue) }
    }
    if (written == null) return null

    return RoundRegistration(roundId, rows.size, barometer = verdict != null, tier = tier)
  }

  private fun issueOf(
    issuedAtMillis: Long,
    roundId: Long,
    features: DoubleArray,
    raw: NowcastVerdict,
    tier: ContextTier?,
  ) = NowcastIssueRecord(
    issuedAtMillis = issuedAtMillis,
    features = features.toList(),
    rawProbability01 = raw.forWindow(RainWindows.ZERO_ONE.label)?.probability ?: 0.0,
    rawProbability13 = raw.forWindow(RainWindows.ONE_THREE.label)?.probability ?: 0.0,
    rawProbability36 = raw.forWindow(RainWindows.THREE_SIX.label)?.probability ?: 0.0,
    modelVersion = ModelVersions.TAG,
    tier = tier?.name,
    roundId = roundId,
  )

  companion object {
    /**
     * Quanto puo' essere vecchio un giro quando arriva al registratore. Il gancio parte appena il
     * giro e' scritto, quindi il ritardo vero e' il giro dei provider (decine di secondi): dieci
     * minuti bastano, e fermano chi iscrivesse un giro vecchio con un verdetto di adesso.
     */
    const val MAX_ROUND_LAG_MILLIS: Long = 10 * 60_000L

    /**
     * Il livello di contesto del giro se il valutatore non lo ha dichiarato: quanto e' vecchio il
     * contesto dei provider (dal suo download vero, non dal giro) e se e' dello stesso posto.
     */
    internal fun tierOf(snapshot: WeatherSnapshot, nowMillis: Long, hasClimatology: Boolean): ContextTier =
      ContextTierDetection.detect(snapshot.context, snapshot.latitude, snapshot.longitude, nowMillis, hasClimatology)
  }
}
