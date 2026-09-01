package dev.pampa.fluidweather.core.sensor

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import dev.pampa.fluidweather.core.data.LatestActivity
import dev.pampa.fluidweather.core.model.ActivityKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Tiene aggiornata l'ultima attivita' riconosciuta, cosi' ogni campione barometrico sa se e' nato
 * fermo, in auto o in ascensore (fase 2: la pulizia scarta i cambi di quota).
 */
class ActivityRecognizer(private val context: Context) {

  fun hasPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
      PackageManager.PERMISSION_GRANTED

  fun start() {
    if (!hasPermission()) return
    try {
      ActivityRecognition.getClient(context)
        .requestActivityUpdates(UPDATE_INTERVAL_MILLIS, pendingIntent())
    } catch (_: SecurityException) {
      // Revocato fra il check e la chiamata: si riparte al prossimo avvio.
    }
  }

  private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
    context,
    0,
    Intent(context, ActivityUpdateReceiver::class.java),
    // MUTABLE perche' e' il sistema a riempire l'intent con il risultato.
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
  )

  private companion object {
    const val UPDATE_INTERVAL_MILLIS = 60_000L
  }
}

class ActivityUpdateReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    if (!ActivityRecognitionResult.hasResult(intent)) return
    val mostProbable = ActivityRecognitionResult.extractResult(intent)?.mostProbableActivity ?: return
    val kind = when (mostProbable.type) {
      DetectedActivity.STILL -> ActivityKind.STILL
      DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> ActivityKind.WALKING
      DetectedActivity.RUNNING -> ActivityKind.RUNNING
      DetectedActivity.ON_BICYCLE -> ActivityKind.ON_BICYCLE
      DetectedActivity.IN_VEHICLE -> ActivityKind.IN_VEHICLE
      else -> ActivityKind.UNKNOWN
    }
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        context.sensorRuntime().latestActivityStore.store(
          LatestActivity(
            kind = kind,
            confidence = mostProbable.confidence,
            observedAtMillis = System.currentTimeMillis(),
          ),
        )
      } finally {
        pending.finish()
      }
    }
  }
}
