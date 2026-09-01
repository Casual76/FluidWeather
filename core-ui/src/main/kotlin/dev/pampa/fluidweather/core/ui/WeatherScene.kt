package dev.pampa.fluidweather.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.util.lerp
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.pampa.fluidweather.core.model.DayPhase
import dev.pampa.fluidweather.core.model.WeatherKind
import java.util.Random
import kotlin.math.sin
import kotlinx.coroutines.android.awaitFrame

/** Quanta scena ci si puo' permettere: deciso dal vetro adattivo, non dalla scena stessa. */
enum class SceneQuality { FULL, REDUCED, STATIC }

data class SkyState(
  val phase: DayPhase,
  val kind: WeatherKind?,
  val cloudCoverPercent: Double?,
)

/**
 * Il cielo animato a tutto schermo: gradiente atmosferico per fase del giorno, nuvole vaporose
 * che scorrono con parallasse, stelle che tremolano nelle notti pulite, pioggia e neve come
 * particelle vere. Tutta la geometria e' allocata una volta (semi fissi) e ogni fotogramma
 * costa solo aritmetica: la scena regge il 120 Hz o si dichiara STATIC, mai a meta'.
 */
@Composable
fun WeatherScene(
  state: SkyState,
  quality: SceneQuality,
  modifier: Modifier = Modifier,
) {
  val sky = remember(state) { SkyPalette.sky(state.phase, state.kind, state.cloudCoverPercent) }

  // Il cielo cambia colore con calma: un fronte che arriva e' una dissolvenza, non uno scatto.
  val top by animateColorAsState(sky.gradient[0], FluidMotion.color(1200), label = "sky-top")
  val mid by animateColorAsState(sky.gradient[1], FluidMotion.color(1200), label = "sky-mid")
  val bottom by animateColorAsState(sky.gradient[2], FluidMotion.color(1200), label = "sky-bottom")

  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  val animated = quality != SceneQuality.STATIC && !reducedMotion

  var frameSeconds by remember { mutableFloatStateOf(0f) }
  LaunchedEffect(animated) {
    if (!animated) return@LaunchedEffect
    val start = awaitFrame()
    while (true) {
      val now = awaitFrame()
      frameSeconds = (now - start) / 1_000_000_000f
    }
  }

  val particles = remember(state.kind, quality) { ParticleField(state.kind, quality) }
  val clouds = remember { CloudField() }
  val stars = remember { StarField() }

  Canvas(modifier) {
    drawRect(Brush.verticalGradient(0f to top, 0.55f to mid, 1f to bottom))
    val t = if (animated) frameSeconds else 0f
    if (sky.starAlpha > 0f) stars.draw(this, t, sky.starAlpha)
    clouds.draw(this, t, sky, state.cloudCoverPercentOrDefault())
    particles.draw(this, t)
  }
}

private fun SkyState.cloudCoverPercentOrDefault(): Float =
  (cloudCoverPercent ?: when (kind) {
    WeatherKind.CLEAR -> 5.0
    WeatherKind.MOSTLY_CLEAR -> 20.0
    WeatherKind.PARTLY_CLOUDY -> 45.0
    null -> 30.0
    else -> 85.0
  }).toFloat()

/**
 * Nuvole procedurali: otto banchi, ciascuno tre lobi radiali sovrapposti, che scorrono a
 * velocita' diverse (parallasse). La copertura decide quanti banchi esistono e quanto pieni.
 */
private class CloudField {
  private val random = Random(1913L)
  private val banks = List(8) { index ->
    Bank(
      y = 0.06f + random.nextFloat() * 0.30f,
      scale = 0.6f + random.nextFloat() * 0.8f,
      speed = (0.004f + random.nextFloat() * 0.010f) * (if (index % 2 == 0) 1f else 1.4f),
      seed = random.nextFloat(),
    )
  }

  private class Bank(val y: Float, val scale: Float, val speed: Float, val seed: Float)

  fun draw(scope: DrawScope, t: Float, sky: SkyPalette.Sky, coverPercent: Float) {
    val visible = ((coverPercent / 100f) * banks.size).toInt().coerceIn(0, banks.size)
    if (visible == 0) return
    val width = scope.size.width
    val height = scope.size.height
    val alpha = lerp(0.35f, 0.75f, coverPercent / 100f)

    for (index in 0 until visible) {
      val bank = banks[index]
      val cloudWidth = width * 0.9f * bank.scale
      val travel = width + cloudWidth
      val x = ((bank.seed * travel + t * bank.speed * width) % travel) - cloudWidth / 2
      val y = height * bank.y
      val radius = cloudWidth / 3.4f
      // Tre lobi: il centro pieno, i fianchi piu' morbidi. Radiali, quindi senza bordi duri.
      lobo(scope, x, y, radius * 1.15f, sky.cloudColor, alpha)
      lobo(scope, x - radius, y + radius * 0.25f, radius * 0.9f, sky.cloudColor, alpha * 0.85f)
      lobo(scope, x + radius, y + radius * 0.2f, radius * 0.95f, sky.cloudColor, alpha * 0.85f)
    }
  }

  private fun lobo(scope: DrawScope, x: Float, y: Float, r: Float, color: Color, alpha: Float) {
    scope.drawCircle(
      brush = Brush.radialGradient(
        colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
        center = Offset(x, y),
        radius = r,
      ),
      radius = r,
      center = Offset(x, y),
    )
  }
}

/** Stelle nelle notti pulite: punti fissi, tremolio in aritmetica pura. */
private class StarField {
  private val random = Random(417L)
  private val xs = FloatArray(70) { random.nextFloat() }
  private val ys = FloatArray(70) { random.nextFloat() * 0.55f }
  private val sizes = FloatArray(70) { 0.8f + random.nextFloat() * 1.6f }
  private val phases = FloatArray(70) { random.nextFloat() * 6.28f }
  private val speeds = FloatArray(70) { 0.5f + random.nextFloat() * 1.5f }

  fun draw(scope: DrawScope, t: Float, baseAlpha: Float) {
    val width = scope.size.width
    val height = scope.size.height
    for (i in xs.indices) {
      val twinkle = 0.65f + 0.35f * sin(t * speeds[i] + phases[i])
      scope.drawCircle(
        color = Color.White.copy(alpha = (baseAlpha * twinkle).coerceIn(0f, 1f)),
        radius = sizes[i],
        center = Offset(xs[i] * width, ys[i] * height),
      )
    }
  }
}

/** Pioggia e neve. Conteggi per qualita': FULL paga il realismo, REDUCED la meta', STATIC zero. */
private class ParticleField(kind: WeatherKind?, quality: SceneQuality) {

  private enum class Mode { RAIN, SNOW, NONE }

  private val mode = when (kind) {
    WeatherKind.DRIZZLE, WeatherKind.RAIN, WeatherKind.HEAVY_RAIN,
    WeatherKind.THUNDERSTORM, WeatherKind.SLEET,
    -> Mode.RAIN
    WeatherKind.SNOW, WeatherKind.HEAVY_SNOW -> Mode.SNOW
    else -> Mode.NONE
  }

  private val count: Int = run {
    val base = when (kind) {
      WeatherKind.DRIZZLE -> 60
      WeatherKind.RAIN, WeatherKind.SLEET -> 130
      WeatherKind.HEAVY_RAIN, WeatherKind.THUNDERSTORM -> 220
      WeatherKind.SNOW -> 90
      WeatherKind.HEAVY_SNOW -> 160
      else -> 0
    }
    when (quality) {
      SceneQuality.FULL -> base
      SceneQuality.REDUCED -> base / 2
      SceneQuality.STATIC -> 0
    }
  }

  private val random = Random(7331L)
  private val xs = FloatArray(count) { random.nextFloat() }
  private val phases = FloatArray(count) { random.nextFloat() }
  private val speeds = FloatArray(count) { 0.75f + random.nextFloat() * 0.5f }
  private val lengths = FloatArray(count) { 0.7f + random.nextFloat() * 0.6f }

  fun draw(scope: DrawScope, t: Float) {
    if (count == 0 || mode == Mode.NONE) return
    val width = scope.size.width
    val height = scope.size.height
    when (mode) {
      Mode.RAIN -> {
        val slant = width * 0.02f
        val streak = height * 0.035f
        for (i in xs.indices) {
          val progress = (phases[i] + t * speeds[i] * 0.9f) % 1.1f
          val y = progress * height * 1.1f - height * 0.05f
          val x = xs[i] * width - progress * slant
          scope.drawLine(
            color = Color(0xFFBFD4EA).copy(alpha = 0.38f),
            start = Offset(x, y),
            end = Offset(x - slant * 0.3f, y + streak * lengths[i]),
            strokeWidth = 2f,
            cap = StrokeCap.Round,
          )
        }
      }
      Mode.SNOW -> {
        for (i in xs.indices) {
          val progress = (phases[i] + t * speeds[i] * 0.12f) % 1.1f
          val y = progress * height * 1.1f - height * 0.05f
          val sway = sin(t * speeds[i] + phases[i] * 6.28f) * width * 0.015f
          scope.drawCircle(
            color = Color.White.copy(alpha = 0.65f),
            radius = 2.2f + lengths[i] * 1.8f,
            center = Offset(xs[i] * width + sway, y),
          )
        }
      }
      Mode.NONE -> Unit
    }
  }
}
