package dev.pampa.fluidweather.core.ai.net

import dev.pampa.fluidweather.core.weather.asArray
import dev.pampa.fluidweather.core.weather.get
import dev.pampa.fluidweather.core.weather.string
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Da codice HTTP + header + corpo a [AiError]. Puro: si prova sul computer con i corpi veri dei
 * tre provider (fixture nei test).
 */
object AiErrorMapper {

  fun map(code: Int, rawHeaders: Map<String?, List<String>>, body: String, nowMillis: Long = System.currentTimeMillis()): AiError {
    val headers = normalize(rawHeaders)
    val json = runCatching { Json.parseToJsonElement(body) }.getOrNull()
    val message = errorMessage(json) ?: body.take(200).ifBlank { "HTTP $code" }
    val lower = message.lowercase()
    return when {
      code == 401 || code == 403 -> AiError.Unauthorized(message)
      code == 400 && ("api key" in lower || "api_key" in lower) -> AiError.Unauthorized(message)
      code == 429 -> AiError.RateLimited(
        retryAfterSec = parseRetryAfter(headers, json, message, nowMillis),
        rateLimit = parseRateLimit(headers, nowMillis),
        freeModelCap = isFreeModelCap(json, message),
        message = message,
      )
      code == 408 || code >= 500 -> AiError.Server(code, message)
      code >= 400 -> AiError.BadRequest(code, message)
      else -> AiError.Server(code, message)
    }
  }

  /** Un'eccezione di rete/IO nella sua categoria; le cancellazioni passano oltre intatte. */
  fun wrap(t: Throwable): Throwable = when (t) {
    is AiError -> t
    is CancellationException -> t
    is SocketTimeoutException -> AiError.Timeout("timeout", t)
    is UnknownHostException, is ConnectException, is SSLException -> AiError.Network(t.message ?: "rete", t)
    is IOException -> AiError.Network(t.message ?: "rete", t)
    else -> t
  }

  /** `error.message` e' dove Groq, Gemini e OpenRouter mettono tutti la frase; il resto e' rumore. */
  fun errorMessage(json: JsonElement?): String? =
    json["error"]["message"].string()?.takeIf { it.isNotBlank() }
      ?: json["message"].string()?.takeIf { it.isNotBlank() }

  fun normalize(rawHeaders: Map<String?, List<String>>): Map<String, String> =
    rawHeaders.entries
      .filter { it.key != null && it.value.isNotEmpty() }
      .associate { it.key!!.lowercase() to it.value.first() }

  /**
   * In ordine di fiducia: header `retry-after` (secondi o data HTTP), `RetryInfo.retryDelay` nei
   * dettagli di Gemini ("37s"), la frase di Groq "try again in 7.66s".
   */
  fun parseRetryAfter(
    headers: Map<String, String>,
    json: JsonElement?,
    message: String,
    nowMillis: Long = System.currentTimeMillis(),
  ): Double? {
    headers["retry-after"]?.trim()?.let { value ->
      value.toDoubleOrNull()?.let { return it.coerceAtLeast(0.0) }
      runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME) }.getOrNull()?.let { at ->
        return ((at.toInstant().toEpochMilli() - nowMillis) / 1000.0).coerceAtLeast(0.0)
      }
    }
    json["error"]["details"].asArray().forEach { detail ->
      if (detail["@type"].string()?.endsWith("RetryInfo") == true) {
        detail["retryDelay"].string()?.let { raw -> parseDuration(raw)?.let { return it } }
      }
    }
    GROQ_TRY_AGAIN.find(message)?.let { match -> return parseDuration(match.groupValues[1]) }
    return null
  }

  /** "1h2m3.5s", "7.66s", "250ms", "2m59.56s" -> secondi. Null se non e' una durata. */
  fun parseDuration(raw: String): Double? {
    val text = raw.trim().lowercase()
    if (text.isEmpty()) return null
    var total = 0.0
    var matched = false
    DURATION_PART.findAll(text).forEach { part ->
      matched = true
      val value = part.groupValues[1].toDouble()
      total += when (part.groupValues[2]) {
        "h" -> value * 3600
        "m" -> value * 60
        "s" -> value
        "ms" -> value / 1000
        else -> 0.0
      }
    }
    return if (matched) total else null
  }

  fun parseRateLimit(headers: Map<String, String>, nowMillis: Long = System.currentTimeMillis()): RateLimitInfo =
    RateLimitInfo(
      limitRequests = headers["x-ratelimit-limit-requests"]?.trim()?.toIntOrNull(),
      remainingRequests = headers["x-ratelimit-remaining-requests"]?.trim()?.toIntOrNull(),
      limitTokens = headers["x-ratelimit-limit-tokens"]?.trim()?.toIntOrNull(),
      remainingTokens = headers["x-ratelimit-remaining-tokens"]?.trim()?.toIntOrNull(),
      resetRequestsSec = headers["x-ratelimit-reset-requests"]?.let { parseResetSeconds(it, nowMillis) },
      resetTokensSec = headers["x-ratelimit-reset-tokens"]?.let { parseResetSeconds(it, nowMillis) },
    )

  /** Groq scrive durate ("2m59.56s"); OpenRouter un timestamp in millisecondi. */
  private fun parseResetSeconds(raw: String, nowMillis: Long): Double? {
    val text = raw.trim()
    text.toLongOrNull()?.let { number ->
      if (number > 1_000_000_000_000L) return ((number - nowMillis) / 1000.0).coerceAtLeast(0.0)
      return number.toDouble()
    }
    return parseDuration(text)
  }

  private fun isFreeModelCap(json: JsonElement?, message: String): Boolean {
    val lower = message.lowercase()
    if ("free" in lower && ("daily" in lower || "per day" in lower || "day" in lower)) return true
    val raw = json["error"]["metadata"]["raw"].string()?.lowercase() ?: return false
    return "free" in raw && "day" in raw
  }

  private val GROQ_TRY_AGAIN = Regex("try again in ([0-9hms.]+)", RegexOption.IGNORE_CASE)
  private val DURATION_PART = Regex("(\\d+(?:\\.\\d+)?)(ms|h|m|s)")
}
