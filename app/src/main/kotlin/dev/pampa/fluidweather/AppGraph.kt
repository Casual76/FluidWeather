package dev.pampa.fluidweather

import android.content.Context
import dev.antigravity.fluidengine.net.EngineHttp
import dev.antigravity.fluidengine.storage.EngineSettingsStore
import dev.pampa.fluidweather.core.data.FluidWeatherDatabase
import dev.pampa.fluidweather.core.cycle.AppVisibility
import dev.pampa.fluidweather.core.cycle.BackgroundCycle
import dev.pampa.fluidweather.core.cycle.BarometerRegistrar
import dev.pampa.fluidweather.core.cycle.CycleTrigger
import dev.pampa.fluidweather.core.cycle.DailySummaryAlarm
import dev.pampa.fluidweather.core.cycle.InAppAlertBus
import dev.pampa.fluidweather.core.cycle.PlaceContextResolver
import dev.pampa.fluidweather.core.cycle.SystemNotifier
import dev.pampa.fluidweather.core.data.LatestActivityStore
import dev.pampa.fluidweather.core.data.NotificationLedgerStore
import dev.pampa.fluidweather.core.data.NotificationSettingsStore
import dev.pampa.fluidweather.core.data.NowcastHistoryStore
import dev.pampa.fluidweather.core.data.OnboardingStore
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.NotificationLedger
import dev.pampa.fluidweather.core.data.ObservationRepository
import dev.pampa.fluidweather.core.data.PressureRepository
import dev.antigravity.fluidengine.ui.theme.AccentPreset
import dev.pampa.fluidweather.core.data.AppearanceSettingsStore
import dev.pampa.fluidweather.core.data.CalibrationStore
import dev.pampa.fluidweather.core.data.FusionSettingsStore
import dev.pampa.fluidweather.core.data.HomeLayoutStore
import dev.pampa.fluidweather.core.data.ProviderKeysStore
import dev.pampa.fluidweather.core.data.RoomVerificationStore
import dev.pampa.fluidweather.core.data.SamplingSettingsStore
import dev.pampa.fluidweather.core.data.SavedLocationsRepository
import dev.pampa.fluidweather.core.data.SelectedPlaceStore
import dev.pampa.fluidweather.core.weather.AirQualityClient
import dev.pampa.fluidweather.core.weather.ForecastFusion
import dev.pampa.fluidweather.core.weather.ForecastVerifier
import dev.pampa.fluidweather.core.weather.FusionCoordinator
import dev.pampa.fluidweather.core.weather.GeocodingClient
import dev.pampa.fluidweather.core.weather.OfficialAlertsClient
import dev.pampa.fluidweather.core.weather.PointWeatherClient
import dev.pampa.fluidweather.core.weather.ProviderHttp
import dev.pampa.fluidweather.core.weather.ProviderScoreboard
import dev.pampa.fluidweather.core.weather.RainViewerClient
import dev.pampa.fluidweather.core.weather.UrlCache
import dev.pampa.fluidweather.core.weather.WeatherSnapshotRefresher
import dev.pampa.fluidweather.core.weather.WeatherSnapshotStore
import dev.pampa.fluidweather.core.weather.WeatherRepository
import dev.pampa.fluidweather.core.weather.WeatherSnapshot
import dev.pampa.fluidweather.core.weather.buildWeatherClients
import java.io.File
import dev.pampa.fluidweather.core.sensor.ActivityRecognizer
import dev.pampa.fluidweather.core.sensor.Barometer
import dev.pampa.fluidweather.core.sensor.CalibrationController
import dev.pampa.fluidweather.core.sensor.CalibrationReference
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

/**
 * La DI dell'app, a mano: un grafo costruito una volta nell'Application. Niente framework —
 * ogni dipendenza si legge, si segue e si sostituisce con un costruttore.
 */
class AppGraph(context: Context) {

  private val appContext: Context = context.applicationContext

  /** Vive quanto il processo: quello che parte qui non ha nessuno da cui essere cancellato. */
  val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val database = FluidWeatherDatabase.build(context)

  val pressureRepository = PressureRepository(database.pressureDao())
  val nowcastHistoryStore = NowcastHistoryStore(database.nowcastHistoryDao())
  val observationRepository = ObservationRepository(database.observationDao())
  val samplingSettingsStore = SamplingSettingsStore(context)
  val engineSettingsStore = EngineSettingsStore(context)
  val calibrationStore = CalibrationStore(context)
  val onboardingStore = OnboardingStore(context)
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
  val airQualityClient = AirQualityClient(providerHttp)

  // Radar (fase 12): i fotogrammi RainViewer e il "adesso" dei pin in una chiamata sola.
  val rainViewerClient = RainViewerClient(providerHttp)
  val pointWeatherClient = PointWeatherClient(providerHttp)

  // Fusione e punteggi (fase 7): verifiche in Room, pesi in cascata, override dell'utente.
  val verificationStore = RoomVerificationStore(database.verificationDao())
  val fusionSettingsStore = FusionSettingsStore(context)
  val fusionCoordinator = FusionCoordinator(
    repository = weatherRepository,
    verifier = ForecastVerifier(verificationStore),
    fusion = ForecastFusion(ProviderScoreboard(verificationStore)),
    fusionSettings = fusionSettingsStore,
    observations = { since -> observationRepository.since(since) },
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
    // Il meteo al ritmo del barometro: dopo ogni passata, il ciclo (costruito qui sotto).
    afterPass = { backgroundCycle.run(CycleTrigger.SAMPLING_PASS) },
  )
  val samplingScheduler = SamplingScheduler(context, samplingSettingsStore)
  val manualBurstController = ManualBurstController(samplingEngine, applicationScope)
  val continuousMonitor = ContinuousMonitor(samplingEngine, samplingSettingsStore)
  val activityRecognizer = ActivityRecognizer(context)

  // Fondamenta UI (fase 8): aspetto, layout della griglia, accento derivato dal meteo.
  val appearanceSettingsStore = AppearanceSettingsStore(context)
  val homeLayoutStore = HomeLayoutStore(context)
  val weatherAccent = MutableStateFlow<AccentPreset?>(null)

  // Localita' (fase 10): salvate su Room, selezione persistita, ricerca keyless.
  val savedLocationsRepository = SavedLocationsRepository(database.savedLocationsDao())
  val selectedPlaceStore = SelectedPlaceStore()
  val geocodingClient = GeocodingClient(providerHttp)

  // Ciclo in background e notifiche (fase 11): l'istantanea che la home legge subito, i
  // quattro canali, la memoria di cio' che e' gia' stato detto.
  val weatherSnapshotStore = WeatherSnapshotStore(File(context.filesDir, "snapshots"))
  val snapshotRefresher = WeatherSnapshotRefresher(fusionCoordinator, weatherSnapshotStore)
  val officialAlertsClient = OfficialAlertsClient(providerHttp)
  val notificationSettingsStore = NotificationSettingsStore(context)
  val notificationLedgerStore = NotificationLedgerStore(context)
  val systemNotifier = SystemNotifier(context)
  val inAppAlerts = InAppAlertBus()
  val appVisibility = AppVisibility()
  val backgroundCycle = BackgroundCycle(
    locationProvider = locationProvider,
    savedLocations = savedLocationsRepository,
    refresher = snapshotRefresher,
    pressureRepository = pressureRepository,
    cleaningPipeline = cleaningPipeline,
    calibrationStore = calibrationStore,
    samplingSettings = samplingSettingsStore,
    notificationSettings = notificationSettingsStore,
    ledgerStore = notificationLedgerStore,
    nowcastHistory = nowcastHistoryStore,
    barometerRegistrar = BarometerRegistrar { verdict, at -> fusionCoordinator.registerBarometer(verdict, at) },
    officialAlerts = officialAlertsClient,
    placeContext = PlaceContextResolver(context),
    notifier = systemNotifier,
    inAppAlerts = inAppAlerts,
    appVisibility = appVisibility,
  )

  // Taratura iniziale (fase 15): dieci minuti in un foreground service, riferimento dai provider.
  val calibrationController = CalibrationController(
    context = appContext,
    engine = samplingEngine,
    repository = pressureRepository,
    store = calibrationStore,
    reference = { calibrationReference() },
  )

  /** La pressione al mare dei provider adesso, per il punto del telefono: il riferimento. */
  private suspend fun calibrationReference(): CalibrationReference? {
    val now = System.currentTimeMillis()
    val here = locationProvider.snapshot()
    val snapshot = if (here != null) {
      snapshotRefresher.fresh(WeatherSnapshot.GPS_KEY, here.latitude, here.longitude, maxAgeMillis = 3 * 3_600_000L)
        ?: runCatching { snapshotRefresher.refresh(WeatherSnapshot.GPS_KEY, here.latitude, here.longitude) }.getOrNull()
    } else {
      weatherSnapshotStore.read(WeatherSnapshot.GPS_KEY)
    } ?: return null
    val hour = snapshot.fused.hours.minByOrNull { abs(it.timestampMillis - now) } ?: return null
    if (abs(hour.timestampMillis - now) > 90 * 60_000L) return null
    val msl = hour.values[FusionVariables.PRESSURE_MSL]?.value ?: return null
    return CalibrationReference(msl, hour.values[FusionVariables.TEMPERATURE]?.value)
  }

  /** Dati e privacy: via tutto l'archivio locale; le impostazioni restano. */
  suspend fun wipeAllData() {
    pressureRepository.clear()
    verificationStore.clear()
    nowcastHistoryStore.clear()
    observationRepository.clear()
    calibrationStore.clear()
    notificationLedgerStore.update { NotificationLedger() }
    weatherSnapshotStore.clear()
    File(appContext.cacheDir, "providers").deleteRecursively()
  }

  /** Il riepilogo giornaliero segue l'impostazione: programmato all'ora scelta, o cancellato. */
  suspend fun rescheduleDailySummary() {
    val settings = notificationSettingsStore.current()
    if (settings.dailySummary) {
      DailySummaryAlarm.schedule(appContext, settings.summaryHour, settings.summaryMinute)
    } else {
      DailySummaryAlarm.cancel(appContext)
    }
  }
}
