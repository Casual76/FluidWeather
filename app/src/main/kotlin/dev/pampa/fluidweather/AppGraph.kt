package dev.pampa.fluidweather

import android.content.Context
import dev.pampa.fluidweather.core.data.FluidWeatherDatabase
import dev.pampa.fluidweather.core.data.LatestActivityStore
import dev.pampa.fluidweather.core.data.PressureRepository
import dev.pampa.fluidweather.core.data.SamplingSettingsStore
import dev.pampa.fluidweather.core.sensor.ActivityRecognizer
import dev.pampa.fluidweather.core.sensor.Barometer
import dev.pampa.fluidweather.core.sensor.ContinuousMonitor
import dev.pampa.fluidweather.core.sensor.LocationProvider
import dev.pampa.fluidweather.core.sensor.ManualBurstController
import dev.pampa.fluidweather.core.sensor.SamplingEngine
import dev.pampa.fluidweather.core.sensor.SamplingScheduler
import dev.pampa.fluidweather.core.sensor.SurveillanceController
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
  private val locationProvider = LocationProvider(context)
  private val surveillanceController = SurveillanceController(context)

  val samplingEngine = SamplingEngine(
    barometer = barometer,
    locationProvider = locationProvider,
    activityStore = latestActivityStore,
    repository = pressureRepository,
    settingsStore = samplingSettingsStore,
    surveillance = surveillanceController,
  )
  val samplingScheduler = SamplingScheduler(context, samplingSettingsStore)
  val manualBurstController = ManualBurstController(samplingEngine, applicationScope)
  val continuousMonitor = ContinuousMonitor(samplingEngine, samplingSettingsStore)
  val activityRecognizer = ActivityRecognizer(context)
}
