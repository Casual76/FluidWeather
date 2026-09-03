package dev.pampa.fluidweather.core.ai.radar

import dev.pampa.fluidweather.core.weather.RainViewerPalette
import kotlin.math.pow

/**
 * Dal colore di un pixel RainViewer (palette "universal blue", `smooth=0`) alla riflettivita'
 * in dBZ. Gli stop a 5 e 10 dBZ sono semitrasparenti, e `Bitmap.getPixels` restituisce colori
 * non premoltiplicati con l'arrotondamento del caso: per quei due l'alfa e' la chiave, per gli
 * altri il colore piu' vicino nello spazio RGB. Un colore che non somiglia a nessuno stop e'
 * "ignoto": contato, perche' troppi ignoti vogliono dire che la palette e' cambiata.
 */
object DbzPalette {

  const val NONE = 0
  const val UNKNOWN = -1

  /** Sotto questa distanza (al quadrato) il colore e' uno stop; 40 per canale copre lo smussamento del PNG. */
  private const val MAX_DISTANCE_SQUARED = 40 * 40

  private class Stop(val dbz: Int, val r: Int, val g: Int, val b: Int)

  private val opaqueStops: List<Stop> = RainViewerPalette.universalBlue
    .filter { it.dbz >= 15 }
    .map { stop ->
      val argb = stop.argb.toInt()
      Stop(stop.dbz, (argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
    }

  fun dbzOf(argb: Int): Int {
    val alpha = (argb ushr 24) and 0xFF
    if (alpha < 40) return NONE
    if (alpha < 125) return 5
    if (alpha < 210) return 10
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    var best: Stop? = null
    var bestDistance = Int.MAX_VALUE
    for (stop in opaqueStops) {
      val dr = r - stop.r
      val dg = g - stop.g
      val db = b - stop.b
      val distance = dr * dr + dg * dg + db * db
      if (distance < bestDistance) {
        bestDistance = distance
        best = stop
      }
    }
    return if (best != null && bestDistance <= MAX_DISTANCE_SQUARED) best.dbz else UNKNOWN
  }

  enum class Intensity(val minDbz: Int) { NONE(Int.MIN_VALUE), DRIZZLE(10), LIGHT(20), MODERATE(30), HEAVY(40), VERY_HEAVY(50), HAIL(60) }

  /** Le stesse soglie della legenda del radar (fase 12). */
  fun intensity(dbz: Int): Intensity = when {
    dbz < 10 -> Intensity.NONE
    dbz < 20 -> Intensity.DRIZZLE
    dbz < 30 -> Intensity.LIGHT
    dbz < 40 -> Intensity.MODERATE
    dbz < 50 -> Intensity.HEAVY
    dbz < 60 -> Intensity.VERY_HEAVY
    else -> Intensity.HAIL
  }

  /** Marshall-Palmer (Z = 200 R^1.6): 20 dBZ ~ 0,6 mm/h, 30 ~ 2,7, 40 ~ 11,5, 50 ~ 49. Approssimato. */
  fun rateMmPerHour(dbz: Int): Double {
    if (dbz < 10) return 0.0
    val z = 10.0.pow(dbz / 10.0)
    return (z / 200.0).pow(1.0 / 1.6)
  }
}
