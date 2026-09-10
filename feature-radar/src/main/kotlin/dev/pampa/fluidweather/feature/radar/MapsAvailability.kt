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

  /**
   * La chiave c'e' ma Google non disegna niente.
   *
   * Il controllo pre-volo sa dire solo se il meta-data e' vuoto. Una chiave *presente e
   * rifiutata* — Maps SDK for Android non abilitato nel progetto Cloud, restrizione per pacchetto
   * o SHA-1 che non corrisponde, fatturazione sospesa — dava una mappa grigia e muta, e chi la
   * guardava non aveva modo di sapere che il problema era in una console web. L'SDK non espone
   * un callback di autorizzazione fallita: quello che si puo' osservare e' che la mappa non
   * finisce mai di caricare, e questo stato dice esattamente quello, senza diagnosi inventate.
   */
  KEY_REFUSED,
}

/**
 * Il radar poggia su Google Maps SDK: servono i Play Services sul dispositivo e la chiave nel
 * manifest (che arriva da `local.properties`, fuori da git). Senza uno dei due il tasto radar si
 * degrada spiegando il motivo, come promesso dal piano.
 */
object MapsAvailability {

  const val KEY_META_DATA = "com.google.android.geo.API_KEY"

  /**
   * Quanto si aspetta prima di dire che la mappa non sta caricando. Dodici secondi: su una rete
   * lenta il primo tile puo' metterci qualche secondo, ma non dodici — e una chiave rifiutata non
   * ci mettera' mai meno.
   */
  const val LOAD_TIMEOUT_MILLIS = 12_000L

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
