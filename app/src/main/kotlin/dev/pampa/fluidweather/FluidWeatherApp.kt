package dev.pampa.fluidweather

import android.app.Application
import dev.pampa.fluidweather.core.cycle.BackgroundCycle
import dev.pampa.fluidweather.core.cycle.CycleRuntime
import dev.pampa.fluidweather.core.cycle.NotificationChannels
import dev.pampa.fluidweather.core.data.LatestActivityStore
import dev.pampa.fluidweather.core.sensor.CalibrationController
import dev.pampa.fluidweather.core.sensor.SamplingEngine
import dev.pampa.fluidweather.core.sensor.SamplingScheduler
import dev.pampa.fluidweather.core.sensor.SensorRuntime
import kotlinx.coroutines.launch

/**
 * Il punto in cui il campionamento parte e resta in piedi: il grafo si costruisce qui, la
 * modalita' corrente si riapplica a ogni avvio del processo (worker e allarmi arrivano anche a
 * processo appena nato, e trovano tutto pronto attraverso [SensorRuntime]).
 */
class FluidWeatherApp : Application(), SensorRuntime, CycleRuntime {

  lateinit var graph: AppGraph
    private set

  override val samplingEngine: SamplingEngine get() = graph.samplingEngine
  override val samplingScheduler: SamplingScheduler get() = graph.samplingScheduler
  override val latestActivityStore: LatestActivityStore get() = graph.latestActivityStore
  override val calibrationController: CalibrationController get() = graph.calibrationController
  override val backgroundCycle: BackgroundCycle get() = graph.backgroundCycle

  override suspend fun rescheduleDailySummary() = graph.rescheduleDailySummary()

  override fun onCreate() {
    super.onCreate()
    graph = AppGraph(this)
    // I canali esistono prima della prima notifica: Android li vuole registrati, sempre.
    NotificationChannels.ensure(this)
    graph.activityRecognizer.start()
    graph.applicationScope.launch {
      graph.samplingScheduler.applyCurrentMode()
      graph.rescheduleDailySummary()
    }
  }
}
