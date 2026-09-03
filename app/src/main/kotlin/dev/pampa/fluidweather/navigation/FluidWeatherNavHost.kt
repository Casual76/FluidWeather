package dev.pampa.fluidweather.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.pampa.fluidweather.AppGraph
import dev.pampa.fluidweather.BuildConfig
import dev.pampa.fluidweather.core.cycle.CycleTrigger
import dev.pampa.fluidweather.feature.benchmark.BenchmarkDependencies
import dev.pampa.fluidweather.feature.benchmark.BenchmarkSheet
import dev.pampa.fluidweather.core.ai.tools.OpenTarget
import dev.pampa.fluidweather.core.ui.HomeWidget
import dev.pampa.fluidweather.feature.assistant.AiSettingsDependencies
import dev.pampa.fluidweather.feature.assistant.AiSettingsScreen
import dev.pampa.fluidweather.feature.assistant.AssistantNavigator
import dev.pampa.fluidweather.feature.assistant.AssistantOverlay
import dev.pampa.fluidweather.feature.assistant.AssistantOverlayState
import dev.pampa.fluidweather.feature.assistant.OverlayMode
import dev.pampa.fluidweather.feature.home.HomeAssistantBar
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dev.pampa.fluidweather.feature.home.HomeDependencies
import dev.pampa.fluidweather.feature.home.HomeScreen
import dev.pampa.fluidweather.feature.radar.RadarDependencies
import dev.pampa.fluidweather.feature.radar.RadarScreen
import dev.pampa.fluidweather.feature.report.ReportDependencies
import dev.pampa.fluidweather.feature.report.ReportSheet
import dev.pampa.fluidweather.feature.settings.AboutDependencies
import dev.pampa.fluidweather.feature.settings.AboutScreen
import dev.pampa.fluidweather.feature.settings.AppearanceDependencies
import dev.pampa.fluidweather.feature.settings.AppearanceScreen
import dev.pampa.fluidweather.feature.settings.DataPrivacyDependencies
import dev.pampa.fluidweather.feature.settings.DataPrivacyScreen
import dev.pampa.fluidweather.feature.settings.DiagnosticsDependencies
import dev.pampa.fluidweather.feature.settings.DiagnosticsScreen
import dev.pampa.fluidweather.feature.settings.EngineAccuracyDependencies
import dev.pampa.fluidweather.feature.settings.HapticsLabDependencies
import dev.pampa.fluidweather.feature.settings.HapticsLabScreen
import dev.pampa.fluidweather.feature.settings.EngineAccuracyScreen
import dev.pampa.fluidweather.feature.settings.NotificationsDependencies
import dev.pampa.fluidweather.feature.settings.NotificationsSettingsScreen
import dev.pampa.fluidweather.feature.settings.OnboardingDependencies
import dev.pampa.fluidweather.feature.settings.OnboardingScreen
import dev.pampa.fluidweather.feature.settings.ProvidersDependencies
import dev.pampa.fluidweather.feature.settings.ProvidersScreen
import dev.pampa.fluidweather.feature.settings.SettingsScreen
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import dev.pampa.fluidweather.core.model.UnitPreferences
import dev.pampa.fluidweather.core.ui.LocalUnits
import dev.pampa.fluidweather.feature.settings.UnitsDependencies
import dev.pampa.fluidweather.feature.settings.UnitsScreen
import java.util.Locale
import dev.pampa.fluidweather.Release

/**
 * Le rotte laterali. Benchmark e Segnalazione NON sono rotte: sono fogli neri che salgono dal
 * basso sopra la home (decisione del 2026-09-02), quindi vivono come stato della home.
 */
private object Routes {
  const val Onboarding = "onboarding"
  const val Home = "home"
  const val Radar = "radar"
  const val Settings = "settings"
  const val Engine = "settings/engine"
  const val Providers = "settings/providers"
  const val Ai = "settings/ai"
  const val Notifications = "settings/notifications"
  const val Appearance = "settings/appearance"
  const val Units = "settings/units"
  const val Diagnostics = "settings/diagnostics"
  const val Haptics = "settings/diagnostics/haptics"
  const val Data = "settings/data"
  const val About = "settings/about"
}

/**
 * Quanto la pagina coperta si sposta mentre quella nuova la copre: abbastanza da leggersi come
 * profondita', non abbastanza da scoprire il bordo. La pagina in arrivo viaggia a tutta larghezza.
 */
private const val CoveredParallax = 0.25f

/**
 * Le transizioni di rotta sono opache e laterali — la regola del design system: una pagina in
 * arrivo non sfuma mai (ogni schermata dipinge il proprio sfondo), e la direzione dice la
 * gerarchia. I tempi sono i budget di [FluidMotion], cosi' "aprire qualcosa" dura uguale ovunque.
 */
@Composable
fun FluidWeatherNavHost(graph: AppGraph, startAtOnboarding: Boolean) {
  val navController = rememberNavController()
  // Le unita' dell'utente (fase 17), lette una volta qui e disponibili a ogni schermata.
  val units by graph.unitsStore.preferences.collectAsState(
    initial = UnitPreferences.forCountry(Locale.getDefault().country),
  )
  CompositionLocalProvider(LocalUnits provides units) {
    FluidWeatherRoutes(graph, navController, startAtOnboarding)
  }
}

@Composable
private fun FluidWeatherRoutes(
  graph: AppGraph,
  navController: androidx.navigation.NavHostController,
  startAtOnboarding: Boolean,
) {
  NavHost(
    navController = navController,
    startDestination = if (startAtOnboarding) Routes.Onboarding else Routes.Home,
    enterTransition = {
      slideInHorizontally(
        animationSpec = tween(FluidMotion.DurationExpand, easing = FluidMotion.EaseEmphasized),
        initialOffsetX = { it },
      )
    },
    exitTransition = {
      slideOutHorizontally(
        animationSpec = tween(FluidMotion.DurationExpand, easing = FluidMotion.EaseEmphasized),
        targetOffsetX = { -(it * CoveredParallax).toInt() },
      )
    },
    popEnterTransition = {
      slideInHorizontally(
        animationSpec = tween(FluidMotion.DurationCollapse, easing = FluidMotion.EaseEmphasized),
        initialOffsetX = { -(it * CoveredParallax).toInt() },
      )
    },
    popExitTransition = {
      slideOutHorizontally(
        animationSpec = tween(FluidMotion.DurationCollapse, easing = FluidMotion.EaseEmphasized),
        targetOffsetX = { it },
      )
    },
  ) {
    composable(Routes.Onboarding) {
      val deps = remember(graph) {
        OnboardingDependencies(
          appearanceStore = graph.appearanceSettingsStore,
          samplingSettings = graph.samplingSettingsStore,
          scheduler = graph.samplingScheduler,
          activityRecognizer = graph.activityRecognizer,
          onboardingStore = graph.onboardingStore,
          calibrationController = graph.calibrationController,
          assistant = graph.aiAssistant,
        )
      }
      OnboardingScreen(
        deps = deps,
        onFinished = {
          navController.navigate(Routes.Home) {
            popUpTo(Routes.Onboarding) { inclusive = true }
          }
        },
      )
    }
    composable(Routes.Home) {
      val homeDeps = remember(graph) {
        HomeDependencies(
          snapshotRefresher = graph.snapshotRefresher,
          snapshotStore = graph.weatherSnapshotStore,
          samplingSettings = graph.samplingSettingsStore,
          locationProvider = graph.locationProvider,
          pressureRepository = graph.pressureRepository,
          nowcastHistory = graph.nowcastHistoryStore,
          calibrationStore = graph.calibrationStore,
          calibrationController = graph.calibrationController,
          learningRepository = graph.learningRepository,
          learningStore = graph.learningStore,
          cleaningPipeline = graph.cleaningPipeline,
          nowcast = graph.nowcastUseCase,
          airQualityClient = graph.airQualityClient,
          appearanceStore = graph.appearanceSettingsStore,
          layoutStore = graph.homeLayoutStore,
          savedLocations = graph.savedLocationsRepository,
          selectedPlaceStore = graph.selectedPlaceStore,
          geocodingClient = graph.geocodingClient,
          onWeatherAccent = { graph.weatherAccent.value = it },
        )
      }
      var benchmarkOpen by remember { mutableStateOf(false) }
      var reportOpen by remember { mutableStateOf(false) }
      var requestedWidget by remember { mutableStateOf<HomeWidget?>(null) }
      // L'assistente (fase 19): il tasto nella barra, l'overlay sopra la home, i deep link.
      val assistant = graph.aiAssistant
      val assistantEnabled by assistant.enabled.collectAsState(initial = false)
      val assistantState by assistant.session.state.collectAsState()
      val overlayState = remember { AssistantOverlayState() }
      val scope = rememberCoroutineScope()
      val navigator = remember(graph, navController) {
        object : AssistantNavigator {
          override fun open(target: OpenTarget) {
            when (target) {
              OpenTarget.RADAR -> navController.navigate(Routes.Radar)
              OpenTarget.BENCHMARK -> benchmarkOpen = true
              OpenTarget.REPORT -> reportOpen = true
              OpenTarget.SETTINGS -> navController.navigate(Routes.Settings)
              OpenTarget.AI_SETTINGS -> navController.navigate(Routes.Ai)
              OpenTarget.NOWCAST -> requestedWidget = HomeWidget.NOWCAST
              OpenTarget.HOURLY -> requestedWidget = HomeWidget.HOURLY
              OpenTarget.DAILY -> requestedWidget = HomeWidget.DAILY
              OpenTarget.PRECIPITATION -> requestedWidget = HomeWidget.PRECIPITATION
              OpenTarget.PRESSURE -> requestedWidget = HomeWidget.PRESSURE
              OpenTarget.AIR_QUALITY -> requestedWidget = HomeWidget.AIR_QUALITY
              OpenTarget.SUN -> requestedWidget = HomeWidget.SUN
              OpenTarget.MOON -> requestedWidget = HomeWidget.MOON
              OpenTarget.DETAILS -> requestedWidget = HomeWidget.DETAILS
            }
          }

          override fun selectPlace(name: String) {
            scope.launch {
              val saved = graph.savedLocationsRepository.places.first().firstOrNull { !it.isGps && it.name.equals(name, ignoreCase = true) }
              val place = saved ?: runCatching { graph.geocodingClient.search(name) }.getOrNull()?.firstOrNull()?.also { graph.savedLocationsRepository.save(it) }
              place?.let { graph.selectedPlaceStore.select(it.id) }
            }
          }
        }
      }
      HomeScreen(
        deps = homeDeps,
        onOpenRadar = { navController.navigate(Routes.Radar) },
        onOpenBenchmark = { benchmarkOpen = true },
        onOpenSettings = { navController.navigate(Routes.Settings) },
        onOpenReport = { reportOpen = true },
        assistant = HomeAssistantBar(
          enabled = assistantEnabled,
          active = overlayState.mode != OverlayMode.HIDDEN,
          working = assistantState.isBusy,
          onTap = { overlayState.openVoice() },
          onLongPress = { overlayState.openText() },
          onBounds = { overlayState.originBounds = it },
        ),
        requestedWidget = requestedWidget,
        onWidgetConsumed = { requestedWidget = null },
        overlay = { chrome ->
          if (assistantEnabled || overlayState.mode != OverlayMode.HIDDEN) {
            AssistantOverlay(assistant = assistant, overlay = overlayState, backdrop = chrome, navigator = navigator)
          }
        },
      )
      val benchmarkDeps = remember(graph) {
        BenchmarkDependencies(
          verificationStore = graph.verificationStore,
          fusionSettings = graph.fusionSettingsStore,
          snapshotStore = graph.weatherSnapshotStore,
        )
      }
      BenchmarkSheet(open = benchmarkOpen, deps = benchmarkDeps, onDismiss = { benchmarkOpen = false })
      val reportDeps = remember(graph) {
        ReportDependencies(
          observations = graph.observationRepository,
          locationProvider = graph.locationProvider,
        )
      }
      ReportSheet(open = reportOpen, deps = reportDeps, onDismiss = { reportOpen = false })
    }
    composable(Routes.Radar) {
      val deps = remember(graph) {
        RadarDependencies(
          engineSettings = graph.engineSettingsStore,
          rainViewer = graph.rainViewerClient,
          pointWeather = graph.pointWeatherClient,
          savedLocations = graph.savedLocationsRepository,
          selectedPlaceStore = graph.selectedPlaceStore,
          locationProvider = graph.locationProvider,
          providerKeys = graph.providerKeysStore,
        )
      }
      RadarScreen(deps = deps, onBack = { navController.popBackStack() })
    }
    composable(Routes.Settings) {
      SettingsScreen(
        onBack = { navController.popBackStack() },
        onOpenEngine = { navController.navigate(Routes.Engine) },
        onOpenProviders = { navController.navigate(Routes.Providers) },
        onOpenAi = { navController.navigate(Routes.Ai) },
        onOpenNotifications = { navController.navigate(Routes.Notifications) },
        onOpenAppearance = { navController.navigate(Routes.Appearance) },
        onOpenUnits = { navController.navigate(Routes.Units) },
        onOpenDiagnostics = { navController.navigate(Routes.Diagnostics) },
        onOpenData = { navController.navigate(Routes.Data) },
        onOpenAbout = { navController.navigate(Routes.About) },
      )
    }
    composable(Routes.Engine) {
      val deps = remember(graph) {
        EngineAccuracyDependencies(
          samplingSettings = graph.samplingSettingsStore,
          scheduler = graph.samplingScheduler,
          calibrationStore = graph.calibrationStore,
          calibrationController = graph.calibrationController,
          pressureRepository = graph.pressureRepository,
          cleaningPipeline = graph.cleaningPipeline,
          learningStore = graph.learningStore,
          learningRepository = graph.learningRepository,
        )
      }
      EngineAccuracyScreen(deps = deps, onBack = { navController.popBackStack() })
    }
    composable(Routes.Providers) {
      val deps = remember(graph) {
        ProvidersDependencies(providerKeys = graph.providerKeysStore, fusionSettings = graph.fusionSettingsStore)
      }
      ProvidersScreen(deps = deps, onBack = { navController.popBackStack() })
    }
    composable(Routes.Ai) {
      val deps = remember(graph) {
        AiSettingsDependencies(assistant = graph.aiAssistant, onOpenDiagnostics = { navController.navigate(Routes.Diagnostics) })
      }
      AiSettingsScreen(deps = deps, onBack = { navController.popBackStack() })
    }
    composable(Routes.Notifications) {
      val deps = remember(graph) {
        NotificationsDependencies(
          settingsStore = graph.notificationSettingsStore,
          ledgerStore = graph.notificationLedgerStore,
          notifier = graph.systemNotifier,
          onSettingsChanged = { graph.rescheduleDailySummary() },
          runCycleNow = { graph.backgroundCycle.run(CycleTrigger.MANUAL).note },
        )
      }
      NotificationsSettingsScreen(deps = deps, onBack = { navController.popBackStack() })
    }
    composable(Routes.Appearance) {
      val deps = remember(graph) {
        AppearanceDependencies(engineSettings = graph.engineSettingsStore, appearanceStore = graph.appearanceSettingsStore)
      }
      AppearanceScreen(deps = deps, onBack = { navController.popBackStack() })
    }
    composable(Routes.Units) {
      val deps = remember(graph) { UnitsDependencies(unitsStore = graph.unitsStore) }
      UnitsScreen(deps = deps, onBack = { navController.popBackStack() })
    }
    composable(Routes.Diagnostics) {
      val deps = remember(graph) {
        DiagnosticsDependencies(
          barometer = graph.barometer,
          settingsStore = graph.samplingSettingsStore,
          repository = graph.pressureRepository,
          burstController = graph.manualBurstController,
          scheduler = graph.samplingScheduler,
          activityRecognizer = graph.activityRecognizer,
          cleaningPipeline = graph.cleaningPipeline,
          fusionCoordinator = graph.fusionCoordinator,
          locationProvider = graph.locationProvider,
          assistant = graph.aiAssistant,
          crashLog = graph.crashLog,
        )
      }
      DiagnosticsScreen(
        deps = deps,
        onBack = { navController.popBackStack() },
        onOpenHaptics = { navController.navigate(Routes.Haptics) },
      )
    }

    composable(Routes.Haptics) {
      val deps = remember(graph) { HapticsLabDependencies(engineSettings = graph.engineSettingsStore) }
      HapticsLabScreen(deps = deps, onBack = { navController.popBackStack() })
    }
    composable(Routes.Data) {
      val deps = remember(graph) {
        DataPrivacyDependencies(
          pressureRepository = graph.pressureRepository,
          verificationStore = graph.verificationStore,
          observations = graph.observationRepository,
          wipeAll = { graph.wipeAllData() },
        )
      }
      DataPrivacyScreen(deps = deps, onBack = { navController.popBackStack() })
    }
    composable(Routes.About) {
      val deps = remember(graph) {
        AboutDependencies(
          appVersion = BuildConfig.VERSION_NAME,
          repositoryUrl = Release.REPOSITORY_URL,
          onReviewOnboarding = { navController.navigate(Routes.Onboarding) },
          updates = graph.updateDependencies(),
        )
      }
      AboutScreen(deps = deps, onBack = { navController.popBackStack() })
    }
  }
}
