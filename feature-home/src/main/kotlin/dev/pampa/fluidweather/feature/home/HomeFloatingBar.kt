package dev.pampa.fluidweather.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.fluidExpandOrigin
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.glassControlSurface
import dev.antigravity.fluidengine.ui.fluidphysics.FluidMorphMenuButton
import dev.antigravity.fluidengine.ui.fluidphysics.FluidMorphMenuState
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.ui.haptics.rememberFluidHaptics
import dev.antigravity.fluidengine.ui.tutorial.fluidTutorialAnchor
import kotlinx.coroutines.launch
import dev.pampa.fluidweather.strings.R
import androidx.compose.ui.res.stringResource

/**
 * Quello che la barra sa dell'assistente, senza conoscerlo (fase 19): se mostrare il tasto, se
 * sta lavorando, cosa fare al tocco (voce) e alla pressione lunga (testo). Lo costruisce `:app`.
 */
@Stable
class HomeAssistantBar(
  val enabled: Boolean,
  val working: Boolean,
  /** L'assistente e' in scena (aureola, card o barra di scrittura): la home si fa da parte. */
  val active: Boolean = false,
  val onTap: () -> Unit,
  val onLongPress: () -> Unit,
  /** Dove sta il tasto, in coordinate della radice: e' da qui che la card si trasforma. */
  val onBounds: (Rect) -> Unit = {},
)

/** L'altezza della barra e dei tasti tondi (decisione 2026-09-02: piu' grandi, a tutta larghezza). */
val HomeBarHeight = 54.dp
private val BarIconSize = 22.dp
private val BarGap = 10.dp

/**
 * La barra flottante a tutta larghezza: radar a sinistra, pillola della localita' elastica al
 * centro, il tasto dell'assistente (solo se attivo) e il menu' a destra. Il menu' e' un morph
 * di vetro: il tocco lo apre, la pressione lunga pure. La pillola e' dello stesso materiale: al
 * tocco si espande in vetro (il pannello vive in [LocationPane]), trascinata cambia posto.
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
  assistant: HomeAssistantBar?,
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
    horizontalArrangement = Arrangement.spacedBy(BarGap),
  ) {
    BarIconButton(
      icon = Icons.Rounded.Radar,
      contentDescription = stringResource(R.string.radar_title),
      backdrop = backdrop,
      onClick = onOpenRadar,
      modifier = Modifier.fluidTutorialAnchor("home_radar"),
    )

    LocationPill(
      name = locationName ?: stringResource(R.string.place_my_location),
      backdrop = backdrop,
      expanded = locationExpanded,
      onBounds = onLocationBounds,
      onTap = onLocationTap,
      onSwipe = onLocationSwipe,
      modifier = Modifier
        .weight(1f)
        .fluidTutorialAnchor("home_pill"),
    )

    AnimatedVisibility(
      visible = assistant?.enabled == true,
      enter = expandHorizontally(FluidMotion.intSize(FluidMotion.DampingChrome, FluidMotion.ResponseSnappy)) + fadeIn(FluidMotion.fadeIn(160)),
      exit = shrinkHorizontally(FluidMotion.intSize(FluidMotion.DampingChrome, FluidMotion.ResponseSnappy)) + fadeOut(FluidMotion.fadeOut(120)),
    ) {
      AssistantButton(
        working = assistant?.working == true,
        backdrop = backdrop,
        onTap = { assistant?.onTap?.invoke() },
        onLongPress = { assistant?.onLongPress?.invoke() },
        modifier = Modifier
          .fluidTutorialAnchor("home_ai")
          .onGloballyPositioned { assistant?.onBounds?.invoke(it.boundsInRoot()) },
      )
    }

    var menuBounds by remember { mutableStateOf<Rect?>(null) }
    Box(Modifier.onGloballyPositioned { menuBounds = it.boundsInRoot() }) {
      // Il modifier esterno precede l'altezza fissa dell'engine (44 dp): un vincolo fisso di 54 dp
      // vince, e il vetro copre tutta la capsula.
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
        modifier = Modifier
          .size(HomeBarHeight)
          .fluidTutorialAnchor("home_menu"),
      )
    }
  }
}

/**
 * Un tasto tondo di vetro da 54 dp: la stessa ricetta di `FluidGlassIconButton` dell'engine, che
 * pero' ha misure interne fisse (48/44 dp) e non si presta alla barra piu' grande.
 */
@Composable
private fun BarIconButton(
  icon: ImageVector,
  contentDescription: String,
  backdrop: GlassBackdropState,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
  /** Cosa si sente al rilascio; null per un tasto la cui azione parla gia' da sola. */
  haptic: FluidHapticEvent? = FluidHapticEvent.Tap,
  content: (@Composable () -> Unit)? = null,
) {
  Box(
    modifier = modifier
      .size(HomeBarHeight)
      .glassControlSurface(backdrop = backdrop, shape = FluidCapsuleShape)
      .fluidPressable(onClick = onClick, onLongClick = onLongClick, pressedScale = 1f, role = Role.Button, haptic = haptic),
    contentAlignment = Alignment.Center,
  ) {
    if (content != null) {
      content()
    } else {
      Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(BarIconSize),
      )
    }
  }
}

/** Il tasto dell'assistente: tocco = voce, pressione lunga = testo; mentre lavora l'icona respira. */
@Composable
private fun AssistantButton(
  working: Boolean,
  backdrop: GlassBackdropState,
  onTap: () -> Unit,
  onLongPress: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  val pulse = rememberInfiniteTransition(label = "assistantPulse")
  val scale by pulse.animateFloat(
    initialValue = 1f,
    targetValue = 1.18f,
    animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
    label = "assistantScale",
  )
  val description = stringResource(R.string.a11y_assistant_button)
  BarIconButton(
    icon = Icons.Rounded.AutoAwesome,
    contentDescription = description,
    backdrop = backdrop,
    onClick = onTap,
    onLongClick = onLongPress,
    modifier = modifier,
    // Il tocco apre il microfono e l'assistente risponde subito con la sua salita: un tap in
    // piu', a un decimo di secondo di distanza, si sentirebbe come una sbavatura sola.
    haptic = null,
  ) {
    Icon(
      imageVector = Icons.Rounded.AutoAwesome,
      contentDescription = description,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier
        .size(BarIconSize)
        .graphicsLayer {
          if (working && !reducedMotion) {
            scaleX = scale
            scaleY = scale
          }
        },
    )
  }
}

/** Quanto bisogna trascinare la pillola perche' il gesto conti come "cambia posto". */
private const val SWIPE_THRESHOLD_PX = 56f

/** Il nome segue il dito, ma con un elastico: un terzo dello spostamento, e torna al rilascio. */
private const val LABEL_FOLLOW = 0.35f

/**
 * La pillola della localita': una capsula di vetro (lo stesso [glassControlSurface] dei tasti
 * della barra) coi due gesti del piano, tocco per espandersi e trascinamento laterale per
 * scorrere i posti. Il rilevatore del trascinamento sta FUORI dal tocco nella catena dei
 * modificatori, cosi' il tocco parte, ma al superamento della soglia il trascinamento lo
 * consuma e il tasto si arrende. Riempie lo spazio fra i tasti tondi (weight dal chiamante).
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
  val haptics = rememberFluidHaptics()
  val scope = rememberCoroutineScope()
  val drag = remember { Animatable(0f) }
  // La direzione dell'ultimo swipe: il nome nuovo entra dal lato da cui il dito e' partito.
  var forward by remember { mutableStateOf(true) }
  // `pointerInput(Unit)` non aggiorna mai il suo nodo, quindi teneva per sempre la PRIMA lambda
  // `onSwipe` — quella che aveva catturato la localita' selezionata al momento in cui la pillola
  // e' entrata in composizione. Ogni swipe ripartiva da quella: avanti portava sempre alla stessa,
  // indietro a un'altra ancora, e alla fine si oscillava fra due posti. `rememberUpdatedState` da'
  // al nodo sempre l'ultima, che e' lo stesso rimedio che la pagina della luna usa da sempre.
  val currentSwipe = rememberUpdatedState(onSwipe)

  Box(
    modifier = modifier
      .fluidExpandOrigin(open = { expanded }, onMeasured = onBounds)
      .pointerInput(Unit) {
        detectHorizontalDragGestures(
          // Il gesto riparte da zero: se il rientro elastico del precedente e' ancora in volo, il
          // suo residuo si sommava a questo e la soglia scattava prima, o dalla parte sbagliata.
          onDragStart = { scope.launch { drag.snapTo(0f) } },
          onHorizontalDrag = { change, delta ->
            change.consume()
            scope.launch { drag.snapTo(drag.value + delta) }
          },
          onDragEnd = {
            val total = drag.value
            when {
              total < -SWIPE_THRESHOLD_PX -> {
                forward = true
                haptics.play(FluidHapticEvent.Threshold)
                currentSwipe.value(true)
              }
              total > SWIPE_THRESHOLD_PX -> {
                forward = false
                haptics.play(FluidHapticEvent.Threshold)
                currentSwipe.value(false)
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
      // Niente feedback qui: il pannello di vetro che si apre dice Open da solo, e due
      // aperture per un tocco solo si sentono come un difetto.
      .fluidPressable(onClick = onTap, pressedScale = 1f, role = Role.Button, haptic = null)
      .height(HomeBarHeight)
      .padding(horizontal = 18.dp),
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
