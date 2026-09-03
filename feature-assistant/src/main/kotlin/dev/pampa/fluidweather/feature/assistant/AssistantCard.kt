package dev.pampa.fluidweather.feature.assistant

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.pampa.fluidweather.core.ai.orchestrator.AnswerChip
import dev.pampa.fluidweather.core.ai.orchestrator.AssistantSession
import dev.pampa.fluidweather.core.ai.orchestrator.AssistantState
import dev.pampa.fluidweather.core.ai.tools.AssistantAction
import dev.pampa.fluidweather.strings.R
import dev.pampa.fluidweather.strings.labelRes

/**
 * La card sotto l'aureola: domanda in piccolo, riga di stato col puntino che respira, risposta
 * in streaming con il markdown leggero, chip sotto, tasto stop discreto mentre lavora. E'
 * vetro sopra la home, non un dialogo: quello che c'e' dietro resta leggibile.
 */
@Composable
fun AssistantCard(
  state: AssistantState,
  pending: AssistantSession.PendingAction?,
  backdrop: GlassBackdropState,
  onStop: () -> Unit,
  onChip: (AnswerChip) -> Unit,
  onConfirm: (Long, Boolean) -> Unit,
  onTapBody: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val question = state.question()
  Column(
    modifier = modifier
      .fillMaxWidth()
      .glassSurface(
        state = backdrop,
        tint = GlassDefaults.modalTint(),
        shape = ContinuousCornerShape(FluidRadius.Sheet),
        role = GlassRole.Modal,
      )
      .fluidPressable(onClick = onTapBody, pressedScale = 1f, role = null)
      .padding(horizontal = 20.dp, vertical = 16.dp),
  ) {
    Row(verticalAlignment = Alignment.Top) {
      Column(Modifier.weight(1f)) {
        if (!question.isNullOrBlank()) {
          Text(
            text = question,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Spacer(Modifier.height(6.dp))
        }
        StatusLine(state)
      }
      if (state.isBusy && state !is AssistantState.AwaitingConfirmation) {
        Spacer(Modifier.width(8.dp))
        StopPill(onStop)
      }
    }
    val body = state.bodyText()
    AnimatedContent(
      targetState = body != null,
      transitionSpec = { fadeIn(FluidMotion.fadeIn(180)).togetherWith(fadeOut(FluidMotion.fadeOut(120))) },
      label = "assistantBody",
    ) { hasBody ->
      if (hasBody && body != null) {
        Column(
          Modifier
            .padding(top = 10.dp)
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState()),
        ) {
          MarkdownBody(body)
        }
      }
    }
    if (state is AssistantState.AwaitingConfirmation && pending != null) {
      Spacer(Modifier.height(12.dp))
      ConfirmationRow(pending.action, onConfirm = { onConfirm(pending.id, it) })
    }
    if (state is AssistantState.Done && state.chips.isNotEmpty()) {
      Spacer(Modifier.height(12.dp))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.chips.forEach { chip ->
          ChipPill(
            label = when (chip) {
              is AnswerChip.Open -> stringResource(AssistantStrings.targetRes(chip.target))
              is AnswerChip.Place -> chip.name
            },
            onClick = { onChip(chip) },
          )
        }
      }
    }
  }
}

@Composable
private fun StatusLine(state: AssistantState) {
  val text: String? = when (state) {
    is AssistantState.Listening -> stringResource(R.string.ai_status_listening)
    AssistantState.Transcribing -> stringResource(R.string.ai_status_transcribing)
    is AssistantState.Classifying -> stringResource(R.string.ai_status_classifying)
    is AssistantState.Working -> {
      val base = stringResource(AssistantStrings.statusRes(state.statusKey))
      if (state.statusExtra > 0) stringResource(R.string.ai_status_parallel, base, state.statusExtra) else base
    }
    is AssistantState.WaitingRateLimit -> stringResource(R.string.ai_status_waiting, state.provider.label, state.secondsLeft)
    is AssistantState.SwitchingProvider -> stringResource(R.string.ai_status_switching, state.to.label)
    is AssistantState.Answering -> stringResource(R.string.ai_status_writing)
    is AssistantState.AwaitingConfirmation -> stringResource(R.string.ai_status_confirm)
    AssistantState.HeardNothing -> stringResource(R.string.ai_heard_nothing)
    is AssistantState.Failed -> failureText(state)
    is AssistantState.Cancelled -> stringResource(R.string.ai_interrupted)
    is AssistantState.Done -> null
    AssistantState.Idle -> null
  }
  if (text == null) return
  val error = state is AssistantState.Failed
  Row(verticalAlignment = Alignment.CenterVertically) {
    if (state.isBusy) {
      BreathingDot(color = MaterialTheme.colorScheme.primary)
      Spacer(Modifier.width(8.dp))
    }
    Text(
      text = text,
      style = MaterialTheme.typography.bodyMedium,
      color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
      fontWeight = FontWeight.Medium,
    )
  }
}

@Composable
private fun failureText(state: AssistantState.Failed): String {
  val retry = state.retryAfterSec
  return if (state.kind == dev.pampa.fluidweather.core.ai.orchestrator.FailureKind.RATE_LIMITED && retry != null) {
    stringResource(R.string.ai_error_rate_limited, retry)
  } else {
    stringResource(AssistantStrings.failureRes(state.kind))
  }
}

@Composable
private fun BreathingDot(color: Color) {
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val transition = rememberInfiniteTransition(label = "dot")
  val alpha by transition.animateFloat(
    initialValue = 0.35f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
    label = "dotAlpha",
  )
  Box(
    Modifier
      .size(8.dp)
      .alpha(if (reduced) 0.9f else alpha)
      .background(color, FluidCapsuleShape),
  )
}

@Composable
private fun StopPill(onStop: () -> Unit) {
  Box(
    modifier = Modifier
      .size(28.dp)
      .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), FluidCapsuleShape)
      // Niente tap: quando la richiesta si ferma arriva lo Stop dell'overlay, e due
      // vibrazioni per un tocco solo si sentono come un difetto.
      .fluidPressable(onClick = onStop, role = Role.Button, haptic = null),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = Icons.Rounded.Stop,
      contentDescription = stringResource(R.string.ai_stop),
      tint = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.size(14.dp),
    )
  }
}

@Composable
private fun ChipPill(label: String, onClick: () -> Unit) {
  Box(
    modifier = Modifier
      .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), FluidCapsuleShape)
      .fluidPressable(onClick = onClick, role = Role.Button)
      .padding(horizontal = 14.dp, vertical = 8.dp),
  ) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
  }
}

@Composable
private fun ConfirmationRow(action: AssistantAction, onConfirm: (Boolean) -> Unit) {
  val label = when (action) {
    AssistantAction.StartBurst -> stringResource(R.string.ai_action_burst)
    is AssistantAction.RecordObservation -> stringResource(R.string.ai_action_observation, stringResource(action.condition.labelRes()))
    is AssistantAction.SavePlace -> stringResource(R.string.ai_action_save_place, action.place.name)
    is AssistantAction.Open -> stringResource(R.string.ai_action_open, stringResource(AssistantStrings.targetRes(action.target)))
    is AssistantAction.SelectPlace -> stringResource(R.string.ai_action_select_place, action.place.name)
  }
  Column {
    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FluidButton(text = stringResource(R.string.ai_confirm), onClick = { onConfirm(true) }, style = FluidButtonStyle.Tinted, size = FluidButtonSize.Small)
      FluidButton(text = stringResource(R.string.ai_cancel), onClick = { onConfirm(false) }, style = FluidButtonStyle.Plain, size = FluidButtonSize.Small)
    }
  }
}

@Composable
private fun MarkdownBody(markdown: String) {
  val blocks = MarkdownLite.blocks(markdown)
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    blocks.forEach { block ->
      when (block) {
        is MarkdownLite.Block.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        is MarkdownLite.Block.Bullet -> Row(verticalAlignment = Alignment.Top) {
          Text(
            text = block.ordinal?.let { "$it." } ?: "•",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(22.dp),
          )
          Text(block.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
      }
    }
  }
}

internal fun AssistantState.question(): String? = when (this) {
  is AssistantState.Classifying -> question
  is AssistantState.Working -> question
  is AssistantState.WaitingRateLimit -> question
  is AssistantState.SwitchingProvider -> question
  is AssistantState.Answering -> question
  is AssistantState.AwaitingConfirmation -> question
  is AssistantState.Done -> question
  is AssistantState.Failed -> question
  is AssistantState.Cancelled -> question
  else -> null
}

internal fun AssistantState.bodyText(): String? = when (this) {
  is AssistantState.Answering -> partial
  is AssistantState.Done -> answer
  is AssistantState.Failed -> partial
  is AssistantState.Cancelled -> partial
  else -> null
}
