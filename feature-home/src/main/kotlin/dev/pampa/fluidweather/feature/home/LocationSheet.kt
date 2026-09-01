package dev.pampa.fluidweather.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.pampa.fluidweather.core.model.Place
import kotlinx.coroutines.delay

/**
 * La pillola espansa: l'elenco delle salvate (GPS sempre in testa) piu' la ricerca. Un tocco
 * su un risultato lo salva e lo seleziona; il cestino toglie; lo swipe sulla pillola scorre
 * questo stesso elenco senza aprirlo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocationSheetHost(
  open: Boolean,
  places: List<Place>,
  selectedId: Long,
  deps: HomeDependencies,
  onDismiss: () -> Unit,
  onSelect: (Place) -> Unit,
  onSaveAndSelect: (Place) -> Unit,
  onRemove: (Place) -> Unit,
) {
  if (!open) return
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = Color(0xFF0B0B0E),
    contentColor = Color.White,
    shape = ContinuousCornerShape(topStart = FluidRadius.Sheet, topEnd = FluidRadius.Sheet),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp)
        .navigationBarsPadding(),
    ) {
      Text(
        text = "Localita'",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
      )
      Spacer(Modifier.height(12.dp))

      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text("Cerca una citta'…", color = Color.White.copy(alpha = 0.4f)) },
        leadingIcon = {
          Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
        },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
          focusedTextColor = Color.White,
          unfocusedTextColor = Color.White,
          focusedBorderColor = Color.White.copy(alpha = 0.4f),
          unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
          cursorColor = Color.White,
        ),
        modifier = Modifier.fillMaxWidth(),
      )
      Spacer(Modifier.height(10.dp))

      LazyColumn {
        if (results.isNotEmpty() || searching) {
          item {
            SheetLabel(if (searching) "Cerco…" else "Risultati")
          }
          items(results, key = { "r${it.id}" }) { place ->
            PlaceRow(
              place = place,
              selected = false,
              trailing = null,
              onClick = {
                onSaveAndSelect(place)
                onDismiss()
              },
            )
          }
        }

        item { SheetLabel("Le tue localita'") }
        items(places, key = { it.id }) { place ->
          PlaceRow(
            place = place,
            selected = place.id == selectedId,
            trailing = if (place.isGps) {
              null
            } else {
              {
                IconButton(onClick = { onRemove(place) }) {
                  Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "Rimuovi",
                    tint = Color.White.copy(alpha = 0.5f),
                  )
                }
              }
            },
            onClick = {
              onSelect(place)
              onDismiss()
            },
          )
        }
        item { Spacer(Modifier.height(24.dp)) }
      }
    }
  }
}

@Composable
private fun SheetLabel(text: String) {
  Text(
    text = text.uppercase(),
    style = MaterialTheme.typography.labelMedium,
    color = Color.White.copy(alpha = 0.5f),
    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
  )
}

@Composable
private fun PlaceRow(
  place: Place,
  selected: Boolean,
  trailing: (@Composable () -> Unit)?,
  onClick: () -> Unit,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(vertical = 10.dp),
  ) {
    if (place.isGps) {
      Icon(
        Icons.Rounded.MyLocation,
        contentDescription = null,
        tint = Color.White.copy(alpha = 0.7f),
      )
      Spacer(Modifier.width(12.dp))
    }
    Column(Modifier.weight(1f)) {
      Text(place.name, style = MaterialTheme.typography.bodyLarge)
      if (place.region != null) {
        Text(
          place.region!!,
          style = MaterialTheme.typography.bodySmall,
          color = Color.White.copy(alpha = 0.55f),
        )
      }
    }
    if (selected) {
      Icon(Icons.Rounded.Check, contentDescription = "Selezionata", tint = MaterialTheme.colorScheme.primary)
    }
    trailing?.invoke()
  }
}
