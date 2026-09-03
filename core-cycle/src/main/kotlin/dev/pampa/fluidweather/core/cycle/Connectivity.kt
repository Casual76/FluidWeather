package dev.pampa.fluidweather.core.cycle

import android.content.Context
import android.net.ConnectivityManager

/**
 * C'e' almeno un trasporto di rete acceso?
 *
 * **Non e' "sono online".** Il predicato e' volutamente il piu' stupido che esista — nessuna rete
 * attiva, cioe' modalita' aereo o tutto spento — e non guarda `NET_CAPABILITY_VALIDATED`, che
 * mente in entrambe le direzioni: dice "validata" dentro un captive portal d'albergo e dice di no
 * su reti perfettamente funzionanti dietro certe VPN.
 *
 * Serve **solo** a non svegliare dieci richieste in parallelo che aspetteranno il timeout per
 * niente. Non finisce mai in una frase mostrata a chi guarda: la UI parla di **eta' dei dati**,
 * perche' quello lo sappiamo davvero, mentre "sei offline" sarebbe una deduzione — i provider
 * possono essere raggiungibili e rispondere tutti 500, o limitare la frequenza, o essere bloccati
 * da un firewall aziendale su un solo dominio.
 *
 * Falsi negativi impossibili (se dice di no, non c'e' proprio niente); falsi positivi innocui, li
 * gestisce comunque il giro che fallisce.
 */
fun networkLikelyAvailable(context: Context): Boolean = runCatching {
  val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
  manager?.activeNetwork != null
}.getOrDefault(true)
