package dev.pampa.fluidweather.nowcast.features

import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.core.model.SampleSource
import dev.pampa.fluidweather.nowcast.climatology.LocalPriors
import dev.pampa.fluidweather.nowcast.climatology.PriorsFixtures
import dev.pampa.fluidweather.nowcast.climatology.WindowClimatology
import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import dev.pampa.fluidweather.nowcast.cleaning.CleaningResult
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3 as V3

class FeatureExtractorV3Test {

  private val hour = 3_600_000L
  private val pipeline = CleaningPipeline()
  private val local: LocalPriors = PriorsFixtures.local()
  private val pooledOnly: LocalPriors = PriorsFixtures.pooledOnly()

  /** Un'emissione di mezzogiorno e venti di un giorno di marzo, dove le tabelle sintetiche hanno storia. */
  private val issue = Instant.parse("2023-03-10T12:20:00Z").toEpochMilli()

  private fun utc(text: String): Long = Instant.parse(text).toEpochMilli()

  private fun cleaning(
    issue: Long = this.issue,
    hours: Int = 24,
    latitude: Double? = null,
    longitude: Double? = null,
    pressureAt: (Double) -> Double = { 1013.0 },
  ): CleaningResult {
    val start = issue - hours * hour
    val samples = (0..hours * 4).map { i ->
      val h = i * 0.25
      PressureSample(
        timestampMillis = start + (h * 3_600_000).toLong(),
        pressureHpa = pressureAt(h),
        source = SampleSource.PERIODIC,
        latitude = latitude,
        longitude = longitude,
      )
    }
    return pipeline.process(samples)
  }

  /** Un contesto completo con S = l'ultimo slot chiuso all'emissione di [fetchedAt]. */
  private fun context(
    fetchedAt: Long = issue - 20 * 60_000L,
    rain: List<Double?> = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
    cloud: Double? = 60.0,
    cloud3: Double? = 40.0,
    temperature: Double? = 12.0,
    temperature3: Double? = 10.0,
    dewPoint: Double? = 8.0,
    dewPoint3: Double? = 7.0,
  ) = NowcastContext(
    relativeHumidityPercent = 80.0,
    dewPointSpreadC = 4.0,
    cloudCoverPercent = cloud,
    windSpeedKmh = 10.0,
    windDirectionDeg = 200.0,
    windDirectionDeg3hAgo = 180.0,
    rainLastHourMm = rain.firstOrNull(),
    rainLast3hMm = rain.take(3).filterNotNull().sum(),
    pressureMslHpa = 1010.0,
    pressureMsl3hAgoHpa = 1012.0,
    slotEndMillis = RainWindows.lastClosedSlotEnd(fetchedAt),
    temperatureC = temperature,
    temperature3hAgoC = temperature3,
    dewPointC = dewPoint,
    dewPoint3hAgoC = dewPoint3,
    cloudCover3hAgoPercent = cloud3,
    rainSlotsMm = rain,
  )

  private fun extract(
    context: NowcastContext? = null,
    normal: Double? = 1013.0,
    priors: LocalPriors = local,
    issue: Long = this.issue,
    cleaning: CleaningResult = cleaning(issue),
    referenceAltitudeKnown: Boolean = true,
    bias: Double = 0.0,
  ): DoubleArray = V3.extract(cleaning, context, normal, issue, priors, referenceAltitudeKnown, bias)!!

  @Test
  fun `il contratto sono quarantadue nomi, i primi venti quelli del v2`() {
    assertEquals(42, V3.names.size)
    assertEquals(42, V3.COUNT)
    assertEquals(FeatureExtractor.names, V3.names.take(20))
    assertEquals(22, V3.TAIL.size)
    assertEquals(V3.names.size, V3.names.toSet().size)
    assertEquals("features-v3", V3.VERSION)
    // Gli indici nominati puntano ai nomi giusti.
    assertEquals("eta-contesto-ore", V3.names[V3.CONTEXT_AGE])
    assertEquals("piove-adesso", V3.names[V3.RAINING_NOW])
    assertEquals("tendenza-temperatura-3h", V3.names[V3.TEMPERATURE_TREND])
    assertEquals("convezione-pomeridiana", V3.names[V3.CONVECTION])
    assertEquals("clima-0-1h", V3.names[V3.CLIMATOLOGY])
    assertEquals("persistenza-3-6h", V3.names[V3.PERSISTENCE + 2])
    assertEquals("regola-barometrica-1-3h", V3.names[V3.BAROMETRIC_RULE + 1])
    assertEquals("livello-mare", V3.names[V3.SEA_LEVEL])
    assertEquals("clima-locale", V3.names[V3.LOCAL_TABLES])
  }

  @Test
  fun `le prime venti feature sono bit per bit quelle del v2`() {
    for (context in listOf<NowcastContext?>(null, context())) {
      for (normal in listOf(null, 1015.0)) {
        val cleaning = cleaning(pressureAt = { h -> 1013.0 - 0.3 * h })
        val v2 = FeatureExtractor.extract(cleaning, context, normal, issue)!!
        val v3 = V3.extract(cleaning, context, normal, issue, local)!!
        for (i in 0 until 20) assertEquals("feature $i", v2[i].toRawBits(), v3[i].toRawBits())
      }
    }
  }

  @Test
  fun `con poca storia non si estrae niente, come nel v2`() {
    assertNull(V3.extract(cleaning(hours = 6), null, null, issue, local))
  }

  // ---------------------------------------------------------------------------- contesto

  @Test
  fun `l'eta' del contesto e' in ore, ritagliata fra zero e quattordici`() {
    // Fetch alle 11:40, slot S = 11:00, emissione 12:20: 1 h 20.
    assertEquals(80.0 / 60.0, extract(context(fetchedAt = utc("2023-03-10T11:40:00Z")))[V3.CONTEXT_AGE], 1e-12)
    // Contesto vecchissimo: ritaglio a 14.
    assertEquals(14.0, extract(context(fetchedAt = issue - 40 * hour))[V3.CONTEXT_AGE], 0.0)
    // Contesto "dal futuro" (un orologio che salta): zero, mai negativo.
    assertEquals(0.0, extract(context(fetchedAt = issue + 3 * hour))[V3.CONTEXT_AGE], 0.0)
    // Senza contesto, o con un contesto senza slot (un chiamante del v2): NaN.
    assertTrue(extract(null)[V3.CONTEXT_AGE].isNaN())
    assertTrue(extract(NowcastContext(rainLastHourMm = 0.4))[V3.CONTEXT_AGE].isNaN())
  }

  @Test
  fun `piove adesso e' la soglia dell'evento sull'ultima ora chiusa`() {
    fun raining(r0: Double?) = extract(context(rain = listOf(r0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)))[V3.RAINING_NOW]
    assertEquals(1.0, raining(0.2), 0.0)
    assertEquals(1.0, raining(3.0), 0.0)
    assertEquals(0.0, raining(0.19), 0.0)
    assertEquals(0.0, raining(0.0), 0.0)
    assertTrue(raining(null).isNaN())
    assertTrue(extract(null)[V3.RAINING_NOW].isNaN())
  }

  @Test
  fun `la pioggia delle ultime sei ore vuole tutti e sei gli slot`() {
    val six: List<Double?> = listOf(0.5, 0.0, 0.3, 0.0, 1.2, 0.0, 9.0)
    // Il settimo slot (k = 6) non conta.
    assertEquals(ln(1.0 + 0.5 + 0.3 + 1.2), extract(context(rain = six))[V3.RAIN_LAST_6H], 1e-12)
    // Un buco in uno dei sei: NaN, non una somma parziale.
    assertTrue(extract(context(rain = six.toMutableList<Double?>().also { it[4] = null }))[V3.RAIN_LAST_6H].isNaN())
    // Un buco nel settimo: non importa.
    assertEquals(ln(1.0 + 2.0), extract(context(rain = listOf(1.0, 1.0, 0.0, 0.0, 0.0, 0.0, null)))[V3.RAIN_LAST_6H], 1e-12)
    // Valori negativi (un artefatto dell'archivio) non tolgono pioggia.
    assertEquals(ln(1.0 + 1.0), extract(context(rain = listOf(1.0, -5.0, 0.0, 0.0, 0.0, 0.0, 0.0)))[V3.RAIN_LAST_6H], 1e-12)
    // Meno di sei slot o nessuno slot: NaN.
    assertTrue(extract(context(rain = listOf(0.0, 0.0, 0.0)))[V3.RAIN_LAST_6H].isNaN())
    assertTrue(extract(null)[V3.RAIN_LAST_6H].isNaN())
    // Un contesto "v2" (senza slot) non ha le feature di sei ore.
    assertTrue(extract(NowcastContext(rainLastHourMm = 1.0))[V3.RAIN_LAST_6H].isNaN())
  }

  @Test
  fun `le ore dall'ultima pioggia scorrono dal piu' recente`() {
    fun since(vararg r: Double?) = extract(context(rain = r.toList()))[V3.HOURS_SINCE_RAIN]
    assertEquals(0.0, since(0.2, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), 0.0)
    assertEquals(1.0, since(0.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0), 0.0)
    assertEquals(3.0, since(0.1, 0.19, 0.0, 2.0, 0.0, 0.0, 0.0), 0.0)
    assertEquals(5.0, since(0.0, 0.0, 0.0, 0.0, 0.0, 0.2, 0.0), 0.0)
    // Nessuna pioggia negli ultimi sei slot: sei, anche se il settimo era bagnato.
    assertEquals(6.0, since(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 8.0), 0.0)
    // Un buco prima della prima pioggia: sconosciuto; una pioggia prima del buco: si sa.
    assertTrue(since(0.0, null, 0.5, 0.0, 0.0, 0.0, 0.0).isNaN())
    assertEquals(0.0, since(0.4, null, 0.0, 0.0, 0.0, 0.0, 0.0), 0.0)
    assertTrue(since(0.0, 0.0, 0.0, 0.0, 0.0, null, 0.0).isNaN())
    assertTrue(extract(null)[V3.HOURS_SINCE_RAIN].isNaN())
  }

  @Test
  fun `le tendenze a tre ore sono differenze fra adesso e tre ore fa, NaN se ne manca una`() {
    val f = extract(context())
    assertEquals(20.0, f[V3.CLOUD_TREND], 1e-12)
    assertEquals(1.0, f[V3.DEW_POINT_TREND], 1e-12)
    assertEquals(2.0, f[V3.TEMPERATURE_TREND], 1e-12)
    val g = extract(context(cloud3 = null, dewPoint = null, temperature3 = null))
    assertTrue(g[V3.CLOUD_TREND].isNaN())
    assertTrue(g[V3.DEW_POINT_TREND].isNaN())
    assertTrue(g[V3.TEMPERATURE_TREND].isNaN())
    val h = extract(null)
    assertTrue(h[V3.CLOUD_TREND].isNaN() && h[V3.DEW_POINT_TREND].isNaN() && h[V3.TEMPERATURE_TREND].isNaN())
  }

  @Test
  fun `senza contesto tutte le feature del contesto sono NaN e la persistenza pure`() {
    val f = extract(null)
    for (i in V3.CONTEXT_AGE..V3.TEMPERATURE_TREND) assertTrue("${V3.names[i]} dovrebbe essere NaN", f[i].isNaN())
    for (w in 0..2) assertTrue(f[V3.PERSISTENCE + w].isNaN())
    // Il resto c'e': tempo, baseline di clima e regola, livello, stato dei dati.
    for (i in listOf(V3.DAY_SIN, V3.DAY_COS, V3.CONVECTION, V3.SEA_LEVEL, V3.NORMAL_MISSING, V3.LOCAL_TABLES)) {
      assertFalse("${V3.names[i]} non dovrebbe essere NaN", f[i].isNaN())
    }
    for (w in 0..2) {
      assertFalse(f[V3.CLIMATOLOGY + w].isNaN())
      assertFalse(f[V3.BAROMETRIC_RULE + w].isNaN())
    }
  }

  // ---------------------------------------------------------------------------- tempo

  private fun expectedDay(issue: Long, latitude: Double): Double {
    val t = Instant.ofEpochMilli(issue).atOffset(ZoneOffset.UTC)
    return (t.dayOfYear - 1) + (t.hour * 3600 + t.minute * 60 + t.second) / 86_400.0 + if (latitude < 0) 182.62 else 0.0
  }

  @Test
  fun `il giorno dell'anno entra come seno e coseno, spostato di mezzo anno a sud`() {
    val july = utc("2023-07-15T12:00:00Z")
    for (latitude in listOf(45.0, -34.0)) {
      val f = extract(issue = july, cleaning = cleaning(july, latitude = latitude, longitude = 0.0))
      val d = expectedDay(july, latitude)
      assertEquals(sin(2 * PI * d / 365.2425), f[V3.DAY_SIN], 1e-12)
      assertEquals(cos(2 * PI * d / 365.2425), f[V3.DAY_COS], 1e-12)
    }
    val north = extract(issue = july, cleaning = cleaning(july, latitude = 45.0, longitude = 0.0))
    val south = extract(issue = july, cleaning = cleaning(july, latitude = -45.0, longitude = 0.0))
    // Mezza stagione di distanza: segni opposti (a meno di due giorni).
    assertEquals(-north[V3.DAY_SIN], south[V3.DAY_SIN], 0.03)
    assertEquals(-north[V3.DAY_COS], south[V3.DAY_COS], 0.03)
    // Senza coordinate la latitudine e' zero: emisfero nord.
    val none = extract(issue = july, cleaning = cleaning(july))
    assertEquals(north[V3.DAY_SIN], none[V3.DAY_SIN], 1e-12)
    // Seno e coseno stanno sul cerchio.
    for (f in listOf(north, south, none)) assertEquals(1.0, f[V3.DAY_SIN] * f[V3.DAY_SIN] + f[V3.DAY_COS] * f[V3.DAY_COS], 1e-9)
  }

  @Test
  fun `la convezione pomeridiana e' estate per pomeriggio`() {
    fun convection(text: String, latitude: Double = 45.0, longitude: Double = 0.0): Double {
      val t = utc(text)
      return extract(issue = t, cleaning = cleaning(t, latitude = latitude, longitude = longitude))[V3.CONVECTION]
    }
    // Il formula, ricalcolata a mano per un pomeriggio di luglio a Greenwich.
    val t = utc("2023-07-15T15:00:00Z")
    val d = expectedDay(t, 45.0)
    assertEquals(max(0.0, cos(2 * PI * (d - 196.0) / 365.2425)) * 1.0, convection("2023-07-15T15:00:00Z"), 1e-12)
    assertTrue(convection("2023-07-15T15:00:00Z") > 0.99)
    // Di notte d'estate: zero. D'inverno nel pomeriggio: zero.
    assertEquals(0.0, convection("2023-07-15T02:00:00Z"), 0.0)
    assertEquals(0.0, convection("2023-01-15T15:00:00Z"), 0.0)
    // A sud gli stessi giorni si scambiano: luglio e' inverno, gennaio estate.
    assertEquals(0.0, convection("2023-07-15T15:00:00Z", latitude = -30.0), 0.0)
    assertTrue(convection("2023-01-15T15:00:00Z", latitude = -30.0) > 0.98)
    // L'ora e' solare: a 90 gradi est le 15 solari sono le 09 UTC.
    assertTrue(convection("2023-07-15T09:00:00Z", longitude = 90.0) > 0.99)
    assertEquals(0.0, convection("2023-07-15T15:00:00Z", longitude = 90.0), 1e-9)
  }

  // ---------------------------------------------------------------------------- baseline come feature

  @Test
  fun `le colonne di baseline sono esattamente quelle di LocalPriors`() {
    val ctx = context(rain = listOf(1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0))
    val cleaning = cleaning(pressureAt = { h -> 1013.0 - 0.8 * h / 3.0 })
    val f = V3.extract(cleaning, ctx, 1013.0, issue, local)!!
    val trend = f[1]
    assertFalse(trend.isNaN())
    for ((w, window) in RainWindows.ALL.withIndex()) {
      assertEquals(WindowClimatology.logitOf(local.climatology(window, issue)), f[V3.CLIMATOLOGY + w], 0.0)
      assertEquals(
        WindowClimatology.logitOf(local.persistence(window, issue, 1.0, ctx.slotEndMillis!!)!!),
        f[V3.PERSISTENCE + w],
        0.0,
      )
      assertEquals(WindowClimatology.logitOf(local.barometric(window, issue, trend)), f[V3.BAROMETRIC_RULE + w], 0.0)
    }
    assertEquals(1.0, f[V3.LOCAL_TABLES], 0.0)
  }

  @Test
  fun `senza la pioggia di adesso la persistenza dice la climatologia`() {
    val noRain = context(rain = listOf(null, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
    val f = extract(noRain)
    for (w in 0..2) {
      assertFalse(f[V3.PERSISTENCE + w].isNaN())
      assertEquals(f[V3.CLIMATOLOGY + w], f[V3.PERSISTENCE + w], 0.0)
    }
    // Un contesto v2 (senza slot) non sa da che ora: stessa cosa.
    val legacy = extract(NowcastContext(rainLastHourMm = 2.0, relativeHumidityPercent = 70.0))
    for (w in 0..2) assertEquals(legacy[V3.CLIMATOLOGY + w], legacy[V3.PERSISTENCE + w], 0.0)
  }

  @Test
  fun `la persistenza legge il ritardo del contesto`() {
    val rain = listOf(1.0, 1.0, 1.0, 0.0, 0.0, 0.0, 0.0)
    val recent = extract(context(fetchedAt = issue - 20 * 60_000L, rain = rain))
    val old = extract(context(fetchedAt = issue - 11 * hour, rain = rain))
    // Stessa pioggia di adesso, ma undici ore fa: la persistenza e' molto meno sicura.
    assertTrue(recent[V3.PERSISTENCE] > old[V3.PERSISTENCE] + 0.5)
    // Oltre tredici ore il ritardo non ha fascia: ripiega sulla climatologia.
    val ancient = extract(context(fetchedAt = issue - 20 * hour, rain = rain))
    assertEquals(ancient[V3.CLIMATOLOGY], ancient[V3.PERSISTENCE], 0.0)
  }

  @Test
  fun `le tabelle di tutti i posti rispondono dove non si conosce il posto`() {
    val f = extract(null, priors = pooledOnly)
    assertEquals(0.0, f[V3.LOCAL_TABLES], 0.0)
    val window = RainWindows.ZERO_ONE
    assertEquals(WindowClimatology.logitOf(pooledOnly.pooled.constantRate(window.label)!!), f[V3.CLIMATOLOGY], 0.0)
    // Nessuna stagione ne' ora: la climatologia e' la stessa a qualunque emissione.
    val later = extract(null, priors = pooledOnly, issue = issue + 6 * hour)
    assertEquals(f[V3.CLIMATOLOGY], later[V3.CLIMATOLOGY], 0.0)
    // Col locale, invece, il flag e' 1 e le tabelle sono un'altra cosa dal globale.
    val l = extract(null, priors = local)
    assertEquals(1.0, l[V3.LOCAL_TABLES], 0.0)
    assertTrue(l[V3.BAROMETRIC_RULE] != f[V3.BAROMETRIC_RULE] || l[V3.CLIMATOLOGY] != f[V3.CLIMATOLOGY])
  }

  @Test
  fun `la regola barometrica legge la tendenza misurata dal telefono`() {
    val falling = V3.extract(cleaning(pressureAt = { h -> 1013.0 - h * 0.5 }), null, 1013.0, issue, local)!!
    val steady = V3.extract(cleaning(pressureAt = { 1013.0 }), null, 1013.0, issue, local)!!
    assertTrue(falling[1] < -0.3)
    assertEquals(-0.5, falling[1], 0.1)
    // Nel mondo sintetico un crollo di pressione precede la pioggia: la regola lo sa.
    assertTrue(falling[V3.BAROMETRIC_RULE + 1] > steady[V3.BAROMETRIC_RULE + 1])
  }

  // ---------------------------------------------------------------------------- livello e stato dei dati

  @Test
  fun `il livello del mare e' rispetto allo standard, con il bias del banco, NaN senza quota di riferimento`() {
    val cleaning = cleaning(pressureAt = { 1009.0 })
    val latest = cleaning.latest!!.levelHpa
    assertEquals(latest - 1013.25, V3.extract(cleaning, null, null, issue, local)!![V3.SEA_LEVEL], 1e-12)
    assertEquals(latest - 1013.25 + 1.5, V3.extract(cleaning, null, null, issue, local, levelBiasHpa = 1.5)!![V3.SEA_LEVEL], 1e-12)
    assertTrue(V3.extract(cleaning, null, null, issue, local, referenceAltitudeKnown = false)!![V3.SEA_LEVEL].isNaN())
    // Il bias non sporca nessun'altra colonna.
    val base = V3.extract(cleaning, null, null, issue, local)!!
    val biased = V3.extract(cleaning, null, null, issue, local, levelBiasHpa = -2.0)!!
    for (i in 0 until V3.COUNT) if (i != V3.SEA_LEVEL) assertEquals(base[i].toRawBits(), biased[i].toRawBits())
  }

  @Test
  fun `la normale assente e' un flag, e l'anomalia diventa NaN`() {
    val withNormal = extract(null, normal = 1013.0)
    val without = extract(null, normal = null)
    assertEquals(0.0, withNormal[V3.NORMAL_MISSING], 0.0)
    assertEquals(1.0, without[V3.NORMAL_MISSING], 0.0)
    assertFalse(withNormal[5].isNaN())
    assertTrue(without[5].isNaN())
  }

  @Test
  fun `il vettore e' deterministico e non dipende dall'ordine delle chiamate`() {
    val ctx = context()
    val a = extract(ctx)
    val b = extract(ctx)
    for (i in 0 until V3.COUNT) assertEquals(a[i].toRawBits(), b[i].toRawBits())
    assertNotNull(a)
  }
}
