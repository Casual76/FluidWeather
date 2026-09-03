package dev.pampa.fluidweather.core.ai.net

import dev.pampa.fluidweather.core.weather.get
import dev.pampa.fluidweather.core.weather.string
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Il client contro un server vero su socket, cosi' il percorso testato e' quello di
 * `HttpURLConnection`: SSE spezzati, timeout per lettura, disconnessione alla cancellazione.
 * Un finto `EngineHttp` non avrebbe detto niente di tutto questo.
 */
class AiHttpServerTest {

  private lateinit var server: TinyHttpServer
  private val http = AiHttp(userAgent = "test", connectTimeoutMillis = 2_000, readTimeoutMillis = 2_000, streamChunkTimeoutMillis = 500)

  @Before
  fun start() {
    server = TinyHttpServer()
  }

  @After
  fun stop() {
    server.close()
  }

  @Test
  fun `postJson manda il corpo e legge JSON e header`() = runBlocking<Unit> {
    var received = ""
    var contentType = ""
    server.handle("/chat") { request, response ->
      received = String(request.body, Charsets.UTF_8)
      contentType = request.headers["content-type"].orEmpty()
      response.reply(200, """{"ok":true,"echo":"ciao"}""", mapOf("x-ratelimit-remaining-tokens" to "7000"))
    }
    val response = http.postJson("${server.base}/chat", mapOf("Authorization" to "Bearer k"), buildJsonObject { put("q", JsonPrimitive("ciao")) })
    assertEquals("""{"q":"ciao"}""", received)
    assertTrue(contentType.startsWith("application/json"))
    assertEquals("ciao", response.body["echo"].string())
    assertEquals(7000, response.rateLimit.remainingTokens)
  }

  @Test
  fun `un 429 con Retry-After diventa RateLimited`() = runBlocking<Unit> {
    server.handle("/limited") { _, response ->
      response.reply(429, """{"error":{"message":"too fast"}}""", mapOf("Retry-After" to "3"))
    }
    val error = runCatching { http.postJson("${server.base}/limited", emptyMap(), buildJsonObject { }) }.exceptionOrNull()
    assertTrue("atteso RateLimited, avuto $error", error is AiError.RateLimited)
    assertEquals(3.0, (error as AiError.RateLimited).retryAfterSec!!, 1e-9)
    assertEquals("too fast", error.message)
  }

  @Test
  fun `lo stream consegna gli eventi uno alla volta e si ferma a DONE`() = runBlocking<Unit> {
    server.handle("/stream") { _, response ->
      response.begin(200, mapOf("Content-Type" to "text/event-stream"))
      listOf(": keep-alive\n\n", "data: {\"n\":1}\n\n", "data: {\"n\":2}\n", "\n", "data: [DONE]\n\n", "data: {\"n\":3}\n\n").forEach {
        response.write(it)
        Thread.sleep(40)
      }
    }
    val events = http.postJsonStream("${server.base}/stream", emptyMap(), buildJsonObject { }).toList()
    assertEquals(listOf("{\"n\":1}", "{\"n\":2}"), events)
  }

  @Test
  fun `gli header dello stream arrivano prima del primo evento`() = runBlocking<Unit> {
    server.handle("/headers") { _, response ->
      response.begin(200, mapOf("Content-Type" to "text/event-stream", "x-ratelimit-remaining-requests" to "12"))
      response.write("data: x\n\n")
    }
    var seen: Map<String, String>? = null
    val events = http.postJsonStream("${server.base}/headers", emptyMap(), buildJsonObject { }, onHeaders = { seen = it }).toList()
    assertEquals(listOf("x"), events)
    assertEquals("12", seen?.get("x-ratelimit-remaining-requests"))
  }

  @Test
  fun `cancellare il flusso chiude la connessione dal lato del server`() = runBlocking<Unit> {
    val broken = CountDownLatch(1)
    server.handle("/slow") { _, response ->
      response.begin(200, mapOf("Content-Type" to "text/event-stream"))
      try {
        response.write("data: first\n\n")
        repeat(300) {
          Thread.sleep(20)
          response.write(": ping\n\n")
        }
      } catch (e: Exception) {
        broken.countDown()
      }
    }
    val first = http.postJsonStream("${server.base}/slow", emptyMap(), buildJsonObject { }).first()
    assertEquals("first", first)
    assertTrue("il server deve vedere la socket chiusa", broken.await(6, TimeUnit.SECONDS))
  }

  @Test
  fun `un server muto in streaming fa scattare il timeout per lettura`() = runBlocking<Unit> {
    server.handle("/mute") { _, response ->
      response.begin(200, mapOf("Content-Type" to "text/event-stream"))
      Thread.sleep(3_000)
    }
    val error = runCatching {
      withTimeout(5_000) { http.postJsonStream("${server.base}/mute", emptyMap(), buildJsonObject { }).toList() }
    }.exceptionOrNull()
    assertTrue("atteso Timeout, avuto $error", error is AiError.Timeout)
  }

  @Test
  fun `il multipart arriva con boundary, campi e file`() = runBlocking<Unit> {
    val file = File.createTempFile("ask", ".wav").apply { writeBytes(ByteArray(300) { it.toByte() }) }
    var body = ByteArray(0)
    var type = ""
    server.handle("/audio") { request, response ->
      body = request.body
      type = request.headers["content-type"].orEmpty()
      response.reply(200, """{"text":"ciao"}""")
    }
    val response = http.postMultipart("${server.base}/audio", emptyMap(), mapOf("model" to "whisper"), file, "audio/wav", "ask.wav")
    assertEquals("ciao", response.body["text"].string())
    assertTrue(type.startsWith("multipart/form-data; boundary="))
    val text = String(body, Charsets.ISO_8859_1)
    assertTrue(text.contains("name=\"model\"\r\n\r\nwhisper"))
    assertTrue(text.contains("filename=\"ask.wav\"\r\nContent-Type: audio/wav"))
    assertTrue(text.endsWith("--\r\n"))
    file.delete()
  }

  @Test
  fun `una risposta 200 non JSON e' un errore di parse`() = runBlocking<Unit> {
    server.handle("/html") { _, response -> response.reply(200, "<html>no</html>") }
    val error = runCatching { http.getJson("${server.base}/html", emptyMap()) }.exceptionOrNull()
    assertTrue("atteso Parse, avuto $error", error is AiError.Parse)
  }
}
