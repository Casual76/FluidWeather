package dev.pampa.fluidweather.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidGlassIconButton
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.fluidExpandOrigin
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.glassControlSurface
import dev.antigravity.fluidengine.ui.fluidphysics.FluidMorphMenuButton
import dev.antigravity.fluidengine.ui.fluidphysics.FluidMorphMenuState
import kotlinx.coroutines.launch
import dev.pampa.fluidweather.strings.R
import androidx.compose.ui.res.stringResource

/**
 * La barra flottante a tre isole: radar a sinistra, pillola della localita' al centro, menu' a
 * destra. Il menu' e' un morph di vetro: il tocco lo apre, la pressione lunga pure, nessun
 * gesto nascosto. La pillola e' dello stesso materiale e dello stesso carattere: al tocco si
 * espande in vetro (il pannello vive in [LocationPane]), trascinata a destra o sinistra cambia
 * posto.
 */
@Composable
internal fun HomeFloatingBar(
  locationName: String?,
  backdrop: GlassBackdropState,
  menuState: FluidMorphMenuState,
  /** Vero mentre il pannello delle localita' e' in scena: la pillola si nasconde, il pannello e' lei. */
  locationExpanded: Boolean,
  /** I limiti della pillola in coordinate della radice: l'origine da cui il pannello nasce. */
  onLocationBounds: (Rect) -> Unit,
  onOpenRadar: () -> Unit,
  onOpenBenchmark: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenReport: () -> Unit,
  onLocationTap: () -> Unit,
  /** true = avanti nell'elenco, false = indietro: lo swipe laterale sulla pillola. */
  onLocationSwipe: (forward: Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  // Le voci del menu' si leggono qui, nel composable: la lambda che le costruisce non lo e'.
  val benchmarkLabel = stringResource(R.string.bench_title)
  val reportLabel = stringResource(R.string.report_title)
  val settingsLabel = stringResource(R.string.settings_title)
  val menuActions = {
    listOf(
      FluidContextAction(benchmarkLabel, Icons.Rounded.Insights) { onOpenBenchmark() },
      FluidContextAction(reportLabel, Icons.Rounded.RateReview) { onOpenReport() },
      FluidContextAction(settingsLabel, Icons.Rounded.Settings) { onOpenSettings() },
    )
  }

  Row(
    modifier = modifier.padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    FluidGlassIconButton(onClick = onOpenRadar, backdrop = backdrop) {
      Icon(
        imageVector = Icons.Rounded.Radar,
        contentDescription = stringResource(R.string.radar_title),
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(20.dp),
      )
    }

    LocationPill(
      name = locationName ?: stringResource(R.string.place_my_location),
      backdrop = backdrop,
      expanded = locationExpanded,
      onBounds = onLocationBounds,
      onTap = onLocationTap,
      onSwipe = onLocationSwipe,
      modifier = Modifier.padding(horizontal = 12.dp),
    )

    var menuBounds by remember { mutableStateOf<Rect?>(null) }
    Box(Modifier.onGloballyPositioned { menuBounds = it.boundsInRoot() }) {
      FluidMorphMenuButton(
        state = menuState,
        actions = menuActions,
        onClick = {
          menuBounds?.let { bounds ->
            menuState.open(bounds, null, Icons.Rounded.Menu, menuActions())
          }
        },
        icon = Icons.Rounded.Menu,
        backdrop = backdrop,
      )
    }
  }
}

/** Quanto bisogna trascinare la pillola perche' il gesto conti come "cambia posto". */
private const val SWIPE_THRESHOLD_PX = 56f

/** Il nome segue il dito, ma con un elastico: un terzo dello spostamento, e torna al rilascio. */
private const val LABEL_FOLLOW = 0.35f

/**
 * La pillola della localita': una capsula di vetro (lo stesso [glassControlSurface] dei tasti
 * della barra) coi due gesti del piano, tocco per espandersi e trascinamento laterale per
 * scorrere i posti. Il primo tentativo appoggiava il gesto sopra un `FluidGlassButton`, e il
 * tasto se lo mangiava: qui il rilevatore del trascinamento sta FUORI dal tocco nella catena
 * dei modificatori, cosi' il tocco parte, ma al superamento della soglia il trascinamento lo
 * consuma e il tasto si arrende.
 */
@Composable
private fun LocationPill(
  name: String,
  backdrop: GlassBackdropState,
  expanded: Boolean,
  onBounds: (Rect) -> Unit,
  onTap: () -> Unit,
  onSwipe: (forward: Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val haptics = LocalHapticFeedback.current
  val scope = rememberCoroutineScope()
  val drag = remember { Animatable(0f) }
  // La direzione dell'ultimo swipe: il nome nuovo entra dal lato da cui il dito e' partito.
  var forward by remember { mutableStateOf(true) }

  Box(
    modifier = modifier
      .fluidExpandOrigin(open = { expanded }, onMeasured = onBounds)
      .pointerInput(Unit) {
        detectHorizontalDragGestures(
          onHorizontalDrag = { change, delta ->
            change.consume()
            scope.launch { drag.snapTo(drag.value + delta) }
          },
          onDragEnd = {
            val total = drag.value
            when {
              total < -SWIPE_THRESHOLD_PX -> {
                forward = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onSwipe(true)
              }
              total > SWIPE_THRESHOLD_PX -> {
                forward = false
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onSwipe(false)
              }
            }
            scope.launch {
              drag.animateTo(
                0f,
                spring(dampingRatio = FluidMotion.DampingChrome, stiffness = FluidMotion.ResponseSnappy),
              )
            }
          },
          onDragCancel = { scope.launch { drag.animateTo(0f) } },
        )
      }
      .glassControlSurface(backdrop = backdrop, shape = FluidCapsuleShape)
      .fluidPressable(onClick = onTap, pressedScale = 1f, role = Role.Button)
      .height(48.dp)
      .widthIn(min = 150.dp, max = 220.dp)
      .padding(horizontal = 20.dp),
    contentAlignment = Alignment.Center,
  ) {
    AnimatedContent(
      targetState = name,
      transitionSpec = {
        val sign = if (forward) 1 else -1
        val enter = slideInHorizontally(
          animationSpec = FluidMotion.intOffset(
            dampingRatio = FluidMotion.DampingChrome,
            stiffness = FluidMotion.ResponseSnappy,
          ),
        ) { width -> sign * width / 2 } + fadeIn(FluidMotion.fadeIn(140))
        val exit = slideOutHorizontally(
          animationSpec = FluidMotion.intOffset(
            dampingRatio = FluidMotion.DampingChrome,
            stiffness = FluidMotion.ResponseSnappy,
          ),
        ) { width -> -sign * width / 2 } + fadeOut(FluidMotion.fadeOut(120))
        enter.togetherWith(exit)
      },
      label = "localita",
    ) { label ->
      Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.graphicsLayer { translationX = drag.value * LABEL_FOLLOW },
      )
    }
  }
}
