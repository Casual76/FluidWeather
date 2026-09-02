package dev.pampa.fluidweather.feature.home

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.pampa.fluidweather.core.ui.HeaderCollapse
import kotlin.math.abs

/**
 * Quanto e' alta la testata grande, misurata dal layout: e' il viaggio del collasso. Finche'
 * non e' misurata vale il default di [HeaderCollapse]; poi la testata la aggiorna a ogni
 * cambio di taglia (font scale, lingua, riga di allerta).
 */
@Stable
internal class HeaderCollapseState {

  var travelPx by mutableFloatStateOf(HeaderCollapse.DEFAULT_TRAVEL_PX)

  /** Quanto e' scorsa la pagina rispetto alla testata: infinito se la testata e' gia' passata. */
  fun scrolledPx(gridState: LazyGridState): Float =
    if (gridState.firstVisibleItemIndex > 0) {
      Float.POSITIVE_INFINITY
    } else {
      gridState.firstVisibleItemScrollOffset.toFloat()
    }

  fun progress(gridState: LazyGridState): Float =
    HeaderCollapse.progress(scrolledPx(gridState), travelPx)
}

/**
 * Il fling che finisce sempre il passaggio di consegne fra testata grande e compatta.
 *
 * Deve succedere DENTRO il fling, non in un effetto che guarda la lista fermarsi: quel secondo
 * scorrimento competerebbe con la sessione di scroll che il gesto possiede ancora e verrebbe
 * rifiutato. Qui lo snap e' la coda dello stesso gesto: un nuovo tocco si riprende la sessione
 * e lo interrompe, che e' esattamente il comportamento che un dito deve avere. E' la ricetta di
 * `rememberFluidTitleSnapFling` dell'engine, che pero' e' cucita sul suo titolo e non sulla
 * nostra testata.
 */
internal class HeaderSnapFlingBehavior(
  private val delegate: FlingBehavior,
  private val animated: Boolean,
  private val snapDeltaPx: () -> Float,
) : FlingBehavior {

  override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
    val remaining = with(delegate) { performFling(initialVelocity) }
    val delta = snapDeltaPx()
    if (!delta.isFinite() || abs(delta) < 0.5f) return remaining
    if (!animated) {
      scrollBy(delta)
      return 0f
    }
    var applied = 0f
    animate(
      initialValue = 0f,
      targetValue = delta,
      animationSpec = spring(
        dampingRatio = FluidMotion.DampingChrome,
        stiffness = FluidMotion.ResponseSnappy,
        visibilityThreshold = 0.5f,
      ),
    ) { value, _ ->
      applied += scrollBy(value - applied)
    }
    return 0f
  }
}
