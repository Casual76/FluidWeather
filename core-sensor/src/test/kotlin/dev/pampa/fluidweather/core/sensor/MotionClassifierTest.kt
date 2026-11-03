package dev.pampa.fluidweather.core.sensor

import dev.pampa.fluidweather.core.model.ActivityKind
import dev.pampa.fluidweather.core.model.LastFix
import dev.pampa.fluidweather.nowcast.cleaning.TransitRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Senza Google Play Services il telefono non ha chi gli dice che e' in auto: lo si deduce dai passi,
 * dall'accelerometro e dalla velocita' fra due fix. Le soglie sono quelle scelte nel classificatore
 * e questi test sono il loro contratto: la pulizia scarta solo cio' che qui e' *in viaggio* con
 * confidenza, e nel dubbio la risposta e' "non so", che non scarta mai.
 */
class MotionClassifierTest {

  private fun features(
    steps: Int = 0,
    stepSensor: Boolean = true,
    std: Double? = 1.5,
    speed: Double? = null,
    window: Double = 4.0,
  ) = MotionFeatures(steps, window, stepSensor, std, speed)

  @Test
  fun `una corsa e' una corsa, e vince su tutto`() {
    // 10 passi in 4 secondi = 150 al minuto.
    val found = MotionClassifier.classify(features(steps = 10, speed = 4.0))

    assertEquals(ActivityKind.RUNNING, found.kind)
    assertFalse(TransitRule.isInTransit(found.kind, found.confidence))
  }

  @Test
  fun `chi cammina in treno cammina`() {
    // 4 passi in 4 secondi = 60 al minuto, mentre la posizione dice 25 m/s.
    val found = MotionClassifier.classify(features(steps = 4, speed = 25.0))

    assertEquals(ActivityKind.WALKING, found.kind)
    assertFalse(TransitRule.isInTransit(found.kind, found.confidence))
  }

  @Test
  fun `senza passi e a 70 all'ora e' un veicolo, e la pulizia lo scarta`() {
    val found = MotionClassifier.classify(features(steps = 0, speed = 19.4))

    assertEquals(ActivityKind.IN_VEHICLE, found.kind)
    assertTrue(TransitRule.isInTransit(found.kind, found.confidence))
  }

  @Test
  fun `senza passi a 20 all'ora si viaggia comunque, e la pulizia lo scarta`() {
    val found = MotionClassifier.classify(features(steps = 0, speed = 5.5))

    assertEquals(ActivityKind.ON_BICYCLE, found.kind)
    assertTrue(TransitRule.isInTransit(found.kind, found.confidence))
  }

  @Test
  fun `a passo d'uomo senza passi non e' un viaggio`() {
    val found = MotionClassifier.classify(features(steps = 0, speed = 1.4, std = 0.4))

    assertFalse(TransitRule.isInTransit(found.kind, found.confidence))
  }

  @Test
  fun `un telefono appoggiato e senza velocita' e' fermo`() {
    val found = MotionClassifier.classify(features(steps = 0, std = 0.03, speed = null))

    assertEquals(ActivityKind.STILL, found.kind)
    assertTrue(found.confidence >= TransitRule.MIN_ACTIVITY_CONFIDENCE)
  }

  @Test
  fun `la velocita' bassa non toglie il fermo, quella alta si`() {
    assertEquals(ActivityKind.STILL, MotionClassifier.classify(features(std = 0.03, speed = 0.2)).kind)
    assertEquals(ActivityKind.UNKNOWN, MotionClassifier.classify(features(std = 0.03, speed = 2.0)).kind)
  }

  @Test
  fun `nel dubbio la risposta e' non so, con confidenza zero`() {
    // Il telefono in mano, fermo ma mosso dalle dita, senza velocita'.
    val found = MotionClassifier.classify(features(steps = 0, std = 0.9, speed = null))

    assertEquals(ActivityKind.UNKNOWN, found.kind)
    assertEquals(0, found.confidence)
    assertFalse(TransitRule.isInTransit(found.kind, found.confidence))
  }

  @Test
  fun `senza rilevatore di passi zero passi non vuol dire fermo`() {
    // Il telefono in tasca: l'accelerometro trema, i passi non si contano, la velocita' non c'e'.
    val found = MotionClassifier.classify(features(steps = 0, stepSensor = false, std = 1.2, speed = null))

    assertEquals(ActivityKind.UNKNOWN, found.kind)
  }

  @Test
  fun `senza accelerometro ne' velocita' non si dice niente`() {
    val found = MotionClassifier.classify(features(std = null, speed = null))

    assertEquals(ActivityKind.UNKNOWN, found.kind)
    assertEquals(0, found.confidence)
  }

  @Test
  fun `la finestra vuota non divide per zero`() {
    val found = MotionClassifier.classify(features(steps = 5, window = 0.0, std = null))

    assertEquals(ActivityKind.UNKNOWN, found.kind)
  }

  // ---- la statistica dell'accelerometro

  @Test
  fun `la deviazione standard di Welford torna con quella fatta a mano`() {
    val stats = RunningStats()
    listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0).forEach(stats::add)

    // Campionaria: sqrt(32 / 7).
    assertEquals(Math.sqrt(32.0 / 7.0), stats.standardDeviation()!!, 1e-9)
    assertEquals(8, stats.size())
  }

  @Test
  fun `con meno di due valori non c'e' deviazione`() {
    val stats = RunningStats()
    assertNull(stats.standardDeviation())
    stats.add(9.81)
    assertNull(stats.standardDeviation())
    stats.add(9.81)
    assertEquals(0.0, stats.standardDeviation()!!, 1e-12)
  }

  // ---- la velocita' fra due fix

  private val bari = LastFix(41.1171, 16.8719, 0L)

  @Test
  fun `la distanza fra Bari e Molfetta e' quella vera`() {
    // Molfetta e' a circa 25 km da Bari.
    val meters = TravelSpeed.distanceMeters(41.1171, 16.8719, 41.2008, 16.5977)
    assertEquals(25_300.0, meters, 1_500.0)
  }

  @Test
  fun `la velocita' media fra due fix e' la distanza sul tempo`() {
    // Dieci minuti verso nord.
    val speed = TravelSpeed.between(bari, 41.1171 + 0.0909, 16.8719, atMillis = 600_000L)

    // 0,0909 gradi di latitudine sono circa 10,1 km in 600 s.
    assertEquals(16.8, speed!!, 0.5)
    assertTrue(speed >= MotionClassifier.VEHICLE_SPEED)
  }

  @Test
  fun `due fix troppo vicini nel tempo non fanno una velocita'`() {
    assertNull(TravelSpeed.between(bari, 41.12, 16.88, atMillis = TravelSpeed.MIN_INTERVAL_MILLIS - 1))
  }

  @Test
  fun `due fix troppo lontani nel tempo non dicono cosa fa il telefono adesso`() {
    assertNull(TravelSpeed.between(bari, 41.2, 16.9, atMillis = TravelSpeed.MAX_INTERVAL_MILLIS + 1))
  }

  @Test
  fun `un fix piu' vecchio del precedente non fa una velocita'`() {
    assertNull(TravelSpeed.between(LastFix(41.1, 16.9, 5_000_000L), 41.2, 16.9, atMillis = 4_000_000L))
  }

  @Test
  fun `restare fermi e' velocita' zero, non assente`() {
    val speed = TravelSpeed.between(bari, bari.latitude, bari.longitude, atMillis = 120_000L)

    assertNotNull(speed)
    assertEquals(0.0, speed!!, 1e-9)
    assertEquals(
      ActivityKind.STILL,
      MotionClassifier.classify(features(std = 0.02, speed = speed)).kind,
    )
  }
}
