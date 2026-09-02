package dev.pampa.fluidweather.feature.home

import android.content.Context
import android.location.Geocoder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import dev.antigravity.fluidengine.ui.theme.AccentPreset
import dev.pampa.fluidweather.core.data.AppearanceSettingsStore
import dev.pampa.fluidweather.core.data.CalibrationStore
import dev.pampa.fluidweather.core.data.HomeLayoutStore
import dev.pampa.fluidweather.core.data.NowcastHistoryStore
import dev.pampa.fluidweather.core.data.PressureRepository
import dev.pampa.fluidweather.core.data.SamplingSettingsStore
import dev.pampa.fluidweather.core.data.SavedLocationsRepository
import dev.pampa.fluidweather.core.data.SelectedPlaceStore
import dev.pampa.fluidweather.core.model.AirQualityNow
import dev.pampa.fluidweather.core.model.BarometerReadiness
import dev.pampa.fluidweather.core.model.CalibrationBurst
import dev.pampa.fluidweather.core.model.DeviceCalibration
import dev.pampa.fluidweather.core.model.NowcastReadiness
import dev.pampa.fluidweather.core.model.Place
import dev.pampa.fluidweather.core.model.DayPhase
import dev.pampa.fluidweather.core.model.FusedForecast
import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.NowcastVerdictRecord
import dev.pampa.fluidweather.core.model.SolarEphemeris
import dev.pampa.fluidweather.core.model.SunTimes
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.core.sensor.CalibrationController
import dev.pampa.fluidweather.core.sensor.LocationProvider
import dev.pampa.fluidweather.core.ui.WeatherAccent
import dev.pampa.fluidweather.core.weather.AirQualityClient
import dev.pampa.fluidweather.core.weather.GeocodingClient
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.core.weather.WeatherSnapshotRefresher
import dev.pampa.fluidweather.core.weather.WeatherSnapshotStore
import dev.pampa.fluidweather.core.weather.toContext
import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import dev.pampa.fluidweather.nowcast.cleaning.CleaningResult
import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.nowcast.verdict.NowcastModel
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import dev.pampa.fluidweather.nowcast.verdict.toRecord
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
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
  val cleaningPipeline: CleaningPipeline,
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
  val dayLengthTodayMillis: Long? = null,
  val dayLengthYesterdayMillis: Long? = null,
)

/** Un'istantanea piu' vecchia di cosi' non si mostra nemmeno come ripiego. */
private const val SHOWABLE_AGE_MILLIS = 12 * 3_600_000L

/**
 * La storia data alla pipeline: ventiquattro ore. Il modello vuole vedere tredici ore di segnale
 * pulito, e con una finestra di dodici il verdetto non poteva esistere — era il baco visto sul
 * telefono dopo diciassette ore (2026-09-02).
 */
private const val HISTORY_WINDOW_MILLIS = 24 * 3_600_000L

/**
 * Il caricamento della home: posizione -> l'ISTANTANEA del ciclo in background, subito -> il
 * giro dei provider solo se l'istantanea e' piu' vecchia della cadenza del barometro -> verdetto
 * locale (fasi 2-5, col contesto dell'opinione piu' completa) -> nome del posto. Poi la home
 * resta in ascolto: ogni istantanea nuova scritta dal ciclo la aggiorna mentre e' aperta.
 * Ogni passo aggiorna lo stato appena sa qualcosa: il cielo cambia colore prima dell'ultimo
 * dettaglio.
 */
@Composable
fun rememberHomeState(deps: HomeDependencies, place: Place): State<HomeUiState> {
  val context = LocalContext.current
  return produceState(initialValue = HomeUiState(phase = phaseFromClock()), key1 = place.id) {
    val now = System.currentTimeMillis()

    // GPS o localita' scelta: da qui in poi il caricamento non sa la differenza.
    val resolved: Triple<Double, Double, String?>? = if (place.isGps) {
      deps.locationProvider.snapshot()?.let { Triple(it.latitude, it.longitude, null) }
    } else {
      Triple(place.latitude, place.longitude, place.name)
    }
    if (resolved == null) {
      value = value.copy(loading = false, hasLocation = false)
      return@produceState
    }
    val (latitude, longitude, presetName) = resolved
    if (presetName != null) value = value.copy(locationName = presetName)

    val phase = SolarEphemeris.phaseAt(now, latitude, longitude)
    value = value.copy(phase = phase, latitude = latitude, longitude = longitude)

    val key = if (place.isGps) WeatherSnapshot.GPS_KEY else WeatherSnapshot.keyFor(place.id)

    // 1) L'istantanea del ciclo in background, SUBITO: la home non rifa' il giro davanti all'utente.
    val cached = deps.snapshotRefresher.fresh(key, latitude, longitude, maxAgeMillis = SHOWABLE_AGE_MILLIS)
    if (cached != null) value = value.applySnapshot(cached, now, deps)

    // 2) Se e' piu' vecchia della cadenza del barometro, il giro si rifa' dietro ai dati in scena.
    val cadenceMillis = deps.samplingSettings.current().mode.cadenceMinutes * 60_000L
    val snapshot = if (cached != null && cached.ageMillis(now) <= cadenceMillis) {
      cached
    } else {
      runCatching { deps.snapshotRefresher.refresh(key, latitude, longitude) }.getOrNull() ?: cached
    }
    if (snapshot != null && snapshot !== cached) value = value.applySnapshot(snapshot, now, deps)

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

    // Il verdetto locale: barometro pulito + contesto dell'opinione piu' completa (dall'istantanea).
    val bundle = snapshot?.context
    val calibrationRecord = runCatching { deps.calibrationStore.current() }.getOrNull()
    val samples = runCatching { deps.pressureRepository.samplesSince(now - HISTORY_WINDOW_MILLIS) }
      .getOrDefault(emptyList())
    val cleaning = runCatching {
      deps.cleaningPipeline.process(
        samples,
        calibration = calibrationRecord?.toDeviceCalibration() ?: DeviceCalibration(),
        temperatureCelsius = value.temperatureC,
      )
    }.getOrNull()
    val verdict = cleaning?.let {
      FeatureExtractor.extract(it, bundle?.toContext(now), normalHpa = null, nowMillis = now)
        ?.let { features -> NowcastModel.trained().verdict(features) }
    }
    val latestRaw = samples.maxByOrNull { it.timestampMillis }?.pressureHpa
    if (verdict != null) runCatching { deps.nowcastHistory.record(verdict.toRecord(now)) }
    val history = runCatching { deps.nowcastHistory.since(now - 24 * 3_600_000L) }.getOrDefault(emptyList())

    val historyHours = cleaning?.filtered
      ?.takeIf { it.size >= 2 }
      ?.let { (it.last().timestampMillis - it.first().timestampMillis) / 3_600_000.0 }
      ?: 0.0
    value = value.copy(
      verdict = verdict,
      cleaning = cleaning,
      latestRawPressureHpa = latestRaw,
      verdictHistory = history,
      readiness = NowcastReadiness.of(
        calibration = calibrationRecord,
        calibrationProgress = deps.calibrationController.progress.value?.let { it.completedSeconds to it.totalSeconds },
        historyHours = historyHours,
        requiredHours = FeatureExtractor.MIN_HISTORY_HOURS,
      ),
      loading = false,
    )

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

    // 3) Il ciclo in background continua a scrivere: finche' la home e' aperta, lo segue.
    var applied = snapshot?.fetchedAtMillis ?: 0L
    deps.snapshotStore.updates.collect { updates ->
      val at = updates[key] ?: return@collect
      if (at <= applied) return@collect
      val fresh = deps.snapshotRefresher.fresh(key, latitude, longitude, maxAgeMillis = SHOWABLE_AGE_MILLIS)
        ?: return@collect
      applied = at
      value = value.applySnapshot(fresh, System.currentTimeMillis(), deps)
    }
  }
}

/** Le ore fuse dell'istantanea dentro lo stato: testata, cielo, dispensa dei widget, accento. */
private fun HomeUiState.applySnapshot(
  snapshot: WeatherSnapshot,
  nowMillis: Long,
  deps: HomeDependencies,
): HomeUiState {
  val fused = snapshot.fused
  val nowValues = fused.at(nowMillis)
  val kind = fused.kindAt(nowMillis)
  val (minToday, maxToday) = fused.todayRange(nowMillis)
  deps.onWeatherAccent(WeatherAccent.presetFor(kind, phase))
  return copy(
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

private fun FusedForecast.at(nowMillis: Long): Map<String, Double>? =
  hours.minByOrNull { abs(it.timestampMillis - nowMillis) }
    ?.takeIf { abs(it.timestampMillis - nowMillis) <= 90 * 60_000L }
    ?.values?.mapValues { it.value.value }

private fun FusedForecast.kindAt(nowMillis: Long): WeatherKind? =
  hours.minByOrNull { abs(it.timestampMillis - nowMillis) }?.kind

/** Min e max del giorno locale, dal fuso: il "Max/Min" della testata. */
private fun FusedForecast.todayRange(nowMillis: Long): Pair<Double?, Double?> {
  val zone = ZoneId.systemDefault()
  val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
  val temperatures = hours
    .filter { Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDate() == today }
    .mapNotNull { it.values[FusionVariables.TEMPERATURE]?.value }
  if (temperatures.isEmpty()) return null to null
  return temperatures.min() to temperatures.max()
}

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
