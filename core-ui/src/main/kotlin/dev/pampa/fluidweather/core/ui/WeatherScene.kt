package dev.pampa.fluidweather.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import kotlin.math.roundToInt
import kotlinx.coroutines.android.awaitFrame

/**
 * Il cielo animato a tutto schermo: gradiente atmosferico per fase del giorno, il sole o la luna
 * (con la sua fase vera) al loro posto, cumuli con la faccia al sole e la pancia in ombra o la
 * coltre del cielo coperto, cirri, stelle che tremolano e la Via Lattea nelle notti pulite,
 * pioggia e neve su piu' piani, i lampi del temporale, la nebbia a bande.
 *
 * **Ogni fotogramma di questa scena non costa solo questa scena.** Il cielo e' la sorgente del
 * vetro: quando si ridisegna, il suo sottoalbero viene registrato di nuovo in un GraphicsLayer e
 * ogni pannello che lo campiona (nove tessere, la barra, la pillola, i pannelli aperti) rifa' la
 * propria catena di effetti. Misurato sul telefono il 2026-09-03, a schermo fermo: 213 fotogrammi
 * in 5 secondi, **tutti** oltre il budget, 69 ms l'uno sul thread della UI. Da qui le tre regole:
 *
 * 1. [running] falso quando la scena non si vede (un foglio aperto sopra, l'app in background):
 *    non e' una decorazione da tenere viva sotto qualcos'altro.
 * 2. Il tempo avanza al massimo [SceneFrameMillis]: le nuvole si spostano di un centesimo di
 *    schermo al secondo, e a 30 Hz invece che a 120 nessuno vede la differenza. Tre fotogrammi su
 *    quattro non toccano piu' nessuno stato, quindi non invalidano niente.
 * 3. Fuori dal ciclo di vita non si anima: `repeatOnLifecycle(RESUMED)`.
 *
 * I pennelli vivono in [SkyPainters.kt] e non qui dentro: il widget di sistema rasterizza **gli
 * stessi** in una bitmap, ed e' l'unico modo perche' i due cieli non possano divergere.
 */
@Composable
fun WeatherScene(
  state: SkyState,
  quality: SceneQuality,
  modifier: Modifier = Modifier,
  /** Falso mentre la scena e' coperta o l'app non e' davanti: il tempo si ferma dov'e'. */
  running: Boolean = true,
) {
  val sky = remember(state) { SkyPalette.sky(state.phase, state.kind, state.cloudCoverPercent) }

  // Il cielo cambia colore con calma: un fronte che arriva e' una dissolvenza, non uno scatto.
  // Si animano le cinque fermate e i tre colori delle nuvole, e il fotogramma legge quelli.
  val stops = sky.stops.mapIndexed { index, (_, color) -> animatedSkyColor(color, "sky-$index") }
  val cloud = animatedSkyColor(sky.cloudColor, "cloud")
  val cloudShadow = animatedSkyColor(sky.cloudShadow, "cloud-shadow")
  val haze = animatedSkyColor(sky.hazeColor, "haze")

  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  val animated = quality != SceneQuality.STATIC && !reducedMotion

  var frameSeconds by remember { mutableFloatStateOf(0f) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(animated, running, lifecycle) {
    if (!animated || !running) return@LaunchedEffect
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      // Il tempo riparte da dove si era fermato: riprendendo l'app le nuvole non saltano.
      val startNanos = awaitFrame() - (frameSeconds * 1_000_000_000f).toLong()
      var lastWrite = 0L
      while (true) {
        val now = awaitFrame()
        if (now - lastWrite < SceneFrameNanos) continue
        lastWrite = now
        frameSeconds = (now - startNanos) / 1_000_000_000f
      }
    }
  }

  // La geometria della scena (dove stanno le nuvole, le stelle, i fiocchi) si decide una volta e
  // resta finche' non cambia il tempo. La copertura si arrotonda al 10%: un 43% che diventa 44%
  // non deve rifare le nuvole da capo.
  val coverBucket = ((state.cloudCoverPercent ?: SkyPalette.defaultCover(state.kind)) / 10.0).roundToInt() * 10.0
  val painter = remember(state.kind, coverBucket, quality) {
    SkyScenePainter(state.copy(cloudCoverPercent = coverBucket), quality, SkyVariation.seedFor(System.currentTimeMillis()))
  }
  // L'astro si ricalcola a ogni minuto di orologio, non a ogni fotogramma: si sposta di un
  // quattrocentesimo di schermo al minuto, e le effemeridi non sono gratis.
  val minute = (frameSeconds / 60f).toInt()
  val body = remember(state.latitude, state.longitude, minute) {
    Celestial.at(System.currentTimeMillis(), state.latitude, state.longitude)
  }

  // La fotografia della luna: `imageResource` la decodifica una volta e la ricorda.
  val moon = ImageBitmap.imageResource(R.drawable.moon_nearside)

  Canvas(modifier) {
    val animatedSky = sky.copy(
      stops = sky.stops.mapIndexed { index, (at, _) -> at to stops[index].value },
      gradient = listOf(stops[0].value, stops[2].value, stops[4].value),
      cloudColor = cloud.value,
      cloudShadow = cloudShadow.value,
      hazeColor = haze.value,
    )
    painter.draw(this, animatedSky, body, if (animated) frameSeconds else 0f, moon)
  }
}

@Composable
private fun animatedSkyColor(target: Color, label: String): State<Color> =
  animateColorAsState(target, FluidMotion.color(1200), label = label)

/**
 * Il passo del cielo: 30 fotogrammi al secondo. Non e' una resa piu' bassa, e' la stessa scena
 * campionata al ritmo con cui si muove davvero — e su un telefono a 120 Hz sono tre invalidazioni
 * del vetro risparmiate su quattro.
 */
private const val SceneFrameMillis = 33L
private const val SceneFrameNanos = SceneFrameMillis * 1_000_000L
