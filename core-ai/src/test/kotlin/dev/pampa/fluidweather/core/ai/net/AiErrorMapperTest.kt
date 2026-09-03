package dev.pampa.fluidweather.core.ai.net

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiErrorMapperTest {

  private fun headers(vararg pairs: Pair<String, String>): Map<String?, List<String>> =
    pairs.associate { (k, v) -> k as String? to listOf(v) }

  @Test
  fun `il 429 di Groq legge retry-after dall'header e i limiti dagli x-ratelimit`() {
    val error = AiErrorMapper.map(
      429,
      headers(
        "Retry-After" to "3",
        "x-ratelimit-limit-tokens" to "8000",
        "x-ratelimit-remaining-tokens" to "120",
        "x-ratelimit-reset-tokens" to "2m59.56s",
        "x-ratelimit-remaining-requests" to "29",
      ),
      """{"error":{"message":"Rate limit reached for model qwen. Please try again in 7.66s","type":"tokens"}}""",
    ) as AiError.RateLimited
    assertEquals(3.0, error.retryAfterSec!!, 1e-9)
    assertEquals(8000, error.rateLimit.limitTokens)
    assertEquals(120, error.rateLimit.remainingTokens)
    assertEquals(29, error.rateLimit.remainingRequests)
    assertEquals(179.56, error.rateLimit.resetTokensSec!!, 1e-6)
    assertFalse(error.freeModelCap)
  }

  @Test
  fun `senza header il retry viene dalla frase di Groq`() {
    val error = AiErrorMapper.map(
      429,
      emptyMap(),
      """{"error":{"message":"Rate limit reached. Please try again in 1m2.5s."}}""",
    ) as AiError.RateLimited
    assertEquals(62.5, error.retryAfterSec!!, 1e-9)
  }

  @Test
  fun `Gemini mette il ritardo in RetryInfo`() {
    val body = """{"error":{"code":429,"message":"Resource has been exhausted","status":"RESOURCE_EXHAUSTED",
      "details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"37s"}]}}"""
    val error = AiErrorMapper.map(429, emptyMap(), body) as AiError.RateLimited
    assertEquals(37.0, error.retryAfterSec!!, 1e-9)
  }

  @Test
  fun `il tetto giornaliero dei modelli gratuiti di OpenRouter si riconosce`() {
    val body = """{"error":{"code":429,"message":"Rate limit exceeded: free-models-per-day. Add 10 credits to unlock 1000 free model requests per day","metadata":{"headers":{}}}}"""
    val error = AiErrorMapper.map(429, emptyMap(), body) as AiError.RateLimited
    assertTrue(error.freeModelCap)
  }

  @Test
  fun `chiave sbagliata in tutte le forme`() {
    assertTrue(AiErrorMapper.map(401, emptyMap(), """{"error":{"message":"Invalid API Key"}}""") is AiError.Unauthorized)
    assertTrue(AiErrorMapper.map(403, emptyMap(), "") is AiError.Unauthorized)
    val gemini = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""
    assertTrue(AiErrorMapper.map(400, emptyMap(), gemini) is AiError.Unauthorized)
  }

  @Test
  fun `400 generico, 404, 5xx e 408 nelle loro categorie`() {
    assertTrue(AiErrorMapper.map(400, emptyMap(), """{"error":{"message":"reasoning_effort not supported"}}""") is AiError.BadRequest)
    assertTrue(AiErrorMapper.map(404, emptyMap(), "") is AiError.BadRequest)
    assertTrue(AiErrorMapper.map(503, emptyMap(), "") is AiError.Server)
    assertTrue(AiErrorMapper.map(408, emptyMap(), "") is AiError.Server)
    assertEquals("HTTP 502", (AiErrorMapper.map(502, emptyMap(), "   ") as AiError.Server).message)
  }

  @Test
  fun `le durate di Groq si sommano`() {
    assertEquals(3723.5, AiErrorMapper.parseDuration("1h2m3.5s")!!, 1e-9)
    assertEquals(0.25, AiErrorMapper.parseDuration("250ms")!!, 1e-9)
    assertEquals(7.66, AiErrorMapper.parseDuration("7.66s")!!, 1e-9)
    assertNull(AiErrorMapper.parseDuration("presto"))
  }

  @Test
  fun `un reset di OpenRouter in millisecondi epoch diventa secondi da adesso`() {
    val now = 1_700_000_000_000L
    val info = AiErrorMapper.parseRateLimit(mapOf("x-ratelimit-reset-requests" to (now + 45_000).toString()), now)
    assertEquals(45.0, info.resetRequestsSec!!, 1e-9)
  }

  @Test
  fun `le eccezioni di rete si incartano e le cancellazioni no`() {
    assertTrue(AiErrorMapper.wrap(SocketTimeoutException()) is AiError.Timeout)
    assertTrue(AiErrorMapper.wrap(UnknownHostException("api")) is AiError.Network)
    val cancellation = kotlinx.coroutines.CancellationException("stop")
    assertTrue(AiErrorMapper.wrap(cancellation) === cancellation)
  }
}
