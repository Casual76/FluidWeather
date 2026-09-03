package dev.pampa.fluidweather.feature.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.fluidweather.core.ai.AiAssistant
import dev.pampa.fluidweather.core.ai.keys.AiSettings
import dev.pampa.fluidweather.core.ai.keys.KeyState
import dev.pampa.fluidweather.core.ai.keys.ThinkingLevel
import dev.pampa.fluidweather.core.ai.provider.ModelCatalogue
import dev.pampa.fluidweather.core.ai.provider.ProviderId
import dev.pampa.fluidweather.strings.R
import java.util.Locale
import kotlinx.coroutines.launch

/** Tutto quello che la pagina dell'assistente tocca. */
class AiSettingsDependencies(
  val assistant: AiAssistant,
  val onOpenDiagnostics: () -> Unit,
)

/**
 * Impostazioni -> Assistente IA: stato, chiavi (con tutorial e verifica), ordine dei provider,
 * modelli per provider, preferenze (ragionamento, voce, azioni, microfono), privacy, diagnostica.
 */
@Composable
fun AiSettingsScreen(deps: AiSettingsDependencies, onBack: () -> Unit) {
  val assistant = deps.assistant
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val settings by assistant.settings.settings.collectAsState(initial = AiSettings())
  val keyStates by assistant.keys.states.collectAsState(initial = emptyMap())
  val remoteOn by assistant.remoteEnabled.collectAsState(initial = true)
  val catalogues by assistant.catalogs.catalogues.collectAsState()
  val keyInfo by assistant.verifier.keyInfo.collectAsState()
  val verified = keyStates.filterValues { it.verified }.keys
  var permissionEpoch by remember { mutableIntStateOf(0) }
  val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionEpoch++ }
  val micGranted = remember(permissionEpoch) { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
  var pickerFor by remember { mutableStateOf<Pair<ProviderId, Boolean>?>(null) } // provider, isStt
  var openRouterSheet by remember { mutableStateOf(false) }

  // I cataloghi si leggono dal disco all'apertura e si rinfrescano se vecchi di un giorno.
  LaunchedEffect(verified) {
    verified.forEach { provider -> scope.launch { assistant.verifier.refreshIfStale(provider) } }
  }

  FluidScreen(title = stringResource(R.string.ai_title), onBack = onBack) {
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.ai_enabled),
          subtitle = when {
            !remoteOn -> stringResource(R.string.ai_suspended_remote)
            verified.isEmpty() -> stringResource(R.string.ai_needs_key)
            else -> stringResource(R.string.ai_enabled_desc)
          },
          badge = {
            FluidSwitch(
              checked = settings.enabled && verified.isNotEmpty() && remoteOn,
              enabled = verified.isNotEmpty() && remoteOn,
              onCheckedChange = { scope.launch { assistant.settings.setEnabled(it) } },
            )
          },
        )
      }
    }

    item { FluidSectionHeader(title = stringResource(R.string.ai_keys_section)) }
    item {
      FluidListGroup {
        ProviderId.entries.forEachIndexed { index, provider ->
          if (index > 0) FluidListDivider()
          AiKeySetup(assistant = assistant, provider = provider, state = keyStates[provider] ?: KeyState(false, null))
          if (provider == ProviderId.OPENROUTER && keyStates[provider]?.present == true) {
            OpenRouterKeyDetails(settings, catalogues[ProviderId.OPENROUTER], keyInfo[ProviderId.OPENROUTER])
          }
        }
      }
    }

    if (verified.size > 1) {
      item { FluidSectionHeader(title = stringResource(R.string.ai_order_section), detail = stringResource(R.string.ai_order_desc)) }
      item {
        Text(stringResource(R.string.ai_order_chat), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        ProviderOrderList(order = settings.chatOrder, available = verified) { scope.launch { assistant.settings.setChatOrder(it) } }
      }
      item {
        Text(stringResource(R.string.ai_order_stt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        ProviderOrderList(order = settings.sttOrder, available = verified) { scope.launch { assistant.settings.setSttOrder(it) } }
      }
    }

    if (verified.isNotEmpty()) {
      item { FluidSectionHeader(title = stringResource(R.string.ai_models_section)) }
      verified.sortedBy { it.ordinal }.forEach { provider ->
        item {
          val catalogue = catalogues[provider]
          val refreshedAt = settings.modelsRefreshedAt[provider]
          FluidListGroup {
            FluidListRow(
              title = "${provider.label} · ${stringResource(R.string.ai_model_chat)}",
              subtitle = ModelSummary(catalogue?.chatById(settings.chatModel(provider)) ?: settings.chatModel(provider)?.let { id -> dev.pampa.fluidweather.core.ai.provider.ModelInfo(id, id, dev.pampa.fluidweather.core.ai.provider.ModelKind.CHAT) }),
              onClick = { if (provider == ProviderId.OPENROUTER) openRouterSheet = true else pickerFor = provider to false },
            )
            FluidListDivider()
            FluidListRow(
              title = "${provider.label} · ${stringResource(R.string.ai_model_stt)}",
              subtitle = catalogue?.sttById(settings.sttModel(provider))?.displayName ?: settings.sttModel(provider),
              onClick = { pickerFor = provider to true },
            )
            FluidListDivider()
            FluidListRow(
              title = stringResource(R.string.ai_models_refresh),
              subtitle = if (refreshedAt != null) stringResource(R.string.ai_models_updated, java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, Locale.getDefault()).format(java.util.Date(refreshedAt))) else stringResource(R.string.ai_models_local),
              onClick = { scope.launch { assistant.verifier.refreshIfStale(provider, force = true) } },
            )
          }
        }
      }
    }

    item { FluidSectionHeader(title = stringResource(R.string.ai_prefs_section)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.ai_thinking),
          subtitle = stringResource(AssistantStrings.thinkingRes(settings.thinking)),
          badge = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              ThinkingLevel.entries.forEach { level ->
                FluidChip(
                  label = stringResource(AssistantStrings.thinkingRes(level)),
                  selected = settings.thinking == level,
                  onClick = { scope.launch { assistant.settings.setThinking(level) } },
                )
              }
            }
          },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.ai_speak),
          subtitle = stringResource(R.string.ai_speak_desc),
          badge = { FluidSwitch(checked = settings.speakReplies, onCheckedChange = { scope.launch { assistant.settings.setSpeakReplies(it) } }) },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.ai_actions),
          subtitle = stringResource(R.string.ai_actions_desc),
          badge = { FluidSwitch(checked = settings.actionsEnabled, onCheckedChange = { scope.launch { assistant.settings.setActionsEnabled(it) } }) },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.ai_mic),
          subtitle = if (micGranted) stringResource(R.string.ai_mic_granted) else stringResource(R.string.ai_mic_denied),
          badge = if (micGranted) {
            null
          } else {
            {
              FluidButton(
                text = stringResource(R.string.ai_mic_allow),
                style = FluidButtonStyle.Tinted,
                size = FluidButtonSize.Small,
                onClick = {
                  micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                  // Negato per sempre: l'unica strada sono le impostazioni di sistema.
                  if (permissionEpoch > 0) {
                    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                  }
                },
              )
            }
          },
        )
      }
    }

    item {
      Text(
        stringResource(R.string.ai_privacy_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      )
    }
    item {
      FluidListGroup {
        FluidListRow(title = stringResource(R.string.diag_title), subtitle = stringResource(R.string.ai_diagnostics_link), onClick = deps.onOpenDiagnostics)
      }
    }
  }

  pickerFor?.let { (provider, isStt) ->
    val catalogue = catalogues[provider] ?: ModelCatalogue(emptyList(), emptyList())
    ModelPickerSheet(
      open = true,
      title = "${provider.label} · ${stringResource(if (isStt) R.string.ai_model_stt else R.string.ai_model_chat)}",
      models = if (isStt) catalogue.stt else catalogue.chat,
      selected = if (isStt) settings.sttModel(provider) else settings.chatModel(provider),
      onSelect = { id -> scope.launch { if (isStt) assistant.settings.setSttModel(provider, id) else assistant.settings.setChatModel(provider, id) } },
      onDismiss = { pickerFor = null },
    )
  }
  OpenRouterModelSheet(
    open = openRouterSheet,
    catalogue = catalogues[ProviderId.OPENROUTER] ?: ModelCatalogue(emptyList(), emptyList()),
    primary = settings.chatModel(ProviderId.OPENROUTER),
    fallbacks = settings.openRouterFallbacks,
    onPrimary = { id -> scope.launch { assistant.settings.setChatModel(ProviderId.OPENROUTER, id) } },
    onFallbacks = { list -> scope.launch { assistant.settings.setOpenRouterFallbacks(list) } },
    onDismiss = { openRouterSheet = false },
  )
}

@Composable
private fun OpenRouterKeyDetails(
  settings: AiSettings,
  catalogue: ModelCatalogue?,
  info: dev.pampa.fluidweather.core.ai.provider.OpenRouterKeyInfo?,
) {
  val chosen = settings.chatModel(ProviderId.OPENROUTER)
  val model = catalogue?.chatById(chosen)
  androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
    val credits = info?.let {
      val left = it.limitRemainingUsd ?: it.limitUsd
      val today = it.usageDailyUsd ?: 0.0
      stringResource(R.string.ai_openrouter_credits, left?.let { v -> String.format(Locale.getDefault(), "%.2f $", v) } ?: "∞", String.format(Locale.getDefault(), "%.3f $", today)) +
        " · " + stringResource(if (it.isFreeTier) R.string.ai_openrouter_free_tier else R.string.ai_openrouter_paid_tier)
    } ?: stringResource(R.string.ai_openrouter_credits_unknown)
    Text(credits, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (chosen != null) {
      Text(
        if (model?.free == true || model == null) stringResource(R.string.ai_openrouter_default_chosen, chosen) else stringResource(R.string.ai_openrouter_no_free, chosen),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (model?.free == true) {
      val today = java.time.LocalDate.now().toEpochDay()
      val count = if (settings.openRouterFreeEpochDay == today) settings.openRouterFreeCount else 0
      val cap = if (info?.isFreeTier == false) 1000 else 50
      Text(stringResource(R.string.ai_openrouter_free_today, count, cap), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}
