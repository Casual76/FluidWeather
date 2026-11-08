package dev.pampa.fluidweather.core.ui

/** Original sample indices and elapsed-time x fractions; each group is a supported segment. */
internal fun timedLineGeometry(times: List<Long>, maxGapMillis: Long): List<List<Pair<Int, Float>>> {
  if (times.size < 2 || maxGapMillis <= 0 || times.zipWithNext().any { (a, b) -> b <= a }) return emptyList()
  val duration = times.last().toDouble() - times.first().toDouble()
  if (duration <= 0) return emptyList()
  val segments = mutableListOf<MutableList<Pair<Int, Float>>>()
  times.forEachIndexed { index, time ->
    if (index == 0 || time - times[index - 1] > maxGapMillis) segments += mutableListOf<Pair<Int, Float>>()
    segments.last() += index to ((time.toDouble() - times.first().toDouble()) / duration).toFloat()
  }
  return segments
}
