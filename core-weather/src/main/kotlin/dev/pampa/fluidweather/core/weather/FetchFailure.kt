package dev.pampa.fluidweather.core.weather

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Perche' un provider non ha risposto, detto in modo leggibile.
 *
 * L'errore finiva nell'istantanea come `error.message` grezzo, quindi la Diagnostica mostrava
 * "Unable to resolve host" accanto a "HTTP 500" senza distinguere le due situazioni, che per chi
 * guarda sono opposte: nella prima non c'e' niente da fare se non aspettare la rete, nella seconda
 * il provider e' giu' e la costellazione dovrebbe coprirlo.
 *
 * Non e' un tentativo di dire "sei offline": lo dice **di questo tentativo**, non del telefono.
 * L'assistente lo fa gia' cosi' (`AiErrorMapper`) e li' e' servito parecchio.
 */
enum class FetchFailureKind {
  /** DNS, connessione, TLS, timeout: non ci siamo arrivati. */
  NETWORK,

  /** Ci siamo arrivati e ha risposto male: HTTP non-2xx, JSON rotto, campi mancanti. */
  PROVIDER,
}

fun failureKindOf(error: Throwable): FetchFailureKind = when (error) {
  is UnknownHostException, is ConnectException, is SocketTimeoutException, is SSLException -> FetchFailureKind.NETWORK
  // `IOException` per ultimo: le quattro sopra ne sono sottoclassi e vanno riconosciute prima.
  is IOException -> FetchFailureKind.NETWORK
  else -> FetchFailureKind.PROVIDER
}

/** La riga che finisce nell'istantanea e nella Diagnostica. */
fun failureTextOf(error: Throwable): String {
  val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
  return when (failureKindOf(error)) {
    FetchFailureKind.NETWORK -> "rete: $detail"
    FetchFailureKind.PROVIDER -> detail
  }
}
