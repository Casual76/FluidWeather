package dev.pampa.fluidweather.core.cycle

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.pampa.fluidweather.core.model.AppNotification
import dev.pampa.fluidweather.core.model.NotificationChannelKind

/**
 * I quattro canali del piano, registrati presso Android. Idempotente: si chiama a ogni avvio
 * del processo, e Android aggiorna nome e descrizione ma NON l'importanza scelta dall'utente.
 */
object NotificationChannels {

  fun ensure(context: Context) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    NotificationChannelKind.entries.forEach { kind ->
      val importance = if (kind.highImportance) {
        NotificationManager.IMPORTANCE_HIGH
      } else {
        NotificationManager.IMPORTANCE_DEFAULT
      }
      manager.createNotificationChannel(
        NotificationChannel(kind.id, kind.label, importance).apply {
          description = kind.description
        },
      )
    }
  }
}

/** Consegna nella tendina di sistema. Sa quando NON puo' (permesso, notifiche spente) e lo dice. */
class SystemNotifier(private val context: Context) {

  fun canPost(): Boolean {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    }
    return true
  }

  /** Vero se consegnata; falso se Android non ce lo lascia fare. */
  fun post(notification: AppNotification): Boolean {
    if (!canPost()) return false
    val builder = NotificationCompat.Builder(context, notification.channel.id)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(notification.title)
      .setContentText(notification.text)
      .setAutoCancel(true)
      .setContentIntent(launchIntent())
      .setPriority(
        if (notification.channel.highImportance) {
          NotificationCompat.PRIORITY_HIGH
        } else {
          NotificationCompat.PRIORITY_DEFAULT
        },
      )
      .setCategory(
        when (notification.channel) {
          NotificationChannelKind.NOWCAST_ALERT, NotificationChannelKind.OFFICIAL_ALERTS -> NotificationCompat.CATEGORY_ALARM
          NotificationChannelKind.PRECIPITATION, NotificationChannelKind.DAILY_SUMMARY -> NotificationCompat.CATEGORY_STATUS
        },
      )
    notification.bigText?.let { builder.setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
    return runCatching {
      NotificationManagerCompat.from(context).notify(notification.id, builder.build())
      true
    }.getOrDefault(false)
  }

  fun cancel(id: Int) {
    NotificationManagerCompat.from(context).cancel(id)
  }

  private fun launchIntent(): PendingIntent? {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
    return PendingIntent.getActivity(
      context,
      0,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }
}
