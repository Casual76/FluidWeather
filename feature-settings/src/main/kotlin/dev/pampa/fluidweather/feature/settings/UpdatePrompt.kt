package dev.pampa.fluidweather.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.foundation.AppUpdateInstallState
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.ui.fluid.FluidAlert
import dev.antigravity.fluidengine.ui.fluid.FluidAlertAction
import dev.pampa.fluidweather.strings.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Dove sta il controllo all'avvio: fermo, trovato qualcosa, oppure gia' in installazione. */
private sealed interface PromptState {
  data object Quiet : PromptState
  data class Found(val update: AvailableAppUpdate) : PromptState
  data class Installing(val update: AvailableAppUpdate, val message: String, val percent: Int?) : PromptState
  data class Failed(val message: String) : PromptState
}

/**
 * Il controllo degli aggiornamenti all'apertura (fase 18, completata il 2026-09-03): l'app guarda
 * il manifest una volta per avvio e, se c'e' una versione nuova sul canale scelto, lo dice con un
 * avviso invece di aspettare che qualcuno vada a cercarlo nelle impostazioni.
 *
 * Tre modi di rispondere, e nessuno e' una trappola: si aggiorna subito, si rimanda (torna al
 * prossimo avvio), o si salta quella versione (non torna piu' finche' non ne esce un'altra). Un
 * controllo che non riesce non dice niente: era in sottofondo, e chi apre l'app voleva il meteo.
 */
@Composable
fun AppUpdatePrompt(deps: UpdateDependencies, enabled: Boolean = true) {
  if (!enabled) return
  val scope = rememberCoroutineScope()
  var state by remember { mutableStateOf<PromptState>(PromptState.Quiet) }
  var install by remember { mutableStateOf<Job?>(null) }

  LaunchedEffect(deps) {
    val channel = deps.releaseSettings.channel.first()
    val ignored = deps.releaseSettings.ignoredVersion.first()
    deps.updater
      .check(currentVersionName = deps.appVersion, channel = channel, ignoredVersion = ignored)
      .onSuccess { update -> if (update != null) state = PromptState.Found(update) }
  }

  val current = state
  if (current is PromptState.Quiet) return

  val downloadingTemplate = stringResource(R.string.update_downloading)
  val later = stringResource(R.string.update_later)
  val skip = stringResource(R.string.update_ignore)
  val now = stringResource(R.string.update_now)
  val close = stringResource(R.string.common_close)

  fun dismiss() {
    install?.cancel()
    install = null
    state = PromptState.Quiet
  }

  val (title, message, actions) = when (current) {
    is PromptState.Found -> Triple(
      stringResource(R.string.update_prompt_title, current.update.version),
      current.update.changelog,
      listOf(
        FluidAlertAction(
          label = now,
          emphasis = FluidAlertAction.Emphasis.Preferred,
          onClick = {
            val update = current.update
            state = PromptState.Installing(update, "", null)
            install = scope.launch {
              deps.updater.install(update).collect { step ->
                state = when (step) {
                  is AppUpdateInstallState.Downloading ->
                    PromptState.Installing(update, "", (step.progress * 100).toInt())
                  is AppUpdateInstallState.Verifying -> PromptState.Installing(update, step.message, null)
                  is AppUpdateInstallState.Installing -> PromptState.Installing(update, step.message, null)
                  is AppUpdateInstallState.AwaitingUserAction -> PromptState.Installing(update, step.message, null)
                  is AppUpdateInstallState.Installed -> PromptState.Quiet
                  is AppUpdateInstallState.Error -> PromptState.Failed(step.message)
                }
              }
            }
          },
        ),
        FluidAlertAction(label = later, onClick = { dismiss() }),
        FluidAlertAction(
          label = skip,
          onClick = {
            val version = current.update.version
            scope.launch { deps.releaseSettings.setIgnoredVersion(version) }
            dismiss()
          },
        ),
      ),
    )
    is PromptState.Installing -> Triple(
      stringResource(R.string.update_prompt_title, current.update.version),
      current.percent?.let { downloadingTemplate.format(it) } ?: current.message,
      // Fermare uno scaricamento e' una scelta legittima: l'avviso non diventa una gabbia.
      listOf(FluidAlertAction(label = later, onClick = { dismiss() })),
    )
    is PromptState.Failed -> Triple(
      stringResource(R.string.update_failed_title),
      current.message,
      listOf(FluidAlertAction(label = close, onClick = { dismiss() })),
    )
    PromptState.Quiet -> return
  }

  FluidAlert(
    onDismissRequest = { if (current !is PromptState.Installing) dismiss() },
    title = title,
    message = message,
    actions = actions,
  )
}
