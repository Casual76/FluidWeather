package dev.pampa.fluidweather.testbench.train

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Gradient boosting compatto (alberi di profondita' 2 su istogrammi a 32 bin, passo di Newton
 * sulle foglie) per il confronto promesso dal piano: quanta accuratezza lascia sul tavolo la
 * logistica lineare? NON viene spedito: se batte nettamente la logistica, la risposta giusta
 * e' migliorare le feature, non imbarcare una foresta.
 */
class Gbm(
  private val rounds: Int = 150,
  private val learningRate: Double = 0.1,
  private val bins: Int = 32,
) {

  private class Node(
    var featureIndex: Int = -1,
    var thresholdBin: Int = -1,
    var value: Double = 0.0,
    var left: Node? = null,
    var right: Node? = null,
  )

  private lateinit var binEdges: Array<DoubleArray>
  private val trees = mutableListOf<Node>()
  private var baseScore = 0.0

  fun fit(features: List<DoubleArray>, labels: List<Boolean>) {
    val n = features.size
    val d = features.first().size

    binEdges = Array(d) { feature ->
      val sorted = features.map { it[feature] }.sorted()
      DoubleArray(bins - 1) { edge -> sorted[((edge + 1).toDouble() / bins * (n - 1)).toInt()] }
    }
    val binned = Array(n) { row ->
      IntArray(d) { feature -> binOf(features[row][feature], binEdges[feature]) }
    }

    val targets = DoubleArray(n) { if (labels[it]) 1.0 else 0.0 }
    val baseRate = targets.average().coerceIn(1e-6, 1 - 1e-6)
    baseScore = ln(baseRate / (1 - baseRate))
    val scores = DoubleArray(n) { baseScore }

    repeat(rounds) {
      val gradient = DoubleArray(n)
      val hessian = DoubleArray(n)
      for (row in 0 until n) {
        val p = sigmoid(scores[row])
        gradient[row] = targets[row] - p
        hessian[row] = (p * (1 - p)).coerceAtLeast(1e-6)
      }

      val root = Node()
      val all = IntArray(n) { it }
      split(root, all, binned, gradient, hessian, depth = 0)
      trees += root

      for (row in 0 until n) {
        scores[row] += learningRate * leafValue(root, binned[row])
      }
    }
  }

  fun predict(features: DoubleArray): Double {
    val binnedRow = IntArray(features.size) { binOf(features[it], binEdges[it]) }
    var score = baseScore
    for (tree in trees) score += learningRate * leafValue(tree, binnedRow)
    return sigmoid(score)
  }

  private fun split(
    node: Node,
    rows: IntArray,
    binned: Array<IntArray>,
    gradient: DoubleArray,
    hessian: DoubleArray,
    depth: Int,
  ) {
    var gradientSum = 0.0
    var hessianSum = 0.0
    for (row in rows) {
      gradientSum += gradient[row]
      hessianSum += hessian[row]
    }
    node.value = gradientSum / (hessianSum + 1.0)
    if (depth >= 2 || rows.size < 64) return

    val d = binned.first().size
    var bestGain = 1e-6
    var bestFeature = -1
    var bestBin = -1
    for (feature in 0 until d) {
      val gradientByBin = DoubleArray(bins)
      val hessianByBin = DoubleArray(bins)
      for (row in rows) {
        val bin = binned[row][feature]
        gradientByBin[bin] += gradient[row]
        hessianByBin[bin] += hessian[row]
      }
      var leftGradient = 0.0
      var leftHessian = 0.0
      for (bin in 0 until bins - 1) {
        leftGradient += gradientByBin[bin]
        leftHessian += hessianByBin[bin]
        val rightGradient = gradientSum - leftGradient
        val rightHessian = hessianSum - leftHessian
        if (leftHessian < 1.0 || rightHessian < 1.0) continue
        val gain = leftGradient * leftGradient / (leftHessian + 1.0) +
          rightGradient * rightGradient / (rightHessian + 1.0) -
          gradientSum * gradientSum / (hessianSum + 1.0)
        if (gain > bestGain) {
          bestGain = gain
          bestFeature = feature
          bestBin = bin
        }
      }
    }
    if (bestFeature < 0) return

    node.featureIndex = bestFeature
    node.thresholdBin = bestBin
    val leftRows = rows.filter { binned[it][bestFeature] <= bestBin }.toIntArray()
    val rightRows = rows.filter { binned[it][bestFeature] > bestBin }.toIntArray()
    node.left = Node().also { split(it, leftRows, binned, gradient, hessian, depth + 1) }
    node.right = Node().also { split(it, rightRows, binned, gradient, hessian, depth + 1) }
  }

  private fun leafValue(node: Node, binnedRow: IntArray): Double {
    var current = node
    while (current.featureIndex >= 0) {
      current = if (binnedRow[current.featureIndex] <= current.thresholdBin) {
        current.left ?: return current.value
      } else {
        current.right ?: return current.value
      }
    }
    return current.value
  }

  private fun binOf(value: Double, edges: DoubleArray): Int {
    if (value.isNaN()) return 0
    var low = 0
    var high = edges.size
    while (low < high) {
      val mid = (low + high) / 2
      if (value > edges[mid]) low = mid + 1 else high = mid
    }
    return low
  }

  private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
}
