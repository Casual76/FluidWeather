package dev.pampa.fluidweather.core.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

data class LocationSnapshot(
  val latitude: Double,
  val longitude: Double,
  val altitudeMeters: Double?,
)

/**
 * Una fotografia della posizione per arricchire i campioni. Senza permesso o senza fix entro il
 * timeout restituisce null: un campione senza quota vale piu' di nessun campione.
 */
class LocationProvider(private val context: Context) {

  fun hasPermission(): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED

  suspend fun snapshot(timeoutMillis: Long = 10_000): LocationSnapshot? {
    if (!hasPermission()) return null
    val client = LocationServices.getFusedLocationProviderClient(context)
    return try {
      withTimeoutOrNull(timeoutMillis) {
        val location =
          client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token).await()
            ?: client.lastLocation.await()
        location?.let {
          LocationSnapshot(
            latitude = it.latitude,
            longitude = it.longitude,
            altitudeMeters = if (it.hasAltitude()) it.altitude else null,
          )
        }
      }
    } catch (_: SecurityException) {
      // Il permesso puo' sparire fra il check e la chiamata (revoca dalle impostazioni).
      null
    }
  }
}
