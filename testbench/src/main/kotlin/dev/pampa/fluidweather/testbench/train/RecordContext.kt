package dev.pampa.fluidweather.testbench.train

import dev.pampa.fluidweather.nowcast.features.NowcastContext
import dev.pampa.fluidweather.testbench.data.StationDataset

/**
 * Il contesto sinottico e la normale climatica pescati dagli archivi, sempre e solo dal
 * passato. E' il surrogato da banco di quello che sul telefono porteranno i provider (fase 6).
 */
class RecordContext(private val dataset: StationDataset) {

  private val byTime = dataset.records.associateBy { it.timestampMillis }
  private val ordered = dataset.records.sortedBy { it.timestampMillis }
  private val prefixMsl = DoubleArray(ordered.size + 1)
  private val prefixMslCount = IntArray(ordered.size + 1)

  init {
    for (i in ordered.indices) {
      val msl = ordered[i].pressureMslHpa
      prefixMsl[i + 1] = prefixMsl[i] + (msl ?: 0.0)
      prefixMslCount[i + 1] = prefixMslCount[i] + if (msl != null) 1 else 0
    }
  }

  fun context(nowMillis: Long): NowcastContext? {
    val now = byTime[nowMillis] ?: return null
    val threeAgo = byTime[nowMillis - 3 * 3_600_000L]
    val rain3 = (0..2).sumOf { byTime[nowMillis - it * 3_600_000L]?.precipitationMm ?: 0.0 }
    val dewSpread = if (now.temperatureC != null && now.dewPointC != null) {
      now.temperatureC!! - now.dewPointC!!
    } else {
      null
    }
    return NowcastContext(
      relativeHumidityPercent = now.relativeHumidityPercent,
      dewPointSpreadC = dewSpread,
      cloudCoverPercent = now.cloudCoverPercent,
      windSpeedKmh = now.windSpeedKmh,
      windDirectionDeg = now.windDirectionDeg,
      windDirectionDeg3hAgo = threeAgo?.windDirectionDeg,
      rainLastHourMm = now.precipitationMm,
      rainLast3hMm = rain3,
    )
  }

  /**
   * La normale del punto: media della MSL sui 30 giorni precedenti, mai il futuro. Nei primi
   * 15 giorni di archivio non esiste — null, e la feature resta neutra. Sul telefono la fara'
   * l'archivio locale (fase 16).
   */
  fun normal(nowMillis: Long): Double? {
    val fromIndex = indexAtOrAfter(nowMillis - 30L * 86_400_000L)
    val toIndex = indexAtOrAfter(nowMillis)
    val count = prefixMslCount[toIndex] - prefixMslCount[fromIndex]
    if (count < 15 * 24) return null
    return (prefixMsl[toIndex] - prefixMsl[fromIndex]) / count
  }

  private fun indexAtOrAfter(timestampMillis: Long): Int {
    var low = 0
    var high = ordered.size
    while (low < high) {
      val mid = (low + high) / 2
      if (ordered[mid].timestampMillis < timestampMillis) low = mid + 1 else high = mid
    }
    return low
  }
}
