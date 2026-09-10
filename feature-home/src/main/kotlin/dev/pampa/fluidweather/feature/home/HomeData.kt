package dev.pampa.fluidweather.feature.home

import android.content.Context
import android.location.Geocoder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import dev.pampa.fluidweather.core.weather.AirQualityClient
import dev.pampa.fluidweather.core.weather.GeocodingClient
import dev.pampa.fluidweather.core.weather.NowcastUseCase
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.core.weather.WeatherSnapshotRefresher
import dev.pampa.fluidweather.core.weather.WeatherSnapshotStore
import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import dev.pampa.fluidweather.nowcast.cleaning.CleaningResult
import dev.pampa.fluidweather.nowcast.learning.NowcastExplanation
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
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

/** Oltre questa distanza l'istantanea non parla piu' di dove sei: e' la stessa del refresher. */
private const val NEARBY_KM = 3.0

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
fun rememberHomeState(deps: HomeDependencies, place: Place): HomeStateHandle {
  val context = LocalContext.current
  // Uno stato che **sopravvive** al cambio di localita'. Con `produceState` il valore iniziale
  // veniva riapplicato a ogni cambio di chiave: la home si svuotava — testata "—", tessere in
  // attesa — per tutto il caricamento, che col GPS puo' durare dieci secondi. Tenendo i dati di
  // prima e dicendo solo "sto caricando", il cambio di posto e' immediato.
  val holder = remember { mutableStateOf(HomeUiState(phase = phaseFromClock())) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  // Cresce a ogni pull to refresh, ed e' la chiave che fa ripartire il caricamento da capo.
  var reloads by remember { mutableIntStateOf(0) }

  LaunchedEffect(place.id, reloads) {
    // Il corpo qui sotto scrive in `value` come prima: e' lo stesso caricamento, spostato.
    var value by holder
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
    val known = runCatching { deps.snapshotRefresher.lastKnown(key) }.getOrNull()
      ?.takeIf { it.ageMillis(now) <= DataAge.SHOWABLE_AGE_MILLIS }
    if (known != null) {
      value = value.copy(
        hasLocation = true,
        latitude = known.latitude,
        longitude = known.longitude,
        phase = SolarEphemeris.phaseAt(now, known.latitude, known.longitude),
      ).applySnapshot(known, now, deps)
    }

    // 1) GPS o localita' scelta: da qui in poi il caricamento non sa la differenza.
    val resolved: Triple<Double, Double, String?>? = if (place.isGps) {
      runCatching { deps.locationProvider.snapshot() }.getOrNull()
        ?.let { Triple(it.latitude, it.longitude, null) }
    } else {
      Triple(place.latitude, place.longitude, place.name)
    }
    val latitude: Double
    val longitude: Double
    val presetName: String?
    // Senza fix ma con un'istantanea si continua con le coordinate di quella — **ma senza rifare
    // il giro**. Aggiornare la posizione di ieri e chiamarla "dove sei" sarebbe il meteo di un
    // altro posto con l'etichetta giusta: meglio un dato vecchio, dichiarato.
    var canRefresh = true
    when {
      resolved != null -> {
        latitude = resolved.first
        longitude = resolved.second
        presetName = resolved.third
      }
      known != null -> {
        latitude = known.latitude
        longitude = known.longitude
        presetName = null
        canRefresh = false
      }
      else -> {
        value = value.copy(loading = false, refreshing = false, hasLocation = false)
        return@LaunchedEffect
      }
    }
    if (presetName != null) value = value.copy(locationName = presetName)

    val phase = SolarEphemeris.phaseAt(now, latitude, longitude)
    value = value.copy(phase = phase, latitude = latitude, longitude = longitude, hasLocation = true)

    // 2) Il giro di rete si rifa' solo se serve: istantanea piu' vecchia della cadenza del
    //    barometro, o presa troppo lontano da qui.
    val cadenceMillis = runCatching { deps.samplingSettings.current().mode.cadenceMinutes * 60_000L }
      .getOrDefault(DEFAULT_CADENCE_MILLIS)
    val cached = known?.takeIf { it.distanceKmTo(latitude, longitude) <= NEARBY_KM }
    val snapshot = if (canRefresh && (cached == null || cached.ageMillis(now) > cadenceMillis)) {
      runCatching { deps.snapshotRefresher.refresh(key, latitude, longitude) }.getOrNull() ?: known
    } else {
      cached
    }
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
    if (place.isGps) {
      val nowcast = deps.nowcast.evaluate(
        snapshot = snapshot,
        nowMillis = now,
        calibrationProgress = deps.calibrationController.progress.value?.let { it.completedSeconds to it.totalSeconds },
        record = true,
      )
      val history = runCatching { deps.nowcastHistory.since(now - 24 * 3_600_000L) }.getOrDefault(emptyList())
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

    val air = runCatching { deps.airQualityClient.now(latitude, longitude) }.getOrNull()
    if (air != null) value = value.copy(airQuality = air)

    if (presetName == null) {
      val name = reverseGeocode(context, latitude, longitude)
      if (name != null) value = value.copy(locationName = name)
    }

    // La raffica di taratura, se gira, muove la barra in tempo reale.
    launch {
      deps.calibrationController.progress.collect { progress ->
        val current = value.readiness ?: return@collect
        val calibrated = current.calibrated || (progress == null && runCatching { deps.calibrationStore.current() }.getOrNull() != null)
        value = value.copy(
          readiness = current.copy(
            calibrationRunning = progress != null,
            calibrationCompletedSeconds = progress?.completedSeconds ?: 0,
            calibrationTotalSeconds = progress?.totalSeconds ?: CalibrationBurst.DURATION_SECONDS,
            calibrated = calibrated,
          ),
        )
      }
    }

    // Il giro e' finito: la rotella dell'aggiornamento si spegne qui, non alla fine dell'effetto,
    // che non finisce mai perche' resta in ascolto del ciclo.
    value = value.copy(loading = false, refreshing = false)

    // 3) Il ciclo in background continua a scrivere: finche' la home e' aperta, lo segue.
    var applied = snapshot?.fetchedAtMillis ?: 0L
    deps.snapshotStore.updates.collect { updates ->
      val at = updates[key] ?: return@collect
      if (at <= applied) return@collect
      val fresh = runCatching { deps.snapshotRefresher.lastKnown(key) }.getOrNull() ?: return@collect
      applied = at
      value = value.applySnapshot(fresh, System.currentTimeMillis(), deps)
    }
  }

  // La barra del barometro, viva.
  //
  // Prima `readiness` si calcolava solo dentro il caricamento qui sopra, e nient'altro la toccava:
  // `applySnapshot` non la sfiora, e il collettore della taratura muove solo la parte della
  // raffica. Chi apriva la home subito dopo l'onboarding vedeva "0 ore di 13" e restava li' a
  // guardarla finche' non tirava giu' per aggiornare — che e' precisamente il baco raccontato.
  LaunchedEffect(place.id, lifecycle) {
    if (!place.isGps) return@LaunchedEffect
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      while (true) {
        delay(READINESS_REFRESH_MILLIS)
        // Fuori dal thread della UI: rileggere ventiquattro ore di campioni e rifarci passare la
        // pipeline di pulizia e' lavoro vero, e qui succede ogni minuto per tutto il tempo che la
        // home resta aperta.
        val readiness = runCatching {
          withContext(Dispatchers.Default) {
            deps.nowcast.readiness(
              nowMillis = System.currentTimeMillis(),
              calibrationProgress = deps.calibrationController.progress.value
                ?.let { it.completedSeconds to it.totalSeconds },
            )
          }
        }.getOrNull() ?: continue
        holder.value = holder.value.copy(readiness = readiness)
      }
    }
  }

  // Il giro all'apertura, e a ogni ritorno sulla home.
  //
  // Non esisteva: a processo vivo l'unico modo di aggiornare era il ciclo in background (che in
  // Doze puo' saltare le sue passate) o il gesto di pull to refresh. Da li' "certe volte i dati
  // non sono disponibili fino a un refresh manuale".
  //
  // **Silenzioso di proposito**: nessuna rotella, nessuno svuotamento. I dati in scena restano
  // quelli finche' non arriva il giro nuovo. La rotella e' la risposta a un gesto, e un
  // aggiornamento che l'utente non ha chiesto non deve far lampeggiare niente.
  LaunchedEffect(place.id, lifecycle) {
    // La prima ripresa e' l'apertura, e di quella si occupa gia' il caricamento qui sopra: farla
    // anche qui vorrebbe dire due giri di rete uguali nello stesso secondo.
    var firstResume = true
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      if (firstResume) {
        firstResume = false
        return@repeatOnLifecycle
      }
      val state = holder.value
      if (state.loading || state.refreshing) return@repeatOnLifecycle
      val now = System.currentTimeMillis()
      val cadenceMillis = runCatching { deps.samplingSettings.current().mode.cadenceMinutes * 60_000L }
        .getOrDefault(DEFAULT_CADENCE_MILLIS)
      val age = DataAge.ageMillis(state.dataAtMillis, now)
      if (age != null && age <= cadenceMillis) return@repeatOnLifecycle
      val key = if (place.isGps) WeatherSnapshot.GPS_KEY else WeatherSnapshot.keyFor(place.id)
      // Un fix corto, non i dieci secondi del caricamento: se il telefono ne ha uno in tasca lo
      // da' subito, e se non ce l'ha si aggiorna dove si era, che e' comunque meglio di niente.
      val here = if (place.isGps) {
        runCatching { deps.locationProvider.snapshot(timeoutMillis = SILENT_FIX_TIMEOUT_MILLIS) }.getOrNull()
      } else {
        null
      }
      val latitude = here?.latitude ?: state.latitude ?: return@repeatOnLifecycle
      val longitude = here?.longitude ?: state.longitude ?: return@repeatOnLifecycle
      val fresh = runCatching { deps.snapshotRefresher.refresh(key, latitude, longitude) }.getOrNull()
        ?: return@repeatOnLifecycle
      holder.value = holder.value.applySnapshot(fresh, System.currentTimeMillis(), deps)
    }
  }

  return remember(holder) { HomeStateHandle(state = holder, refresh = { reloads++ }) }
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
    runCatching {
      @Suppress("DEPRECATION")
      Geocoder(context, Locale.getDefault())
        .getFromLocation(latitude, longitude, 1)
        ?.firstOrNull()
        ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
    }.getOrNull()
  }
