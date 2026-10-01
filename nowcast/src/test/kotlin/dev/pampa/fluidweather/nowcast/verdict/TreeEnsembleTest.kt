package dev.pampa.fluidweather.nowcast.verdict

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TreeEnsembleTest {

  /**
   * Un albero a mano: radice su colonna 1 (x <= 0,5 a sinistra, NaN a sinistra), a sinistra una foglia,
   * a destra uno split su colonna 2 (x <= -1 a sinistra, NaN a destra) con due foglie.
   *
   *   0: interno c1 <= 0.5, valore 0.1, NaN a sinistra, destro = 2
   *   1: foglia 0.3
   *   2: interno c2 <= -1, valore -0.2, NaN a destra, destro = 4
   *   3: foglia -0.5
   *   4: foglia 0.05
   */
  private fun tree(): Tree = Tree(
    flags = byteArrayOf(Tree.NAN_LEFT.toByte(), Tree.LEAF.toByte(), 0, Tree.LEAF.toByte(), Tree.LEAF.toByte()),
    values = floatArrayOf(0.1f, 0.3f, -0.2f, -0.5f, 0.05f),
    features = intArrayOf(1, -1, 2, -1, -1),
    thresholds = floatArrayOf(0.5f, Float.NaN, -1f, Float.NaN, Float.NaN),
    rights = intArrayOf(2, -1, 4, -1, -1),
  )

  private fun stump(): Tree = Tree(
    flags = byteArrayOf(0, Tree.LEAF.toByte(), Tree.LEAF.toByte()),
    values = floatArrayOf(0f, -0.25f, 0.25f),
    features = intArrayOf(0, -1, -1),
    thresholds = floatArrayOf(0f, Float.NaN, Float.NaN),
    rights = intArrayOf(2, -1, -1),
  )

  private fun ensemble(offset: Int = 3, offsetMean: Double = -1.5): TreeEnsemble =
    TreeEnsemble(featureCount = 5, tier = 2, window = 1, offsetFeature = offset, baseScore = 0.2, offsetMean = offsetMean, trees = listOf(tree(), stump()))

  private fun x(vararg values: Double) = values

  @Test
  fun `il file si rilegge identico, byte per byte`() {
    val original = ensemble()
    val bytes = original.encode()
    assertEquals(original.encodedSize, bytes.size)
    val again = TreeEnsemble.parse(bytes, 5)
    assertArrayEquals(bytes, again.encode())
    assertEquals(original.baseScore, again.baseScore, 0.0)
    assertEquals(original.offsetFeature, again.offsetFeature)
    assertEquals(2, again.tier)
    assertEquals(1, again.window)
    val probe = x(0.3, 0.9, -2.0, -0.7, 1.0)
    assertEquals(original.score(probe), again.score(probe), 0.0)
  }

  @Test
  fun `il punteggio e' base + ancora + foglie, a mano`() {
    val e = ensemble()
    // c1 = 0.9 > 0.5 -> destra; c2 = -2 <= -1 -> foglia -0.5; stump: c0 = 0.3 > 0 -> 0.25.
    assertEquals(0.2 + (-0.7) + (-0.5f).toDouble() + 0.25, e.score(x(0.3, 0.9, -2.0, -0.7, 1.0)), 1e-9)
  }

  @Test
  fun `il punteggio e' il bias piu' la somma dei contributi`() {
    val e = ensemble()
    val probes = listOf(
      x(0.3, 0.9, -2.0, -0.7, 1.0),
      x(-1.0, 0.1, 5.0, Double.NaN, 0.0),
      x(Double.NaN, Double.NaN, Double.NaN, 2.0, Double.NaN),
      x(0.0, 0.5, -1.0, -1.5, 3.0),
    )
    for (probe in probes) {
      val explanation = e.explain(probe)
      assertEquals(e.score(probe), explanation.score, 1e-12)
      assertEquals(explanation.score, explanation.bias + explanation.contributions.sum(), 1e-9)
      // Solo le colonne degli split e l'ancora possono avere merito.
      assertEquals(0.0, explanation.contributions[4], 0.0)
    }
  }

  @Test
  fun `il NaN segue la strada imparata per ogni split`() {
    val t = tree()
    // Radice: NaN a sinistra -> foglia 1.
    assertEquals(1, t.leafOf(x(0.0, Double.NaN, 0.0, 0.0, 0.0)))
    // Radice a destra, poi c2 NaN va a destra -> foglia 4.
    assertEquals(4, t.leafOf(x(0.0, 2.0, Double.NaN, 0.0, 0.0)))
    // Il confine: x <= soglia va a sinistra.
    assertEquals(1, t.leafOf(x(0.0, 0.5, 0.0, 0.0, 0.0)))
    assertEquals(3, t.leafOf(x(0.0, 0.6, -1.0, 0.0, 0.0)))
  }

  @Test
  fun `un'ancora mancante vale la sua media e non contribuisce`() {
    val e = ensemble()
    val withNaN = e.explain(x(0.3, 0.9, -2.0, Double.NaN, 1.0))
    val atMean = e.explain(x(0.3, 0.9, -2.0, -1.5, 1.0))
    assertEquals(atMean.score, withNaN.score, 1e-12)
    assertEquals(0.0, withNaN.contributions[3], 0.0)
    val noAnchor = ensemble(offset = -1, offsetMean = 0.0)
    assertEquals(0.2 + 0.3f.toDouble() + (-0.25f).toDouble(), noAnchor.score(x(-1.0, 0.1, 0.0, 99.0, 0.0)), 1e-9)
  }

  @Test
  fun `un file rovinato non si carica`() {
    val bytes = ensemble().encode()
    expectRefused("troncato") { TreeEnsemble.parse(bytes.copyOf(bytes.size - 3), 5) }
    expectRefused("byte in piu'") { TreeEnsemble.parse(bytes + byteArrayOf(0), 5) }
    expectRefused("altro contratto") { TreeEnsemble.parse(bytes, 42) }
    val wrongMagic = bytes.copyOf().also { it[0] = 'X'.code.toByte() }
    expectRefused("firma") { TreeEnsemble.parse(wrongMagic, 5) }
    val wrongVersion = bytes.copyOf().also { it[4] = 9 }
    expectRefused("versione") { TreeEnsemble.parse(wrongVersion, 5) }
  }

  @Test
  fun `un albero con le frecce sbagliate e' rifiutato`() {
    expectRefused("figlio destro sbagliato") {
      Tree(
        flags = byteArrayOf(0, Tree.LEAF.toByte(), Tree.LEAF.toByte()),
        values = floatArrayOf(0f, 1f, 2f),
        features = intArrayOf(0, -1, -1),
        thresholds = floatArrayOf(0f, Float.NaN, Float.NaN),
        rights = intArrayOf(1, -1, -1),
      )
    }
    expectRefused("split fuori dalle feature") {
      TreeEnsemble(3, 0, 0, -1, 0.0, 0.0, listOf(Tree(byteArrayOf(0, 1, 1), floatArrayOf(0f, 0f, 0f), intArrayOf(7, -1, -1), floatArrayOf(0f, 0f, 0f), intArrayOf(2, -1, -1))))
    }
  }

  private fun expectRefused(what: String, block: () -> Unit) {
    try {
      block()
      fail("doveva rifiutare: $what")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message != null)
    }
  }
}
