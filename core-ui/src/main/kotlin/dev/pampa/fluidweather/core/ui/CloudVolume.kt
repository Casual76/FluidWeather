package dev.pampa.fluidweather.core.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import dev.pampa.fluidweather.core.model.WeatherKind
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/** Bounded CPU field, independent of pixels, clock, Android and the celestial ephemeris.
 * Four depth samples approximate optical thickness; contours cache the integration, so drawing
 * only submits paths. This is illustrative cloud structure, never measured cloud geometry.
 */
internal class CloudVolumeField(val air: SkyAtmosphere, val seed: Int) {
  val columns = 64
  val rows = 40
  val opacity = FloatArray((columns + 1) * (rows + 1))
  val shade = FloatArray(opacity.size)
  private val horizontalCount = columns * (rows + 1)
  private val verticalCount = (columns + 1) * rows
  private val edgeCount = horizontalCount + verticalCount + columns * rows
  private class Workspace(count: Int) {
    val first = IntArray(count)
    val second = IntArray(count)
    val px = FloatArray(count)
    val py = FloatArray(count)
    val visited = BooleanArray(count)
  }
  private val workspace by lazy { Workspace(edgeCount) }

  init {
    for (y in 1 until rows) for (x in 1 until columns) {
      val u = x.toFloat() / columns * 1.5f - 0.25f
      val v = y.toFloat() / rows * 1.15f - 0.15f
      val broad = noise(u * 3f, v * 3.2f, seed)
      val warp = noise(u * 5f + 19f, v * 4f, seed + 3) - 0.5f
      var mass = 0f
      for (z in 0..3) {
        val px = u * 5f + warp * 0.75f + z * 0.41f
        val py = v * 6f + z * 0.37f
        val structure = broad * 0.36f + noise(px, py, seed + z) * 0.34f +
          noise(px * 2.1f, py * 2.1f, seed + 9) * 0.2f +
          noise(px * 4.3f, py * 4.3f, seed + 17) * 0.1f
        val threshold = 0.78f - air.cloudCoverage * 0.68f
        val density = smooth((structure - threshold) / (0.22f + air.diffuseLight * 0.1f))
        mass += density * (0.65f + 0.35f * broad)
      }
      // An uneven, feathered base. No cloud geometry survives at v=1 (52% of the scene).
      val bottom = 0.68f + broad * 0.21f + air.cloudDepth * 0.08f
      val fade = 1f - smooth((v - bottom + 0.12f) / 0.22f)
      val optical = mass * (0.35f + air.cloudDepth * 1.25f) * fade
      opacity[y * (columns + 1) + x] = 1f - exp(-optical)
    }
    for (y in 1 until rows) for (x in 1 until columns) {
      val i = y * (columns + 1) + x
      val v = y.toFloat() / rows
      val above = opacity[(y - 1) * (columns + 1) + x]
      val local = opacity[i]
      // Ambient occlusion and loss of light through the ceiling; darker bases, lit shoulders.
      shade[i] = local * ((above - local) * 1.3f + v * 0.46f +
        noise(x * 0.14f, y * 0.18f, seed + 51) * 0.38f + air.cloudDarkness * 0.24f)
        .coerceIn(0f, 0.94f)
    }
  }

  /** Marching triangles with shared edge identities: closed contours, no cell seams or circles. */
  fun contour(values: FloatArray, level: Float): Path {
    val stride = columns + 1
    val first = workspace.first
    val second = workspace.second
    val px = workspace.px
    val py = workspace.py
    first.fill(-1)
    second.fill(-1)
    fun edge(id: Int, a: Int, b: Int): Int {
      if ((values[a] > level) == (values[b] > level)) return -1
      val f = (level - values[a]) / (values[b] - values[a])
      px[id] = ((a % stride) + ((b % stride) - (a % stride)) * f) / columns * 1.5f - 0.25f
      py[id] = ((a / stride) + ((b / stride) - (a / stride)) * f) / rows * 1.15f - 0.15f
      return id
    }
    fun connect(a: Int, b: Int, c: Int) {
      val p = if (a >= 0) a else b
      val q = if (c >= 0) c else b
      if (p < 0 || q < 0 || p == q) return
      if (first[p] < 0) first[p] = q else second[p] = q
      if (first[q] < 0) first[q] = p else second[q] = p
    }
    for (y in 0 until rows) for (x in 0 until columns) {
      val a = y * stride + x
      val b = a + 1
      val c = a + stride + 1
      val d = a + stride
      val diagonal = edge(horizontalCount + verticalCount + y * columns + x, a, c)
      connect(edge(y * columns + x, a, b),
        edge(horizontalCount + y * stride + x + 1, b, c), diagonal)
      connect(diagonal, edge((y + 1) * columns + x, d, c),
        edge(horizontalCount + y * stride + x, a, d))
    }
    val visited = workspace.visited
    visited.fill(false)
    return Path().apply {
      for (start in 0 until edgeCount) {
        if (first[start] < 0 || visited[start]) continue
        moveTo(px[start], py[start])
        var previous = -1
        var current = start
        do {
          visited[current] = true
          val next = if (first[current] != previous) first[current] else second[current]
          if (next < 0) break
          lineTo(px[next], py[next])
          previous = current
          current = next
        } while (current != start && !visited[current])
        close()
      }
      // Nested holes must cut through the ceiling rather than fill with the same winding.
      fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
    }
  }

  companion object {
    private fun smooth(v: Float): Float = v.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    private fun hash(x: Int, y: Int, seed: Int): Float {
      var n = x * 374761393 + y * 668265263 + seed * 1442695041
      n = (n xor (n ushr 13)) * 1274126177
      return ((n xor (n ushr 16)) ushr 8) / 16777215f
    }
    private fun noise(x: Float, y: Float, seed: Int): Float {
      val ix = floor(x).toInt()
      val iy = floor(y).toInt()
      val fx = smooth(x - ix)
      val fy = smooth(y - iy)
      val a = hash(ix, iy, seed) * (1f - fx) + hash(ix + 1, iy, seed) * fx
      val b = hash(ix, iy + 1, seed) * (1f - fx) + hash(ix + 1, iy + 1, seed) * fx
      return a * (1f - fy) + b * fy
    }
  }
}

/** One fixed tessellation shared by phone, widget and desktop. No size/light/time cache churn. */
internal class CloudVolumeLayer private constructor(private val air: SkyAtmosphere, geometry: Geometry) {
  private val mass = geometry.mass
  private val shadows = geometry.shadows
  private var palette: SkyPalette.Sky? = null
  private var light = Float.NaN
  private var illumination: Brush? = null
  private var underside: Brush? = null

  fun draw(scope: DrawScope, t: Float, sky: SkyPalette.Sky, body: CelestialBody?, quality: SceneQuality) {
    // Direction is continuous and cheap; the integrated density never depends on minute ticks.
    val lightX = body?.x ?: 0.7f
    if (palette?.cloudColor != sky.cloudColor || palette?.cloudShadow != sky.cloudShadow || light != lightX) {
      palette = sky
      light = lightX
      illumination = Brush.linearGradient(listOf(sky.cloudColor,
        lerp(sky.cloudColor, sky.cloudShadow, 0.24f + air.cloudDarkness * 0.22f)),
        Offset(lightX, -0.2f), Offset(1f - lightX, 1f))
      underside = Brush.verticalGradient(listOf(
        lerp(sky.cloudColor, sky.cloudShadow, 0.5f), sky.cloudShadow), 0f, 1f)
    }
    val drift = if (quality == SceneQuality.STATIC) 0f else
      sin(t.toDouble() * 0.025 * air.windSpeedFactor).toFloat() * 0.035f
    with(scope) {
      clipRect {
        withTransform({ scale(size.width, size.height, Offset.Zero); translate(drift, 0f) }) {
          // Equal opacity increments (rather than stacking a uniform gray veil).
          for (i in mass.indices) drawPath(mass[i], illumination!!, alpha = 1f / (16.5f - i))
          val shadowStrength = 0.75f - air.diffuseLight * 0.25f
          for (i in shadows.indices) drawPath(shadows[i], underside!!, alpha = shadowStrength / (8.5f - i))
        }
      }
    }
  }

  companion object {
    private data class Key(val air: SkyAtmosphere, val seed: Int)
    private class Geometry(air: SkyAtmosphere, seed: Int) {
      val mass: Array<Path>
      val shadows: Array<Path>
      init {
        val field = CloudVolumeField(air, seed)
        mass = Array(16) { field.contour(field.opacity, (it + 0.5f) / 16f) }
        shadows = Array(8) { field.contour(field.shade, (it + 0.5f) / 8f) }
      }
    }
    private val cache = LinkedHashMap<Key, Geometry>()

    /** Retain at most four immutable geometries; brushes belong to the returned painter layer. */
    @Synchronized fun forState(state: SkyState, seed: Int): CloudVolumeLayer? {
      if (state.kind == WeatherKind.FOG) return null
      val a = state.atmosphere?.boundedForPainting() ?: SkyAtmosphere(
        cloudCoverage = (state.cloudCoverPercentOrDefault().takeIf { it.isFinite() }
          ?: SkyPalette.defaultCover(state.kind).toFloat()).div(100f).coerceIn(0f, 1f),
        cloudDepth = when (state.kind) {
          WeatherKind.CLOUDY -> 0.65f
          WeatherKind.HEAVY_RAIN, WeatherKind.THUNDERSTORM -> 0.95f
          WeatherKind.RAIN, WeatherKind.DRIZZLE, WeatherKind.SLEET -> 0.72f
          else -> 0.35f
        },
        cloudDarkness = when (state.kind) {
          WeatherKind.HEAVY_RAIN, WeatherKind.THUNDERSTORM -> 0.9f
          WeatherKind.CLOUDY, WeatherKind.RAIN -> 0.5f
          else -> 0.2f
        },
        diffuseLight = if (state.kind == WeatherKind.CLOUDY) 0.8f else 0.4f,
      )
      if (a.cloudCoverage <= 0.01f) return null
      // Only optical geometry fields enter the cache; wind, rain and haze cannot rebuild it.
      val geometry = a.copy(windSpeedFactor = 1f, haze = 0f, precipitationStrength = 0f, storminess = 0f)
      val key = Key(geometry, seed)
      val existing = cache.remove(key)
      val layer = existing ?: Geometry(geometry, seed)
      cache[key] = layer
      if (cache.size > 4) cache.remove(cache.keys.first())
      return CloudVolumeLayer(a, layer)
    }
  }
}
