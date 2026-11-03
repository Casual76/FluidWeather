package dev.pampa.fluidweather.core.sensor

import android.location.LocationManager
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Da dove si chiede un fix al `LocationManager`, e in che ordine. Pura: i nomi dei provider sono
 * stringhe, e il test non ha bisogno di Android.
 *
 * Il servizio di posizione di Google (il "fused") non c'e' piu': e' una libreria proprietaria, e
 * l'app e' GPL-3.0. Il `LocationManager` di Android ha gli stessi mattoni, uno per uno:
 *
 *  - **a basso consumo** — `fused` (dove il telefono ne ha uno, da Android 12), `network` (celle e
 *    Wi-Fi): un fix in pochi secondi, senza quota; e' l'equivalente di `BALANCED_POWER_ACCURACY`;
 *  - **`gps`**: la quota e la precisione, ma a freddo ci mette mezzo minuto e consuma. Si chiede
 *    dopo, solo se il basso consumo non ha risposto, e solo con il permesso preciso.
 */
internal object LocationPlan {

  const val FUSED: String = LocationManager.FUSED_PROVIDER
  const val NETWORK: String = LocationManager.NETWORK_PROVIDER
  const val GPS: String = LocationManager.GPS_PROVIDER
  const val PASSIVE: String = LocationManager.PASSIVE_PROVIDER

  /** Quanto del tempo del fix nuovo si da' al basso consumo prima di accendere il GPS. */
  const val LOW_POWER_SHARE: Double = 0.6

  /** Un gruppo di provider che si interrogano insieme, col suo pezzo del tempo (0..1). */
  data class Stage(val providers: List<String>, val share: Double)

  /**
   * I gruppi per il fix nuovo, nell'ordine in cui si provano. [enabled] sono i provider accesi
   * ora (`getProviders(true)`): un servizio di posizione spento nelle impostazioni li svuota
   * tutti, e il fix nuovo non si prova nemmeno — si passa all'ultima posizione nota.
   */
  fun freshStages(enabled: Set<String>, fineGranted: Boolean): List<Stage> {
    val lowPower = listOf(FUSED, NETWORK).filter { it in enabled }
    val gps = if (fineGranted && GPS in enabled) listOf(GPS) else emptyList()
    return when {
      lowPower.isNotEmpty() && gps.isNotEmpty() -> listOf(Stage(lowPower, LOW_POWER_SHARE), Stage(gps, 1.0 - LOW_POWER_SHARE))
      lowPower.isNotEmpty() -> listOf(Stage(lowPower, 1.0))
      gps.isNotEmpty() -> listOf(Stage(gps, 1.0))
      else -> emptyList()
    }
  }

  /**
   * I provider da cui leggere l'ultima posizione nota: tutti quelli che il telefono ha, meno quelli
   * che chiedono il permesso preciso quando c'e' solo l'approssimato (il `gps` e il passivo lo
   * rifiutano con una `SecurityException`, e non c'e' ragione di provarci).
   */
  fun lastKnownProviders(all: List<String>, fineGranted: Boolean): List<String> =
    all.filter { fineGranted || (it != GPS && it != PASSIVE) }
}

/** Il piu' recente fra piu' posizioni note: il `fixedAtMillis` piu' alto, e a parita' la prima. */
internal object LastKnown {

  fun newest(candidates: List<LocationSnapshot>): LocationSnapshot? =
    candidates.maxByOrNull { it.fixedAtMillis ?: Long.MIN_VALUE }
}

/** Il primo risultato fra piu' richieste fatte insieme. */
internal object LocationRace {

  /**
   * Lancia tutte le [sources] insieme e restituisce il primo valore non nullo; le altre si
   * annullano. Se nessuna ne da' uno, null: quando l'ultima ha finito, o prima, se il chiamante
   * le ferma con un timeout. Una fonte che lancia un'eccezione (tranne l'annullamento) vale
   * "niente fix": il basso consumo che si rompe non deve portarsi dietro il GPS.
   */
  suspend fun <T : Any> first(sources: List<suspend () -> T?>): T? {
    if (sources.isEmpty()) return null
    val result = CompletableDeferred<T?>()
    val pending = AtomicInteger(sources.size)
    return coroutineScope {
      val jobs = sources.map { source ->
        launch {
          val value = try {
            source()
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            null
          }
          if (value != null) result.complete(value) else if (pending.decrementAndGet() == 0) result.complete(null)
        }
      }
      try {
        result.await()
      } finally {
        // Le richieste ancora in volo si chiudono: senza, il coroutineScope le aspetterebbe fino in fondo.
        jobs.forEach { it.cancel() }
      }
    }
  }
}
