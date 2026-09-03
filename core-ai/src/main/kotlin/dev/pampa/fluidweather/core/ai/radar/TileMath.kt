package dev.pampa.fluidweather.core.ai.radar

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * La geometria delle tile web-mercator (256 px, come RainViewer e ogni mappa a tile): da
 * lat/lon al pixel globale allo zoom scelto, alla tile che lo contiene, alla scala in metri per
 * pixel. Pura, e con i numeri fissati nei test su punti noti.
 */
object TileMath {

  const val TILE_SIZE = 256

  data class Global(val x: Double, val y: Double)

  data class TileCoord(val x: Int, val y: Int)

  /** Le coordinate del pixel nel piano globale di zoom `z` (0..2^z*256). */
  fun toGlobalPixel(latitude: Double, longitude: Double, zoom: Int): Global {
    val n = (1 shl zoom).toDouble()
    val x = (longitude + 180.0) / 360.0 * n * TILE_SIZE
    val latR = Math.toRadians(latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE))
    val y = (1.0 - ln(tan(latR) + 1.0 / cos(latR)) / PI) / 2.0 * n * TILE_SIZE
    return Global(x, y)
  }

  fun toLatLon(gx: Double, gy: Double, zoom: Int): Pair<Double, Double> {
    val n = (1 shl zoom).toDouble() * TILE_SIZE
    val longitude = gx / n * 360.0 - 180.0
    val latitude = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * gy / n))))
    return latitude to longitude
  }

  fun tileOf(global: Global): TileCoord = TileCoord(floor(global.x / TILE_SIZE).toInt(), floor(global.y / TILE_SIZE).toInt())

  /** Metri per pixel: 156 543 m all'equatore a zoom 0, per il coseno della latitudine, diviso 2^z. */
  fun metersPerPixel(latitude: Double, zoom: Int): Double =
    156_543.03392 * cos(Math.toRadians(latitude)) / (1 shl zoom)

  /** L'antimeridiano: la tile a destra dell'ultima e' la prima. */
  fun wrapX(tileX: Int, zoom: Int): Int = Math.floorMod(tileX, 1 shl zoom)

  const val MAX_LATITUDE = 85.05
}
