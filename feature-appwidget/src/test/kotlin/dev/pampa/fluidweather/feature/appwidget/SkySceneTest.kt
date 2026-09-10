package dev.pampa.fluidweather.feature.appwidget

import dev.pampa.fluidweather.core.model.DayPhase
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La fase del giorno che decide il cielo del widget, e il budget delle bitmap.
 *
 * Solo le parti aritmetiche: il disegno tocca `android.graphics`, che in un test JVM non esiste.
 * Ma la decisione "che ora del giorno e'" e' proprio quella che puo' sbagliare in silenzio — un
 * widget con il cielo di mezzogiorno alle undici di sera si nota subito, e a quel punto e' gia'
 * sul telefono di qualcuno.
 */
class SkySceneTest {

  private val zone = ZoneId.of("Europe/Rome")

  /** 15 giugno 2026, mezzogiorno a Roma. */
  private val mezzogiorno = 1_781_517_600_000L

  @Test
  fun `con le coordinate si usa l'effemeride vera`() {
    // Firenze a mezzogiorno di giugno: il sole e' alto, non c'e' niente da indovinare.
    assertEquals(DayPhase.DAY, SkyScene.phaseOf(43.8, 11.2, mezzogiorno, zone))
  }

  @Test
  fun `sopra il circolo polare l'effemeride vince sull'orologio`() {
    // Tromso a meta' giugno: a mezzanotte il sole non tramonta, sta basso sull'orizzonte. Il cielo
    // giusto e' quello del crepuscolo, non il nero pieno che direbbe l'orologio. E' esattamente il
    // motivo per cui le coordinate hanno la precedenza quando ci sono.
    val mezzanotte = mezzogiorno + 12 * 3_600_000L

    val conCoordinate = SkyScene.phaseOf(69.65, 18.96, mezzanotte, zone)
    val soloOrologio = SkyScene.phaseOf(null, null, mezzanotte, zone)

    assertEquals(DayPhase.NIGHT, soloOrologio)
    assertTrue(
      "l'effemeride dice $conCoordinate: sotto il sole di mezzanotte non e' notte",
      conCoordinate != DayPhase.NIGHT,
    )
  }

  @Test
  fun `senza coordinate si ripiega sull'orologio, grossolano ma mai spento`() {
    assertEquals(DayPhase.DAY, SkyScene.phaseOf(null, null, mezzogiorno, zone))
    assertEquals(DayPhase.NIGHT, SkyScene.phaseOf(null, null, mezzogiorno + 12 * 3_600_000L, zone))
    assertEquals(DayPhase.DAWN, SkyScene.phaseOf(null, null, mezzogiorno - 5 * 3_600_000L, zone))
    assertEquals(DayPhase.DUSK, SkyScene.phaseOf(null, null, mezzogiorno + 7 * 3_600_000L, zone))
  }

  @Test
  fun `una coordinata sola non basta`() {
    // Meta' informazione e' peggio di niente: si ripiega, non si indovina la longitudine.
    assertEquals(DayPhase.DAY, SkyScene.phaseOf(43.8, null, mezzogiorno, zone))
    assertEquals(DayPhase.DAY, SkyScene.phaseOf(null, 11.2, mezzogiorno, zone))
  }

  @Test
  fun `le tre bitmap insieme stanno nel budget di un RemoteViews`() {
    // Il budget e' ~1,5 MB per aggiornamento, e con SizeMode.Responsive le tre taglie viaggiano
    // INSIEME: contarne una sola sarebbe il modo di scoprire il limite dal launcher, che taglia il
    // widget senza dire niente. Mezzo megabyte lascia spazio a tutto il resto delle RemoteViews.
    val totale = SkyScene.totalBytes()

    assertTrue("le bitmap del cielo pesano $totale byte in tutto", totale < 512 * 1024)
  }

  @Test
  fun `ogni taglia ha le proporzioni della sua cella`() {
    // Una bitmap quadrata stirata su una cella 4x1 fa una scena schiacciata, e con ContentScale.Crop
    // fa una scena tagliata: le proporzioni giuste sono l'unico modo di non dover scegliere.
    val (largheggaPiccola, altezzaPiccola) = SkyScene.sizeFor(AppWidgetTier.SMALL)
    val (_, altezzaMedia) = SkyScene.sizeFor(AppWidgetTier.MEDIUM)
    val (_, altezzaGrande) = SkyScene.sizeFor(AppWidgetTier.LARGE)

    assertEquals(largheggaPiccola, altezzaPiccola)
    assertTrue("la media e' larga e bassa", altezzaMedia < altezzaGrande)
    assertTrue("la grande non e' piu' alta che larga", altezzaGrande < largheggaPiccola)
  }
}
