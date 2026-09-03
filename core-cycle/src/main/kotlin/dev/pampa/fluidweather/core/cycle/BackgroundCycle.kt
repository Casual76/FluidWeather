package dev.pampa.fluidweather.core.cycle

import android.content.Context
import android.location.Geocoder
import dev.pampa.fluidweather.core.data.CalibrationStore
import dev.pampa.fluidweather.core.data.LearningRepository
import dev.pampa.fluidweather.core.data.LearningStore
import dev.pampa.fluidweather.core.data.NotificationLedgerStore
import dev.pampa.fluidweather.core.data.NotificationSettingsStore
import dev.pampa.fluidweather.core.data.NowcastHistoryStore
import dev.pampa.fluidweather.core.data.PressureRepository
import dev.pampa.fluidweather.core.data.RoomVerificationStore
import dev.pampa.fluidweather.core.data.SamplingSettingsStore
import dev.pampa.fluidweather.core.data.SavedLocationsRepository
import dev.pampa.fluidweather.core.model.AppNotification
import dev.pampa.fluidweather.core.model.DataAge
import dev.pampa.fluidweather.core.model.DataFreshness
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.NowcastIssueRecord
import dev.pampa.fluidweather.core.model.PlattParamsRecord
import dev.pampa.fluidweather.core.model.OfficialAlert
import dev.pampa.fluidweather.core.model.hourAround
import dev.pampa.fluidweather.core.sensor.LocationProvider
import dev.pampa.fluidweather.core.weather.OfficialAlertsClient
import dev.pampa.fluidweather.core.weather.NowcastUseCase
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.core.weather.WeatherSnapshotRefresher
import dev.pampa.fluidweather.nowcast.cleaning.CalibrationMath
import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.nowcast.learning.LearningStateBuilder
import dev.pampa.fluidweather.nowcast.learning.PlattCalibration
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import dev.pampa.fluidweather.nowcast.verdict.toRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Chi iscrive il verdetto del barometro alla verifica (il coordinatore della fusione). */
fun interface BarometerRegistrar {
  suspend fun register(verdict: NowcastVerdict, nowMillis: Long)
}

/** Chi ha chiesto il giro: ogni innesco ha il suo appetito di rete e di GPS. */
enum class CycleTrigger { SAMPLING_PASS, SURVEILLANCE_TICK, DAILY_SUMMARY, MANUAL }

/** Le notifiche che l'app in primo piano mostra come banner invece che in tendina. */
class InAppAlertBus {
  private val flow = MutableSharedFlow<AppNotification>(extraBufferCapacity = 8)
  val events: SharedFlow<AppNotification> = flow
  fun emit(notification: AppNotification): Boolean = flow.tryEmit(notification)
}

/** Se l'app e' davanti agli occhi dell'utente: lo dice l'Activity, lo legge il ciclo. */
class AppVisibility {
  private val state = MutableStateFlow(false)
  val inForeground: StateFlow<Boolean> = state
  fun set(visible: Boolean) {
    state.value = visible
  }
}

/** Il paese e i nomi d'area di un punto, dal geocoder del telefono: servono alle allerte ufficiali. */
data class PlaceContext(
  val countryCode: String?,
  val areaCandidates: List<String>,
  val locality: String?,
)

class PlaceContextResolver(private val context: Context) {

  private val cache = mutableMapOf<String, PlaceContext>()

  suspend fun resolve(latitude: Double, longitude: Double): PlaceContext? {
    val key = "%.2f,%.2f".format(Locale.ROOT, latitude, longitude)
    synchronized(cache) { cache[key] }?.let { return it }
    val resolved = withContext(Dispatchers.IO) {
      runCatching {
        @Suppress("DEPRECATION")
        Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1)?.firstOrNull()
      }.getOrNull()
    } ?: return null
    val result = PlaceContext(
      countryCode = resolved.countryCode,
      areaCandidates = listOfNotNull(resolved.adminArea, resolved.subAdminArea, resolved.locality),
      locality = resolved.locality ?: resolved.subAdminArea ?: resolved.adminArea,
    )
    synchronized(cache) {
      if (cache.size > 32) cache.clear()
      cache[key] = result
    }
    return result
  }
}

/** Com'e' andata: per la diagnostica e per i test del ciclo. */
data class CycleOutcome(
  val note: String,
  val snapshot: WeatherSnapshot?,
  val verdict: NowcastVerdict?,
  val delivered: List<AppNotification>,
)

/**
 * Il ciclo in background: a ogni passata del barometro (e a ogni minuto di sorveglianza) il
 * meteo si rinfresca, il verdetto si ricalcola e le notifiche escono. E' il "ritmo del
 * barometro" chiesto sul telefono il 2026-09-02: la home trova l'istantanea gia' pronta.
 *
 * Un mutex serializza le passate: worker, allarme esatto e servizio di sorveglianza possono
 * arrivare insieme, e due giri paralleli sullo stesso registro si contraddirebbero.
 */
class BackgroundCycle(
  private val locationProvider: LocationProvider,
  private val savedLocations: SavedLocationsRepository,
  private val refresher: WeatherSnapshotRefresher,
  private val pressureRepository: PressureRepository,
  private val cleaningPipeline: CleaningPipeline,
  private val calibrationStore: CalibrationStore,
  private val learningRepository: LearningRepository,
  private val learningStore: LearningStore,
  private val samplingSettings: SamplingSettingsStore,
  private val notificationSettings: NotificationSettingsStore,
  private val ledgerStore: NotificationLedgerStore,
  private val nowcastHistory: NowcastHistoryStore,
  /** Serve solo alla potatura: la tabella delle verifiche non ha nessun altro che la sfoltisca. */
  private val verificationStore: RoomVerificationStore,
  private val nowcastUseCase: NowcastUseCase,
  private val barometerRegistrar: BarometerRegistrar,
  private val officialAlerts: OfficialAlertsClient,
  private val placeContext: PlaceContextResolver,
  private val notifier: SystemNotifier,
  private val inAppAlerts: InAppAlertBus,
  private val appVisibility: AppVisibility,
  private val texts: NotificationTexts,
  private val clock: () -> Long = System::currentTimeMillis,
  private val zone: () -> ZoneId = { ZoneId.systemDefault() },
  /**
   * C'e' almeno un trasporto di rete acceso.
   *
   * Solo un risparmio, mai una dichiarazione: la UI non dira' mai "sei offline" per colpa di
   * questo. "C'e' un trasporto" non e' "i provider hanno risposto" — possono essere raggiungibili
   * e rispondere tutti 500, o limitare la frequenza, o essere bloccati da un firewall. Qui il
   * predicato e' volutamente il piu' stupido possibile (proprio niente acceso: modalita' aereo),
   * perche' cosi' un falso negativo e' impossibile e un falso positivo lo gestisce comunque il
   * giro che fallisce.
   *
   * Il default e' `true`: nessun test esistente cambia comportamento.
   */
  private val networkLikelyAvailable: () -> Boolean = { true },
) {

  private val mutex = Mutex()

  suspend fun run(trigger: CycleTrigger): CycleOutcome = mutex.withLock {
    val now = clock()
    val point = resolvePoint()
    if (point == null) {
      val outcome = CycleOutcome(texts.cycleNoPosition(), null, null, emptyList())
      record(now, outcome)
      return outcome
    }
    val (key, latitude, longitude) = point

    // 1) Il giro meteo, ma solo se serve davvero: un'istantanea abbastanza giovane vale quanto
    // un giro nuovo, e costa zero. La soglia e' un fatto dei dati, non di chi ha chiamato
    // (vedi RefreshBudget), cosi' vale anche per i chiamanti che non esistono ancora.
    val cadenceMillis = samplingSettings.current().mode.cadenceMinutes * 60_000L
    val enough = RefreshBudget.enoughMillis(trigger, cadenceMillis)
    val cached = refresher.fresh(key, latitude, longitude, maxAgeMillis = SHOWABLE_AGE_MILLIS)
    val snapshot = when {
      cached != null && cached.ageMillis(now) <= enough -> cached
      // Senza nemmeno un trasporto acceso il giro fallirebbe dopo dieci timeout in parallelo.
      !networkLikelyAvailable() -> cached
      else -> runCatching { refresher.refresh(key, latitude, longitude) }.getOrNull() ?: cached
    }

    // 2) Il verdetto locale (stadi 1-5) dal caso d'uso condiviso con la home (fase 19): barometro
    // pulito + contesto dell'opinione piu' completa; lo storico dei verdetti si scrive qui.
    //
    // **Solo se il punto e' dove sei.** Senza permesso di posizione il ciclo ripiega sulla prima
    // localita' salvata, e allora il barometro di questo telefono non parla di quel posto: un
    // verdetto calcolato li' sarebbe un'allerta per una citta' dove il sensore non c'e', e
    // finirebbe anche nello storico e nella classifica del benchmark.
    val here = key == WeatherSnapshot.GPS_KEY
    val nowcast = if (here) nowcastUseCase.evaluate(snapshot, now, calibrationProgress = null, record = true) else null
    val cleaning = nowcast?.cleaning
    val features = nowcast?.features
    val explanation = nowcast?.explanation
    val verdict = explanation?.verdict
    // In classifica alla pari: il barometro si iscrive alla verifica quando si iscrivono i
    // provider (una volta l'ora, lo decide il refresher), cosi' i conti sono confrontabili.
    val registeredNow = snapshot?.predictionsRegisteredAtMillis?.let { abs(now - it) < 5 * 60_000L } == true
    if (verdict != null && registeredNow) runCatching { barometerRegistrar.register(verdict, now) }
    if (explanation != null && features != null && registeredNow) {
      // L'archivio da cui si impara: feature e probabilita' GREZZE del verdetto iscritto.
      val raw = explanation.rawVerdict
      runCatching {
        learningRepository.recordIssue(
          NowcastIssueRecord(
            issuedAtMillis = now,
            features = features.toList(),
            rawProbability01 = raw.forWindow("0-1h")?.probability ?: 0.0,
            rawProbability13 = raw.forWindow("1-3h")?.probability ?: 0.0,
            rawProbability36 = raw.forWindow("3-6h")?.probability ?: 0.0,
          ),
        )
      }
      refineCalibration(cleaning, snapshot, now)
      maybeRefitPlatt(now)
    }

    // 3) Le allerte ufficiali, solo se il canale e' acceso: niente rete per niente.
    val settings = notificationSettings.current()
    val context = if (settings.officialAlerts || trigger == CycleTrigger.DAILY_SUMMARY) {
      runCatching { placeContext.resolve(latitude, longitude) }.getOrNull()
    } else {
      null
    }
    val alerts: List<OfficialAlert> = if (settings.officialAlerts && context != null) {
      runCatching {
        officialAlerts.forPoint(latitude, longitude, context.countryCode, context.areaCandidates)
      }.getOrDefault(emptyList())
    } else {
      emptyList()
    }

    // 4) La politica decide, il registro ricorda, la consegna sceglie il mezzo.
    // Stessa soglia della riga in testata: l'app e il centro notifiche devono essere d'accordo su
    // cosa vuol dire "fresco", e un numero solo e' l'unico modo perche' lo restino.
    val ledger = ledgerStore.current()
    val decision = AlertPolicy.decide(
      texts = texts,
      inputs = AlertInputs(
        nowMillis = now,
        zone = zone(),
        settings = settings,
        ledger = ledger,
        verdict = verdict,
        // Le ore fuse servono a decidere l'inizio e la fine della pioggia. Dopo la correzione
        // del giro a vuoto qui puo' arrivare la previsione IN CACHE, e annunciare "pioggia alle
        // 16" leggendo un bundle di cinque ore fa sarebbe una bugia con l'orologio sbagliato.
        // Il barometro invece continua: quello e' il sensore di questo telefono e non aspetta
        // nessuno — ed e' proprio quando manca la rete che serve di piu'.
        fusedHours = DataAge.hoursForDecisions(snapshot?.fused?.hours.orEmpty(), snapshot?.fetchedAtMillis, now),
        officialAlerts = alerts,
      ),
    )
    val delivered = decision.notifications.filter { deliver(it) }.toMutableList()
    if (decision.cancelNowcastAlert) notifier.cancel(AlertPolicy.NOWCAST_ID)
    var nextLedger = decision.ledger

    // 5) La potatura degli archivi, una volta al giorno.
    //
    // Qui e non in un lavoro suo: il ramo del riepilogo gira gia' esattamente una volta al giorno
    // e tiene gia' il mutex, quindi non serve ne' un altro schedulatore ne' un'altra sveglia. Le
    // quattro funzioni erano tutte scritte e nessuna aveva un chiamante: `pressure_samples`
    // cresceva per sempre, ed e' proprio l'archivio su cui vive l'offline.
    if (trigger == CycleTrigger.DAILY_SUMMARY) pruneArchives(now)

    // 6) Il riepilogo, quando e' la sua ora e non e' gia' uscito oggi.
    if (trigger == CycleTrigger.DAILY_SUMMARY && settings.dailySummary) {
      val today = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate().toEpochDay()
      if (nextLedger.summaryEpochDay != today) {
        val summary = DailySummary.compose(
          nowMillis = now,
          zone = zone(),
          hours = snapshot?.fused?.hours ?: emptyList(),
          verdict = verdict,
          pressureTrendHpaPerHour = cleaning?.latest?.trendHpaPerHour,
          locationName = context?.locality,
          texts = texts,
          dataAtMillis = snapshot?.fetchedAtMillis,
        )
        if (summary != null && deliver(summary)) {
          delivered += summary
          nextLedger = nextLedger.copy(summaryEpochDay = today)
        }
      }
    }

    val note = texts.cycleNote(
      nowMillis = now,
      zone = zone(),
      trigger = trigger,
      providersResponding = snapshot?.providersResponding,
      providersTotal = snapshot?.fetches?.size,
      fromSnapshot = snapshot != null && snapshot === cached,
      level = verdict?.level,
      delivered = delivered.size,
    )
    val outcome = CycleOutcome(note, snapshot, verdict, delivered)
    ledgerStore.update { nextLedger.copy(lastCycleAtMillis = now, lastCycleNote = note) }
    outcome
  }

  /** Ogni sei ore la ricalibrazione si ristima sulle coppie (grezza, esito) raccolte. */
  /**
   * Le quattro potature, ognuna col suo motivo scritto dove vive la costante.
   *
   * `runCatching` una per una: un archivio che non si lascia potare (file bloccato, disco pieno)
   * non deve impedire agli altri tre di farlo, e soprattutto non deve far fallire il ciclo, che
   * qui e' arrivato per mandare il riepilogo.
   */
  private suspend fun pruneArchives(now: Long) {
    runCatching { pressureRepository.prune(now) }
    runCatching { nowcastHistory.prune(now) }
    runCatching { learningRepository.prune(now) }
    runCatching { verificationStore.prune(now) }
  }

  private suspend fun maybeRefitPlatt(now: Long) {
    if (now - runCatching { learningStore.lastFitMillis() }.getOrDefault(0L) < REFIT_INTERVAL_MILLIS) return
    runCatching {
      val issues = learningRepository.issuesSince(now - LearningRepository.KEEP_MILLIS)
      val outcomes = learningRepository.outcomesSince(now - LearningRepository.KEEP_MILLIS)
      // I verdetti nati senza contesto dei provider si registrano comunque — escluderli
      // introdurrebbe un errore sistematico legato al meteo (si resta senza campo fuori, in
      // montagna, col brutto tempo, cioe' proprio dove il modello deve essere piu' giusto) e
      // lascerebbe orfani i loro esiti, che si scrivono dopo e sono chiavati sull'ora d'emissione.
      // Ma se ce ne sono abbastanza dei completi, la mappa si tara su quelli: due popolazioni con
      // distribuzioni diverse dentro una regressione sola sono una media di due cose.
      val withContext = issues.filter { FeatureExtractor.hasContext(it.features.toDoubleArray()) }
      val corpus = if (withContext.size >= PlattCalibration.MIN_SAMPLES * 2) withContext else issues
      val fitted = listOf("0-1h", "1-3h", "3-6h").mapNotNull { window ->
        val samples = LearningStateBuilder.samples(window, corpus, outcomes)
        PlattCalibration.fit(samples)?.let { PlattParamsRecord(window, it.a, it.b, samples.size, now) }
      }
      learningStore.save(fitted, fittedAtMillis = now)
      nowcastUseCase.invalidateLearning()
    }
  }

  /** Un confronto l'ora fra locale corretto e riferimento: il bias insegue, la fiducia sale. */
  private suspend fun refineCalibration(cleaning: dev.pampa.fluidweather.nowcast.cleaning.CleaningResult?, snapshot: WeatherSnapshot?, now: Long) {
    val record = runCatching { calibrationStore.current() }.getOrNull() ?: return
    val local = cleaning?.cleaned?.lastOrNull()?.takeIf { abs(it.timestampMillis - now) <= 30 * 60_000L } ?: return
    val hour = snapshot?.fused?.hourAround(now) ?: return
    val reference = hour.values[FusionVariables.PRESSURE_MSL]?.value ?: return
    runCatching { calibrationStore.save(CalibrationMath.refine(record, local.seaLevelPressureHpa, reference, now)) }
  }

  /** In primo piano il banner in-app, altrimenti la tendina (decisione 2026-09-02). */
  private fun deliver(notification: AppNotification): Boolean =
    if (appVisibility.inForeground.value) inAppAlerts.emit(notification) else notifier.post(notification)

  /** GPS se c'e', altrimenti la prima localita' salvata: le notifiche sono per dove sei. */
  private suspend fun resolvePoint(): Triple<String, Double, Double>? {
    if (locationProvider.hasPermission()) {
      locationProvider.snapshot(timeoutMillis = LOCATION_TIMEOUT_MILLIS)?.let {
        return Triple(WeatherSnapshot.GPS_KEY, it.latitude, it.longitude)
      }
    }
    val saved = savedLocations.places.first().firstOrNull { !it.isGps } ?: return null
    return Triple(WeatherSnapshot.keyFor(saved.id), saved.latitude, saved.longitude)
  }

  private suspend fun record(now: Long, outcome: CycleOutcome) {
    ledgerStore.update { it.copy(lastCycleAtMillis = now, lastCycleNote = outcome.note) }
  }

  private companion object {
    /** Un'istantanea piu' vecchia di cosi' non si mostra nemmeno come ripiego. */
    const val SHOWABLE_AGE_MILLIS = 12 * 3_600_000L

    const val LOCATION_TIMEOUT_MILLIS = 15_000L

    const val REFIT_INTERVAL_MILLIS = 6 * 3_600_000L

  }
}
