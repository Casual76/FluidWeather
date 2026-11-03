package dev.pampa.fluidweather.core.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.util.Consumer
import dev.pampa.fluidweather.core.data.LastFixStore
import dev.pampa.fluidweather.core.model.LastFix
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
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
  /**
   * Quanto veloce si muoveva il telefono, in metri al secondo, secondo la miglior stima: la
   * velocita' del fix, se il provider la da' (il GPS si', la rete no), altrimenti quella media dal
   * fix precedente ([TravelSpeed]). Null se non si sa. Serve a [MotionClassifier]: Android non ha
   * un riconoscimento dell'attivita' fuori da Google Play, e "in auto" si capisce da qui.
   */
  val speedMetersPerSecond: Double? = null,
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
 * E' il `LocationManager` di Android, non Google Play Services (libreria proprietaria, fuori da
 * un'app GPL-3.0). Il comportamento e' quello di prima: il fix nuovo ha il suo budget, e se non
 * arriva si ripiega sull'ultima posizione nota ([LocationFallback]); lo stesso permesso preciso o
 * approssimato; e una posizione simulata (l'emulatore, un'app di test) passa dal provider `gps` o
 * `network` come una vera.
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

  private val manager: LocationManager? = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

  fun hasPermission(): Boolean = hasFine() || hasCoarse()

  private fun hasFine(): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

  private fun hasCoarse(): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

  suspend fun snapshot(timeoutMillis: Long = 10_000): LocationSnapshot? {
    if (!hasPermission() || manager == null) return null
    return try {
      LocationFallback.firstFix(
        current = { freshFix(LocationFallback.currentBudgetMillis(timeoutMillis)) },
        lastKnown = { lastKnownFix() },
        totalTimeoutMillis = timeoutMillis,
      )?.let { fix -> withTravelSpeed(fix) }?.also { fix ->
        // Non lancia mai: un ultimo fix non scritto costa un giro, non la posizione di adesso.
        runCatching { lastFixStore?.record(LastFix(fix.latitude, fix.longitude, fix.fixedAtMillis ?: clock())) }
      }
    } catch (_: SecurityException) {
      // Il permesso puo' sparire fra il check e la chiamata (revoca dalle impostazioni).
      null
    }
  }

  /**
   * Il fix nuovo: prima il basso consumo (rete, e il "fused" dove c'e'), poi il GPS se serve e se
   * c'e' il permesso preciso. Ogni gruppo ha il suo pezzo del tempo e dentro il gruppo vince il
   * primo che risponde: il Wi-Fi non aspetta il GPS.
   */
  private suspend fun freshFix(budgetMillis: Long): LocationSnapshot? {
    val manager = manager ?: return null
    val enabled = runCatching { manager.getProviders(true).toSet() }.getOrDefault(emptySet())
    for (stage in LocationPlan.freshStages(enabled, hasFine())) {
      val share = (budgetMillis * stage.share).toLong().coerceAtLeast(1L)
      val fix = withTimeoutOrNull(share) {
        LocationRace.first(stage.providers.map { provider -> suspend { requestCurrent(manager, provider) } })
      }
      if (fix != null) return fix
    }
    return null
  }

  /** Un fix da un provider solo; un annullamento chiude anche la richiesta al sistema (niente batteria sprecata). */
  private suspend fun requestCurrent(manager: LocationManager, provider: String): LocationSnapshot? =
    suspendCancellableCoroutine { continuation ->
      val signal = CancellationSignal()
      continuation.invokeOnCancellation { signal.cancel() }
      try {
        LocationManagerCompat.getCurrentLocation(
          manager,
          provider,
          signal,
          ContextCompat.getMainExecutor(context),
          Consumer<Location?> { location ->
            if (continuation.isActive) continuation.resumeWith(Result.success(location?.toSnapshot()))
          },
        )
      } catch (_: SecurityException) {
        if (continuation.isActive) continuation.resumeWith(Result.success(null))
      } catch (_: IllegalArgumentException) {
        // Un provider che il telefono non ha (o non ha piu'): non e' un fix, non e' un errore.
        if (continuation.isActive) continuation.resumeWith(Result.success(null))
      }
    }

  /** L'ultima posizione nota: la piu' recente fra quelle che i provider hanno in memoria. */
  private suspend fun lastKnownFix(): LocationSnapshot? {
    val manager = manager ?: return null
    currentCoroutineContext().ensureActive()
    val providers = LocationPlan.lastKnownProviders(runCatching { manager.allProviders }.getOrDefault(emptyList()), hasFine())
    return LastKnown.newest(
      providers.mapNotNull { provider ->
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.toSnapshot()
      },
    )
  }

  /**
   * Aggiunge la velocita' se il fix non l'aveva: la media dal fix precedente, che sta in
   * [lastFixStore] prima che questo lo sostituisca. Va letta *prima* di `record`.
   */
  private suspend fun withTravelSpeed(fix: LocationSnapshot): LocationSnapshot {
    if (fix.speedMetersPerSecond != null) return fix
    val previous = runCatching { lastFixStore?.current() }.getOrNull() ?: return fix
    val speed = TravelSpeed.between(previous, fix.latitude, fix.longitude, fix.fixedAtMillis ?: clock())
    return if (speed == null) fix else fix.copy(speedMetersPerSecond = speed)
  }

  private fun Location.toSnapshot() = LocationSnapshot(
    latitude = latitude,
    longitude = longitude,
    altitudeMeters = if (hasAltitude()) altitude else null,
    fixedAtMillis = time.takeIf { it > 0L },
    speedMetersPerSecond = if (hasSpeed()) speed.toDouble() else null,
  )
}

/**
 * Il primo fix utile: quello nuovo se arriva, altrimenti l'ultimo noto.
 *
 * Prima i due stavano sotto UN timeout solo: se il fix nuovo restava appeso (succede coi servizi
 * di localizzazione in risparmio energetico) scadeva tutto e anche l'ultima posizione nota, che e'
 * istantanea, andava persa: il giro del barometro moriva per un fix che il telefono aveva.
 * Adesso il fix nuovo ha il suo budget e l'ultimo noto il suo, e la somma non supera mai il
 * totale, cosi' i chiamanti con un tetto (5 s) lo mantengono.
 *
 * Un'eccezione del fix nuovo (tranne [SecurityException], che e' una revoca da segnalare) vale
 * "non e' arrivato" e ripiega sull'ultimo noto. Pura e senza Android: si prova con il tempo virtuale.
 */
internal object LocationFallback {

  /** Quanto si aspetta l'ultima posizione nota: e' una lettura locale, due secondi sono generosi. */
  const val LAST_KNOWN_TIMEOUT_MILLIS: Long = 2_000L

  /** Il tempo che resta al fix nuovo su un totale di [totalTimeoutMillis] (il resto e' dell'ultimo noto). */
  fun currentBudgetMillis(totalTimeoutMillis: Long): Long =
    (totalTimeoutMillis - LAST_KNOWN_TIMEOUT_MILLIS).coerceAtLeast(totalTimeoutMillis / 2)

  suspend fun <T : Any> firstFix(
    current: suspend () -> T?,
    lastKnown: suspend () -> T?,
    totalTimeoutMillis: Long,
  ): T? {
    val currentBudget = currentBudgetMillis(totalTimeoutMillis)
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
      // Un annullamento del chiamante si propaga; un fix fallito e' solo "niente fix".
      currentCoroutineContext().ensureActive()
      null
    }
}
