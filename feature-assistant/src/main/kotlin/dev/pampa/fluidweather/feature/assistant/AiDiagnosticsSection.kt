package dev.pampa.fluidweather.feature.assistant

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.fluidweather.core.ai.AiAssistant
import dev.pampa.fluidweather.core.ai.keys.AiSettings
import dev.pampa.fluidweather.core.ai.orchestrator.AiRequestLog
import dev.pampa.fluidweather.core.ai.provider.ProviderId
import dev.pampa.fluidweather.strings.R
import dev.pampa.fluidweather.strings.TimeFormats
import java.util.Locale

/**
 * La sezione "Assistente IA" della Diagnostica: stato, le ultime dieci richieste (provider e
 * cambi, gruppi, tool con tempi, token, costo, attese) e le quote residue lette dagli header.
 * Le domande stesse restano in memoria e spariscono con l'app.
 */
fun LazyListScope.aiDiagnosticsSection(assistant: AiAssistant) {
  item { FluidSectionHeader(title = stringResource(R.string.diag_ai_section)) }
  item { AiDiagnosticsState(assistant) }
  item { AiDiagnosticsRequests(assistant) }
}

@Composable
private fun AiDiagnosticsState(assistant: AiAssistant) {
  val settings by assistant.settings.settings.collectAsState(initial = AiSettings())
  val enabled by assistant.enabled.collectAsState(initial = false)
  val limits by assistant.diagnostics.rateLimits.collectAsState()
  val keys by assistant.keys.states.collectAsState(initial = emptyMap())
  val verified = keys.filterValues { it.verified }.keys
  FluidListGroup {
    FluidListRow(
      title = stringResource(R.string.diag_ai_state),
      subtitle = if (enabled) {
        stringResource(R.string.diag_ai_on, settings.chatOrder.filter { it in verified }.joinToString(" → ") { "${it.label} (${settings.chatModel(it) ?: "—"})" })
      } else {
        stringResource(R.string.diag_ai_off)
      },
    )
    limits.forEach { (provider, info) ->
      val parts = buildList {
        info.remainingTokens?.let { add(stringResource(R.string.diag_ai_quota_tokens, it)) }
        info.remainingRequests?.let { add(stringResource(R.string.diag_ai_quota_requests, it)) }
      }
      if (parts.isNotEmpty()) {
        FluidListDivider()
        FluidListRow(title = stringResource(R.string.diag_ai_quota, provider.label, parts.joinToString(" · ")), subtitle = "")
      }
    }
  }
}

@Composable
private fun AiDiagnosticsRequests(assistant: AiAssistant) {
  val entries by assistant.diagnostics.entries.collectAsState()
  FluidListGroup {
    if (entries.isEmpty()) {
      FluidListRow(title = stringResource(R.string.diag_ai_requests), subtitle = stringResource(R.string.diag_ai_none))
      return@FluidListGroup
    }
    entries.forEachIndexed { index, log ->
      if (index > 0) FluidListDivider()
      RequestRow(log)
    }
  }
}

@Composable
private fun RequestRow(log: AiRequestLog) {
  val provider = buildString {
    append(log.provider.label)
    if (log.switchedTo.isNotEmpty()) append(" ").append(stringResource(R.string.diag_ai_switched, log.switchedTo.joinToString(", ") { it.label }))
    append(" · ").append(log.model)
  }
  val details = buildList {
    add(stringResource(R.string.diag_ai_request_meta, provider, log.steps, log.durationMillis))
    if (log.groups.isNotEmpty()) add(stringResource(R.string.diag_ai_groups, log.groups.joinToString(", ")))
    if (log.tools.isNotEmpty()) add(stringResource(R.string.diag_ai_tools, log.tools.joinToString(", ") { "${it.name} ${it.millis} ms${if (it.ok) "" else " ✕"}" }))
    log.usage?.let { usage ->
      add(stringResource(R.string.diag_ai_tokens, usage.promptTokens, usage.completionTokens))
      usage.costUsd?.let { add(stringResource(R.string.diag_ai_cost, String.format(Locale.getDefault(), "%.4f $", it))) }
    }
    if (log.waitedSeconds > 0) add(stringResource(R.string.diag_ai_waited, log.waitedSeconds))
    log.error?.let { add(it) }
  }.joinToString("\n")
  FluidListRow(
    title = log.question.take(80),
    subtitle = details,
    meta = TimeFormats.time(log.startedAtMillis),
  )
}

internal fun ProviderId.short(): String = label
