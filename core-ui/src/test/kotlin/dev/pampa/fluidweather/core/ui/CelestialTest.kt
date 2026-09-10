package dev.pampa.fluidweather.core.ui

import dev.pampa.fluidweather.core.model.Moon
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dove sta l'astro, e se c'e'.
 *
 * Il disegno in se' sono quattro cerchi e non ha molto da sbagliare; la decisione **quale** astro
 * e **dove** invece si sbaglia in silenzio, ed e' quella che si vede subito da fuori: un sole a
 * mezzanotte, o una luna piena disegnata la notte di luna nuova.
 */
class CelestialTest {

  private val zone = ZoneId.of("Europe/Rome")
  private val firenzeLat = 43.77
  private val firenzeLon = 11.26

  /** 15 giugno 2026, mezzogiorno a Roma. */
  private val mezzogiorno = 1_781_517_600_000L

  @Test
  fun `a mezzogiorno c'e' il sole, alto`() {
    val astro = Celestial.at(mezzogiorno, firenzeLat, firenzeLon, zone)

    assertNotNull(astro)
    assertEquals(CelestialBody.Kind.SUN, astro!!.kind)
    assertEquals("il sole non ha fasi", 1f, astro.illuminated, 0f)
    assertTrue("a giugno a mezzogiorno il sole e' quasi allo zenit: y=${astro.y}", astro.y < 0.16f)
    assertTrue("ed e' a meta' del suo arco: x=${astro.x}", astro.x in 0.4f..0.65f)
  }

  @Test
  fun `il sole attraversa il cielo da est a ovest`() {
    val mattina = Celestial.at(mezzogiorno - 4 * 3_600_000L, firenzeLat, firenzeLon, zone)!!
    val pomeriggio = Celestial.at(mezzogiorno + 4 * 3_600_000L, firenzeLat, firenzeLon, zone)!!

    assertTrue("la mattina sta a sinistra di mezzogiorno", mattina.x < pomeriggio.x)
    assertTrue("e piu' in basso di mezzogiorno", mattina.y > 0.16f)
  }

  @Test
  fun `di notte il sole non c'e' mai`() {
    // Il baco che si vede da fuori: un sole disegnato alle due di notte.
    val notte = mezzogiorno + 14 * 3_600_000L

    val astro = Celestial.at(notte, firenzeLat, firenzeLon, zone)

    assertTrue("alle due di notte non c'e' il sole", astro?.kind != CelestialBody.Kind.SUN)
  }

  @Test
  fun `la luna si disegna solo quando e' davvero sopra l'orizzonte`() {
    // Ventiquattro ore, un campione all'ora: ogni volta che compare una luna, deve essere una
    // notte e la luna deve essere davvero su. "C'e' la luna" e' un fatto, non un orario.
    val lune = (0 until 24).mapNotNull {
      Celestial.at(mezzogiorno + it * 3_600_000L, firenzeLat, firenzeLon, zone)
    }.filter { it.kind == CelestialBody.Kind.MOON }

    lune.forEach {
      assertTrue("una luna dentro la tela: y=${it.y}", it.y in 0f..1f)
      assertTrue("una luna con una fase sensata: ${it.illuminated}", it.illuminated in 0f..1f)
    }
  }

  @Test
  fun `la notte di luna nuova non si disegna nessuna luna`() {
    // Cercata davvero, non scelta a memoria: si scorre finche' la frazione illuminata non crolla.
    val lunaNuova = (0 until 40 * 24).map { mezzogiorno + it * 3_600_000L }
      .minByOrNull { Moon.illuminatedFraction(it) }!!
    // Le ore intorno, quelle di notte: nessuna deve mostrare una luna.
    val notti = (-12..12).map { lunaNuova + it * 3_600_000L }
      .mapNotNull { Celestial.at(it, firenzeLat, firenzeLon, zone) }

    assertTrue(
      "a luna nuova non c'e' niente da disegnare",
      notti.none { it.kind == CelestialBody.Kind.MOON },
    )
  }

  @Test
  fun `la luna piena si disegna piena`() {
    val lunaPiena = (0 until 40 * 24).map { mezzogiorno + it * 3_600_000L }
      .maxByOrNull { Moon.illuminatedFraction(it) }!!
    val disegnate = (-12..12).map { lunaPiena + it * 3_600_000L }
      .mapNotNull { Celestial.at(it, firenzeLat, firenzeLon, zone) }
      .filter { it.kind == CelestialBody.Kind.MOON }

    assertTrue("una luna piena si vede in qualche momento della notte", disegnate.isNotEmpty())
    disegnate.forEach {
      assertTrue("piena vuol dire piena: ${it.illuminated}", it.illuminated > 0.9f)
    }
  }

  @Test
  fun `senza coordinate si ripiega, ma non si resta senza cielo`() {
    // Un cielo vuoto sarebbe piu' sbagliato di un cielo approssimato: la stessa scelta che fa gia'
    // la fase del giorno quando la posizione non e' ancora arrivata.
    (0 until 24).forEach {
      val astro = Celestial.at(mezzogiorno + it * 3_600_000L, null, null, zone)
      assertNotNull("ora $it senza coordinate", astro)
      assertTrue("dentro la tela", astro!!.x in 0f..1f && astro.y in 0f..1f)
    }
  }

  @Test
  fun `una coordinata sola non basta`() {
    assertNotNull(Celestial.at(mezzogiorno, firenzeLat, null, zone))
    assertNotNull(Celestial.at(mezzogiorno, null, firenzeLon, zone))
  }

  @Test
  fun `l'astro sta sempre nella meta' alta della tela`() {
    // Sotto la meta' ci sono il testo della home e i numeri del widget: un sole disegnato li'
    // sotto non e' un cielo, e' un ostacolo.
    val campioni = (0 until 48).mapNotNull {
      Celestial.at(mezzogiorno + it * 1_800_000L, firenzeLat, firenzeLon, zone)
    }

    assertTrue(campioni.isNotEmpty())
    campioni.forEach { assertTrue("y=${it.y}", it.y in 0.09f..0.47f) }
  }

  @Test
  fun `sotto il sole di mezzanotte non spunta una luna`() {
    // Tromso a meta' giugno: il sole non tramonta. La luna non deve prendere il suo posto solo
    // perche' l'orologio dice mezzanotte.
    val mezzanotte = mezzogiorno + 12 * 3_600_000L

    val astro = Celestial.at(mezzanotte, 69.65, 18.96, zone)

    assertEquals(CelestialBody.Kind.SUN, astro?.kind)
  }

  @Test
  fun `una luna che non e' ne' piena ne' nuova ha un verso`() {
    val crescente = (0 until 40 * 24).map { mezzogiorno + it * 3_600_000L }
      .mapNotNull { Celestial.at(it, firenzeLat, firenzeLon, zone) }
      .filter { it.kind == CelestialBody.Kind.MOON && it.illuminated in 0.2f..0.8f }

    assertTrue("qualche luna a meta' esiste", crescente.isNotEmpty())
    assertTrue("e ce n'e' di crescenti", crescente.any { it.waxing })
    assertTrue("e di calanti", crescente.any { !it.waxing })
  }

  @Test
  fun `una luna sotto l'orizzonte non si disegna`() {
    // Quando ne' il sole ne' la luna sono su, la scena resta senza astro: stelle e basta.
    val senzaAstro = (0 until 40 * 24).map { mezzogiorno + it * 3_600_000L }
      .count { Celestial.at(it, firenzeLat, firenzeLon, zone) == null }

    assertTrue("in quaranta giorni ci sono ore senza niente in cielo: $senzaAstro", senzaAstro > 24)
    assertNull(
      "e sono ore in cui la luna e' davvero giu'",
      (0 until 40 * 24).map { mezzogiorno + it * 3_600_000L }
        .firstOrNull { Celestial.at(it, firenzeLat, firenzeLon, zone) == null }
        ?.let { Celestial.at(it, firenzeLat, firenzeLon, zone) },
    )
  }
}
