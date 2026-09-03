package dev.pampa.fluidweather.nowcast.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Riconoscere un verdetto nato senza il contesto dei provider.
 *
 * Serve a non mescolare due popolazioni nella taratura di Platt: senza contesto sette feature su
 * sedici sono NaN e il modello le imputa alla media, quindi la probabilita' grezza che ne esce ha
 * una distribuzione sua. La marcatura c'era gia' nell'archivio (i NaN si scrivono e si rileggono
 * come tali) e mancava solo chi la leggesse.
 */
class ContextPresenceTest {

  private fun vettore(vararg presenti: Int): DoubleArray {
    val features = DoubleArray(FeatureExtractor.names.size) { Double.NaN }
    presenti.forEach { features[it] = 1.0 }
    return features
  }

  @Test
  fun `un vettore tutto NaN non ha contesto`() {
    assertFalse(FeatureExtractor.hasContext(vettore()))
  }

  @Test
  fun `basta una delle tre feature del contesto`() {
    // Umidita', copertura, vento: le tre che arrivano SEMPRE insieme quando il bundle c'e'.
    assertTrue(FeatureExtractor.hasContext(vettore(7)))
    assertTrue(FeatureExtractor.hasContext(vettore(9)))
    assertTrue(FeatureExtractor.hasContext(vettore(10)))
  }

  @Test
  fun `il solo barometro non conta come contesto`() {
    // Le tendenze (0-4) e l'incertezza (6) ci sono anche senza rete: se contassero, ogni verdetto
    // sembrerebbe completo e la partizione non servirebbe a niente.
    assertFalse(FeatureExtractor.hasContext(vettore(0, 1, 2, 3, 4, 6)))
  }

  @Test
  fun `la rotazione del vento da sola non basta`() {
    // L'indice 11 e' NaN anche COL contesto, quando manca il punto di tre ore fa: guardarlo
    // farebbe scambiare per "senza contesto" dei verdetti che il contesto ce l'avevano.
    assertFalse(FeatureExtractor.hasContext(vettore(11)))
  }

  @Test
  fun `l'anomalia di livello non e' un indizio, e' sempre assente`() {
    // L'indice 5 e' NaN sempre: nessuno passa ancora la normale climatica.
    assertFalse(FeatureExtractor.hasContext(vettore(5)))
  }

  @Test
  fun `un vettore corto non fa esplodere niente`() {
    // Un archivio scritto da una versione con meno feature non deve far cadere la ritaratura.
    assertFalse(FeatureExtractor.hasContext(doubleArrayOf(1.0, 2.0)))
    assertFalse(FeatureExtractor.hasContext(DoubleArray(0)))
  }

  @Test
  fun `gli indici guardati sono davvero quelli del contesto`() {
    // Se qualcuno riordina `names`, questo test lo dice prima che la partizione diventi casuale.
    assertEquals("umidita'", FeatureExtractor.names[7])
    assertEquals("copertura", FeatureExtractor.names[9])
    assertEquals("vento", FeatureExtractor.names[10])
  }
}
