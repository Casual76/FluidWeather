package dev.pampa.fluidweather.feature.assistant

import androidx.annotation.StringRes
import dev.pampa.fluidweather.core.ai.tools.OpenTarget
import dev.pampa.fluidweather.core.ai.tools.ToolGroup
import dev.pampa.fluidweather.core.ai.tools.titleRes
import dev.pampa.fluidweather.strings.R

/**
 * Da chiavi di stato e pagine alle parole: il dominio dell'app che `engine-ai-ui` non puo' conoscere.
 * Il resto delle parole dell'assistente (errori, tasti, impostazioni) sta in `AiUiStrings.kt`.
 * Il test delle etichette verifica che ogni gruppo di strumenti ne abbia una.
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

  /** Il nome di una pagina: lo stesso che la sessione usa nella frase della conferma. */
  @StringRes
  fun targetRes(target: OpenTarget): Int = target.titleRes()
}
