package dev.pampa.fluidweather.core.ai.net

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Un server HTTP/1.1 minuscolo su `ServerSocket`, per i test del client: i moduli Android
 * compilano contro android.jar, dove `com.sun.net.httpserver` non esiste. Fa una cosa sola —
 * leggere una richiesta e lasciare che il gestore scriva la risposta a mano, con
 * `Connection: close` — ed e' esattamente quello che serve per provare SSE spezzati, timeout e
 * disconnessioni dal lato del server.
 */
class TinyHttpServer : AutoCloseable {

  class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

  class Response(private val out: OutputStream) {
    private var begun = false

    fun begin(code: Int, headers: Map<String, String> = emptyMap(), contentLength: Long? = null) {
      check(!begun)
      begun = true
      val head = StringBuilder("HTTP/1.1 $code ${reason(code)}\r\nConnection: close\r\n")
      headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
      contentLength?.let { head.append("Content-Length: ").append(it).append("\r\n") }
      head.append("\r\n")
      out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
      out.flush()
    }

    fun write(text: String) {
      out.write(text.toByteArray(Charsets.UTF_8))
      out.flush()
    }

    fun reply(code: Int, body: String, headers: Map<String, String> = emptyMap()) {
      val bytes = body.toByteArray(Charsets.UTF_8)
      begin(code, headers + ("Content-Type" to "application/json"), bytes.size.toLong())
      out.write(bytes)
      out.flush()
    }

    private fun reason(code: Int) = when (code) {
      200 -> "OK"
      400 -> "Bad Request"
      401 -> "Unauthorized"
      429 -> "Too Many Requests"
      500 -> "Internal Server Error"
      else -> "Status"
    }
  }

  private val socket = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
  private val executor = Executors.newCachedThreadPool()
  private val running = AtomicBoolean(true)
  private val handlers = mutableMapOf<String, (Request, Response) -> Unit>()

  val port: Int get() = socket.localPort
  val base: String get() = "http://127.0.0.1:$port"

  init {
    executor.execute {
      while (running.get()) {
        val client = try {
          socket.accept()
        } catch (e: IOException) {
          break
        }
        executor.execute { serve(client) }
      }
    }
  }

  fun handle(path: String, handler: (Request, Response) -> Unit) {
    synchronized(handlers) { handlers[path] = handler }
  }

  private fun serve(client: Socket) {
    client.use { connection ->
      try {
        val input = BufferedInputStream(connection.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0) ?: return
        val path = parts.getOrNull(1)?.substringBefore('?') ?: return
        val headers = linkedMapOf<String, String>()
        while (true) {
          val line = readLine(input) ?: break
          if (line.isEmpty()) break
          val colon = line.indexOf(':')
          if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
          val n = input.read(body, read, length - read)
          if (n < 0) break
          read += n
        }
        val handler = synchronized(handlers) { handlers[path] }
        val response = Response(connection.getOutputStream())
        if (handler == null) {
          response.reply(404, "{}")
        } else {
          handler(Request(method, path, headers, body), response)
        }
      } catch (e: IOException) {
        // Il client ha chiuso: e' un esito che alcuni test vogliono vedere, non un errore.
      }
    }
  }

  private fun readLine(input: InputStream): String? {
    val bytes = java.io.ByteArrayOutputStream()
    while (true) {
      val b = input.read()
      if (b < 0) return if (bytes.size() == 0) null else bytes.toString("ISO-8859-1")
      if (b == '\n'.code) break
      if (b != '\r'.code) bytes.write(b)
    }
    return bytes.toString("ISO-8859-1")
  }

  override fun close() {
    running.set(false)
    runCatching { socket.close() }
    executor.shutdownNow()
  }
}
