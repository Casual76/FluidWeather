package dev.pampa.fluidweather.core.ai.radar

import dev.pampa.fluidweather.core.weather.RadarFrame
import dev.pampa.fluidweather.core.weather.RadarFrames
import kotlin.math.max
import kotlin.math.min

/** Una tile decodificata: ARGB non premoltiplicato, riga per riga. */
class Raster(val width: Int, val height: Int, val argb: IntArray) {
  fun pixel(x: Int, y: Int): Int = argb[y * width + x]
}

sealed interface TileResult {
  data class Ok(val raster: Raster) : TileResult

  /** Il server non ha la tile (404): niente eco, non un buco nei dati. */
  data object Empty : TileResult

  /** Rete o decodifica fallite: un buco nei dati, che abbassa la copertura. */
  data object Missing : TileResult
}

/** Chi procura le tile; la versione vera scarica e decodifica, i test disegnano raster sintetici. */
fun interface TileSource {
  suspend fun raster(url: String): TileResult
}

/**
 * Un quadrato di pixel attorno al punto, gia' tradotto in dBZ: `-1` ignoto o mancante, `0`
 * niente, `5..65` eco. Tutto il campionamento ragiona su questo, non sulle tile.
 */
class RadarWindow(val size: Int, val dbz: IntArray, val timeMillis: Long) {
  val center: Int get() = size / 2

  fun at(x: Int, y: Int): Int = if (x in 0 until size && y in 0 until size) dbz[y * size + x] else DbzPalette.UNKNOWN

  val known: Int get() = dbz.count { it >= 0 }
  val coverage: Double get() = known.toDouble() / (size * size)
  val unknownColours: Int get() = dbz.count { it == DbzPalette.UNKNOWN }

  /** Il massimo dBZ in un quadrato di lato 2r+1 attorno a (x, y); -1 se tutto ignoto. */
  fun maxAround(x: Int, y: Int, radius: Int): Int {
    var best = DbzPalette.UNKNOWN
    for (dy in -radius..radius) for (dx in -radius..radius) {
      val v = at(x + dx, y + dy)
      if (v > best) best = v
    }
    return best
  }
}

/**
 * Costruisce la finestra di un fotogramma copiando i rettangoli che si sovrappongono dalle
 * (una-quattro) tile toccate. La tile del centro mancante rende la finestra inutilizzabile.
 */
class WindowBuilder(private val tiles: TileSource, private val zoom: Int, private val size: Int) {

  data class Placement(val tile: TileMath.TileCoord, val wrappedX: Int, val originX: Int, val originY: Int)

  /** Le tile toccate dalla finestra centrata sul pixel globale, con l'angolo di ogni tile nel sistema della finestra. */
  fun placements(center: TileMath.Global): List<Placement> {
    val left = (center.x - size / 2).toInt()
    val top = (center.y - size / 2).toInt()
    val first = TileMath.tileOf(TileMath.Global(left.toDouble(), top.toDouble()))
    val last = TileMath.tileOf(TileMath.Global((left + size - 1).toDouble(), (top + size - 1).toDouble()))
    val result = mutableListOf<Placement>()
    for (ty in first.y..last.y) for (tx in first.x..last.x) {
      result += Placement(
        tile = TileMath.TileCoord(tx, ty),
        wrappedX = TileMath.wrapX(tx, zoom),
        originX = tx * TileMath.TILE_SIZE - left,
        originY = ty * TileMath.TILE_SIZE - top,
      )
    }
    return result
  }

  /** Null quando la tile che contiene il centro non c'e' proprio. */
  suspend fun build(frames: RadarFrames, frame: RadarFrame, center: TileMath.Global, urlOf: (RadarFrame, Int, Int) -> String): RadarWindow? {
    val dbz = IntArray(size * size) { DbzPalette.UNKNOWN }
    val centerTile = TileMath.tileOf(center)
    var centreMissing = false
    for (placement in placements(center)) {
      val maxY = (1 shl zoom) - 1
      if (placement.tile.y < 0 || placement.tile.y > maxY) continue
      val result = tiles.raster(urlOf(frame, placement.wrappedX, placement.tile.y))
      val x0 = max(0, placement.originX)
      val y0 = max(0, placement.originY)
      val x1 = min(size, placement.originX + TileMath.TILE_SIZE)
      val y1 = min(size, placement.originY + TileMath.TILE_SIZE)
      when (result) {
        is TileResult.Ok -> {
          val raster = result.raster
          for (y in y0 until y1) {
            val ty = y - placement.originY
            if (ty !in 0 until raster.height) continue
            for (x in x0 until x1) {
              val tx = x - placement.originX
              if (tx !in 0 until raster.width) continue
              dbz[y * size + x] = DbzPalette.dbzOf(raster.pixel(tx, ty))
            }
          }
        }
        TileResult.Empty -> for (y in y0 until y1) for (x in x0 until x1) dbz[y * size + x] = DbzPalette.NONE
        TileResult.Missing -> if (placement.tile == centerTile) centreMissing = true
      }
    }
    if (centreMissing) return null
    return RadarWindow(size, dbz, frame.timeMillis)
  }
}
