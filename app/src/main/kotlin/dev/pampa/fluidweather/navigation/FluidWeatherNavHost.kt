package dev.pampa.fluidweather.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.pampa.fluidweather.AppGraph
import dev.pampa.fluidweather.feature.benchmark.BenchmarkScreen
import dev.pampa.fluidweather.feature.home.HomeDependencies
import dev.pampa.fluidweather.feature.home.HomeScreen
import dev.pampa.fluidweather.feature.radar.RadarScreen
import dev.pampa.fluidweather.feature.report.ReportScreen
import dev.pampa.fluidweather.feature.settings.DiagnosticsDependencies
import dev.pampa.fluidweather.feature.settings.DiagnosticsScreen
import dev.pampa.fluidweather.feature.settings.SettingsScreen

private object Routes {
  const val Home = "home"
  const val Radar = "radar"
  const val Benchmark = "benchmark"
  const val Settings = "settings"
  const val Diagnostics = "settings/diagnostics"
  const val Report = "report"
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
          fusionCoordinator = graph.fusionCoordinator,
          locationProvider = graph.locationProvider,
          pressureRepository = graph.pressureRepository,
          cleaningPipeline = graph.cleaningPipeline,
          appearanceStore = graph.appearanceSettingsStore,
          layoutStore = graph.homeLayoutStore,
          onWeatherAccent = { graph.weatherAccent.value = it },
        )
      }
      HomeScreen(
        deps = homeDeps,
        onOpenRadar = { navController.navigate(Routes.Radar) },
        onOpenBenchmark = { navController.navigate(Routes.Benchmark) },
        onOpenSettings = { navController.navigate(Routes.Settings) },
        onOpenReport = { navController.navigate(Routes.Report) },
      )
    }
    composable(Routes.Radar) { RadarScreen(onBack = { navController.popBackStack() }) }
    composable(Routes.Benchmark) { BenchmarkScreen(onBack = { navController.popBackStack() }) }
    composable(Routes.Settings) {
      SettingsScreen(
        onBack = { navController.popBackStack() },
        onOpenDiagnostics = { navController.navigate(Routes.Diagnostics) },
      )
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
    composable(Routes.Report) { ReportScreen(onBack = { navController.popBackStack() }) }
  }
}
