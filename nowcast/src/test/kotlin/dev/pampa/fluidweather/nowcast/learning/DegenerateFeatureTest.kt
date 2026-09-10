package dev.pampa.fluidweather.nowcast.learning

import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il baco che questo test tiene chiuso, per esteso.
 *
 * Il banco addestrava su campioni orari perfettamente regolari, quindi il filtro convergeva
 * sempre allo stesso sigma e la feature "incertezza della tendenza" era una costante. La sua
 * deviazione finiva sul pavimento del trainer, 1e-6. Poi `Analogs.standardize` divideva per
 * quella deviazione, e la guardia guardava solo `<= 0.0`: sul telefono, dove il sigma varia
 * eccome, quella singola dimensione valeva 10^5 e si mangiava la distanza euclidea intera. Gli
 * "analoghi storici" non ordinavano piu' per situazione barica: ordinavano per densita' di
 * campionamento. E poi la loro frequenza entrava nel verdetto con peso fino a 0,38.
 *
 * La feature colpevole non c'e' piu', ma la guardia resta: il prossimo che aggiunge una feature
 * costante non deve poter rifare la stessa cosa.
 */
class DegenerateFeatureTest {

  private val featureCount = FeatureExtractor.names.size

  private fun vector(vararg values: Pair<Int, Double>): DoubleArray =
    DoubleArray(featureCount).also { array -> values.forEach { (i, v) -> array[i] = v } }

  @Test
  fun `una feature senza varianza non entra nella distanza`() {
    val means = DoubleArray(featureCount)
    val sds = DoubleArray(featureCount) { 1.0 }
    // L'indice 5 (anomalia-livello) e' fra quelli che gli analoghi guardano: lo si rende degenere.
    sds[5] = 1e-6

    val query = Analogs.standardize(vector(5 to 1.0), means, sds)
    val other = Analogs.standardize(vector(5 to -1.0), means, sds)

    assertEquals(0.0, Analogs.distance(query, other), 1e-12)
  }

  @Test
  fun `senza la guardia la stessa coppia disterebbe duemilioni`() {
    // La prova che il numero non e' teorico: e' quello che il modello spedito aveva davvero.
    val degenerate = (1.0 - (-1.0)) / 1e-6
    assertTrue("la scala del baco: $degenerate", degenerate > 1e6)
  }

  @Test
  fun `le feature normali continuano a contare`() {
    val means = DoubleArray(featureCount)
    val sds = DoubleArray(featureCount) { 1.0 }

    val query = Analogs.standardize(vector(1 to 1.0), means, sds)
    val other = Analogs.standardize(vector(1 to -1.0), means, sds)

    assertEquals(2.0, Analogs.distance(query, other), 1e-9)
  }

  @Test
  fun `gli analoghi guardano solo grandezze bariche e l'ora`() {
    // Se qualcuno ci infila una feature che senza rete non esiste, gli analoghi smettono di
    // raccontare "cio' che il barometro da solo ha visto" e diventano un'altra cosa.
    val baric = setOf(
      "tendenza-1h", "tendenza-3h", "tendenza-6h", "tendenza-12h",
      "accelerazione-3h", "anomalia-livello", "caduta-3h", "ora-sin", "ora-cos",
    )
    val looked = Analogs.featureIndices.map { FeatureExtractor.names[it] }.toSet()
    assertEquals(baric, looked)
  }
}
