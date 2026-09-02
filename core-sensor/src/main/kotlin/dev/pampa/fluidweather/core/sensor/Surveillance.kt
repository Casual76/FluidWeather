package dev.pampa.fluidweather.core.sensor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.pampa.fluidweather.core.model.PressureTrend
import dev.pampa.fluidweather.core.model.SampleSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Accende e spegne la marcia di sorveglianza. Su Android 12+ un avvio dal background puo' essere
 * rifiutato dal sistema: in quel caso si rinuncia in silenzio — il giro periodico continua, e
 * l'esenzione batteria (chiesta in diagnostica) toglie il limite.
 */
class SurveillanceController(private val context: Context) {

  fun start() {
    runCatching {
      ContextCompat.startForegroundService(context, Intent(context, SurveillanceService::class.java))
    }
  }

  fun stop() {
    context.stopService(Intent(context, SurveillanceService::class.java))
  }
}

/**
 * La seconda marcia: una lettura al minuto finche' la pressione si muove. Foreground service con
 * tipo specialUse (Android 14+), notifica a bassa importanza che dice il perche'.
 */
class SurveillanceService : Service() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var looping = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startInForeground()
    if (!looping) {
      looping = true
      scope.launch { watch() }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private suspend fun watch() {
    val engine = sensorRuntime().samplingEngine
    val startedAt = System.currentTimeMillis()
    var quietMinutes = 0
    while (scope.isActive) {
      engine.collect(source = SampleSource.SURVEILLANCE, durationSeconds = 0)
      engine.notifyPassCompleted()
      val calm = !PressureTrend.callsForSurveillance(engine.currentTrend())
      quietMinutes = if (calm) quietMinutes + 1 else 0
      val expired = System.currentTimeMillis() - startedAt > MAX_RUNTIME_MILLIS
      if (quietMinutes >= QUIET_MINUTES_TO_STOP || expired) {
        stopSelf()
        return
      }
      delay(SAMPLE_INTERVAL_MILLIS)
    }
  }

  private fun startInForeground() {
    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(
      NotificationChannel(
        CHANNEL_ID,
        "Sorveglianza barometrica",
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = "Attiva solo mentre la pressione sta cambiando rapidamente."
      },
    )
    val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_menu_compass)
      .setContentTitle("Sorveglianza barometrica attiva")
      .setContentText("La pressione sta cambiando in fretta: letture piu' frequenti per un po'.")
      .setOngoing(true)
      .build()
    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    } else {
      0
    }
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
  }

  private companion object {
    const val CHANNEL_ID = "surveillance"
    const val NOTIFICATION_ID = 41
    const val SAMPLE_INTERVAL_MILLIS = 60_000L

    /** Tanti minuti consecutivi di quiete, e la marcia si spegne da sola. */
    const val QUIET_MINUTES_TO_STOP = 15

    /** Comunque mai piu' di due ore di fila: al giro periodico successivo puo' riaccendersi. */
    const val MAX_RUNTIME_MILLIS = 2 * 60 * 60_000L
  }
}
