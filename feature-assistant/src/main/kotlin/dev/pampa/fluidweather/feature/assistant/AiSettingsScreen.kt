package dev.pampa.fluidweather.feature.assistant

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ai.keys.AiSettings
import dev.antigravity.fluidengine.ai.keys.VerifyResult
import dev.antigravity.fluidengine.aiui.settings.FluidAiLock
import dev.antigravity.fluidengine.aiui.settings.FluidAiSettingsActions
import dev.antigravity.fluidengine.aiui.settings.FluidAiSettingsOptions
import dev.antigravity.fluidengine.aiui.settings.FluidAiSettingsSlots
import dev.antigravity.fluidengine.aiui.settings.FluidAiSettingsState
import dev.antigravity.fluidengine.aiui.settings.fluidAiSettingsSection
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.tutorial.fluidTutorialAnchor
import dev.pampa.fluidweather.core.ai.AiAssistant
import dev.pampa.fluidweather.core.ui.TutorialScreen
import dev.pampa.fluidweather.core.ui.TutorialSlot
import dev.pampa.fluidweather.strings.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Cosa serve alla pagina: l'assistente, e dove si va per la diagnostica. */
class AiSettingsDependencies(
  val assistant: AiAssistant,
  val onOpenDiagnostics: () -> Unit,
)

/**
 * Impostazioni -> Assistente IA: la sezione di `engine-ai-ui` (interruttore, chiavi con tutorial e
 * verifica, ordine dei servizi con trascinamento, modelli nel vetro, preferenze, microfono, privacy)
 * e, in coda, il link alla diagnostica. Qui resta il dominio dell'app: il blocco a distanza del
 * manifest (`locked`), il suggerimento al primo uso, le parole nelle due lingue.
 */
@Composable
fun AiSettingsScreen(deps: AiSettingsDependencies, onBack: () -> Unit) {
  val assistant = deps.assistant
  val scope = rememberCoroutineScope()
  val settings by assistant.settings.settings.collectAsState(initial = AiSettings())
  val keyStates by assistant.keys.states.collectAsState(initial = emptyMap())
  val remoteOn by assistant.remoteEnabled.collectAsState(initial = true)
  val catalogues by assistant.catalogs.catalogues.collectAsState()
  val keyInfo by assistant.verifier.keyInfo.collectAsState()
  val strings = rememberAiSettingsStrings()
  val suspendedReason = stringResource(R.string.ai_suspended_remote)
  val verified = keyStates.filterValues { it.verified }.keys

  // I cataloghi si leggono dal disco all'apertura e si rinfrescano se vecchi di un giorno.
  LaunchedEffect(verified) {
    verified.forEach { provider -> scope.launch { assistant.verifier.refreshIfStale(provider) } }
  }

  val state = FluidAiSettingsState(
    settings = settings,
    keys = keyStates,
    catalogues = catalogues,
    keyInfo = keyInfo,
    locked = if (remoteOn) null else FluidAiLock(suspendedReason),
  )
  val actions = remember(assistant) {
    FluidAiSettingsActions(
      saveAndVerify = { provider, key ->
        try {
          if (key != null) assistant.keys.set(provider, key)
          assistant.verifier.verify(provider)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Throwable) {
          // Qui si tocca il Keystore, il disco e la rete: la traccia va al quaderno della Diagnostica.
          assistant.reportError(e, "verifica chiave ${provider.id}")
          throw e
        }
      },
      removeKey = { provider -> scope.launch { assistant.keys.set(provider, null) } },
      setEnabled = { scope.launch { assistant.settings.setEnabled(it) } },
      setChatOrder = { scope.launch { assistant.settings.setChatOrder(it) } },
      setSttOrder = { scope.launch { assistant.settings.setSttOrder(it) } },
      setModel = { provider, tier, id -> scope.launch { assistant.settings.setModel(provider, tier, id) } },
      setSttModel = { provider, id -> scope.launch { assistant.settings.setSttModel(provider, id) } },
      setOpenRouterFallbacks = { scope.launch { assistant.settings.setOpenRouterFallbacks(it) } },
      setOpenRouterDataPolicy = { scope.launch { assistant.settings.setOpenRouterDataPolicy(it) } },
      refreshCatalogue = { provider -> scope.launch { assistant.verifier.refreshIfStale(provider, force = true) } },
      setThinking = { scope.launch { assistant.settings.setThinking(it) } },
      setSpeakReplies = { scope.launch { assistant.settings.setSpeakReplies(it) } },
      setActionsEnabled = { scope.launch { assistant.settings.setActionsEnabled(it) } },
    )
  }
  val diagnosticsTitle = stringResource(R.string.diag_title)
  val diagnosticsLink = stringResource(R.string.ai_diagnostics_link)

  FluidScreen(title = stringResource(R.string.ai_title), onBack = onBack) {
    fluidAiSettingsSection(
      state = state,
      actions = actions,
      strings = strings,
      // Un solo modello per servizio (niente router e niente profondo): e' quello che l'app ha sempre avuto.
      options = FluidAiSettingsOptions(tiers = false, recentRequests = false),
      slots = FluidAiSettingsSlots(
        beforeKeys = {
          item(key = "ai-tutorial") {
            // Il suggerimento al primo uso si aggancia a una riga sottile in cima: la sezione
            // dell'engine disegna da se' il gruppo dell'interruttore, e non porta un'ancora.
            TutorialSlot(screen = TutorialScreen.AI)
            Spacer(Modifier.fluidTutorialAnchor("ai_state_group").fillMaxWidth().height(1.dp))
          }
        },
        footer = {
          item(key = "ai-diagnostics") {
            FluidListGroup {
              FluidListRow(title = diagnosticsTitle, subtitle = diagnosticsLink, onClick = deps.onOpenDiagnostics)
            }
          }
        },
      ),
      glass = false,
    )
  }
}
