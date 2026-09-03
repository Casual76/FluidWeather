package dev.pampa.fluidweather.feature.assistant

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.pampa.fluidweather.core.ai.AiAssistant
import dev.pampa.fluidweather.core.ai.keys.KeyState
import dev.pampa.fluidweather.core.ai.keys.VerifyResult
import dev.pampa.fluidweather.core.ai.provider.ProviderId
import dev.pampa.fluidweather.strings.R
import dev.pampa.fluidweather.strings.TimeFormats
import kotlinx.coroutines.launch

/** I link e i passi del tutorial di ciascun provider. */
object ProviderGuides {
  fun consoleUrl(provider: ProviderId): String = when (provider) {
    ProviderId.GROQ -> "https://console.groq.com/keys"
    ProviderId.GEMINI -> "https://aistudio.google.com/apikey"
    ProviderId.OPENROUTER -> "https://openrouter.ai/settings/keys"
  }

  fun consoleHost(provider: ProviderId): String = when (provider) {
    ProviderId.GROQ -> "console.groq.com"
    ProviderId.GEMINI -> "aistudio.google.com"
    ProviderId.OPENROUTER -> "openrouter.ai"
  }

  const val OPENROUTER_CREDITS_URL = "https://openrouter.ai/settings/credits"

  fun steps(provider: ProviderId): List<Int> = when (provider) {
    ProviderId.GROQ -> listOf(R.string.ai_tutorial_groq_1, R.string.ai_tutorial_groq_2, R.string.ai_tutorial_groq_3, R.string.ai_tutorial_groq_4)
    ProviderId.GEMINI -> listOf(R.string.ai_tutorial_gemini_1, R.string.ai_tutorial_gemini_2, R.string.ai_tutorial_gemini_3, R.string.ai_tutorial_gemini_4)
    ProviderId.OPENROUTER -> listOf(R.string.ai_tutorial_openrouter_1, R.string.ai_tutorial_openrouter_2, R.string.ai_tutorial_openrouter_3, R.string.ai_tutorial_openrouter_4)
  }
}

/**
 * La scheda di una chiave: stato, tutorial a quattro passi con il tasto che apre la console,
 * campo (la chiave salvata non si ri-mostra mai: vuoto = "lascia quella salvata"), Verifica
 * con l'esito inline, Rimuovi. Identica nell'onboarding e nelle impostazioni; [onSurface] e'
 * il colore del testo, perche' nell'onboarding si sta sopra il cielo.
 */
@Composable
fun AiKeySetup(
  assistant: AiAssistant,
  provider: ProviderId,
  state: KeyState,
  modifier: Modifier = Modifier,
  onSurface: Color = MaterialTheme.colorScheme.onSurface,
  onSurfaceVariant: Color = MaterialTheme.colorScheme.onSurfaceVariant,
  initiallyExpanded: Boolean = false,
  onVerified: (VerifyResult.Ok) -> Unit = {},
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var expanded by remember { mutableStateOf(initiallyExpanded) }
  var input by remember { mutableStateOf("") }
  var verifying by remember { mutableStateOf(false) }
  var outcome by remember { mutableStateOf<String?>(null) }
  var outcomeError by remember { mutableStateOf(false) }
  val verifiedText = stringResource(R.string.ai_key_verified, 0, 0)
  val invalidText = stringResource(R.string.ai_key_invalid)
  val failedPrefix = stringResource(R.string.ai_key_failed, "")

  Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(provider.label, style = MaterialTheme.typography.titleSmall, color = onSurface)
        Text(
          text = when {
            state.verified -> stringResource(R.string.ai_key_saved, TimeFormats.shortDate(state.verifiedAtMillis!!))
            state.present -> stringResource(R.string.ai_key_present_unverified)
            else -> stringResource(R.string.ai_key_missing)
          },
          style = MaterialTheme.typography.bodySmall,
          color = onSurfaceVariant,
        )
      }
      FluidButton(
        text = if (expanded) stringResource(R.string.common_close) else stringResource(R.string.ai_tutorial_title),
        style = FluidButtonStyle.Plain,
        size = FluidButtonSize.Small,
        onClick = { expanded = !expanded },
      )
    }
    AnimatedVisibility(visible = expanded) {
      Column(Modifier.padding(top = 8.dp)) {
        ProviderGuides.steps(provider).forEachIndexed { index, res ->
          Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
            Text("${index + 1}.", style = MaterialTheme.typography.bodySmall, color = onSurfaceVariant, modifier = Modifier.width(20.dp))
            Text(stringResource(res), style = MaterialTheme.typography.bodySmall, color = onSurface)
          }
        }
        if (provider == ProviderId.OPENROUTER) {
          Spacer(Modifier.height(4.dp))
          Text(stringResource(R.string.ai_openrouter_credits_note), style = MaterialTheme.typography.bodySmall, color = onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Row {
          FluidButton(
            text = stringResource(R.string.ai_key_open_console, ProviderGuides.consoleHost(provider)),
            style = FluidButtonStyle.Tinted,
            size = FluidButtonSize.Small,
            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ProviderGuides.consoleUrl(provider)))) } },
          )
          if (provider == ProviderId.OPENROUTER) {
            Spacer(Modifier.width(8.dp))
            FluidButton(
              text = stringResource(R.string.ai_openrouter_add_credits),
              style = FluidButtonStyle.Plain,
              size = FluidButtonSize.Small,
              onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ProviderGuides.OPENROUTER_CREDITS_URL))) } },
            )
          }
        }
      }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      FluidTextField(
        value = input,
        onValueChange = { input = it.trim(); outcome = null },
        placeholder = if (state.present) stringResource(R.string.ai_key_paste_keep) else stringResource(R.string.ai_key_paste),
        visualTransformation = PasswordVisualTransformation(),
        isError = outcomeError,
        modifier = Modifier.weight(1f),
      )
      Spacer(Modifier.width(8.dp))
      if (input.isNotBlank() || state.present) {
        FluidButton(
          text = if (verifying) stringResource(R.string.ai_key_verifying) else stringResource(R.string.ai_key_verify),
          style = FluidButtonStyle.Tinted,
          loading = verifying,
          enabled = !verifying,
          onClick = {
            verifying = true
            outcome = null
            scope.launch {
              if (input.isNotBlank()) assistant.keys.set(provider, input)
              when (val result = assistant.verifier.verify(provider)) {
                is VerifyResult.Ok -> {
                  outcome = verifiedText.replace("0", result.catalogue.chat.size.toString()).let { first ->
                    // Due numeri: chat e trascrizione. Sostituzione ordinata, senza formattatori esotici.
                    context.getString(R.string.ai_key_verified, result.catalogue.chat.size, result.catalogue.stt.size)
                  }
                  outcomeError = false
                  input = ""
                  onVerified(result)
                }
                VerifyResult.Invalid -> {
                  outcome = invalidText
                  outcomeError = true
                }
                is VerifyResult.Failed -> {
                  outcome = failedPrefix.trim().trimEnd(':') + ": " + (result.error?.message ?: "?")
                  outcomeError = true
                }
              }
              verifying = false
            }
          },
        )
      }
      if (state.present && input.isBlank()) {
        Spacer(Modifier.width(4.dp))
        FluidButton(
          text = stringResource(R.string.common_remove),
          style = FluidButtonStyle.Plain,
          size = FluidButtonSize.Small,
          onClick = { scope.launch { assistant.keys.set(provider, null); outcome = null } },
        )
      }
    }
    outcome?.let {
      Spacer(Modifier.height(4.dp))
      Text(it, style = MaterialTheme.typography.bodySmall, color = if (outcomeError) MaterialTheme.colorScheme.error else onSurfaceVariant)
    }
  }
}
