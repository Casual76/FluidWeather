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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.LocalFluidCanvasBackdrop
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import androidx.compose.ui.semantics.semantics

/**
 * La tessera della griglia: vetro di contenuto quando c'e' un canvas in scena (il cielo),
 * superficie opaca traslucida quando non c'e' — il degrado dichiarato del vetro adattivo.
 * Il vetro sta sul CONTENITORE, mai sulle righe dentro: e' la regola del design system.
 */
@Composable
fun GlassTile(
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  /** Cosa fa il tocco, per TalkBack: "tocca due volte per Apri Nowcast". */
  onClickLabel: String? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val canvas = LocalFluidCanvasBackdrop.current

  val surface = if (canvas != null) {
    Modifier.glassSurface(
      state = canvas,
      tint = GlassDefaults.contentTint(),
      shape = TileShape,
      role = GlassRole.Content,
      // Il ruolo `Content` presume uno sfondo statico e cattura una volta sola per tutta la vita
      // del nodo. Qui lo sfondo e' un cielo che si muove: quella cattura era il primo fotogramma
      // di cielo, tenuto per sempre. Da fuori si vedeva come "il vetro si e' fermato", e su una
      // tessera riciclata dalla griglia come "questa tessera non ha il vetro".
      sampleOnce = false,
      // Ma non a ogni fotogramma: nove tessere vive costavano 17 ms in piu' per fotogramma
      // (misurato sul telefono). A 30 Hz — il passo con cui si muove anche il cielo — la
      // rifrazione non e' mai piu' vecchia di un fotogramma di scena, quindi non si vedono
      // scatti, e resta un quarto del costo di una cattura per fotogramma. Il risparmio grosso
      // durante lo scorrimento lo fa la qualita' che scende col dito (`FluidGlassQuality`):
      // lente, dispersione e bordi si assottigliano, la sfocatura resta.
      resampleIntervalMillis = 33L,
    )
  } else {
    Modifier.background(
      MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f),
      TileShape,
    )
  }

  val interaction = remember { MutableInteractionSource() }
  Column(
    modifier = modifier
      .fillMaxWidth()
      .clip(TileShape)
      .then(surface)
      .then(
        if (onClick != null) {
          // Un solo nodo per TalkBack: la tessera si legge intera, poi il suo gesto.
          Modifier
            .semantics(mergeDescendants = true) {}
            // Muta di proposito: la tessera apre il foglio nero del dato, ed e' il foglio a
            // dire Open quando arriva. Qui si sentirebbe la stessa apertura due volte.
            .clickable(interactionSource = interaction, indication = null, onClickLabel = onClickLabel, onClick = onClick)
        } else {
          Modifier
        },
      )
      .padding(14.dp),
    content = content,
  )
}

/**
 * La forma delle tessere, allocata una volta sola. Dentro la composizione era un oggetto nuovo a
 * ogni ricomposizione, e bastava quello a far ricostruire la catena di `RenderEffect` del vetro.
 */
private val TileShape: Shape = ContinuousCornerShape(FluidRadius.Group)
