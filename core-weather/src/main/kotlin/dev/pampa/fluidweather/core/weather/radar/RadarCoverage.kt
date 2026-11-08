package dev.pampa.fluidweather.core.weather.radar

import dev.pampa.fluidweather.core.weather.RadarFrames

/** Geographic support, independent from raster download/palette completeness. */
enum class GeographicCoverage { SUPPORTED, OUTSIDE, UNKNOWN }
data class CoverageReading(val state: GeographicCoverage, val downloadedAtMillis: Long? = null)

object RadarCoverage {
  fun pixel(argb: Int): GeographicCoverage = when {
    argb ushr 24 == 0 -> GeographicCoverage.SUPPORTED
    argb == 0xFF000000.toInt() -> GeographicCoverage.OUTSIDE
    else -> GeographicCoverage.UNKNOWN
  }

  /** Once per sample, one to four tiles, not once per radar frame. Mixed masks are unknown.
   * https://www.rainviewer.com/api/weather-maps-api.html
   */
  suspend fun read(frames: RadarFrames, tiles: TileSource, placements: List<WindowBuilder.Placement>, zoom: Int, size: Int): CoverageReading {
    var outside = 0
    var supported = 0
    val downloads = mutableListOf<Long?>()
    for (p in placements) {
      val result = tiles.raster(frames.coverageUrl(zoom, p.wrappedX, p.tile.y))
      val ok = result as? TileResult.Ok
      downloads += ok?.downloadedAtMillis
      for (y in maxOf(0, p.originY) until minOf(size, p.originY + TileMath.TILE_SIZE)) {
        for (x in maxOf(0, p.originX) until minOf(size, p.originX + TileMath.TILE_SIZE)) {
          val tx = x - p.originX
          val ty = y - p.originY
          val state = if (ok != null && tx in 0 until ok.raster.width && ty in 0 until ok.raster.height)
            pixel(ok.raster.pixel(tx, ty)) else GeographicCoverage.UNKNOWN
          when (state) {
            GeographicCoverage.SUPPORTED -> supported++
            GeographicCoverage.OUTSIDE -> outside++
            GeographicCoverage.UNKNOWN -> Unit
          }
        }
      }
    }
    val state = when {
      supported == size * size -> GeographicCoverage.SUPPORTED
      outside == size * size -> GeographicCoverage.OUTSIDE
      else -> GeographicCoverage.UNKNOWN
    }
    return CoverageReading(state, downloads.takeIf { it.isNotEmpty() && it.all { t -> t != null } }?.filterNotNull()?.minOrNull())
  }
}
