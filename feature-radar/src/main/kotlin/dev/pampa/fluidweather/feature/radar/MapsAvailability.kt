package dev.pampa.fluidweather.feature.radar

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/** Perche' il radar non puo' aprirsi, quando non puo': lo si dice, non si mostra una mappa grigia. */
enum class MapsStatus {
  READY,
  PLAY_SERVICES_MISSING,
  KEY_MISSING,
}

/**
 * Il radar poggia su Google Maps SDK: servono i Play Services sul dispositivo e la chiave nel
 * manifest (che arriva da `local.properties`, fuori da git). Senza uno dei due il tasto radar si
 * degrada spiegando il motivo, come promesso dal piano.
 */
object MapsAvailability {

  const val KEY_META_DATA = "com.google.android.geo.API_KEY"

  fun check(context: Context): MapsStatus {
    val services = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
    if (services != ConnectionResult.SUCCESS) return MapsStatus.PLAY_SERVICES_MISSING
    val key = runCatching {
      context.packageManager
        .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        .metaData
        ?.getString(KEY_META_DATA)
    }.getOrNull()
    if (key.isNullOrBlank()) return MapsStatus.KEY_MISSING
    return MapsStatus.READY
  }
}
