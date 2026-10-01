package dev.pampa.fluidweather.core.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dev.pampa.fluidweather.core.data.LastFixStore
import dev.pampa.fluidweather.core.model.LastFix
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

data class LocationSnapshot(
  val latitude: Double,
  val longitude: Double,
  val altitudeMeters: Double?,
  /**
   * Quando il sistema ha preso il fix (`Location.time`), non quando lo abbiamo letto: l'ultima
   * posizione nota che il fallback restituisce puo' avere ore, e chi decide se e' "qui" ha bisogno
   * dell'eta' vera. Null se il sistema non la dichiara.
   */
  val fixedAtMillis: Long? = null,
) {

  /**
   * Il fix e' "di adesso"? [LocationProvider.snapshot] ripiega sull'ultima posizione nota quando il
   * fix nuovo non arriva, e quella puo' avere ore o giorni: le coordinate sono buone per mostrare
   * il meteo, ma un giro fatto li' non e' un giro "del telefono qui" e non deve iscrivere niente
   * alla classifica del barometro. Senza istante dichiarato (il sistema non l'ha dato) vale come
   * fresco: e' il caso del fix appena chiesto, non dell'ultima posizione, che l'istante ce l'ha.
   */
  fun isFreshAt(nowMillis: Long): Boolean {
    val fixedAt = fixedAtMillis ?: return true
    return nowMillis - fixedAt in -CLOCK_SKEW_MILLIS..FRESH_MAX_AGE_MILLIS
  }

  companion object {
    /** Un fix "di adesso": dieci minuti, il passo piu' lento del campionamento. */
    const val FRESH_MAX_AGE_MILLIS: Long = 10 * 60_000L

    /** Quanto un fix puo' sembrare dal futuro ed essere ancora buono: l'orologio si corregge a salti. */
    const val CLOCK_SKEW_MILLIS: Long = 5 * 60_000L
  }
}

/**
 * Una fotografia della posizione per arricchire i campioni. Senza permesso o senza fix entro il
 * timeout restituisce null: un campione senza quota vale piu' di nessun campione.
 *
 * Ogni fix letto si ricorda in [lastFixStore]: in background il fix nuovo spesso non arriva, e
 * l'ultimo di un'ora fa e' quello che permette al ciclo di dire ancora "il telefono e' qui" (vedi
 * `PointResolver`) invece di perdere il giro del barometro.
 */
class LocationProvider(
  private val context: Context,
  private val lastFixStore: LastFixStore? = null,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  fun hasPermission(): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED

  suspend fun snapshot(timeoutMillis: Long = 10_000): LocationSnapshot? {
    if (!hasPermission()) return null
    val client = LocationServices.getFusedLocationProviderClient(context)
    val cancellation = CancellationTokenSource()
    return try {
      LocationFallback.firstFix(
        current = {
          client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellation.token).await()?.toSnapshot()
        },
        lastKnown = { client.lastLocation.await()?.toSnapshot() },
        totalTimeoutMillis = timeoutMillis,
      )?.also { fix ->
        // Non lancia mai: un ultimo fix non scritto costa un giro, non la posizione di adesso.
        runCatching { lastFixStore?.record(LastFix(fix.latitude, fix.longitude, fix.fixedAtMillis ?: clock())) }
      }
    } catch (_: SecurityException) {
      // Il permesso puo' sparire fra il check e la chiamata (revoca dalle impostazioni).
      null
    } finally {
      // Un fix nuovo che non e' arrivato non deve restare in richiesta (e consumare batteria).
      cancellation.cancel()
    }
  }

  private fun Location.toSnapshot() = LocationSnapshot(
    latitude = latitude,
    longitude = longitude,
    altitudeMeters = if (hasAltitude()) altitude else null,
    fixedAtMillis = time.takeIf { it > 0L },
  )
}

/**
 * Il primo fix utile: quello nuovo se arriva, altrimenti l'ultimo noto.
 *
 * Prima i due stavano sotto UN timeout solo: se `getCurrentLocation` restava appeso (succede coi
 * servizi di localizzazione in risparmio energetico) scadeva tutto e anche l'ultima posizione nota,
 * che e' istantanea, andava persa: il giro del barometro moriva per un fix che il telefono aveva.
 * Adesso il fix nuovo ha il suo budget e l'ultimo noto il suo, e la somma non supera mai il
 * totale, cosi' i chiamanti con un tetto (5 s) lo mantengono.
 *
 * Un'eccezione del fix nuovo (tranne [SecurityException], che e' una revoca da segnalare) vale
 * "non e' arrivato" e ripiega sull'ultimo noto. Pura e senza Android: si prova con il tempo virtuale.
 */
internal object LocationFallback {

  /** Quanto si aspetta l'ultima posizione nota: e' una lettura locale, due secondi sono generosi. */
  const val LAST_KNOWN_TIMEOUT_MILLIS: Long = 2_000L

  suspend fun <T : Any> firstFix(
    current: suspend () -> T?,
    lastKnown: suspend () -> T?,
    totalTimeoutMillis: Long,
  ): T? {
    val currentBudget = (totalTimeoutMillis - LAST_KNOWN_TIMEOUT_MILLIS).coerceAtLeast(totalTimeoutMillis / 2)
    val lastKnownBudget = (totalTimeoutMillis - currentBudget).coerceAtLeast(0L)
    attempt(currentBudget, current)?.let { return it }
    return attempt(lastKnownBudget, lastKnown)
  }

  private suspend fun <T : Any> attempt(budgetMillis: Long, read: suspend () -> T?): T? =
    try {
      withTimeoutOrNull(budgetMillis) { read() }
    } catch (security: SecurityException) {
      throw security
    } catch (failure: Exception) {
      // Un annullamento del chiamante si propaga; un Task annullato o fallito e' solo "niente fix".
      currentCoroutineContext().ensureActive()
      null
    }
}
