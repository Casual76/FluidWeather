package dev.pampa.fluidweather.testbench.data

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** La risposta grezza di un GET: stato e corpo, senza interpretazione. */
class HttpReply(val status: Int, val body: String)

/** Il trasporto e' un'interfaccia perche' i test dei fetcher non devono toccare la rete. */
fun interface HttpTransport {
  fun get(url: String): HttpReply
}

/** Il trasporto vero: il client JDK, con lo stesso User-Agent di [OpenMeteoFetcher]. */
class JdkTransport : HttpTransport {

  private val client = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(20))
    .build()

  override fun get(url: String): HttpReply {
    val request = HttpRequest.newBuilder(URI.create(url))
      .timeout(Duration.ofMinutes(3))
      .header("User-Agent", USER_AGENT)
      .GET()
      .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    return HttpReply(response.statusCode(), response.body())
  }

  companion object {
    const val USER_AGENT = "FluidWeather-testbench (uso non commerciale; dev.pampa.fluidweather)"
  }
}

/**
 * Il modo in cui il banco parla con Open-Meteo: ogni tentativo passa dal [CallBudget] (quindi
 * anche i tentativi ripetuti contano), un 429 si aspetta invece di insistere, gli errori di rete
 * e i 5xx si riprovano con pazienza crescente. Tutto il resto — 200, ma anche un 400 che il
 * chiamante sa leggere ("corsa non disponibile") — torna indietro cosi' com'e'.
 *
 * Dati meteo di Open-Meteo.com (CC BY 4.0), uso non commerciale.
 */
class OpenMeteoHttp(
  private val budget: CallBudget,
  private val transport: HttpTransport = JdkTransport(),
  private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
  private val log: (String) -> Unit = ::println,
) {

  /** Peso speso nelle ultime 24 ore (da questo e da qualunque altro processo): per i rapporti di avanzamento. */
  fun budgetUsedToday(): Int = budget.usedLast(CallBudget.DAY)

  /**
   * @param weight il peso della richiesta per il budget ([CallBudget.weightOf]).
   * @param label come chiamarla nei messaggi ("sesto-fiorentino/ukmo_seamless").
   */
  fun get(url: String, weight: Int, label: String): HttpReply {
    var backoff = FIRST_BACKOFF_MILLIS
    for (attempt in 1..MAX_ATTEMPTS) {
      budget.acquire(weight)
      val reply = try {
        transport.get(url)
      } catch (e: IOException) {
        if (attempt == MAX_ATTEMPTS) throw IllegalStateException("fetch fallito per $label: ${e.message}", e)
        log("  $label: errore di rete (${e.javaClass.simpleName}), aspetto ${backoff / 1000} s e riprovo ($attempt/$MAX_ATTEMPTS)")
        sleeper(backoff)
        backoff = minOf(backoff * 2, MAX_BACKOFF_MILLIS)
        continue
      }
      when {
        reply.status == 429 -> {
          check(attempt < MAX_ATTEMPTS) { "fetch fallito per $label: troppi 429 (${reason(reply.body)})" }
          // "Minutely/Hourly/Daily API request limit exceeded": la finestra che e' scoppiata dice
          // quanto conviene aspettare. Se il nostro conto era giusto non dovrebbe succedere, ma un
          // altro uso dello stesso IP (o un peso sottostimato) puo' bastare a farlo succedere.
          val wait = tooManyRequestsWait(reply.body, backoff)
          log("  $label: 429 (${reason(reply.body)}), aspetto ${wait / 1000} s e riprovo ($attempt/$MAX_ATTEMPTS)")
          sleeper(wait)
          backoff = minOf(backoff * 2, MAX_BACKOFF_MILLIS)
        }
        // Anche "questa corsa non esiste" arriva cosi', a volte: 200 e l'errore nel corpo
        // (`modelRunUnavailable`, visto sulle prime corse ECMWF delle 06). Non e' un intoppo del
        // server ma un fatto dell'archivio: riprovare vuol dire un'ora di attese per ogni buco.
        // La si restituisce, e decide chi ha chiesto.
        reply.status == 200 && isRunUnavailable(reply.body) -> return reply
        // Open-Meteo risponde in streaming: se il calcolo scade a meta' risposta lo stato e' gia'
        // partito (200) e l'errore finisce nel corpo ("Unexpected error while streaming data:
        // timeoutReached"), magari dopo meta' CSV. Succede sulle richieste da un anno di un modello
        // lento (visto su bergen/gem_seamless): e' un 5xx travestito, si riprova.
        reply.status in 500..599 || (reply.status == 200 && isStreamingError(reply.body)) -> {
          check(attempt < MAX_ATTEMPTS) { "fetch fallito per $label: HTTP ${reply.status} (${reason(reply.body)})" }
          log("  $label: HTTP ${reply.status} (${reason(reply.body).take(80)}), aspetto ${backoff / 1000} s e riprovo ($attempt/$MAX_ATTEMPTS)")
          sleeper(backoff)
          backoff = minOf(backoff * 2, MAX_BACKOFF_MILLIS)
        }
        else -> return reply
      }
    }
    error("fetch fallito per $label: troppi tentativi")
  }

  companion object {
    const val MAX_ATTEMPTS = 8
    const val FIRST_BACKOFF_MILLIS = 15_000L
    const val MAX_BACKOFF_MILLIS = 10 * 60_000L

    /** Il campo "reason" del JSON d'errore di Open-Meteo, o l'inizio del corpo se non c'e'. */
    fun reason(body: String): String {
      val match = Regex(""""reason"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(body)
      return match?.groupValues?.get(1) ?: body.take(120)
    }

    /** La corsa richiesta non c'e' nell'archivio: un buco permanente, non un errore da ritentare. */
    fun isRunUnavailable(body: String): Boolean = "modelRunUnavailable" in body

    internal fun isStreamingError(body: String): Boolean =
      "Unexpected error while streaming" in body || "timeoutReached" in body

    internal fun tooManyRequestsWait(body: String, backoff: Long): Long {
      val reason = reason(body).lowercase()
      return when {
        "daily" in reason -> 30 * 60_000L
        "hourly" in reason -> 10 * 60_000L
        "minutely" in reason -> 65_000L
        else -> backoff
      }
    }
  }
}
