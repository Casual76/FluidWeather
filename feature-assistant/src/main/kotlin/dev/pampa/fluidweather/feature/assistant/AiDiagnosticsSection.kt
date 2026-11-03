package dev.pampa.fluidweather.feature.assistant

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.antigravity.fluidengine.ai.keys.AiSettings
import dev.antigravity.fluidengine.ai.keys.KeyState
import dev.antigravity.fluidengine.ai.net.RateLimitInfo
import dev.antigravity.fluidengine.ai.orchestrator.AiRequestLog
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.aiui.settings.fluidAiDiagnosticsSection
import dev.antigravity.fluidengine.aiui.strings.FluidAiSettingsStrings
import dev.pampa.fluidweather.core.ai.AiAssistant

/** Quello che la sezione della Diagnostica legge, come dati: i flussi si raccolgono nella pagina, non nella lista. */
@Immutable
class AiDiagnosticsData(
  val settings: AiSettings,
  val keys: Map<ProviderId, KeyState>,
  val enabled: Boolean,
  val rateLimits: Map<ProviderId, RateLimitInfo>,
  val requests: List<AiRequestLog>,
)

@Composable
fun rememberAiDiagnostics(assistant: AiAssistant): AiDiagnosticsData {
  val settings by assistant.settings.settings.collectAsState(initial = AiSettings())
  val enabled by assistant.enabled.collectAsState(initial = false)
  val limits by assistant.diagnostics.rateLimits.collectAsState()
  val keys by assistant.keys.states.collectAsState(initial = emptyMap())
  val entries by assistant.diagnostics.entries.collectAsState()
  return AiDiagnosticsData(settings, keys, enabled, limits, entries)
}

/**
 * La sezione "Assistente IA" della Diagnostica: stato, la catena dei servizi, le quote residue
 * lette dagli header e le ultime richieste (provider e cambi, gruppi, strumenti con tempi, token,
 * costo, attese). E' di `engine-ai-ui`; le domande restano in memoria e spariscono con l'app.
 */
fun LazyListScope.aiDiagnosticsSection(data: AiDiagnosticsData, strings: FluidAiSettingsStrings) {
  fluidAiDiagnosticsSection(
    settings = data.settings,
    keys = data.keys,
    enabled = data.enabled,
    rateLimits = data.rateLimits,
    requests = data.requests,
    strings = strings,
  )
}
