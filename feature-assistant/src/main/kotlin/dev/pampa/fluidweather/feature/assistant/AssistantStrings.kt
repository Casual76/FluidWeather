package dev.pampa.fluidweather.feature.assistant

import androidx.annotation.StringRes
import dev.pampa.fluidweather.core.ai.orchestrator.AssistantState
import dev.pampa.fluidweather.core.ai.orchestrator.FailureKind
import dev.pampa.fluidweather.core.ai.provider.ProviderId
import dev.pampa.fluidweather.core.ai.tools.OpenTarget
import dev.pampa.fluidweather.core.ai.tools.ToolGroup
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
    FailureKind.NO_LOCATION -> R.string.ai_error_no_location
    FailureKind.UNKNOWN -> R.string.ai_error_unknown
  }

  @StringRes
  fun targetRes(target: OpenTarget): Int = when (target) {
    OpenTarget.RADAR -> R.string.radar_title
    OpenTarget.BENCHMARK -> R.string.bench_title
    OpenTarget.REPORT -> R.string.report_title
    OpenTarget.SETTINGS -> R.string.settings_title
    OpenTarget.AI_SETTINGS -> R.string.ai_title
    OpenTarget.NOWCAST -> R.string.ai_target_nowcast
    OpenTarget.HOURLY -> R.string.ai_target_hourly
    OpenTarget.DAILY -> R.string.ai_target_daily
    OpenTarget.PRECIPITATION -> R.string.ai_target_precipitation
    OpenTarget.PRESSURE -> R.string.ai_target_pressure
    OpenTarget.AIR_QUALITY -> R.string.ai_target_air
    OpenTarget.SUN -> R.string.ai_target_sun
    OpenTarget.MOON -> R.string.ai_target_moon
    OpenTarget.DETAILS -> R.string.ai_target_details
  }

  @StringRes
  fun thinkingRes(level: dev.pampa.fluidweather.core.ai.keys.ThinkingLevel): Int = when (level) {
    dev.pampa.fluidweather.core.ai.keys.ThinkingLevel.LOW -> R.string.ai_thinking_low
    dev.pampa.fluidweather.core.ai.keys.ThinkingLevel.MEDIUM -> R.string.ai_thinking_medium
    dev.pampa.fluidweather.core.ai.keys.ThinkingLevel.HIGH -> R.string.ai_thinking_high
  }

  fun providerLabel(provider: ProviderId): String = provider.label

  /** Vero se lo stato merita la card espansa di default (una risposta, un errore, una conferma). */
  fun wantsExpanded(state: AssistantState): Boolean = when (state) {
    is AssistantState.Done, is AssistantState.Failed, is AssistantState.AwaitingConfirmation, is AssistantState.Answering -> true
    else -> false
  }
}
