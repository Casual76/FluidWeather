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

  FluidScreen(title = "Notifiche", onBack = onBack) {
    if (permissionMissing) {
      item { FluidSectionHeader(title = "Permesso") }
      item {
        FluidListGroup {
          FluidListRow(
            title = "Permesso alle notifiche",
            subtitle = "Senza, Android non mostra nulla: i canali qui sotto restano muti.",
            badge = {
              FluidButton(
                text = "Concedi",
                style = FluidButtonStyle.Tinted,
                onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
              )
            },
          )
        }
      }
    }

    item { FluidSectionHeader(title = "Canali") }
    item {
      FluidListGroup {
        NotificationChannelKind.entries.forEachIndexed { index, kind ->
          if (index > 0) FluidListDivider()
          FluidListRow(
            title = kind.label,
            subtitle = kind.description,
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
              title = "Orario del riepilogo",
              subtitle = "Quando arriva, ogni giorno",
              meta = String.format(Locale.ROOT, "%02d:%02d", settings.summaryHour, settings.summaryMinute),
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
                  true,
                ).show()
              },
            )
          }
        }
      }
    }

    item { FluidSectionHeader(title = "Prova e stato") }
    item {
      FluidListGroup {
        FluidListRow(
          title = "Invia una notifica di prova",
          subtitle = "Sul canale dell'allerta nowcast, cosi' vedi suono e importanza",
          badge = {
            FluidButton(
              text = "Prova",
              style = FluidButtonStyle.Tinted,
              onClick = {
                deps.notifier.post(
                  AppNotification(
                    channel = NotificationChannelKind.NOWCAST_ALERT,
                    id = AlertPolicy.NOWCAST_ID + 1,
                    title = "Notifica di prova",
                    text = "Cosi' arriverebbe un'allerta del barometro.",
                    bigText = "E' una prova: nessuna pioggia in vista. Le impostazioni del canale " +
                      "(suono, vibrazione) sono quelle di sistema.",
                  ),
                )
              },
            )
          },
        )
        FluidListDivider()
        FluidListRow(
          title = "Esegui il ciclo adesso",
          subtitle = cycleNote ?: ledger.lastCycleNote ?: "Nessun ciclo in background ancora eseguito.",
          badge = {
            FluidButton(
              text = if (running) "..." else "Vai",
              style = FluidButtonStyle.Tinted,
              enabled = !running,
              onClick = {
                running = true
                scope.launch {
                  try {
                    cycleNote = runCatching { deps.runCycleNow() }.getOrElse { "errore: ${it.message}" }
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
          title = "Impostazioni di sistema dei canali",
          subtitle = "Suono, vibrazione e importanza di ogni canale, dove Android li tiene",
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
