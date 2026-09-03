package dev.pampa.fluidweather

import android.content.Context
import dev.antigravity.fluidengine.net.EngineHttp
import dev.antigravity.fluidengine.storage.EngineSettingsStore
import dev.pampa.fluidweather.core.cycle.networkLikelyAvailable
import dev.pampa.fluidweather.core.data.CrashLog
import dev.pampa.fluidweather.core.data.FluidWeatherDatabase
import dev.pampa.fluidweather.core.cycle.AppVisibility
import dev.pampa.fluidweather.core.cycle.BackgroundCycle
import dev.pampa.fluidweather.core.cycle.BarometerRegistrar
import dev.pampa.fluidweather.core.cycle.CycleTrigger
import dev.pampa.fluidweather.core.cycle.DailySummaryAlarm
import dev.pampa.fluidweather.core.cycle.InAppAlertBus
import dev.pampa.fluidweather.core.cycle.PlaceContextResolver
import dev.pampa.fluidweather.core.cycle.SystemNotifier
import dev.pampa.fluidweather.core.ai.AiAssistant
import dev.pampa.fluidweather.core.ai.data.AiDataSources
import dev.pampa.fluidweather.core.data.LatestActivityStore
import dev.pampa.fluidweather.core.data.LearningRepository
import dev.pampa.fluidweather.core.data.LearningStore
import dev.pampa.fluidweather.core.data.NotificationLedgerStore
import dev.pampa.fluidweather.core.data.NotificationSettingsStore
import dev.pampa.fluidweather.core.data.NowcastHistoryStore
import dev.pampa.fluidweather.core.data.OnboardingStore
import dev.pampa.fluidweather.core.data.TutorialStore
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.NotificationLedger
import dev.pampa.fluidweather.core.model.NowcastOutcomeRecord
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
import dev.pampa.fluidweather.core.model.SampleSource
import dev.pampa.fluidweather.core.model.nearestHour
import dev.pampa.fluidweather.core.ui.TutorialController
import dev.pampa.fluidweather.core.weather.AirQualityClient
import dev.pampa.fluidweather.core.weather.ForecastFusion
import dev.pampa.fluidweather.core.weather.ForecastVerifier
import dev.pampa.fluidweather.core.weather.FusionCoordinator
import dev.pampa.fluidweather.core.weather.GeocodingClient
import dev.pampa.fluidweather.core.weather.OfficialAlertsClient
import dev.pampa.fluidweather.core.weather.PointWeatherClient
import dev.pampa.fluidweather.core.weather.ProviderHttp
import dev.pampa.fluidweather.core.weather.ProviderScoreboard
import dev.pampa.fluidweather.core.weather.RainEvent
import dev.pampa.fluidweather.core.weather.RainViewerClient
import dev.pampa.fluidweather.core.weather.UrlCache
import dev.pampa.fluidweather.core.weather.WeatherSnapshotRefresher
import dev.pampa.fluidweather.core.weather.WeatherSnapshotStore
import dev.pampa.fluidweather.core.weather.WeatherRepository
import dev.pampa.fluidweather.core.weather.NowcastUseCase
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
import dev.pampa.fluidweather.core.cycle.ResourceNotificationTexts
import dev.pampa.fluidweather.core.data.UnitsStore
import dev.pampa.fluidweather.strings.UnitFormatter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import dev.pampa.fluidweather.feature.settings.ReleaseSettingsStore
import dev.pampa.fluidweather.feature.settings.UpdateDependencies

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
  // Apprendimento on-device (fase 16): l'archivio da cui si impara e le mappe di ricalibrazione.
  val learningRepository = LearningRepository(database.learningDao())
  val learningStore = LearningStore(context)
  val samplingSettingsStore = SamplingSettingsStore(context)
  val engineSettingsStore = EngineSettingsStore(context)
  val calibrationStore = CalibrationStore(context)
  val onboardingStore = OnboardingStore(context)

  /** Il quaderno degli errori (1.0.3): un'app in prova senza computer attaccato, quando cade, non
   * lascerebbe niente. La Diagnostica lo mostra e lo fa copiare. */
  val crashLog = CrashLog(File(context.filesDir, "diagnostics/crash-log.txt"))

  /** Il banner della caduta si mostra una volta per avvio, non a ogni ricomposizione. */
  val crashNoticeShown = java.util.concurrent.atomic.AtomicBoolean(false)

  /** Cosa e' gia' stato spiegato (fase 21), e il controllore che la UI osserva. */
  val tutorialStore = TutorialStore(context)
  val tutorialController = TutorialController(
    seen = tutorialStore.seen.stateIn(applicationScope, SharingStarted.Eagerly, emptySet()),
    disabled = tutorialStore.disabledAll.stateIn(applicationScope, SharingStarted.Eagerly, false),
    onSeen = { id -> applicationScope.launch { tutorialStore.markSeen(id) } },
    onDisabled = { disabled -> applicationScope.launch { tutorialStore.setDisabledAll(disabled) } },
    onReplay = { applicationScope.launch { tutorialStore.resetAll() } },
  )
  val latestActivityStore = LatestActivityStore(context)

  val barometer = Barometer(context)
  val locationProvider = LocationProvider(context)
  private val surveillanceController = SurveillanceController(context)

  // Un solo EngineHttp per tutta l'app, con lo User-Agent descrittivo che MET Norway pretende
  // e gli altri apprezzano: lo usano i provider (fase 6), l'updater e la config remota (fase 18).
  private val engineHttp = EngineHttp(userAgent = Release.userAgent)

  // Il rilascio (fase 18): l'aggiornamento in-app e la meta' remota dell'engine leggono lo
  // stesso manifest.json del Pampa Store; il canale seguito e' una scelta dell'utente.
  val updater = fluidWeatherUpdater(appContext, engineHttp)
  val remoteConfig = fluidWeatherRemoteConfig(appContext, engineHttp)
  val releaseSettings = ReleaseSettingsStore(
    appContext,
    defaultChannel = ReleaseSettingsStore.channelFromName(BuildConfig.DEFAULT_UPDATE_CHANNEL),
  )

  fun updateDependencies() = UpdateDependencies(
    appVersion = BuildConfig.VERSION_NAME,
    updater = updater,
    releaseSettings = releaseSettings,
    remoteConfig = remoteConfig,
  )

  // Il livello provider (fase 6): cache per-URL nella cacheDir.
  val providerKeysStore = ProviderKeysStore(context)
  private val providerHttp = ProviderHttp(
    http = engineHttp,
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
    verifier = ForecastVerifier(
      store = verificationStore,
      // Ogni giudizio sul barometro diventa un esito da cui imparare (fase 16).
      onJudged = { prediction, truth ->
        if (prediction.providerId == RainEvent.LOCAL_BAROMETER_ID) {
          RainEvent.windowOf(prediction.variable)?.let { window ->
            learningRepository.recordOutcome(NowcastOutcomeRecord(prediction.issuedAtMillis, window.nowcastLabel, truth >= 0.5))
          }
        }
      },
    ),
    fusion = ForecastFusion(ProviderScoreboard(verificationStore)),
    fusionSettings = fusionSettingsStore,
    observations = { since -> observationRepository.since(since) },
  )

  /** Stadi 1-2 del nowcast: puro JVM, gli stessi bit che girano nel banco di prova. */
  val cleaningPipeline = CleaningPipeline()

  /** Gli stadi 1-5 in un punto solo (fase 19): lo usano la home, il ciclo in background e l'assistente. */
  val nowcastUseCase = NowcastUseCase(
    pressureRepository = pressureRepository,
    cleaningPipeline = cleaningPipeline,
    calibrationStore = calibrationStore,
    learningRepository = learningRepository,
    learningStore = learningStore,
    nowcastHistory = nowcastHistoryStore,
  )

  val samplingEngine = SamplingEngine(
    barometer = barometer,
    locationProvider = locationProvider,
    activityStore = latestActivityStore,
    repository = pressureRepository,
    settingsStore = samplingSettingsStore,
    surveillance = surveillanceController,
    cleaningPipeline = cleaningPipeline,
    // Il meteo al ritmo del barometro: dopo ogni passata, il ciclo (costruito qui sotto).
    afterPass = { source ->
      backgroundCycle.run(
        if (source == SampleSource.SURVEILLANCE) CycleTrigger.SURVEILLANCE_TICK else CycleTrigger.SAMPLING_PASS,
      )
    },
  )
  val samplingScheduler = SamplingScheduler(context, samplingSettingsStore)
  val manualBurstController = ManualBurstController(samplingEngine, applicationScope)
  val continuousMonitor = ContinuousMonitor(samplingEngine, samplingSettingsStore)
  val activityRecognizer = ActivityRecognizer(context)

  // Fondamenta UI (fase 8): aspetto, layout della griglia, accento derivato dal meteo.
  val appearanceSettingsStore = AppearanceSettingsStore(context)
  val homeLayoutStore = HomeLayoutStore(context)
  // Le unita' scelte (fase 17): le legge la UI, e le notifiche quando devono scrivere un numero.
  val unitsStore = UnitsStore(context)
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
  // Le unita' dei testi delle notifiche, tenute pronte da un collettore: la lambda viene
  // chiamata anche dal Main (la prova del ciclo dalle impostazioni), e li' un `runBlocking` su
  // una lettura del DataStore blocca la schermata.
  val notificationUnits = unitsStore.preferencesIn(applicationScope)
  val notificationTexts = ResourceNotificationTexts(context) {
    UnitFormatter(context.resources, notificationUnits.value)
  }
  val inAppAlerts = InAppAlertBus()
  val appVisibility = AppVisibility()
  val backgroundCycle = BackgroundCycle(
    locationProvider = locationProvider,
    savedLocations = savedLocationsRepository,
    refresher = snapshotRefresher,
    pressureRepository = pressureRepository,
    cleaningPipeline = cleaningPipeline,
    calibrationStore = calibrationStore,
    learningRepository = learningRepository,
    learningStore = learningStore,
    samplingSettings = samplingSettingsStore,
    notificationSettings = notificationSettingsStore,
    ledgerStore = notificationLedgerStore,
    nowcastHistory = nowcastHistoryStore,
    verificationStore = verificationStore,
    nowcastUseCase = nowcastUseCase,
    barometerRegistrar = BarometerRegistrar { verdict, at -> fusionCoordinator.registerBarometer(verdict, at) },
    officialAlerts = officialAlertsClient,
    placeContext = PlaceContextResolver(context),
    notifier = systemNotifier,
    inAppAlerts = inAppAlerts,
    appVisibility = appVisibility,
    texts = notificationTexts,
    // Solo un risparmio: con tutto spento non si sveglia un giro che aspetterebbe dieci timeout.
    networkLikelyAvailable = { networkLikelyAvailable(appContext) },
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
    val hour = snapshot.fused.nearestHour(now)?.first ?: return null
    if (abs(hour.timestampMillis - now) > 90 * 60_000L) return null
    val msl = hour.values[FusionVariables.PRESSURE_MSL]?.value ?: return null
    return CalibrationReference(msl, hour.values[FusionVariables.TEMPERATURE]?.value)
  }

  // L'assistente IA (fase 19): chiavi cifrate, tre provider, tool sui dati, radar numerico, voce.
  val aiAssistant = AiAssistant(
    context = appContext,
    scope = applicationScope,
    engineHttp = engineHttp,
    userAgent = Release.userAgent,
    referer = Release.REPOSITORY_URL,
    appTitle = "FluidWeather",
    rainViewer = rainViewerClient,
    remoteConfig = remoteConfig,
    reportError = { error, label -> crashLog.record(error, label) },
    sources = { radarSampler ->
      AiDataSources(
        snapshotRefresher = snapshotRefresher,
        snapshotStore = weatherSnapshotStore,
        nowcast = nowcastUseCase,
        pressureRepository = pressureRepository,
        nowcastHistory = nowcastHistoryStore,
        samplingSettings = samplingSettingsStore,
        calibrationStore = calibrationStore,
        calibrationController = calibrationController,
        locationProvider = locationProvider,
        savedLocations = savedLocationsRepository,
        selectedPlaceStore = selectedPlaceStore,
        geocodingClient = geocodingClient,
        airQualityClient = airQualityClient,
        officialAlerts = officialAlertsClient,
        placeContext = PlaceContextResolver(appContext),
        verificationStore = verificationStore,
        fusionSettings = fusionSettingsStore,
        providerKeys = providerKeysStore,
        weatherRepository = weatherRepository,
        rainViewer = rainViewerClient,
        radarSampler = radarSampler,
        observations = observationRepository,
        notificationSettings = notificationSettingsStore,
        notificationLedger = notificationLedgerStore,
        manualBurst = manualBurstController,
        unitsStore = unitsStore,
      )
    },
  )

  /** Dati e privacy: via tutto l'archivio locale; le impostazioni restano. */
  suspend fun wipeAllData() {
    pressureRepository.clear()
    verificationStore.clear()
    nowcastHistoryStore.clear()
    observationRepository.clear()
    calibrationStore.clear()
    learningRepository.clear()
    learningStore.clear()
    notificationLedgerStore.update { NotificationLedger() }
    weatherSnapshotStore.clear()
    File(appContext.cacheDir, "providers").deleteRecursively()
    File(appContext.cacheDir, "radar-tiles").deleteRecursively()
    aiAssistant.diagnostics.clear()
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
