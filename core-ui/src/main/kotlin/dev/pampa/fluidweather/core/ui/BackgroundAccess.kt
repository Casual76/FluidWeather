package dev.pampa.fluidweather.core.ui

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.pampa.fluidweather.strings.R

/**
 * Quanto il sistema lascia lavorare l'app in background, visto da qui.
 *
 * Nasce col baco dei due Samsung (2026-09-26): la storia barometrica si fermava perche' One UI
 * metteva l'app "in sospensione", e l'app non lo diceva a nessuno. Qui si legge quello che le API
 * pubbliche lasciano leggere; lo stato "in sospensione" di Samsung non ha un'API, quindi quella
 * parte si spiega a parole e si porta l'utente nel posto giusto.
 */
data class BackgroundAccess(
  /** L'app e' fuori dall'ottimizzazione batteria (Doze la tratta con piu' riguardo). */
  val unrestrictedBattery: Boolean,
  /** Il sistema la limita esplicitamente in background (Android 9+; "Limitata" nelle impostazioni). */
  val backgroundRestricted: Boolean,
  /** Un Samsung: c'e' anche il giro delle "app in sospensione" da spiegare. */
  val samsung: Boolean,
  /** Il bucket di standby (Android 9+), per la Diagnostica: ad app aperta e' sempre ACTIVE. */
  val standbyBucket: Int? = null,
) {
  /** Il nome del bucket come lo chiama Android: e' diagnostica, non si traduce. */
  val standbyBucketName: String
    get() = when (standbyBucket) {
      null -> "—"
      UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE"
      UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET"
      UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT"
      UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE"
      UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "RESTRICTED"
      else -> standbyBucket.toString()
    }

  /** C'e' qualcosa che l'utente puo' sistemare. */
  val fixable: Boolean get() = !unrestrictedBattery || backgroundRestricted || samsung

  companion object {
    fun read(context: Context): BackgroundAccess {
      val power = context.getSystemService(PowerManager::class.java)
      val activity = context.getSystemService(ActivityManager::class.java)
      return BackgroundAccess(
        unrestrictedBattery = power?.isIgnoringBatteryOptimizations(context.packageName) == true,
        backgroundRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && activity?.isBackgroundRestricted == true,
        samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
        standbyBucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          runCatching { context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket }.getOrNull()
        } else {
          null
        },
      )
    }
  }
}

/** Le porte verso le impostazioni di sistema. Nessuna lancia: una porta chiusa apre la successiva. */
object BackgroundAccessIntents {

  /** Il dialogo di sistema "Consentire all'app di restare sempre in background?". */
  fun requestUnrestrictedBattery(context: Context) {
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    if (!start(context, request)) openAppDetails(context)
  }

  /** La pagina dell'app nelle impostazioni: da li' Batteria › Senza restrizioni. */
  fun openAppDetails(context: Context) {
    start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
  }

  /**
   * I limiti di utilizzo in background di One UI, dove vivono le liste delle app in sospensione.
   * Il componente non e' un'API pubblica e cambia fra le versioni: se non si apre, si ripiega sulla
   * pagina dell'app, che e' comunque a due tocchi dalla batteria.
   */
  fun openSamsungSleepingApps(context: Context) {
    val candidates = listOf(
      ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
      ComponentName("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"),
    )
    for (component in candidates) {
      if (start(context, Intent().setComponent(component))) return
    }
    openAppDetails(context)
  }

  private fun start(context: Context, intent: Intent): Boolean = runCatching {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }.isSuccess
}

/** Lo stato letto a ogni ripresa: tornando dalle impostazioni la riga "fatto" si aggiorna da sola. */
@Composable
fun rememberBackgroundAccess(): BackgroundAccess {
  val context = LocalContext.current
  var access by remember { mutableStateOf(BackgroundAccess.read(context)) }
  LifecycleResumeEffect(context) {
    access = BackgroundAccess.read(context)
    onPauseOrDispose { }
  }
  return access
}

/**
 * Le righe del rimedio, per le pagine scure (i fogli dei widget). Le impostazioni usano le loro
 * righe di lista con gli stessi testi e le stesse porte.
 */
@Composable
fun BackgroundAccessRows(titleColor: Color, bodyColor: Color, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val access = rememberBackgroundAccess()
  Column(modifier) {
    if (access.unrestrictedBattery) {
      AccessRow(stringResource(R.string.bg_access_battery_ok), stringResource(R.string.bg_access_battery_ok_desc), titleColor, bodyColor)
    } else {
      AccessRow(stringResource(R.string.bg_access_battery), stringResource(R.string.bg_access_battery_desc), titleColor, bodyColor) {
        BackgroundAccessIntents.requestUnrestrictedBattery(context)
      }
    }
    if (access.backgroundRestricted) {
      AccessRow(stringResource(R.string.bg_access_restricted), stringResource(R.string.bg_access_restricted_desc), titleColor, bodyColor) {
        BackgroundAccessIntents.openAppDetails(context)
      }
    }
    if (access.samsung) {
      AccessRow(stringResource(R.string.bg_access_samsung), stringResource(R.string.bg_access_samsung_desc), titleColor, bodyColor) {
        BackgroundAccessIntents.openSamsungSleepingApps(context)
      }
    }
  }
}

@Composable
private fun AccessRow(title: String, body: String, titleColor: Color, bodyColor: Color, onClick: (() -> Unit)? = null) {
  Column(
    Modifier
      .fillMaxWidth()
      .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
      .padding(vertical = 10.dp),
  ) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor)
    Spacer(Modifier.height(2.dp))
    Text(body, style = MaterialTheme.typography.bodySmall, color = bodyColor)
  }
}
