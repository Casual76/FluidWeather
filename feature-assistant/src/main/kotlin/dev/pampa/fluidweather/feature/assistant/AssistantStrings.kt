package dev.pampa.fluidweather.feature.assistant

import androidx.annotation.StringRes
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ai.orchestrator.FailureKind
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.pampa.fluidweather.core.ai.tools.OpenTarget
import dev.pampa.fluidweather.core.ai.tools.ToolGroup
import dev.pampa.fluidweather.core.ai.tools.titleRes
import dev.pampa.fluidweather.strings.R

/**
 * Da chiavi di stato, gruppi di tool, errori e pagine alle parole: l'unico posto in cui la UI
 * dell'assistente sceglie una stringa. Il test delle etichette verifica che ogni chiave ne abbia una.
 */
object AssistantStrings {

  @StringRes
  fun statusRes(key: String): Int = when (key) {
    "thinking" -> R.string.ai_status_thinking
    "more_tools" -> R.string.ai_status_more_tools
    ToolGroup.PLACES.statusKey -> R.string.ai_status_places
    ToolGroup.NOWCAST.statusKey -> R.string.ai_status_nowcast
    ToolGroup.HOURLY.statusKey -> R.string.ai_status_hourly
    ToolGroup.DAILY.statusKey -> R.string.ai_status_daily
    ToolGroup.PRECIP.statusKey -> R.string.ai_status_precip
    ToolGroup.AIR.statusKey -> R.string.ai_status_air
    ToolGroup.SKY.statusKey -> R.string.ai_status_sky
    ToolGroup.PROVIDERS.statusKey -> R.string.ai_status_providers
    ToolGroup.ALERTS.statusKey -> R.string.ai_status_alerts
    ToolGroup.APP.statusKey -> R.string.ai_status_app
    else -> R.string.ai_status_thinking
  }

  @StringRes
  fun failureRes(kind: FailureKind): Int = when (kind) {
    FailureKind.NO_KEYS -> R.string.ai_error_no_keys
    FailureKind.UNAUTHORIZED -> R.string.ai_error_unauthorized
    FailureKind.RATE_LIMITED -> R.string.ai_error_rate_limited_generic
    FailureKind.NETWORK -> R.string.ai_error_network
    FailureKind.TIMEOUT -> R.string.ai_error_timeout
    FailureKind.BLOCKED -> R.string.ai_error_blocked
    FailureKind.PROVIDER -> R.string.ai_error_provider
    FailureKind.MICROPHONE -> R.string.ai_error_microphone
    FailureKind.TRANSCRIPTION -> R.string.ai_error_transcription
    FailureKind.UNKNOWN -> R.string.ai_error_unknown
    FailureKind.MODEL_UNAVAILABLE -> R.string.ai_error_model_unavailable
    FailureKind.CONTEXT_TOO_LONG -> R.string.ai_error_context_too_long
  }

  /** Il nome di una pagina: lo stesso che la sessione usa nella frase della conferma. */
  @StringRes
  fun targetRes(target: OpenTarget): Int = target.titleRes()

  @StringRes
  fun thinkingRes(level: dev.antigravity.fluidengine.ai.keys.ThinkingLevel): Int = when (level) {
    dev.antigravity.fluidengine.ai.keys.ThinkingLevel.LOW -> R.string.ai_thinking_low
    dev.antigravity.fluidengine.ai.keys.ThinkingLevel.MEDIUM -> R.string.ai_thinking_medium
    dev.antigravity.fluidengine.ai.keys.ThinkingLevel.HIGH -> R.string.ai_thinking_high
  }

  fun providerLabel(provider: ProviderId): String = provider.label

  /** Vero se lo stato merita la card espansa di default (una risposta, un errore, una conferma). */
  fun wantsExpanded(state: AssistantState): Boolean = when (state) {
    is AssistantState.Done, is AssistantState.Failed, is AssistantState.AwaitingConfirmation, is AssistantState.Answering -> true
    else -> false
  }
}
