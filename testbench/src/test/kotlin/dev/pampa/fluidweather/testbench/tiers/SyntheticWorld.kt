package dev.pampa.fluidweather.testbench.tiers

import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import dev.pampa.fluidweather.testbench.data.BenchLocation
import dev.pampa.fluidweather.testbench.data.HourlyRecord
import dev.pampa.fluidweather.testbench.data.HourlySeries
import dev.pampa.fluidweather.testbench.data.StationDataset

/**
 * Un mondo sintetico per i test del banco onesto: localita' finte con un ciclo di quattro giorni
 * fisicamente coerente (la pressione crolla di 1 hPa/h prima della pioggia, resta bassa mentre
 * piove nelle prime sei ore del ciclo, poi risale), cosi' il barometro e la persistenza hanno
 * qualcosa da dire e i test possono ragionare sui numeri.
 *
 * Le serie cominciano a un'ora piena (2024-01-01T00:00Z): gli archivi veri lo sono.
 */
internal object SyntheticWorld {

  const val START_MILLIS = 1_704_067_200_000L
  const val HOUR = 3_600_000L

  fun location(name: String, longitude: Double = 11.0) = BenchLocation(name, 43.0, longitude, "fixture")

  /** Pioggia del ciclo: le ore 0-5 di ogni 96, spostate di [phase] ore. */
  fun raining(hour: Int, phase: Int): Boolean = Math.floorMod(hour + phase, 96) < 6

  fun pressure(hour: Int, phase: Int): Double {
    val dayHour = Math.floorMod(hour + phase, 96)
    return when {
      dayHour < 6 -> 1007.0
      dayHour < 18 -> 1007.0 + (dayHour - 6) * 0.5
      dayHour < 90 -> 1013.0
      else -> 1013.0 - (dayHour - 90) * 1.0
    }
  }

  /**
   * Gli ingressi di una localita' sintetica lunga [days] giorni.
   *
   * [panelOverrideAfterMillis]: dopo questo istante (estremo escluso: valgono gli slot che si chiudono
   * dopo) la pioggia dei tre giudici e' sostituita da [panelOverrideMm] ogni ora. E' il modo di
   * costruire due mondi identici fino a un istante e diversissimi dopo: se una baseline cambia,
   * ha guardato il futuro. [panelOverrideUntilMillis] chiude quella finestra (estremo incluso): con i due
   * limiti si altera un solo anno. [panelFromMillis]: il pannello comincia da li' (come fuori Europa,
   * dove i giudici non hanno storia); il barometro e il contesto ci sono comunque.
   */
  fun inputs(
    name: String,
    days: Int = 60,
    phase: Int = 0,
    longitude: Double = 11.0,
    panelOverrideAfterMillis: Long? = null,
    panelOverrideMm: Double = 5.0,
    panelOverrideUntilMillis: Long? = null,
    panelFromMillis: Long = START_MILLIS,
    contextFromMillis: Long = START_MILLIS,
    providerPop: Map<String, Map<Long, Double>> = emptyMap(),
  ): LocationInputs {
    val location = location(name, longitude)
    val hours = days * 24
    val times = LongArray(hours) { START_MILLIS + it * HOUR }
    val records = (0 until hours).map { h ->
      val p = pressure(h, phase)
      HourlyRecord(
        timestampMillis = times[h],
        temperatureC = 15.0,
        relativeHumidityPercent = 70.0,
        dewPointC = 10.0,
        surfacePressureHpa = p,
        pressureMslHpa = p,
        precipitationMm = if (raining(h, phase)) 1.0 else 0.0,
        cloudCoverPercent = 50.0,
        windSpeedKmh = 10.0,
        windDirectionDeg = 180.0,
      )
    }
    val dataset = StationDataset(location, elevationMeters = 0.0, records = records)

    fun panelRain(h: Int): Double = when {
      panelOverrideAfterMillis != null && times[h] > panelOverrideAfterMillis &&
        (panelOverrideUntilMillis == null || times[h] <= panelOverrideUntilMillis) -> panelOverrideMm
      raining(h, phase) -> 1.0
      else -> 0.0
    }
    val panelHours = (0 until hours).filter { times[it] >= panelFromMillis }
    val panelTimes = LongArray(panelHours.size) { times[panelHours[it]] }
    val members = TruthPanel.MODELS.associateWith {
      HourlySeries(
        panelTimes,
        mapOf(
          "precipitation" to DoubleArray(panelHours.size) { panelRain(panelHours[it]) },
          "pressure_msl" to DoubleArray(panelHours.size) { pressure(panelHours[it], phase) },
        ),
      )
    }

    val contextTimes = times.filter { it >= contextFromMillis }.toLongArray()
    val offset = ((contextFromMillis - START_MILLIS) / HOUR).toInt()
    val context = HourlySeries(
      contextTimes,
      mapOf(
        "temperature_2m" to DoubleArray(contextTimes.size) { 15.0 },
        "relative_humidity_2m" to DoubleArray(contextTimes.size) { 70.0 },
        "dew_point_2m" to DoubleArray(contextTimes.size) { 10.0 },
        "pressure_msl" to DoubleArray(contextTimes.size) { pressure(it + offset, phase) },
        "precipitation" to DoubleArray(contextTimes.size) { if (raining(it + offset, phase)) 1.0 else 0.0 },
        "cloud_cover" to DoubleArray(contextTimes.size) { 50.0 },
        "wind_speed_10m" to DoubleArray(contextTimes.size) { 10.0 },
        "wind_direction_10m" to DoubleArray(contextTimes.size) { 180.0 },
      ),
    )
    return LocationInputs(location, dataset, members, context, providerPop)
  }
}
