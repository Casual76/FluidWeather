package dev.pampa.fluidweather.feature.assistant

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.fluidweather.core.ai.provider.ProviderId
import dev.pampa.fluidweather.strings.R

/**
 * L'ordine dei provider: una lista che si riordina tenendo premuto e trascinando (come la
 * griglia della home) e, per chi non trascina, con le frecce su e giu'. Solo i provider con
 * una chiave verificata compaiono; gli altri restano in coda all'ordine salvato.
 */
@Composable
fun ProviderOrderList(
  order: List<ProviderId>,
  available: Set<ProviderId>,
  onReorder: (List<ProviderId>) -> Unit,
) {
  val visible = order.filter { it in available }
  val haptics = LocalHapticFeedback.current
  val rowHeightPx = with(LocalDensity.current) { 64.dp.toPx() }
  var dragging by remember { mutableIntStateOf(-1) }
  var dragOffset by remember { mutableFloatStateOf(0f) }

  fun move(from: Int, to: Int) {
    if (from == to || to !in visible.indices) return
    val mutable = visible.toMutableList()
    val item = mutable.removeAt(from)
    mutable.add(to, item)
    onReorder(mutable + order.filter { it !in available })
  }

  FluidListGroup {
    visible.forEachIndexed { index, provider ->
      if (index > 0) FluidListDivider()
      val isDragging = dragging == index
      FluidListRow(
        title = provider.label,
        subtitle = if (index == 0) stringResource(R.string.ai_model_primary) else stringResource(R.string.ai_model_fallback),
        modifier = Modifier
          .zIndex(if (isDragging) 1f else 0f)
          .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
          .pointerInput(visible) {
            detectDragGesturesAfterLongPress(
              onDragStart = {
                dragging = index
                dragOffset = 0f
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
              },
              onDrag = { change, delta ->
                change.consume()
                dragOffset += delta.y
                val shift = (dragOffset / rowHeightPx).toInt()
                if (shift != 0) {
                  val target = (index + shift).coerceIn(visible.indices)
                  if (target != index) {
                    move(index, target)
                    dragging = -1
                    dragOffset = 0f
                  }
                }
              },
              onDragEnd = { dragging = -1; dragOffset = 0f },
              onDragCancel = { dragging = -1; dragOffset = 0f },
            )
          },
        leading = {
          Icon(Icons.Rounded.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        badge = {
          Row {
            ArrowButton(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.ai_order_move_up), enabled = index > 0) { move(index, index - 1) }
            ArrowButton(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.ai_order_move_down), enabled = index < visible.lastIndex) { move(index, index + 1) }
          }
        },
      )
    }
  }
}

@Composable
private fun ArrowButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
  androidx.compose.foundation.layout.Box(
    modifier = Modifier
      .size(36.dp)
      .fluidPressable(onClick = onClick, enabled = enabled, role = Role.Button),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = description,
      tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.8f else 0.25f),
      modifier = Modifier.size(20.dp),
    )
  }
}
