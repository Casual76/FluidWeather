package dev.pampa.fluidweather.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.config.EngineRemoteConfig
import dev.antigravity.fluidengine.foundation.AppUpdateInstallState
import dev.antigravity.fluidengine.foundation.AppUpdater
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.foundation.UpdateChannel
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.ui.haptics.rememberFluidHaptics
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.fluidweather.strings.R
import kotlinx.coroutines.launch

/** Tutto quello che serve a controllare e installare un aggiornamento. */
class UpdateDependencies(
  val appVersion: String,
  val updater: AppUpdater,
  val releaseSettings: ReleaseSettingsStore,
  val remoteConfig: EngineRemoteConfig,
)

/** Cosa sta facendo la riga degli aggiornamenti: si legge come una sola frase. */
private sealed interface UpdateStatus {
  data object Idle : UpdateStatus
  data object Checking : UpdateStatus
  data object Latest : UpdateStatus
  data object CheckFailed : UpdateStatus
  data class Available(val update: AvailableAppUpdate) : UpdateStatus
  data class Downloading(val percent: Int) : UpdateStatus
  data class Message(val text: String) : UpdateStatus
  data object Installed : UpdateStatus
  data class Failed(val message: String) : UpdateStatus
}

/**
 * Le righe degli aggiornamenti (fase 18), dentro un gruppo gia' aperto: il canale (stabile o
 * beta), il controllo con l'installazione, e "salta questa versione" quando ce n'e' una. Il
 * controllo e l'installazione sono la stessa riga perche' sono la stessa storia per chi legge:
 * "c'e' una versione nuova, tocca e arriva".
 */
@Composable
fun AppUpdateRows(deps: UpdateDependencies) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val channel by deps.releaseSettings.channel.collectAsState(initial = UpdateChannel.STABLE)
  val ignored by deps.releaseSettings.ignoredVersion.collectAsState(initial = "")
  var status by remember { mutableStateOf<UpdateStatus>(UpdateStatus.Idle) }
  var busy by remember { mutableStateOf(false) }

  // Un aggiornamento finisce mentre l'utente guarda altrove: l'esito si sente.
  val haptics = rememberFluidHaptics()
  LaunchedEffect(status) {
    when (status) {
      UpdateStatus.Installed -> haptics.play(FluidHapticEvent.Success)
      UpdateStatus.CheckFailed, is UpdateStatus.Failed -> haptics.play(FluidHapticEvent.Error)
      else -> Unit
    }
  }

  // Le frasi si leggono nel composable: il click e la raccolta del flow non lo sono.
  val installedText = stringResource(R.string.update_installed)
  val downloadingTemplate = stringResource(R.string.update_downloading)

  UpdateChannel.entries.forEach { candidate ->
    FluidListRow(
      title = stringResource(if (candidate == UpdateChannel.STABLE) R.string.channel_stable else R.string.channel_beta),
      subtitle = stringResource(if (candidate == UpdateChannel.STABLE) R.string.channel_stable_desc else R.string.channel_beta_desc),
      badge = if (channel == candidate) {
        { Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.common_chosen), tint = MaterialTheme.colorScheme.primary) }
      } else {
        null
      },
      onClick = {
        scope.launch {
          deps.releaseSettings.setChannel(candidate)
          status = UpdateStatus.Idle
        }
      },
    )
    FluidListDivider()
  }

  val current = status
  FluidListRow(
    title = stringResource(R.string.about_updates_title),
    subtitle = when (current) {
      UpdateStatus.Idle -> stringResource(R.string.update_tap_to_check)
      UpdateStatus.Checking -> stringResource(R.string.update_checking)
      UpdateStatus.Latest -> stringResource(R.string.update_latest)
      UpdateStatus.CheckFailed -> stringResource(R.string.update_check_failed)
      is UpdateStatus.Available -> stringResource(R.string.update_available, current.update.version)
      is UpdateStatus.Downloading -> stringResource(R.string.update_downloading, current.percent)
      is UpdateStatus.Message -> current.text
      UpdateStatus.Installed -> installedText
      is UpdateStatus.Failed -> stringResource(R.string.update_error, current.message)
    },
    meta = deps.appVersion,
    onClick = {
      if (busy) return@FluidListRow
      val available = (current as? UpdateStatus.Available)?.update
      scope.launch {
        busy = true
        try {
          if (available == null) {
            status = UpdateStatus.Checking
            deps.updater.check(currentVersionName = deps.appVersion, channel = channel, ignoredVersion = ignored)
              .onSuccess { found -> status = if (found == null) UpdateStatus.Latest else UpdateStatus.Available(found) }
              .onFailure { status = UpdateStatus.CheckFailed }
          } else {
            deps.updater.install(available).collect { state ->
              status = when (state) {
                is AppUpdateInstallState.Downloading -> UpdateStatus.Downloading((state.progress * 100).toInt())
                is AppUpdateInstallState.Verifying -> UpdateStatus.Message(state.message)
                is AppUpdateInstallState.Installing -> UpdateStatus.Message(state.message)
                is AppUpdateInstallState.AwaitingUserAction -> UpdateStatus.Message(state.message)
                is AppUpdateInstallState.Installed -> UpdateStatus.Installed
                is AppUpdateInstallState.Error -> UpdateStatus.Failed(state.message)
              }
            }
          }
        } finally {
          busy = false
        }
      }
    },
  )

  val available = (current as? UpdateStatus.Available)?.update
  if (available != null) {
    FluidListDivider()
    FluidListRow(
      title = stringResource(R.string.update_changelog, available.version),
      subtitle = available.changelog.ifBlank { "—" },
      badge = {
        FluidButton(
          text = stringResource(R.string.update_ignore),
          style = FluidButtonStyle.Plain,
          onClick = {
            scope.launch {
              deps.releaseSettings.setIgnoredVersion(available.version)
              status = UpdateStatus.Idle
            }
          },
        )
      },
    )
  }
}
