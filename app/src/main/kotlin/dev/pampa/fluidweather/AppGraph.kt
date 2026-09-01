package dev.pampa.fluidweather

import android.content.Context
import dev.antigravity.fluidengine.net.EngineHttp
import dev.pampa.fluidweather.core.data.FluidWeatherDatabase
import dev.pampa.fluidweather.core.data.LatestActivityStore
import dev.pampa.fluidweather.core.data.PressureRepository
import dev.pampa.fluidweather.core.data.FusionSettingsStore
import dev.pampa.fluidweather.core.data.ProviderKeysStore
import dev.pampa.fluidweather.core.data.RoomVerificationStore
import dev.pampa.fluidweather.core.data.SamplingSettingsStore
import dev.pampa.fluidweather.core.weather.ForecastFusion
import dev.pampa.fluidweather.core.weather.ForecastVerifier
import dev.pampa.fluidweather.core.weather.FusionCoordinator
import dev.pampa.fluidweather.core.weather.ProviderHttp
import dev.pampa.fluidweather.core.weather.ProviderScoreboard
import dev.pampa.fluidweather.core.weather.UrlCache
import dev.pampa.fluidweather.core.weather.WeatherRepository
import dev.pampa.fluidweather.core.weather.buildWeatherClients
import java.io.File
import dev.pampa.fluidweather.core.sensor.ActivityRecognizer
import dev.pampa.fluidweather.core.sensor.Barometer
import dev.pampa.fluidweather.core.sensor.ContinuousMonitor
import dev.pampa.fluidweather.core.sensor.LocationProvider
import dev.pampa.fluidweather.core.sensor.ManualBurstController
import dev.pampa.fluidweather.core.sensor.SamplingEngine
import dev.pampa.fluidweather.core.sensor.SamplingScheduler
import dev.pampa.fluidweather.core.sensor.SurveillanceController
import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * La DI dell'app, a mano: un grafo costruito una volta nell'Application. Niente framework —
 * ogni dipendenza si legge, si segue e si sostituisce con un costruttore.
 */
class AppGraph(context: Context) {

  /** Vive quanto il processo: quello che parte qui non ha nessuno da cui essere cancellato. */
  val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val database = FluidWeatherDatabase.build(context)

  val pressureRepository = PressureRepository(database.pressureDao())
  val samplingSettingsStore = SamplingSettingsStore(context)
  val latestActivityStore = LatestActivityStore(context)

  val barometer = Barometer(context)
  val locationProvider = LocationProvider(context)
  private val surveillanceController = SurveillanceController(context)

  // Il livello provider (fase 6): un solo EngineHttp con lo User-Agent descrittivo che
  // MET Norway pretende e gli altri apprezzano; cache per-URL nella cacheDir.
  val providerKeysStore = ProviderKeysStore(context)
  private val providerHttp = ProviderHttp(
    http = EngineHttp(userAgent = "FluidWeather/0.1 (dev.pampa.fluidweather; uso personale non commerciale)"),
    cache = UrlCache(File(context.cacheDir, "providers")),
  )
  val weatherRepository = WeatherRepository(
    clients = buildWeatherClients(providerHttp),
    keysStore = providerKeysStore,
  )

  // Fusione e punteggi (fase 7): verifiche in Room, pesi in cascata, override dell'utente.
  private val verificationStore = RoomVerificationStore(database.verificationDao())
  val fusionSettingsStore = FusionSettingsStore(context)
  val fusionCoordinator = FusionCoordinator(
    repository = weatherRepository,
    verifier = ForecastVerifier(verificationStore),
    fusion = ForecastFusion(ProviderScoreboard(verificationStore)),
    fusionSettings = fusionSettingsStore,
  )

  /** Stadi 1-2 del nowcast: puro JVM, gli stessi bit che girano nel banco di prova. */
  val cleaningPipeline = CleaningPipeline()

  val samplingEngine = SamplingEngine(
    barometer = barometer,
    locationProvider = locationProvider,
    activityStore = latestActivityStore,
    repository = pressureRepository,
    settingsStore = samplingSettingsStore,
    surveillance = surveillanceController,
    cleaningPipeline = cleaningPipeline,
  )
  val samplingScheduler = SamplingScheduler(context, samplingSettingsStore)
  val manualBurstController = ManualBurstController(samplingEngine, applicationScope)
  val continuousMonitor = ContinuousMonitor(samplingEngine, samplingSettingsStore)
  val activityRecognizer = ActivityRecognizer(context)
}
