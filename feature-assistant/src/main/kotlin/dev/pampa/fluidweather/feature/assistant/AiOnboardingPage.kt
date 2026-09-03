package dev.pampa.fluidweather.feature.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.pampa.fluidweather.core.ai.AiAssistant
import dev.pampa.fluidweather.core.ai.keys.AiSettings
import dev.pampa.fluidweather.core.ai.keys.KeyState
import dev.pampa.fluidweather.core.ai.provider.ProviderId
import dev.pampa.fluidweather.strings.R
import kotlinx.coroutines.launch

/**
 * La pagina dell'assistente nell'onboarding (penultima, prima della taratura): "No, magari
 * dopo" preselezionato; con "Si'" le tre schede delle chiavi, il permesso del microfono e il
 * toggle delle azioni. Il testo e' bianco: sta sopra il cielo.
 */
@Composable
fun AiOnboardingPage(assistant: AiAssistant, onNext: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val settings by assistant.settings.settings.collectAsState(initial = AiSettings())
  val keyStates by assistant.keys.states.collectAsState(initial = emptyMap())
  var wantsAssistant by remember { mutableStateOf(false) }
  var permissionEpoch by remember { mutableIntStateOf(0) }
  val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionEpoch++ }
  val micGranted = remember(permissionEpoch) { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
  val anyVerified = keyStates.values.any { it.verified }
  val white = Color.White
  val dim = Color.White.copy(alpha = 0.75f)

  Text(stringResource(R.string.onb_step_ai).uppercase(), style = MaterialTheme.typography.labelMedium, color = dim)
  Spacer(Modifier.height(8.dp))
  Text(stringResource(R.string.onb_ai_title), style = MaterialTheme.typography.headlineLarge, color = white)
  Spacer(Modifier.height(14.dp))
  Text(stringResource(R.string.onb_ai_text), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.9f))
  Spacer(Modifier.height(24.dp))

  ChoiceCard(
    title = stringResource(R.string.onb_ai_no),
    subtitle = stringResource(R.string.onb_ai_no_desc),
    selected = !wantsAssistant,
    onClick = {
      wantsAssistant = false
      scope.launch { assistant.settings.setEnabled(false) }
    },
  )
  ChoiceCard(
    title = stringResource(R.string.onb_ai_yes),
    subtitle = stringResource(R.string.onb_ai_yes_desc),
    selected = wantsAssistant,
    onClick = { wantsAssistant = true },
  )

  if (wantsAssistant) {
    Spacer(Modifier.height(12.dp))
    Column(
      Modifier
        .fillMaxWidth()
        .background(Color.White.copy(alpha = 0.10f), ContinuousCornerShape(FluidRadius.Card)),
    ) {
      ProviderId.entries.forEach { provider ->
        AiKeySetup(
          assistant = assistant,
          provider = provider,
          state = keyStates[provider] ?: KeyState(false, null),
          onSurface = white,
          onSurfaceVariant = dim,
          initiallyExpanded = provider == ProviderId.GROQ,
          onVerified = { scope.launch { assistant.settings.setEnabled(true) } },
        )
      }
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
      Column(Modifier.weight(1f)) {
        Text(stringResource(R.string.onb_ai_mic), style = MaterialTheme.typography.bodyMedium, color = white)
        Text(stringResource(R.string.onb_ai_mic_desc), style = MaterialTheme.typography.bodySmall, color = dim)
      }
      if (micGranted) {
        Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.onb_granted), tint = white)
      } else {
        FluidButton(text = stringResource(R.string.ai_mic_allow), style = FluidButtonStyle.Tinted, onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) })
      }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
      Text(stringResource(R.string.onb_ai_actions), style = MaterialTheme.typography.bodyMedium, color = white, modifier = Modifier.weight(1f))
      FluidSwitch(checked = settings.actionsEnabled, onCheckedChange = { scope.launch { assistant.settings.setActionsEnabled(it) } })
    }
    if (!anyVerified) {
      Spacer(Modifier.height(6.dp))
      Text(stringResource(R.string.onb_ai_not_ready), style = MaterialTheme.typography.bodySmall, color = dim)
    }
  }

  Spacer(Modifier.height(16.dp))
  FluidButton(text = stringResource(R.string.common_next), onClick = onNext, fillWidth = true)
}

@Composable
private fun ChoiceCard(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 3.dp)
      .background(Color.White.copy(alpha = if (selected) 0.22f else 0.10f), ContinuousCornerShape(FluidRadius.Card))
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 10.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White)
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
    }
    if (selected) Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.common_chosen), tint = Color.White)
  }
}
