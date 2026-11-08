package dev.pampa.fluidweather.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.pampa.fluidweather.core.model.WeatherKind
import java.util.Random
import kotlin.math.roundToInt

/** One shared RGBA atlas; geometry and tint caches live with the painter, not with each frame. */
internal class AtlasCloudLayer(state: SkyState, quality: SceneQuality, seed: Long, private val atlas: ImageBitmap) {
  private val air = state.atmosphere?.boundedForPainting()
  private val cover = air?.cloudCoverage ?: (state.cloudCoverPercentOrDefault() / 100f).coerceIn(0f, 1f)
  private val depth = air?.cloudDepth ?: if (cover > .8f) .65f else .3f
  private val bank = cover > .7f
  private val random = Random(seed)
  private data class Cloud(val tile: Int, val x: Float, val y: Float, val width: Float, val speed: Float, val alpha: Float)
  private val clouds = buildList {
    if (cover > .02f && state.kind != WeatherKind.FOG) {
      val count = if (quality == SceneQuality.FULL) (2 + cover * 4).toInt() else (1 + cover * 3).toInt()
      repeat(count) { i ->
        val tile = when {
          bank && depth > .65f -> if (i % 2 == 0) 3 else 5
          bank -> if (i % 3 == 0) 1 else 4
          depth < .2f && i == 0 -> 2
          else -> i % 2
        }
        // Stratify the field: random edge-only placement can turn a fully covered
        // sky into an empty frame. Jitter adds variation within supported coverage.
        val x = if (bank) -.55f + (i % 3) * .22f else -.6f + i.toFloat() / count * 1.35f
        val y = if (bank) -.34f + (i % 3) * .09f else -.06f + (i % 2) * .2f
        add(Cloud(tile, x + random.nextFloat() * .12f,
          y + random.nextFloat() * .04f,
          if (bank) 1.55f + random.nextFloat() * 1.1f else .75f + random.nextFloat() * .7f,
          .0015f + random.nextFloat() * .0035f,
          (cover * (if (bank) .83f else 1.25f)).coerceIn(.06f, .96f)))
      }
    }
  }
  private val sourceSize = IntSize(atlas.width / 3, atlas.height / 2)
  private val sourceOffsets = (0..5).map { IntOffset(it % 3 * sourceSize.width, it / 3 * sourceSize.height) }
  private var face: Color? = null
  private var shade: Color? = null
  private var tint: ColorFilter? = null

  fun draw(scope: DrawScope, t: Float, sky: SkyPalette.Sky) {
    if (clouds.isEmpty()) return
    if (face != sky.cloudColor || shade != sky.cloudShadow) {
      face = sky.cloudColor
      shade = sky.cloudShadow
      val f = sky.cloudColor
      val s = sky.cloudShadow
      // Preserve photographed volume: black -> shadow, white -> illuminated cloud face.
      fun row(a: Float, b: Float) = floatArrayOf((a-b)*.2126f, (a-b)*.7152f, (a-b)*.0722f, 0f, b*255f)
      tint = ColorFilter.colorMatrix(ColorMatrix(row(f.red,s.red) + row(f.green,s.green) +
        row(f.blue,s.blue) + floatArrayOf(0f,0f,0f,1f,0f)))
    }
    val time = t * (air?.windSpeedFactor ?: 1f)
    for (cloud in clouds) with(scope) {
      val span = 1f + cloud.width
      val x = ((cloud.x + time * cloud.speed + cloud.width) % span + span) % span - cloud.width
      val width = (size.width * cloud.width).roundToInt().coerceAtLeast(1)
      drawImage(atlas, srcOffset = sourceOffsets[cloud.tile], srcSize = sourceSize,
        dstOffset = IntOffset((x * size.width).roundToInt(), (cloud.y * size.height).roundToInt()),
        dstSize = IntSize(width, width), alpha = cloud.alpha, colorFilter = tint, filterQuality = FilterQuality.Medium)
    }
  }
}
