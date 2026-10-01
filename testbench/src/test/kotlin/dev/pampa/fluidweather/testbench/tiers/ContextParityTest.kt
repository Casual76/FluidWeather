package dev.pampa.fluidweather.testbench.tiers

import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.core.model.SampleSource
import dev.pampa.fluidweather.nowcast.climatology.LocalPriors
import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import dev.pampa.fluidweather.nowcast.features.ContextSlotLookup
import dev.pampa.fluidweather.nowcast.features.ContextSlots
import dev.pampa.fluidweather.nowcast.features.ContextVariable
import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.testbench.baselines.HonestBaselines
import dev.pampa.fluidweather.testbench.data.HourlySeries
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le feature del v3 sono le stesse sul telefono e sul banco: il telefono legge un pacchetto best_match per
 * timestamp esatto (`ForecastBundle.points.associateBy { it.timestampMillis }`), il banco legge la serie
 * stitched con [HourlySeriesReader]; entrambi passano da [ContextSlots.contextAt] e da
 * [FeatureExtractorV3.extract]. Qui il pacchetto del telefono e' costruito da zero con i nomi dei campi di
 * `ForecastPoint` (temperatureC, dewPointC, cloudCoverPercent...), cosi' il test prova anche che il mapping
 * campo -> variabile e' quello giusto, e che un pacchetto con righe *future* non cambia niente.
 */
class ContextParityTest {

  private val hour = 3_600_000L
  private val start = SyntheticWorld.START_MILLIS

  /** Una serie con valori diversi in ogni ora e ogni variabile, e qualche buco, cosi' ogni campo e' esercitato. */
  private val hours = 240

  private val series: HourlySeries = run {
    val random = Random(99)
    val times = LongArray(hours) { start + it * hour }
    fun column(offset: Double, scale: Double, hole: Double = 0.03) =
      DoubleArray(hours) { if (random.nextDouble() < hole) Double.NaN else offset + scale * random.nextDouble() }
    HourlySeries(
      times,
      mapOf(
        "temperature_2m" to column(8.0, 10.0),
        "relative_humidity_2m" to column(50.0, 50.0),
        "dew_point_2m" to column(2.0, 8.0),
        "pressure_msl" to column(1000.0, 20.0),
        "cloud_cover" to column(0.0, 100.0),
        "wind_speed_10m" to column(0.0, 30.0),
        "wind_direction_10m" to column(0.0, 360.0),
        "precipitation" to DoubleArray(hours) { if (random.nextDouble() < 0.03) Double.NaN else if (random.nextDouble() < 0.4) random.nextDouble() * 3 else 0.0 },
      ),
    )
  }

  private val reader = HourlySeriesReader(series)

  /** Le righe del pacchetto del telefono, per timestamp: un `Map<Long, ForecastPoint>` qui ridotto ai campi che servono. */
  private class PhonePoint(
    val temperatureC: Double?,
    val relativeHumidityPercent: Double?,
    val dewPointC: Double?,
    val pressureMslHpa: Double?,
    val precipitationMm: Double?,
    val cloudCoverPercent: Double?,
    val windSpeedKmh: Double?,
    val windDirectionDeg: Double?,
  )

  private fun bundle(from: Long, to: Long): Map<Long, PhonePoint> {
    val points = LinkedHashMap<Long, PhonePoint>()
    var t = from
    while (t <= to) {
      fun v(name: String): Double? = if (series.indexOf(t) < 0) null else series.at(t, name)
      if (series.indexOf(t) >= 0) {
        points[t] = PhonePoint(
          v("temperature_2m"), v("relative_humidity_2m"), v("dew_point_2m"), v("pressure_msl"),
          v("precipitation"), v("cloud_cover"), v("wind_speed_10m"), v("wind_direction_10m"),
        )
      }
      t += hour
    }
    return points
  }

  /** `fun ForecastBundle.toContextAt(fetchedAt)` del telefono: il lookup per timestamp esatto sul pacchetto. */
  private fun phoneContext(fetchedAt: Long, bundle: Map<Long, PhonePoint>) =
    ContextSlots.contextAt(fetchedAt) { slot, variable ->
      val point = bundle[slot] ?: return@contextAt null
      when (variable) {
        ContextVariable.TEMPERATURE -> point.temperatureC
        ContextVariable.RELATIVE_HUMIDITY -> point.relativeHumidityPercent
        ContextVariable.DEW_POINT -> point.dewPointC
        ContextVariable.PRESSURE_MSL -> point.pressureMslHpa
        ContextVariable.PRECIPITATION -> point.precipitationMm
        ContextVariable.CLOUD_COVER -> point.cloudCoverPercent
        ContextVariable.WIND_SPEED -> point.windSpeedKmh
        ContextVariable.WIND_DIRECTION -> point.windDirectionDeg
      }
    }

  @Test
  fun `il pacchetto del telefono e la serie del banco danno lo stesso contesto`() {
    val random = Random(5)
    var compared = 0
    repeat(300) {
      val fetchedAt = start + 8 * hour + (random.nextDouble() * (hours - 40) * hour).toLong()
      val s = RainWindows.lastClosedSlotEnd(fetchedAt)
      // Il pacchetto con past_hours=6 e le previsioni dopo S (per quarantotto ore, come un pacchetto vero).
      val fullBundle = bundle(s - 6 * hour, s + 48 * hour)
      val bench = ContextSources.contextAsOf(reader, fetchedAt)
      val phone = phoneContext(fetchedAt, fullBundle)
      if (bench == null) {
        assertNull(phone)
      } else {
        assertNotNull(phone)
        assertEquals(bench.context, phone)
        assertEquals(bench.slotEndMillis, phone!!.slotEndMillis)
        // Senza le righe future, stesso contesto: il pacchetto puo' contenerle, il contesto non le legge.
        assertEquals(phone, phoneContext(fetchedAt, bundle(s - 6 * hour, s)))
        compared++
      }
    }
    assertTrue("confrontati $compared contesti", compared > 250)
  }

  @Test
  fun `un pacchetto senza le sei ore indietro da' NaN dove il dato manca, come sul banco`() {
    val fetchedAt = start + 100 * hour + 11 * 60_000L
    val s = RainWindows.lastClosedSlotEnd(fetchedAt)
    // Il telefono ha solo S-3h..S: niente pioggia di sei ore, ma le tendenze a tre ore ci sono.
    val short = bundle(s - 3 * hour, s)
    val context = phoneContext(fetchedAt, short)!!
    assertTrue(context.rainSlotsMm!!.drop(4).all { it == null })
    val cleaning = cleaning(fetchedAt)
    val priors = LocalPriors.pooledOnly(pooled())
    val f = FeatureExtractorV3.extract(cleaning, context, 1013.0, fetchedAt + 20 * 60_000L, priors)!!
    assertTrue(f[FeatureExtractorV3.RAIN_LAST_6H].isNaN())
    // Con le sei ore complete, lo stesso contesto ha la pioggia di sei ore.
    val full = phoneContext(fetchedAt, bundle(s - 6 * hour, s))!!
    val g = FeatureExtractorV3.extract(cleaning, full, 1013.0, fetchedAt + 20 * 60_000L, priors)!!
    if (full.rainSlotsMm!!.take(6).all { it != null }) assertTrue(!g[FeatureExtractorV3.RAIN_LAST_6H].isNaN())
  }

  @Test
  fun `le feature del v3 sono identiche bit per bit fra pacchetto e serie`() {
    val priors = LocalPriors.pooledOnly(pooled())
    val random = Random(21)
    var compared = 0
    repeat(120) {
      val fetchedAt = start + 30 * hour + (random.nextDouble() * 150 * hour).toLong()
      val issue = fetchedAt + (random.nextDouble() * 12 * hour).toLong()
      val s = RainWindows.lastClosedSlotEnd(fetchedAt)
      val bench = ContextSources.contextAsOf(reader, fetchedAt)?.context
      val phone = phoneContext(fetchedAt, bundle(s - 6 * hour, s + 24 * hour))
      val cleaning = cleaning(issue)
      val a = FeatureExtractorV3.extract(cleaning, bench, 1012.0, issue, priors)!!
      val b = FeatureExtractorV3.extract(cleaning, phone, 1012.0, issue, priors)!!
      for (i in a.indices) assertEquals("feature ${FeatureExtractorV3.names[i]}", a[i].toRawBits(), b[i].toRawBits())
      compared++
    }
    assertEquals(120, compared)
  }

  @Test
  fun `il lookup non viene mai interrogato oltre S, nemmeno dalle sei ore`() {
    val asked = ArrayList<Long>()
    val lookup = ContextSlotLookup { slot, variable ->
      asked += slot
      reader.value(slot, variable.openMeteo)
    }
    val fetchedAt = start + 100 * hour + 59 * 60_000L
    val context = ContextSlots.contextAt(fetchedAt, lookup)!!
    assertEquals(start + 100 * hour, context.slotEndMillis)
    assertTrue(asked.max() <= context.slotEndMillis!!)
    assertTrue(asked.min() >= context.slotEndMillis!! - 6 * hour)
  }

  private fun cleaning(issue: Long) = CleaningPipeline().process(
    (0..24 * 4).map { i ->
      PressureSample(issue - 24 * hour + i * 900_000L, 1012.0 - 0.1 * i / 4.0, SampleSource.PERIODIC, latitude = 44.0, longitude = 11.0)
    },
  )

  private fun pooled() = run {
    val input = SyntheticWorld.inputs("p", days = 40)
    val period = TierPeriod("x", start + 30 * 24 * hour, start + 35 * 24 * hour, start)
    val honest = HonestBaselines.buildAll(period, listOf(input)).getValue("p")
    checkNotNull(honest.pooledOnly()).pooled
  }
}
