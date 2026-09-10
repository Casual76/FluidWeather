package dev.pampa.fluidweather.nowcast.cleaning

import dev.pampa.fluidweather.core.model.ActivityKind
import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.core.model.SampleSource
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La taratura di chi non sta fermo.
 *
 * Il caso che ha fatto riscrivere tutto: durante i dieci minuti della raffica si passa per tre
 * paesi a quote diverse. Prima la stima riduceva al mare la mediana di TUTTA la raffica con una
 * quota sola, e la confrontava con **un riferimento unico chiesto alla fine** — quello dell'ultimo
 * paese. Il numero che ne usciva non descriveva nessuno dei tre posti, e nessuno poteva
 * accorgersene: un bias sbagliato di un hPa somiglia moltissimo a un bias giusto.
 */
class CalibrationSegmentsTest {

  private val inizio = 1_781_517_600_000L

  /**
   * Il bias vero del sensore di questo telefono finto: +1,80 hPa **al livello del mare**, che e'
   * il livello a cui la taratura definisce il proprio numero.
   */
  private val biasVero = 1.80

  /**
   * Tre paesi, tre pressioni al mare diverse: non e' una cattiveria del test, e' cosa succede
   * davvero quando ci si sposta di qualche decina di chilometri mentre passa un fronte.
   */
  private val marePrimo = 1013.0
  private val mareSecondo = 1011.6
  private val mareTerzo = 1010.4

  /** Quanto la riduzione al mare moltiplica la pressione letta a quella quota. */
  private fun fattore(quotaMetri: Double): Double = SeaLevel.reduce(1.0, quotaMetri, 15.0)

  /** La lettura che darebbe questo sensore, a quella quota, con quel mare vero sopra la testa. */
  private fun lettura(quotaMetri: Double, mare: Double): Double = (mare + biasVero) / fattore(quotaMetri)

  private fun campione(
    secondo: Int,
    quotaMetri: Double,
    mare: Double,
    latitudine: Double = 43.80,
    attivita: ActivityKind = ActivityKind.STILL,
  ) = PressureSample(
    timestampMillis = inizio + secondo * 1_000L,
    pressureHpa = lettura(quotaMetri, mare),
    source = SampleSource.CALIBRATION,
    burstId = "raffica",
    altitudeMeters = quotaMetri,
    latitude = latitudine,
    longitude = 11.2,
    activity = attivita,
    activityConfidence = 90,
  )

  /** Le tre soste, con in mezzo i due tratti in macchina. */
  private fun ilGiroDeiTrePaesi(): List<PressureSample> = buildList {
    (0 until 120).forEach { add(campione(it, 50.0, marePrimo)) }
    (120 until 200).forEach {
      val avanzamento = it - 120
      add(
        campione(
          secondo = it,
          quotaMetri = 50.0 + avanzamento * 3.0,
          mare = marePrimo,
          latitudine = 43.80 + avanzamento * 0.0009,
          attivita = ActivityKind.IN_VEHICLE,
        ),
      )
    }
    (200 until 320).forEach { add(campione(it, 290.0, mareSecondo, latitudine = 43.87)) }
    (320 until 400).forEach {
      val avanzamento = it - 320
      add(
        campione(
          secondo = it,
          quotaMetri = 290.0 + avanzamento * 2.0,
          mare = mareSecondo,
          latitudine = 43.87 + avanzamento * 0.0009,
          attivita = ActivityKind.IN_VEHICLE,
        ),
      )
    }
    (400 until 540).forEach { add(campione(it, 450.0, mareTerzo, latitudine = 43.94)) }
  }

  private fun riferimentoDi(quando: Long, mare: Double, latitudine: Double) = CalibrationReferenceSample(
    atMillis = quando,
    mslHpa = mare,
    temperatureCelsius = 15.0,
    latitude = latitudine,
    longitude = 11.2,
  )

  @Test
  fun `tre paesi a tre quote diventano tre tratti, e i viaggi si buttano`() {
    val tratti = CalibrationSegments.of(ilGiroDeiTrePaesi())

    assertEquals("tre soste, tre tratti", 3, tratti.size)
    assertEquals(50.0, tratti[0].altitudeMeters!!, 0.001)
    assertEquals(290.0, tratti[1].altitudeMeters!!, 0.001)
    assertEquals(450.0, tratti[2].altitudeMeters!!, 0.001)
    // Nessun tratto contiene i minuti in macchina: sarebbero centosessanta secondi in piu'.
    assertTrue(tratti.all { it.durationSeconds <= 141 })
  }

  @Test
  fun `il bias viene fuori giusto anche passando per tre paesi`() {
    val tratti = CalibrationSegments.of(ilGiroDeiTrePaesi())
    val riferimenti = listOf(
      riferimentoDi(inizio + 60_000L, marePrimo, 43.80),
      riferimentoDi(inizio + 260_000L, mareSecondo, 43.87),
      riferimentoDi(inizio + 470_000L, mareTerzo, 43.94),
    )

    val record = CalibrationMath.estimateSegments(tratti, riferimenti, inizio + 600_000L)

    assertNotNull(record)
    assertEquals("ogni tratto col suo riferimento, e il bias e' quello vero", biasVero, record!!.biasHpa, 0.05)
    assertEquals(3, record.segmentCount)
    assertTrue("tre tratti concordi: dispersione minima, ${record.spreadHpa}", record.spreadHpa < 0.05)
  }

  @Test
  fun `la vecchia strada, un riferimento solo alla fine, sbaglia di un hPa`() {
    // Il test che spiega perche' il cambio serviva: e' esattamente cio' che faceva `estimate`, con
    // i campioni cosi' come stavano in archivio e la pressione al mare dell'ULTIMO posto.
    val campioni = ilGiroDeiTrePaesi()

    val vecchio = CalibrationMath.estimate(
      stationPressures = campioni.map { it.pressureHpa },
      altitudeMeters = CalibrationMath.median(campioni.mapNotNull { it.altitudeMeters }),
      temperatureCelsius = 15.0,
      referenceMslHpa = mareTerzo,
      nowMillis = inizio + 600_000L,
    )

    assertNotNull(vecchio)
    val errore = abs(vecchio!!.biasHpa - biasVero)
    assertTrue("la vecchia strada sbagliava di $errore hPa", errore > 0.8)
  }

  @Test
  fun `un tratto troppo corto non e' un tratto`() {
    val campioni = (0 until 30).map { campione(it, 50.0, marePrimo) }

    assertTrue(CalibrationSegments.of(campioni).isEmpty())
  }

  @Test
  fun `senza posizione non si segmenta, si torna a essere una raffica sola`() {
    // Un telefono senza permesso non ha quota ne' coordinate. Li' non c'e' niente da giudicare, e
    // l'assenza di informazione non deve diventare un sospetto: la taratura si comporta come ha
    // sempre fatto, un tratto solo, e la fiducia lo dichiara come ha sempre fatto.
    val campioni = (0 until 300).map {
      campione(it, 0.0, marePrimo).copy(altitudeMeters = null, latitude = null, longitude = null)
    }

    val tratti = CalibrationSegments.of(campioni)

    assertEquals(1, tratti.size)
    assertEquals(null, tratti.first().altitudeMeters)
  }

  @Test
  fun `il tempo utile conta solo i tratti validi`() {
    val campioni = buildList {
      (0 until 120).forEach { add(campione(it, 50.0, marePrimo)) }
      (120 until 400).forEach { add(campione(it, 50.0, marePrimo, attivita = ActivityKind.IN_VEHICLE)) }
    }

    val utile = CalibrationSegments.usefulSeconds(CalibrationSegments.of(campioni))

    assertTrue("i minuti in auto non contano: $utile s", utile in 100..130)
  }

  @Test
  fun `una lettura sbagliata in un tratto non sposta il bias degli altri`() {
    // Mediana pesata e non media pesata: e' questa la ragione.
    val tratti = listOf(
      CalibrationSegment(inizio, inizio + 120_000L, List(120) { lettura(50.0, marePrimo) }, 50.0, 43.80, 11.2),
      CalibrationSegment(inizio + 200_000L, inizio + 320_000L, List(120) { lettura(290.0, marePrimo) }, 290.0, 43.81, 11.2),
      // Il terzo ha preso una craniata: dieci hPa fuori.
      CalibrationSegment(inizio + 400_000L, inizio + 540_000L, List(140) { lettura(450.0, marePrimo) + 10.0 }, 450.0, 43.82, 11.2),
    )
    val riferimenti = listOf(riferimentoDi(inizio + 270_000L, marePrimo, 43.81))

    val record = CalibrationMath.estimateSegments(tratti, riferimenti, inizio + 600_000L)

    assertNotNull(record)
    assertEquals(biasVero, record!!.biasHpa, 0.2)
    assertTrue("la dispersione denuncia il tratto sbagliato: ${record.spreadHpa}", record.spreadHpa > 1.0)
  }

  @Test
  fun `la fiducia sale con la concordanza e scende con la discordia`() {
    val concordi = CalibrationMath.agreementFactor(segmentCount = 3, spreadHpa = 0.05)
    val discordi = CalibrationMath.agreementFactor(segmentCount = 3, spreadHpa = 2.0)
    val unoSolo = CalibrationMath.agreementFactor(segmentCount = 1, spreadHpa = 0.0)

    assertTrue("tre tratti d'accordo valgono piu' di uno solo", concordi > unoSolo)
    assertEquals("con un tratto solo non c'e' concordanza da misurare", 1.0, unoSolo, 0.0)
    assertEquals("tre tratti che si contraddicono non prendono premi", 1.0, discordi, 0.0)
  }

  @Test
  fun `senza riferimenti non si inventa niente`() {
    val tratti = listOf(
      CalibrationSegment(inizio, inizio + 120_000L, List(120) { 1000.0 }, 50.0, 43.8, 11.2),
    )

    assertEquals(null, CalibrationMath.estimateSegments(tratti, emptyList(), inizio))
    assertEquals(null, CalibrationMath.estimateSegments(emptyList(), emptyList(), inizio))
  }

  @Test
  fun `il riferimento piu' vicino nel tempo e nello spazio vince`() {
    val tratto = CalibrationSegment(
      fromMillis = inizio,
      toMillis = inizio + 120_000L,
      pressures = List(120) { lettura(50.0, marePrimo) },
      altitudeMeters = 50.0,
      latitude = 43.80,
      longitude = 11.2,
    )
    val vicino = riferimentoDi(inizio + 60_000L, marePrimo, 43.80)
    val lontano = CalibrationReferenceSample(inizio - 5 * 3_600_000L, marePrimo + 6.0, 15.0, 45.0, 9.0)

    val record = CalibrationMath.estimateSegments(listOf(tratto), listOf(lontano, vicino), inizio + 120_000L)

    assertNotNull(record)
    assertEquals("il riferimento di qui e adesso, non quello di ieri a Milano", biasVero, record!!.biasHpa, 0.05)
  }
}
