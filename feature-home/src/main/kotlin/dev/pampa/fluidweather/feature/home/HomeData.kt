package dev.pampa.fluidweather.feature.home

import android.content.Context
import android.location.Geocoder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.antigravity.fluidengine.ui.theme.AccentPreset
import dev.pampa.fluidweather.core.data.AppearanceSettingsStore
import dev.pampa.fluidweather.core.data.CalibrationStore
import dev.pampa.fluidweather.core.data.HomeLayoutStore
import dev.pampa.fluidweather.core.data.LearningRepository
import dev.pampa.fluidweather.core.data.LearningStore
import dev.pampa.fluidweather.core.data.NowcastHistoryStore
import dev.pampa.fluidweather.core.data.PressureRepository
import dev.pampa.fluidweather.core.data.SamplingSettingsStore
import dev.pampa.fluidweather.core.data.SavedLocationsRepository
import dev.pampa.fluidweather.core.data.SelectedPlaceStore
import dev.pampa.fluidweather.core.model.AirQualityNow
import dev.pampa.fluidweather.core.model.BarometerReadiness
import dev.pampa.fluidweather.core.model.CalibrationBurst
import dev.pampa.fluidweather.core.model.DataAge
import dev.pampa.fluidweather.core.model.Place
import dev.pampa.fluidweather.core.model.DayPhase
import dev.pampa.fluidweather.core.model.FusedForecast
import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.NowcastVerdictRecord
import dev.pampa.fluidweather.core.model.SolarEphemeris
import dev.pampa.fluidweather.core.model.SunTimes
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.core.model.nearestHour
import dev.pampa.fluidweather.core.model.todayRange
import dev.pampa.fluidweather.core.sensor.CalibrationController
import dev.pampa.fluidweather.core.sensor.LocationProvider
import dev.pampa.fluidweather.core.ui.WeatherAccent
import dev.pampa.fluidweather.strings.R
import dev.pampa.fluidweather.core.weather.AirQualityClient
import dev.pampa.fluidweather.core.weather.GeocodingClient
import dev.pampa.fluidweather.core.weather.NowcastUseCase
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.core.weather.WeatherSnapshotRefresher
import dev.pampa.fluidweather.core.weather.WeatherSnapshotStore
import dev.pampa.fluidweather.nowcast.cleaning.CleaningResult
import dev.pampa.fluidweather.nowcast.learning.NowcastExplanation
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import dev.pampa.fluidweather.core.model.GeoPoint
import dev.pampa.fluidweather.core.model.WeatherBrief
import dev.pampa.fluidweather.core.data.RoutineWindowStore
import kotlinx.coroutines.flow.combine
import dev.pampa.fluidweather.core.weather.WeatherBriefService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tutto quello che la home tocca; lo costruisce :app dal suo grafo. */
class HomeDependencies(
  /** L'istantanea del ciclo in background e il giro quando serve (fase 11). */
  val snapshotRefresher: WeatherSnapshotRefresher,
  val snapshotStore: WeatherSnapshotStore,
  val samplingSettings: SamplingSettingsStore,
  val locationProvider: LocationProvider,
  val pressureRepository: PressureRepository,
  val nowcastHistory: NowcastHistoryStore,
  val calibrationStore: CalibrationStore,
  val calibrationController: CalibrationController,
  val learningRepository: LearningRepository,
  val learningStore: LearningStore,
  /** Gli stadi 1-5 in un punto solo, condiviso col ciclo in background (fase 19). */
  val nowcast: NowcastUseCase,
  val airQualityClient: AirQualityClient,
  val appearanceStore: AppearanceSettingsStore,
  val layoutStore: HomeLayoutStore,
  val savedLocations: SavedLocationsRepository,
  val selectedPlaceStore: SelectedPlaceStore,
  val geocodingClient: GeocodingClient,
  /** La home deriva l'accento dal meteo e lo consegna al tema dell'app. */
  val onWeatherAccent: (AccentPreset) -> Unit,
  /**
   * Il dispositivo ha un barometro. Senza (il Galaxy Tab S9 su cui si e' visto, 2026-09-26) la
   * tessera del nowcast restava in home a dire "appena cominciata" per sempre: senza sensore la
   * home fa come fuori casa — niente nowcast, la pressione dai provider.
   */
  val barometerAvailable: Boolean = true,
  val weatherBriefService: WeatherBriefService,
  val weatherSummaryGateway: dev.pampa.fluidweather.core.model.WeatherSummaryGateway,
  val dataGeneration: () -> Long,
  val routineWindowStore: RoutineWindowStore,
)

data class HomeUiState(
  val loading: Boolean = true,
  val hasLocation: Boolean = true,
  val locationName: String? = null,
  val latitude: Double? = null,
  val longitude: Double? = null,
  val temperatureC: Double? = null,
  val kind: WeatherKind? = null,
  val maxC: Double? = null,
  val minC: Double? = null,
  val cloudCover: Double? = null,
  val phase: DayPhase = DayPhase.DAY,
  val verdict: NowcastVerdict? = null,
  val providersResponding: Int = 0,
  /** Le ore fuse (passato recente incluso): la dispensa di tutti i widget. */
  val fusedHours: List<FusedHour> = emptyList(),
  val airQuality: AirQualityNow? = null,
  /** Il segnale barometrico pulito (stadi 1-3): pressione e nowcast ci leggono dentro. */
  val cleaning: CleaningResult? = null,
  /** L'ultima lettura grezza del sensore, senza correzioni: il compatto della Pressione. */
  val latestRawPressureHpa: Double? = null,
  val sunTimesToday: SunTimes.Times? = null,
  /** La pioggia OSSERVATA nelle ore passate (analisi dell'opinione piu' completa): la verita' dello storico. */
  val observedPrecipitation: List<Pair<Long, Double>> = emptyList(),
  /** I verdetti delle ultime 24 ore, dallo storico: la pagina del nowcast li disegna. */
  val verdictHistory: List<NowcastVerdictRecord> = emptyList(),
  /** A che punto e' il barometro: la barra unica (raffica, poi storia) finche' non c'e' verdetto. */
  val readiness: BarometerReadiness? = null,
  /** Vero mentre il gesto di aggiornamento rifa' il giro: lo legge la rotella in cima. */
  val refreshing: Boolean = false,
  /**
   * Vero solo quando la localita' scelta e' quella dove sei.
   *
   * Il barometro e' quello del telefono: misura la pressione **qui**, non a duecento chilometri.
   * Prima il verdetto si calcolava comunque e — peggio — al modello arrivava il contesto meteo
   * della citta' lontana insieme al segnale di questo sensore: sensore di qui, contesto di la'.
   * Era il motivo per cui i numeri del nowcast cambiavano cambiando posto, che e' proprio la cosa
   * che non doveva succedere. Fuori casa il nowcast non c'e' e la pressione la danno i provider.
   */
  val barometerApplies: Boolean = true,
  /** Il dispositivo ha un barometro: decide perche' la pressione viene dai provider, e come dirlo. */
  val sensorAvailable: Boolean = true,
  /**
   * Quando risale il giro dei provider che sta in scena; null = non c'e' nessun giro in scena.
   *
   * Un campo solo, non uno stato: la soglia oltre cui diventa una frase e' una decisione di
   * presentazione ([dev.pampa.fluidweather.core.model.DataAge]), non un fatto dei dati. E niente
   * flag "l'ultimo giro e' fallito": l'unico caso in cui differirebbe dall'eta' e' "fallito ma il
   * dato e' ancora fresco", e li' la cosa giusta da dire e' niente.
   */
  val dataAtMillis: Long? = null,
  /** Il verdetto spiegato: grezzo, ricalibrato, analoghi (fase 16). */
  val nowcastExplanation: NowcastExplanation? = null,
  val dayLengthTodayMillis: Long? = null,
  val dayLengthYesterdayMillis: Long? = null,
  val weatherBrief: WeatherBrief? = null,
  val routineReadError: Boolean = false,
)

/**
 * Ogni quanto la barra del barometro si ricalcola mentre la home e' aperta.
 *
 * Un minuto: la storia cresce di un punto ogni cadenza (5-30 minuti) e la barra non ha niente di
 * piu' fine da dire, ma un minuto e' abbastanza spesso da non sembrare ferma — che e' esattamente
 * il difetto che aveva, perche' si calcolava **una volta sola** all'apertura.
 */
private const val READINESS_REFRESH_MILLIS = 60_000L

/** Quanto si aspetta al massimo un fix per un giro silenzioso: la ripresa non deve inchiodarsi. */
private const val SILENT_FIX_TIMEOUT_MILLIS = 5_000L

/** La cadenza di ripiego quando le impostazioni non rispondono: la bilanciata. */
private const val DEFAULT_CADENCE_MILLIS = 15 * 60_000L

/**
 * Il caricamento della home: posizione -> l'ISTANTANEA del ciclo in background, subito -> il
 * giro dei provider solo se l'istantanea e' piu' vecchia della cadenza del barometro -> verdetto
 * locale (fasi 2-5, col contesto dell'opinione piu' completa) -> nome del posto. Poi la home
 * resta in ascolto: ogni istantanea nuova scritta dal ciclo la aggiorna mentre e' aperta.
 * Ogni passo aggiorna lo stato appena sa qualcosa: il cielo cambia colore prima dell'ultimo
 * dettaglio.
 */
/** Lo stato della home e il gesto che lo rifa': quello che serve alla schermata. */
class HomeStateHandle(
  val state: State<HomeUiState>,
  /** Rifa' tutto adesso, posizione compresa. Non si aspetta: lo stato racconta come va. */
  val refresh: () -> Unit,
)

@Composable
fun rememberHomeState(deps: HomeDependencies, place: Place, active: Boolean = true): HomeStateHandle {
  val context = LocalContext.current
  // Different locations have different holders: no frame can pair a new name with old weather.
  val holder = remember(deps, place) { mutableStateOf(HomeUiState(
    phase = phaseFromClock(), locationName = place.name.takeIf { it.isNotBlank() },
    latitude = homePoint(place.latitude, place.longitude)?.latitude,
    longitude = homePoint(place.latitude, place.longitude)?.longitude,
    sensorAvailable = deps.barometerAvailable,
    barometerApplies = place.isGps && deps.barometerAvailable,
  )) }
  var briefSnapshot by remember(deps, place) { mutableStateOf<WeatherSnapshot?>(null) }
  var invalidatedBefore by remember(deps) { mutableStateOf<Long?>(null) }
  var wiped by remember(deps, place) { mutableStateOf(false) }
  val key = if (place.isGps) WeatherSnapshot.GPS_KEY else WeatherSnapshot.keyFor(place.id)
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  // Cresce a ogni pull to refresh, ed e' la chiave che fa ripartire il caricamento da capo.
  var reloads by remember { mutableIntStateOf(0) }

  LaunchedEffect(deps, place, reloads, active, lifecycle, wiped) {
    if (!active || wiped) return@LaunchedEffect
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      coroutineScope {
        // Il corpo qui sotto scrive in `value` come prima: e' lo stesso caricamento, spostato.
        val loadGeneration = deps.dataGeneration()
        var value by GenerationHomeState(holder, deps.dataGeneration, loadGeneration)
        value = value.copy(loading = true, refreshing = reloads > 0)
        val now = System.currentTimeMillis()
        val key = if (place.isGps) WeatherSnapshot.GPS_KEY else WeatherSnapshot.keyFor(place.id)

        // 0) Quello che si sa gia', PRIMA di chiedere qualcosa a chiunque — GPS compreso.
        //
        // Era il difetto piu' grosso dell'offline, e si vedeva anche online: la posizione si risolveva
        // per prima, e con un fix lento erano fino a dieci secondi di testata a "—" con un'istantanea
        // perfetta sul disco. Senza fix del tutto (al chiuso, permesso negato, modalita' aereo) si
        // usciva subito e la schermata restava **vuota**. Ma l'istantanea porta dentro di se' le
        // proprie coordinate: per dipingere il cielo e sapere che ore sono non serve il GPS.
        //
        // E si legge con `lastKnown`, senza limite di distanza: cosa vale la pena mostrare lo decide
        // l'eta' dichiarata in testata, non un taglio muto a tre chilometri.
        val known = homeCatching { deps.snapshotRefresher.lastKnown(key) }.getOrNull()
          ?.takeIf { homeSnapshotMatches(place, homePoint(if (place.isGps) it.latitude else place.latitude, if (place.isGps) it.longitude else place.longitude), it) && (invalidatedBefore == null || it.fetchedAtMillis > invalidatedBefore!!) && it.ageMillis(now) <= DataAge.SHOWABLE_AGE_MILLIS }
        if (!place.isGps && known != null) deps.savedLocations.backfillZoneId(place.id, known.zoneId)
        if (deps.dataGeneration() == loadGeneration) briefSnapshot = known
        if (known != null) {
          value = value.copy(
            hasLocation = true,
            latitude = known.latitude,
            longitude = known.longitude,
            phase = SolarEphemeris.phaseAt(now, known.latitude, known.longitude),
          ).applySnapshot(known, now, deps)
        }

        // 1) GPS o localita' scelta: da qui in poi il caricamento non sa la differenza.
        // Il fix puo' essere l'ultima posizione nota, di ore fa: il meteo si aggiorna li' lo stesso, ma
        // il giro non si iscrive alla classifica come "dov'e' il telefono" (null = la regola di sempre).
        var registerHere: Boolean? = null
        val resolved: Triple<Double, Double, String?>? = if (place.isGps) {
          homeCatching { deps.locationProvider.snapshot() }.getOrNull()
            ?.let { fix ->
              if (!fix.isFreshAt(System.currentTimeMillis())) registerHere = false
              Triple(fix.latitude, fix.longitude, null)
            }
        } else {
          Triple(place.latitude, place.longitude, place.name)
        }
        val validResolved = resolved?.takeIf { homePoint(it.first, it.second) != null }
        val latitude: Double
        val longitude: Double
        val presetName: String?
        // Senza fix ma con un'istantanea si continua con le coordinate di quella — **ma senza rifare
        // il giro**. Aggiornare la posizione di ieri e chiamarla "dove sei" sarebbe il meteo di un
        // altro posto con l'etichetta giusta: meglio un dato vecchio, dichiarato.
        var canRefresh = true
        when {
          validResolved != null -> {
            latitude = validResolved.first
            longitude = validResolved.second
            presetName = validResolved.third
          }
          known != null -> {
            latitude = known.latitude
            longitude = known.longitude
            presetName = null
            canRefresh = false
          }
          else -> {
            value = value.copy(loading = false, refreshing = false, hasLocation = false)
            return@coroutineScope
          }
        }
        if (presetName != null) value = value.copy(locationName = presetName)

        val phase = SolarEphemeris.phaseAt(now, latitude, longitude)
        value = value.copy(phase = phase, latitude = latitude, longitude = longitude, hasLocation = true)

        // 2) Il giro di rete si rifa' solo se serve: istantanea piu' vecchia della cadenza del
        //    barometro, o presa troppo lontano da qui.
        val cadenceMillis = homeCatching { deps.samplingSettings.current().mode.cadenceMinutes * 60_000L }
          .getOrDefault(DEFAULT_CADENCE_MILLIS)
        val cached = known?.takeIf { homeSnapshotMatches(place, homePoint(latitude, longitude), it) && (invalidatedBefore == null || it.fetchedAtMillis > invalidatedBefore!!) }
        if (known != null && cached == null) {
          value = HomeUiState(locationName = presetName, latitude = latitude, longitude = longitude,
            phase = phase, sensorAvailable = deps.barometerAvailable,
            barometerApplies = place.isGps && deps.barometerAvailable)
          briefSnapshot = null
        }
        val snapshotCandidate = if (canRefresh && (cached == null || cached.ageMillis(now) > cadenceMillis)) {
          homeCatching { deps.snapshotRefresher.refresh(key, latitude, longitude, registerPredictions = registerHere) }.getOrNull()
            ?: cached
        } else {
          cached
        }
        val snapshot = homeCatching { deps.snapshotRefresher.lastKnown(key) }.getOrNull()
          ?.takeIf { homeSnapshotMatches(place, homePoint(latitude, longitude), it) && (invalidatedBefore == null || it.fetchedAtMillis > invalidatedBefore!!) }
          ?: snapshotCandidate?.takeIf { homeSnapshotMatches(place, homePoint(latitude, longitude), it) && (invalidatedBefore == null || it.fetchedAtMillis > invalidatedBefore!!) }
        if (deps.dataGeneration() == loadGeneration) briefSnapshot = snapshot
        if (!place.isGps && snapshot != null) deps.savedLocations.backfillZoneId(place.id, snapshot.zoneId)
        if (snapshot != null && snapshot !== known) value = value.applySnapshot(snapshot, now, deps)

        // Sole: oggi e ieri, per il "piu' corto/lungo di ieri" del widget.
        val zone = ZoneId.systemDefault()
        val todayStartUtc = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
          .atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val sunToday = SunTimes.forDay(todayStartUtc, latitude, longitude)
        val sunYesterday = SunTimes.forDay(todayStartUtc - 86_400_000L, latitude, longitude)
        value = value.copy(
          sunTimesToday = sunToday,
          dayLengthTodayMillis = sunToday.lengthMillis(),
          dayLengthYesterdayMillis = sunYesterday.lengthMillis(),
        )

        // Il verdetto locale (stadi 1-5) dal caso d'uso condiviso col ciclo in background (fase 19):
        // barometro pulito + contesto dell'opinione piu' completa (dall'istantanea), registrato nello
        // storico. **Solo dove sei**: su una citta' lontana questo sensore non ha niente da dire, e
        // registrarne il verdetto sporcherebbe anche lo storico con previsioni di un altro posto.
        if (place.isGps && deps.barometerAvailable) {
          val nowcast = withContext(Dispatchers.Default) { deps.nowcast.evaluate(
            snapshot = snapshot,
            nowMillis = now,
            calibrationProgress = deps.calibrationController.progress.value?.let { it.completedSeconds to it.totalSeconds },
            record = true,
            pointLatitude = latitude,
            pointLongitude = longitude,
          ) }
          val history = homeCatching { deps.nowcastHistory.since(now - 24 * 3_600_000L) }.getOrDefault(emptyList())
          value = value.copy(
            barometerApplies = true,
            verdict = nowcast.verdict,
            cleaning = nowcast.cleaning,
            latestRawPressureHpa = nowcast.latestRawPressureHpa,
            verdictHistory = history,
            nowcastExplanation = nowcast.explanation,
            readiness = nowcast.readiness,
            loading = false,
          )
        } else {
          value = value.copy(
            barometerApplies = false,
            verdict = null,
            cleaning = null,
            latestRawPressureHpa = null,
            verdictHistory = emptyList(),
            nowcastExplanation = null,
            readiness = null,
            loading = false,
          )
        }

        val air = homeCatching { deps.airQualityClient.now(latitude, longitude) }.getOrNull()
        if (air != null) value = value.copy(airQuality = air)

        if (presetName == null) {
          val name = reverseGeocode(context, latitude, longitude)
          if (name != null) value = value.copy(locationName = name)
        }

        // La raffica di taratura, se gira, muove la barra in tempo reale.
        launch {
          deps.calibrationController.progress.collect { progress ->
            val current = value.readiness ?: return@collect
            val calibrated = current.calibrated || (progress == null && homeCatching { deps.calibrationStore.current() }.getOrNull() != null)
            value = value.copy(
              readiness = current.copy(
                calibrationRunning = progress != null,
                calibrationCompletedSeconds = progress?.completedSeconds ?: 0,
                calibrationTotalSeconds = progress?.totalSeconds ?: CalibrationBurst.DURATION_SECONDS,
                calibrationWaiting = progress?.waitingForStillness == true,
                calibrated = calibrated,
              ),
            )
          }
        }

        // Il giro e' finito: la rotella dell'aggiornamento si spegne qui, non alla fine dell'effetto,
        // che non finisce mai perche' resta in ascolto del ciclo.
        value = value.copy(loading = false, refreshing = false)

      }
    }
  }

  // Observe invalidation even while covered/paused. A wipe cannot resurrect in-flight inputs.
  LaunchedEffect(deps, place, reloads) {
    val observed = deps.dataGeneration()
    while (true) {
      if (deps.dataGeneration() != observed) {
        invalidatedBefore = System.currentTimeMillis()
        wiped = true
        briefSnapshot = null
        holder.value = HomeUiState(loading = false,
          locationName = place.name.takeIf { it.isNotBlank() },
          sensorAvailable = deps.barometerAvailable, barometerApplies = place.isGps && deps.barometerAvailable)
        return@LaunchedEffect
      }
      delay(1_000L)
    }
  }
  LaunchedEffect(deps, place, reloads) {
    var hadStoredData = deps.snapshotStore.changes.value.isNotEmpty()
    var observed = deps.dataGeneration()
    deps.snapshotStore.changes.collect { updates ->
      val readGeneration = deps.dataGeneration()
      // StateFlow may conflate the empty map with a new post-wipe write. The epoch
      // change still invalidates the old holder before reading any replacement.
      if ((updates.isEmpty() && hadStoredData) || readGeneration != observed) {
        invalidatedBefore = System.currentTimeMillis()
        wiped = true
        briefSnapshot = null
        holder.value = HomeUiState(loading = false, locationName = place.name.takeIf { it.isNotBlank() },
          latitude = homePoint(place.latitude, place.longitude)?.latitude,
          longitude = homePoint(place.latitude, place.longitude)?.longitude,
          sensorAvailable = deps.barometerAvailable, barometerApplies = place.isGps && deps.barometerAvailable)
      }
      observed = readGeneration
      hadStoredData = updates.isNotEmpty()
      if (updates[key] == null) return@collect
      val fresh = try {
        homeCatching { deps.snapshotRefresher.lastKnown(key) }.getOrNull()
      } catch (cancelled: CancellationException) {
        // A store read invalidated by clear is cancelled independently of its observer.
        // A cancelled composition must still propagate cancellation immediately.
        currentCoroutineContext().ensureActive()
        return@collect
      } ?: return@collect
      currentCoroutineContext().ensureActive()
      if (deps.dataGeneration() != readGeneration) return@collect
      val recovering = wiped
      val point = if (recovering) {
        if (place.isGps) homePoint(fresh.latitude, fresh.longitude) else homePoint(place.latitude, place.longitude)
      } else homePoint(holder.value.latitude, holder.value.longitude)
      if (!recovering && invalidatedBefore != null && fresh.fetchedAtMillis <= invalidatedBefore!!) return@collect
      if (!homeSnapshotMatches(place, point, fresh)) return@collect
      if (!place.isGps) deps.savedLocations.backfillZoneId(place.id, fresh.zoneId)
      currentCoroutineContext().ensureActive()
      if (deps.dataGeneration() != readGeneration) return@collect
      briefSnapshot = fresh
      holder.value = holder.value.applySnapshot(fresh, System.currentTimeMillis(), deps)
      if (recovering) {
        // The guarded store read is from the current epoch; old pre-wipe commits are
        // rejected upstream. Re-arm consumers without extending any source clock.
        invalidatedBefore = null
        wiped = false
        reloads++
      }
    }
  }

  val briefController = remember(deps) { HomeBriefController(deps.weatherBriefService::brief, generation = deps.dataGeneration) }
  LaunchedEffect(deps, place, lifecycle, active, wiped) {
    if (!active || wiped) return@LaunchedEffect
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      snapshotFlow { HomeBriefInput(place, homePoint(holder.value.latitude, holder.value.longitude), briefSnapshot) }
        .combine(deps.routineWindowStore.windows.recoverHomeRoutines { failed ->
          holder.value = holder.value.copy(routineReadError = failed)
        }) { input, windows -> input.copy(windows = windows) }
        .collectLatest { input ->
          briefController.consume(input, latestSnapshot = {
            // Store notifications conflate equal fetch timestamps; read content at each consumption.
            deps.snapshotRefresher.lastKnown(key)?.takeIf { invalidatedBefore == null || it.fetchedAtMillis > invalidatedBefore!! }
          }, latestWindows = {
            readLatestHomeRoutines(deps.routineWindowStore::current) { failed ->
              holder.value = holder.value.copy(routineReadError = failed)
            }
          }) { brief -> holder.value = holder.value.copy(weatherBrief = brief) }
        }
    }
  }

  // La barra del barometro, viva.
  //
  // Prima `readiness` si calcolava solo dentro il caricamento qui sopra, e nient'altro la toccava:
  // `applySnapshot` non la sfiora, e il collettore della taratura muove solo la parte della
  // raffica. Chi apriva la home subito dopo l'onboarding vedeva "0 ore di 13" e restava li' a
  // guardarla finche' non tirava giu' per aggiornare — che e' precisamente il baco raccontato.
  LaunchedEffect(deps, place, lifecycle, active, wiped) {
    if (!active || wiped || !place.isGps || !deps.barometerAvailable) return@LaunchedEffect
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      val observed = deps.dataGeneration()
      while (true) {
        // Prima si calcola, poi si aspetta: senza un fix GPS il caricamento esce subito e la
        // readiness non la calcola nessuno, quindi per un minuto intero la tessera diceva solo
        // "in attesa" — anche quando il campionamento era fermo da giorni (2026-09-26). La barra
        // del barometro non ha bisogno della posizione: legge l'archivio.
        // Fuori dal thread della UI: rileggere ventiquattro ore di campioni e rifarci passare la
        // pipeline di pulizia e' lavoro vero, e qui succede ogni minuto per tutto il tempo che la
        // home resta aperta.
        val readiness = homeCatching {
          withContext(Dispatchers.Default) {
            deps.nowcast.readiness(
              nowMillis = System.currentTimeMillis(),
              calibrationProgress = deps.calibrationController.progress.value
                ?.let { it.completedSeconds to it.totalSeconds },
            )
          }
        }.getOrNull()
        if (readiness != null && deps.dataGeneration() == observed) holder.value = holder.value.copy(readiness = readiness)
        delay(READINESS_REFRESH_MILLIS)
      }
    }
  }

  return remember(holder) { HomeStateHandle(state = holder, refresh = { wiped = false; reloads++ }) }
}

/** Le ore fuse dell'istantanea dentro lo stato: testata, cielo, dispensa dei widget, accento. */
private fun HomeUiState.applySnapshot(
  snapshot: WeatherSnapshot,
  nowMillis: Long,
  deps: HomeDependencies,
): HomeUiState {
  val fused = snapshot.fused
  // Un'istantanea senza ore non ha niente da dire, e "niente da dire" non vuol dire "cancella
  // quello che c'era": prima azzerava temperatura, tipo, min/max e nuvole, e la home si svuotava
  // sotto gli occhi. Si aggiorna solo l'eta', che e' l'unica cosa che quel giro ha davvero detto.
  if (fused.hours.isEmpty()) return copy(dataAtMillis = snapshot.fetchedAtMillis)
  val nowValues = fused.at(nowMillis)
  val kind = fused.kindAt(nowMillis)
  val (minToday, maxToday) = fused.todayRange(nowMillis)
  deps.onWeatherAccent(WeatherAccent.presetFor(kind, phase))
  return copy(
    dataAtMillis = snapshot.fetchedAtMillis,
    temperatureC = nowValues?.get(FusionVariables.TEMPERATURE),
    kind = kind,
    minC = minToday,
    maxC = maxToday,
    cloudCover = nowValues?.get(FusionVariables.CLOUD_COVER),
    providersResponding = snapshot.providersResponding,
    fusedHours = fused.hours,
    observedPrecipitation = snapshot.context?.hourly
      ?.filter { it.timestampMillis <= nowMillis }
      ?.mapNotNull { point -> point.precipitationMm?.let { point.timestampMillis to it } }
      ?: observedPrecipitation,
  )
}

/**
 * La nota sotto la pressione dei provider. Il motivo per cui il barometro non parla e' diverso:
 * altrove il sensore e' qui e non la', senza sensore semplicemente non c'e' (tablet, 2026-09-26).
 */
internal val HomeUiState.providerPressureNoteRes: Int
  get() = if (sensorAvailable) R.string.tile_barometer_here_only else R.string.tile_no_barometer_device

private fun SunTimes.Times.lengthMillis(): Long? {
  val rise = sunriseMillis ?: return null
  val set = sunsetMillis ?: return null
  return (set - rise).takeIf { it > 0 }
}

// Niente piu' tetto di 90 minuti, e la stessa ora per il valore e per il tipo.
//
// Il tetto c'era solo sui valori, non sul tipo: con un'istantanea di due ore fa la testata scriveva
// "—" e sotto continuava a dire "Sereno". Ed era anche il vero motivo per cui la home sembrava
// vuota senza rete. Ora si mostra l'ultimo dato che c'e' e a dire quanto e' vecchio ci pensa la
// riga della testata: e' la decisione presa — nessun tetto, ma sempre datato.
private fun FusedForecast.at(nowMillis: Long): Map<String, Double>? =
  nearestHour(nowMillis)?.first?.values?.mapValues { it.value.value }

private fun FusedForecast.kindAt(nowMillis: Long): WeatherKind? = nearestHour(nowMillis)?.first?.kind

/** Senza posizione il cielo non mente ne' spegne: fase grossolana dall'orologio locale. */
private fun phaseFromClock(): DayPhase {
  val hour = LocalTime.now().hour
  return when (hour) {
    in 6..7 -> DayPhase.DAWN
    in 8..17 -> DayPhase.DAY
    in 18..19 -> DayPhase.DUSK
    else -> DayPhase.NIGHT
  }
}

private suspend fun reverseGeocode(context: Context, latitude: Double, longitude: Double): String? =
  withContext(Dispatchers.IO) {
    homeCatching {
      @Suppress("DEPRECATION")
      Geocoder(context, Locale.getDefault())
        .getFromLocation(latitude, longitude, 1)
        ?.firstOrNull()
        ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
    }.getOrNull()
  }

/** Ordinary failures may degrade gracefully; structured cancellation must always propagate. */
private inline fun <T> homeCatching(block: () -> T): Result<T> = try {
  Result.success(block())
} catch (cancelled: CancellationException) {
  throw cancelled
} catch (failure: Exception) {
  Result.failure(failure)
}

/** Protect legacy async writes against wipe as well as coroutine cancellation. */
private class GenerationHomeState(
  private val holder: androidx.compose.runtime.MutableState<HomeUiState>,
  private val generation: () -> Long,
  private val observed: Long,
) {
  operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): HomeUiState = holder.value
  operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: HomeUiState) {
    if (generation() == observed) holder.value = value
  }
}
