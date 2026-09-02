package dev.pampa.fluidweather.feature.settings

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.fluidweather.core.cycle.AlertPolicy
import dev.pampa.fluidweather.core.cycle.SystemNotifier
import dev.pampa.fluidweather.core.data.NotificationLedgerStore
import dev.pampa.fluidweather.core.data.NotificationSettingsStore
import dev.pampa.fluidweather.core.model.AppNotification
import dev.pampa.fluidweather.core.model.NotificationChannelKind
import dev.pampa.fluidweather.core.model.NotificationLedger
import dev.pampa.fluidweather.core.model.NotificationSettings
import java.util.Locale
import kotlinx.coroutines.launch
import dev.pampa.fluidweather.strings.R
import androidx.compose.ui.res.stringResource
import dev.pampa.fluidweather.strings.TimeFormats
import dev.pampa.fluidweather.strings.descriptionRes
import dev.pampa.fluidweather.strings.labelRes

/** Tutto quello che la pagina delle notifiche tocca; lo costruisce :app dal suo grafo. */
class NotificationsDependencies(
  val settingsStore: NotificationSettingsStore,
  val ledgerStore: NotificationLedgerStore,
  val notifier: SystemNotifier,
  /** Dopo un cambio di impostazioni: il riepilogo va riprogrammato (o cancellato). */
  val onSettingsChanged: suspend () -> Unit,
  /** Un giro del ciclo in background adesso; ritorna la nota di diagnostica. */
  val runCycleNow: suspend () -> String,
)

/**
 * La categoria "Notifiche" delle impostazioni (fase 11): un interruttore per canale, l'ora del
 * riepilogo, il permesso di Android 13+, e i due tasti che rendono verificabile la promessa
 * della fase — "l'allerta arriva e rispetta le impostazioni" — senza aspettare un temporale.
 */
@Composable
fun NotificationsSettingsScreen(deps: NotificationsDependencies, onBack: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val settings by deps.settingsStore.settings.collectAsState(initial = NotificationSettings())
  val ledger by deps.ledgerStore.ledger.collectAsState(initial = NotificationLedger())

  var permissionEpoch by remember { mutableIntStateOf(0) }
  val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { permissionEpoch++ }
  val permissionMissing = remember(permissionEpoch) {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
  }
  var cycleNote by remember { mutableStateOf<String?>(null) }
  var running by remember { mutableStateOf(false) }

  fun setEnabled(kind: NotificationChannelKind, enabled: Boolean) {
    scope.launch {
      deps.settingsStore.setEnabled(kind, enabled)
      deps.onSettingsChanged()
    }
  }

  FluidScreen(title = stringResource(R.string.notif_title), onBack = onBack) {
    if (permissionMissing) {
      item { FluidSectionHeader(title = stringResource(R.string.notif_permission)) }
      item {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.notif_permission_row),
            subtitle = stringResource(R.string.notif_permission_desc),
            badge = {
              FluidButton(
                text = stringResource(R.string.common_grant),
                style = FluidButtonStyle.Tinted,
                onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
              )
            },
          )
        }
      }
    }

    item { FluidSectionHeader(title = stringResource(R.string.notif_channels)) }
    item {
      FluidListGroup {
        NotificationChannelKind.entries.forEachIndexed { index, kind ->
          if (index > 0) FluidListDivider()
          FluidListRow(
            title = stringResource(kind.labelRes()),
            subtitle = stringResource(kind.descriptionRes()),
            badge = {
              FluidSwitch(
                checked = settings.enabled(kind),
                onCheckedChange = { setEnabled(kind, it) },
              )
            },
          )
          if (kind == NotificationChannelKind.DAILY_SUMMARY) {
            FluidListDivider()
            FluidListRow(
              title = stringResource(R.string.notif_summary_time),
              subtitle = stringResource(R.string.notif_summary_time_desc),
              meta = TimeFormats.clock(settings.summaryHour, settings.summaryMinute),
              onClick = {
                TimePickerDialog(
                  context,
                  { _, hour, minute ->
                    scope.launch {
                      deps.settingsStore.setSummaryTime(hour, minute)
                      deps.onSettingsChanged()
                    }
                  },
                  settings.summaryHour,
                  settings.summaryMinute,
                  TimeFormats.is24Hour(),
                ).show()
              },
            )
          }
        }
      }
    }

    item { FluidSectionHeader(title = stringResource(R.string.notif_test_title)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.notif_test),
          subtitle = stringResource(R.string.notif_test_desc),
          badge = {
            // Le parole si leggono qui, nel composable: il click non e' un contesto composable.
            val testTitle = stringResource(R.string.notif_test_notification_title)
            val testText = stringResource(R.string.notif_test_notification_text)
            val testBigText = stringResource(R.string.notif_test_notification_big)
            FluidButton(
              text = stringResource(R.string.notif_test_button),
              style = FluidButtonStyle.Tinted,
              onClick = {
                deps.notifier.post(
                  AppNotification(
                    channel = NotificationChannelKind.NOWCAST_ALERT,
                    id = AlertPolicy.NOWCAST_ID + 1,
                    title = testTitle,
                    text = testText,
                    bigText = testBigText,
                  ),
                )
              },
            )
          },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.notif_run_cycle),
          subtitle = cycleNote ?: ledger.lastCycleNote ?: stringResource(R.string.notif_no_cycle),
          badge = {
            FluidButton(
              text = if (running) "..." else stringResource(R.string.common_go),
              style = FluidButtonStyle.Tinted,
              enabled = !running,
              onClick = {
                running = true
                scope.launch {
                  try {
                    cycleNote = runCatching { deps.runCycleNow() }.getOrElse { context.getString(R.string.common_error, it.message ?: "") }
                  } finally {
                    running = false
                  }
                }
              },
            )
          },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.notif_system_settings),
          subtitle = stringResource(R.string.notif_system_settings_desc),
          onClick = {
            context.startActivity(
              Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
          },
        )
      }
    }
  }
}
