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
import dev.pampa.fluidweather.core.cycle.CycleTrigger
import dev.pampa.fluidweather.feature.benchmark.BenchmarkDependencies
import dev.pampa.fluidweather.feature.benchmark.BenchmarkSheet
import dev.pampa.fluidweather.feature.home.HomeDependencies
import dev.pampa.fluidweather.feature.home.HomeScreen
import dev.pampa.fluidweather.feature.radar.RadarDependencies
import dev.pampa.fluidweather.feature.radar.RadarScreen
import dev.pampa.fluidweather.feature.report.ReportDependencies
import dev.pampa.fluidweather.feature.report.ReportSheet
import dev.pampa.fluidweather.feature.settings.DiagnosticsDependencies
import dev.pampa.fluidweather.feature.settings.DiagnosticsScreen
import dev.pampa.fluidweather.feature.settings.NotificationsDependencies
import dev.pampa.fluidweather.feature.settings.NotificationsSettingsScreen
import dev.pampa.fluidweather.feature.settings.SettingsScreen

/**
 * Le rotte laterali. Benchmark e Segnalazione NON sono rotte: sono fogli neri che salgono dal
 * basso sopra la home (decisione del 2026-09-02), quindi vivono come stato della home.
 */
private object Routes {
  const val Home = "home"
  const val Radar = "radar"
  const val Settings = "settings"
  const val Diagnostics = "settings/diagnostics"
  const val Notifications = "settings/notifications"
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
fun FluidWeatherNavHost(graph: AppGraph) {
  val navController = rememberNavController()
  NavHost(
    navController = navController,
    startDestination = Routes.Home,
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
    composable(Routes.Home) {
      val homeDeps = remember(graph) {
        HomeDependencies(
          snapshotRefresher = graph.snapshotRefresher,
          snapshotStore = graph.weatherSnapshotStore,
          samplingSettings = graph.samplingSettingsStore,
          locationProvider = graph.locationProvider,
          pressureRepository = graph.pressureRepository,
          nowcastHistory = graph.nowcastHistoryStore,
          cleaningPipeline = graph.cleaningPipeline,
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
      HomeScreen(
        deps = homeDeps,
        onOpenRadar = { navController.navigate(Routes.Radar) },
        onOpenBenchmark = { benchmarkOpen = true },
        onOpenSettings = { navController.navigate(Routes.Settings) },
        onOpenReport = { reportOpen = true },
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
        onOpenDiagnostics = { navController.navigate(Routes.Diagnostics) },
        onOpenNotifications = { navController.navigate(Routes.Notifications) },
      )
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
        )
      }
      DiagnosticsScreen(deps = deps, onBack = { navController.popBackStack() })
    }
  }
}
