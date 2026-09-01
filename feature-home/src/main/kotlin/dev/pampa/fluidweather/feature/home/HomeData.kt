package dev.pampa.fluidweather.feature.home

import android.content.Context
import android.location.Geocoder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import dev.antigravity.fluidengine.ui.theme.AccentPreset
import dev.pampa.fluidweather.core.data.AppearanceSettingsStore
import dev.pampa.fluidweather.core.data.HomeLayoutStore
import dev.pampa.fluidweather.core.data.PressureRepository
import dev.pampa.fluidweather.core.data.SavedLocationsRepository
import dev.pampa.fluidweather.core.data.SelectedPlaceStore
import dev.pampa.fluidweather.core.model.AirQualityNow
import dev.pampa.fluidweather.core.model.Place
import dev.pampa.fluidweather.core.model.DayPhase
import dev.pampa.fluidweather.core.model.FusedForecast
import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.SolarEphemeris
import dev.pampa.fluidweather.core.model.SunTimes
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.core.sensor.LocationProvider
import dev.pampa.fluidweather.core.ui.WeatherAccent
import dev.pampa.fluidweather.core.weather.AirQualityClient
import dev.pampa.fluidweather.core.weather.FusionCoordinator
import dev.pampa.fluidweather.core.weather.GeocodingClient
import dev.pampa.fluidweather.core.weather.ProviderRegistry
import dev.pampa.fluidweather.core.weather.toContext
import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import dev.pampa.fluidweather.nowcast.cleaning.CleaningResult
import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.nowcast.verdict.NowcastModel
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Tutto quello che la home tocca; lo costruisce :app dal suo grafo. */
class HomeDependencies(
  val fusionCoordinator: FusionCoordinator,
  val locationProvider: LocationProvider,
  val pressureRepository: PressureRepository,
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
  val dayLengthTodayMillis: Long? = null,
  val dayLengthYesterdayMillis: Long? = null,
)

/**
 * Il caricamento della home: posizione -> giro completo del meteo (fase 7) -> verdetto locale
 * (fasi 2-5, col contesto del provider migliore) -> nome del posto. Ogni passo aggiorna lo
 * stato appena sa qualcosa: il cielo cambia colore prima che arrivi l'ultimo dettaglio.
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
    val here = object {
      val latitude = latitude
      val longitude = longitude
    }
    if (presetName != null) value = value.copy(locationName = presetName)

    val phase = SolarEphemeris.phaseAt(now, here.latitude, here.longitude)
    value = value.copy(phase = phase, latitude = here.latitude, longitude = here.longitude)

    val round = runCatching { deps.fusionCoordinator.refresh(here.latitude, here.longitude) }
      .getOrNull()
    val fused = round?.fused
    val nowValues = fused?.at(now)
    val temperature = nowValues?.get(FusionVariables.TEMPERATURE)
    val kind = fused?.kindAt(now)
    val (minToday, maxToday) = fused?.todayRange(now) ?: (null to null)
    val cloud = nowValues?.get(FusionVariables.CLOUD_COVER)

    deps.onWeatherAccent(WeatherAccent.presetFor(kind, phase))
    value = value.copy(
      temperatureC = temperature,
      kind = kind,
      minC = minToday,
      maxC = maxToday,
      cloudCover = cloud,
      providersResponding = round?.fetches?.count { it.bundle != null } ?: 0,
      fusedHours = fused?.hours ?: emptyList(),
    )

    // Sole: oggi e ieri, per il "piu' corto/lungo di ieri" del widget.
    val zone = ZoneId.systemDefault()
    val todayStartUtc = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
      .atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
    val sunToday = SunTimes.forDay(todayStartUtc, here.latitude, here.longitude)
    val sunYesterday = SunTimes.forDay(todayStartUtc - 86_400_000L, here.latitude, here.longitude)
    value = value.copy(
      sunTimesToday = sunToday,
      dayLengthTodayMillis = sunToday.lengthMillis(),
      dayLengthYesterdayMillis = sunYesterday.lengthMillis(),
    )

    // Il verdetto locale: barometro pulito + contesto del provider piu' completo.
    val bundle = round?.fetches
      ?.firstOrNull { it.descriptor.id == ProviderRegistry.OPEN_METEO }?.bundle
    val samples = runCatching { deps.pressureRepository.samplesSince(now - 12 * 3_600_000L) }
      .getOrDefault(emptyList())
    val cleaning = runCatching {
      deps.cleaningPipeline.process(samples, temperatureCelsius = temperature)
    }.getOrNull()
    val verdict = cleaning?.let {
      FeatureExtractor.extract(it, bundle?.toContext(now), normalHpa = null, nowMillis = now)
        ?.let { features -> NowcastModel.trained().verdict(features) }
    }
    val latestRaw = samples.maxByOrNull { it.timestampMillis }?.pressureHpa

    value = value.copy(
      verdict = verdict,
      cleaning = cleaning,
      latestRawPressureHpa = latestRaw,
      loading = false,
    )

    val air = runCatching { deps.airQualityClient.now(here.latitude, here.longitude) }.getOrNull()
    if (air != null) value = value.copy(airQuality = air)

    if (presetName == null) {
      val name = reverseGeocode(context, here.latitude, here.longitude)
      if (name != null) value = value.copy(locationName = name)
    }
  }
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
