package dev.pampa.fluidweather.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.FluidHairline
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.pampa.fluidweather.core.model.Place
import kotlinx.coroutines.delay

/**
 * La pillola espansa: la stessa capsula di vetro che si apre nel pannello dell'engine
 * ([FluidGlassModalPresentation.Expand]) — la sagoma viaggia dal tasto al pannello con la
 * rifrazione addosso, come il menu' a destra — con dentro l'elenco delle salvate (GPS sempre in
 * testa) e la ricerca. Un tocco su un risultato lo salva e lo seleziona; la X toglie.
 *
 * Non un foglio nero: la localita' non e' una pagina di dettaglio, e' un cambio di scena, e
 * deve restare attaccata al tasto da cui nasce (feedback sul telefono, 2026-09-02).
 */
@Composable
internal fun LocationPane(
  open: Boolean,
  origin: () -> Rect?,
  places: List<Place>,
  selectedId: Long,
  deps: HomeDependencies,
  onDismiss: () -> Unit,
  onSelect: (Place) -> Unit,
  onSaveAndSelect: (Place) -> Unit,
  onRemove: (Place) -> Unit,
) {
  FluidGlassModalPortal(
    visible = open,
    onDismissRequest = onDismiss,
    origin = origin,
    presentation = FluidGlassModalPresentation.Expand,
    paneTitle = "Localita'",
  ) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    // Debounce: si cerca quando le dita si fermano, non a ogni lettera.
    LaunchedEffect(query) {
      if (query.length < 2) {
        results = emptyList()
        return@LaunchedEffect
      }
      delay(350)
      searching = true
      results = runCatching { deps.geocodingClient.search(query) }.getOrDefault(emptyList())
      searching = false
    }

    Column(Modifier.fillMaxWidth()) {
      FluidTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = "Cerca una citta'…",
        leading = {
          Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp),
          )
        },
        modifier = Modifier
          .fillMaxWidth()
          .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
      )

      LazyColumn(Modifier.heightIn(max = 340.dp)) {
        if (results.isNotEmpty() || searching) {
          item { PaneLabel(if (searching) "Cerco…" else "Risultati") }
          items(results, key = { "r${it.id}" }) { place ->
            PlaceRow(
              place = place,
              selected = false,
              onRemove = null,
              onClick = {
                onSaveAndSelect(place)
                onDismiss()
              },
            )
          }
        }

        item { PaneLabel("Le tue localita'") }
        items(places, key = { it.id }) { place ->
          PlaceRow(
            place = place,
            selected = place.id == selectedId,
            onRemove = if (place.isGps) null else ({ onRemove(place) }),
            onClick = {
              onSelect(place)
              onDismiss()
            },
          )
        }
        item { Spacer(Modifier.size(8.dp)) }
      }

      // Con la tastiera aperta il pannello, che nasce dal basso, deve salire sopra di lei: lo
      // spazio in coda e' esattamente l'altezza della tastiera, cosi' l'elenco resta visibile.
      Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.ime))
    }
  }
}

@Composable
private fun PaneLabel(text: String) {
  Text(
    text = text.uppercase(),
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
  )
}

@Composable
private fun PlaceRow(
  place: Place,
  selected: Boolean,
  onRemove: (() -> Unit)?,
  onClick: () -> Unit,
) {
  val onSurface = MaterialTheme.colorScheme.onSurface
  Column {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = onClick,
        )
        .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
      if (place.isGps) {
        Icon(
          Icons.Rounded.MyLocation,
          contentDescription = null,
          tint = onSurface.copy(alpha = 0.7f),
          modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
      }
      Column(Modifier.weight(1f)) {
        Text(place.name, style = MaterialTheme.typography.bodyLarge, color = onSurface)
        if (place.region != null) {
          Text(
            place.region!!,
            style = MaterialTheme.typography.bodySmall,
            color = onSurface.copy(alpha = 0.55f),
          )
        }
      }
      if (selected) {
        Icon(
          Icons.Rounded.Check,
          contentDescription = "Selezionata",
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(18.dp),
        )
      }
      if (onRemove != null) {
        Spacer(Modifier.width(8.dp))
        Box(
          modifier = Modifier
            .size(28.dp)
            .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null,
              onClick = onRemove,
            ),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            Icons.Rounded.Close,
            contentDescription = "Rimuovi",
            tint = onSurface.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp),
          )
        }
      }
    }
    FluidHairline(startInset = 16.dp)
  }
}
