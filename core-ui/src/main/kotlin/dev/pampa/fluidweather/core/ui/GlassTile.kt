package dev.pampa.fluidweather.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.LocalFluidCanvasBackdrop
import dev.antigravity.fluidengine.ui.fluid.glassSurface

/**
 * La tessera della griglia: vetro di contenuto quando c'e' un canvas in scena (il cielo),
 * superficie opaca traslucida quando non c'e' — il degrado dichiarato del vetro adattivo.
 * Il vetro sta sul CONTENITORE, mai sulle righe dentro: e' la regola del design system.
 */
@Composable
fun GlassTile(
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val canvas = LocalFluidCanvasBackdrop.current
  val shape = ContinuousCornerShape(FluidRadius.Group)

  val surface = if (canvas != null) {
    Modifier.glassSurface(
      state = canvas,
      tint = GlassDefaults.contentTint(),
      shape = shape,
      role = GlassRole.Content,
    )
  } else {
    Modifier.background(
      MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f),
      shape,
    )
  }

  val interaction = remember { MutableInteractionSource() }
  Column(
    modifier = modifier
      .fillMaxWidth()
      .clip(shape)
      .then(surface)
      .then(
        if (onClick != null) {
          Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
        } else {
          Modifier
        },
      )
      .padding(14.dp),
    content = content,
  )
}
