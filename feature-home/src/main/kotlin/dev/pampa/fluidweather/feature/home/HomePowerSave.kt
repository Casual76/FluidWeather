package dev.pampa.fluidweather.feature.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dev.pampa.fluidweather.core.ui.isPowerSaveActive

internal fun homePowerSaveChanges(context: Context) = homePowerSaveChanges({ isPowerSaveActive(context) }) { changed ->
  val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) { changed() }
  }
  ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
    ContextCompat.RECEIVER_NOT_EXPORTED)
  val unsubscribe: () -> Unit = { context.unregisterReceiver(receiver) }
  unsubscribe
}
