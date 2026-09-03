package dev.pampa.fluidweather.feature.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.fluidweather.core.ai.provider.ModelCatalogue
import dev.pampa.fluidweather.core.ai.provider.ModelInfo
import dev.pampa.fluidweather.core.ai.provider.OpenRouterCatalog
import dev.pampa.fluidweather.core.ai.provider.ProviderId
import dev.pampa.fluidweather.core.ui.BlackSheet
import dev.pampa.fluidweather.core.ui.BlackSheetNote
import dev.pampa.fluidweather.core.ui.BlackSheetSectionTitle
import dev.pampa.fluidweather.strings.R
import java.util.Locale

private fun contextLabel(tokens: Int?): String? = tokens?.let {
  when {
    it >= 1_000_000 -> "${it / 1_000_000}M"
    it >= 1_000 -> "${it / 1_000}k"
    else -> it.toString()
  }
}

private fun priceLabel(perM: Double?): String = when {
  perM == null -> "—"
  perM == 0.0 -> "0 $"
  perM < 0.01 -> "<0,01 $"
  else -> String.format(Locale.getDefault(), "%.2f $", perM)
}

/** Un modello in una riga: nome, id, contesto, prezzo se esiste, badge. */
@Composable
private fun ModelRow(model: ModelInfo, selected: Boolean, extra: String?, onClick: () -> Unit, badge: (@Composable () -> Unit)? = null) {
  val meta = buildList {
    contextLabel(model.contextWindow)?.let { add(stringResource(R.string.ai_model_context, it)) }
    if (model.pricePromptPerM != null) add(stringResource(R.string.ai_model_price, priceLabel(model.pricePromptPerM), priceLabel(model.priceCompletionPerM)))
    if (model.free) add(stringResource(R.string.ai_model_free))
    if (model.supportsReasoning) add(stringResource(R.string.ai_model_filter_reasoning))
    extra?.let { add(it) }
  }.joinToString(" · ")
  FluidListRow(
    title = model.displayName,
    subtitle = if (model.id == model.displayName) meta else "${model.id}${if (meta.isNotEmpty()) " · $meta" else ""}",
    onClick = onClick,
    leading = if (selected) {
      { Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.common_chosen), tint = MaterialTheme.colorScheme.primary) }
    } else {
      null
    },
    badge = badge,
  )
}

/** L'elenco semplice di Groq e Gemini: il preferito in testa, un tocco sceglie. */
@Composable
fun ModelPickerSheet(
  open: Boolean,
  title: String,
  models: List<ModelInfo>,
  selected: String?,
  onSelect: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  if (!open) return
  BlackSheet(title = title, onDismiss = onDismiss) {
    if (models.isEmpty()) {
      BlackSheetNote(stringResource(R.string.ai_model_none))
    } else {
      FluidListGroup {
        models.forEachIndexed { index, model ->
          if (index > 0) FluidListDivider()
          ModelRow(model = model, selected = model.id == selected, extra = null, onClick = { onSelect(model.id); onDismiss() })
        }
      }
    }
    Spacer(Modifier.height(24.dp))
  }
}

/**
 * Il catalogo di OpenRouter: ricerca, filtri (i tool sono obbligatori e non si tolgono),
 * sezioni Consigliati / Gratuiti / Tutti, il primario e fino a due riserve.
 */
@Composable
fun OpenRouterModelSheet(
  open: Boolean,
  catalogue: ModelCatalogue,
  primary: String?,
  fallbacks: List<String>,
  onPrimary: (String) -> Unit,
  onFallbacks: (List<String>) -> Unit,
  onDismiss: () -> Unit,
) {
  if (!open) return
  var query by remember { mutableStateOf("") }
  var onlyReasoning by remember { mutableStateOf(false) }
  var onlyFree by remember { mutableStateOf(false) }
  val all = catalogue.chat.filter { it.supportsTools }
  val filtered = all.filter { model ->
    (query.isBlank() || model.id.contains(query, ignoreCase = true) || model.displayName.contains(query, ignoreCase = true)) &&
      (!onlyReasoning || model.supportsReasoning) &&
      (!onlyFree || model.free)
  }
  val recommended = OpenRouterCatalog.recommended(catalogue).filter { it in filtered }
  val free = filtered.filter { it.free }
  val fullLabel = stringResource(R.string.ai_model_fallbacks_full)
  BlackSheet(title = ProviderId.OPENROUTER.label, onDismiss = onDismiss) {
    FluidTextField(
      value = query,
      onValueChange = { query = it },
      placeholder = stringResource(R.string.ai_model_search),
      modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
      FluidChip(label = stringResource(R.string.ai_model_filter_tools), selected = true, onClick = {}, enabled = false)
      FluidChip(label = stringResource(R.string.ai_model_filter_reasoning), selected = onlyReasoning, onClick = { onlyReasoning = !onlyReasoning })
      FluidChip(label = stringResource(R.string.ai_model_filter_free), selected = onlyFree, onClick = { onlyFree = !onlyFree })
    }
    val sections = listOf(
      stringResource(R.string.ai_model_recommended) to recommended,
      stringResource(R.string.ai_model_free) to free,
      stringResource(R.string.ai_model_all) to filtered,
    )
    sections.forEach { (title, models) ->
      if (models.isEmpty()) return@forEach
      BlackSheetSectionTitle(title)
      FluidListGroup {
        models.take(if (title == sections.last().first) 200 else 24).forEachIndexed { index, model ->
          if (index > 0) FluidListDivider()
          val isPrimary = model.id == primary
          val isFallback = model.id in fallbacks
          ModelRow(
            model = model,
            selected = isPrimary,
            extra = if (isFallback) stringResource(R.string.ai_model_fallback) else null,
            onClick = { onPrimary(model.id); if (isFallback) onFallbacks(fallbacks - model.id) },
            badge = if (isPrimary) {
              null
            } else {
              {
                FluidButton(
                  text = if (isFallback) stringResource(R.string.ai_model_remove_fallback) else stringResource(R.string.ai_model_use_as_fallback),
                  style = FluidButtonStyle.Plain,
                  size = FluidButtonSize.Small,
                  enabled = isFallback || fallbacks.size < 2,
                  onClick = { onFallbacks(if (isFallback) fallbacks - model.id else (fallbacks + model.id).take(2)) },
                )
              }
            },
          )
        }
      }
      if (fallbacks.size >= 2 && title == sections.first().first) BlackSheetNote(fullLabel)
      Spacer(Modifier.height(8.dp))
    }
    if (filtered.isEmpty()) BlackSheetNote(stringResource(R.string.ai_model_none))
    Spacer(Modifier.height(24.dp))
  }
}

@Composable
internal fun ModelSummary(model: ModelInfo?): String = when {
  model == null -> "—"
  model.free -> "${model.displayName} · ${stringResource(R.string.ai_model_free)}"
  else -> model.displayName
}

internal fun ModelCatalogue.chatById(id: String?): ModelInfo? = chat.firstOrNull { it.id == id }
internal fun ModelCatalogue.sttById(id: String?): ModelInfo? = stt.firstOrNull { it.id == id }
