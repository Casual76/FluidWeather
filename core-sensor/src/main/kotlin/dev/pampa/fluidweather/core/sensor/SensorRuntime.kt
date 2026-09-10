package dev.pampa.fluidweather.core.sensor

import android.content.Context
import dev.pampa.fluidweather.core.data.LatestActivityStore

/**
 * Quello che worker, receiver e servizi — istanziati dal sistema, non da noi — devono poter
 * raggiungere. L'Application dell'app lo implementa: e' la DI manuale del progetto, un cast al
 * posto di un framework.
 */
interface SensorRuntime {
  val samplingEngine: SamplingEngine
  val samplingScheduler: SamplingScheduler
  val samplingHealth: SamplingHealth
  val latestActivityStore: LatestActivityStore
  val calibrationController: CalibrationController
}

fun Context.sensorRuntime(): SensorRuntime = applicationContext as SensorRuntime
