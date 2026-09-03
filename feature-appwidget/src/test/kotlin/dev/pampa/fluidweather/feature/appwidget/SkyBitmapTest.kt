package dev.pampa.fluidweather.feature.appwidget

import dev.pampa.fluidweather.core.model.DayPhase
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La fase del giorno che decide il cielo del widget.
 *
 * Solo la parte aritmetica: il disegno della bitmap tocca `android.graphics`, che in un test JVM
 * non esiste. Ma la decisione "che ora del giorno e'" e' proprio quella che puo' sbagliare in
 * silenzio — un widget con il cielo di mezzogiorno alle undici di sera si nota subito, e a quel
 * punto e' gia' sul telefono di qualcuno.
 */
class SkyBitmapTest {

  private val zone = ZoneId.of("Europe/Rome")

  /** 15 giugno 2026, mezzogiorno a Roma. */
  private val mezzogiorno = 1_781_517_600_000L

  @Test
  fun `con le coordinate si usa l'effemeride vera`() {
    // Firenze a mezzogiorno di giugno: il sole e' alto, non c'e' niente da indovinare.
    assertEquals(DayPhase.DAY, SkyBitmap.phaseOf(43.8, 11.2, mezzogiorno, zone))
  }

  @Test
  fun `sopra il circolo polare l'effemeride vince sull'orologio`() {
    // Tromso a meta' giugno: a mezzanotte il sole non tramonta, sta basso sull'orizzonte. Il cielo
    // giusto e' quello del crepuscolo, non il nero pieno che direbbe l'orologio. E' esattamente il
    // motivo per cui le coordinate hanno la precedenza quando ci sono.
    val mezzanotte = mezzogiorno + 12 * 3_600_000L

    val conCoordinate = SkyBitmap.phaseOf(69.65, 18.96, mezzanotte, zone)
    val soloOrologio = SkyBitmap.phaseOf(null, null, mezzanotte, zone)

    assertEquals(DayPhase.NIGHT, soloOrologio)
    assertTrue(
      "l'effemeride dice $conCoordinate: sotto il sole di mezzanotte non e' notte",
      conCoordinate != DayPhase.NIGHT,
    )
  }

  @Test
  fun `senza coordinate si ripiega sull'orologio, grossolano ma mai spento`() {
    assertEquals(DayPhase.DAY, SkyBitmap.phaseOf(null, null, mezzogiorno, zone))
    assertEquals(DayPhase.NIGHT, SkyBitmap.phaseOf(null, null, mezzogiorno + 12 * 3_600_000L, zone))
    assertEquals(DayPhase.DAWN, SkyBitmap.phaseOf(null, null, mezzogiorno - 5 * 3_600_000L, zone))
    assertEquals(DayPhase.DUSK, SkyBitmap.phaseOf(null, null, mezzogiorno + 7 * 3_600_000L, zone))
  }

  @Test
  fun `una coordinata sola non basta`() {
    // Meta' informazione e' peggio di niente: si ripiega, non si indovina la longitudine.
    assertEquals(DayPhase.DAY, SkyBitmap.phaseOf(43.8, null, mezzogiorno, zone))
    assertEquals(DayPhase.DAY, SkyBitmap.phaseOf(null, 11.2, mezzogiorno, zone))
  }

  @Test
  fun `la bitmap resta minuscola`() {
    // Il budget di un RemoteViews e' ~1,5 MB per aggiornamento: una bitmap a piena dimensione se
    // lo mangerebbe quasi tutto e il launcher taglierebbe il widget senza dire niente.
    val byteStimati = SkyBitmap.WIDTH_PX * SkyBitmap.HEIGHT_PX * 4

    assertTrue("la bitmap del cielo e' cresciuta: $byteStimati byte", byteStimati < 32 * 1024)
  }
}
