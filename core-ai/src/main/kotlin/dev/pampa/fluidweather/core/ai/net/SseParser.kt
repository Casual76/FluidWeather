package dev.pampa.fluidweather.core.ai.net

/**
 * Un parser di Server-Sent Events riga per riga, come dice la specifica: le righe `data:` di un
 * evento si accumulano, una riga vuota lo chiude, i commenti (`: OPENROUTER PROCESSING`, i
 * keep-alive di Groq) e i campi `event:`/`id:`/`retry:` non ci interessano. Una istanza per
 * stream; [flush] recupera un ultimo evento non terminato a fine connessione.
 */
class SseParser {

  private val buffer = mutableListOf<String>()

  /** Ritorna il payload di un evento appena completo, altrimenti null. */
  fun feed(rawLine: String): String? {
    val line = rawLine.removeSuffix("\r")
    if (line.isEmpty()) return flush()
    if (line.startsWith(":")) return null
    val colon = line.indexOf(':')
    val field = if (colon >= 0) line.substring(0, colon) else line
    if (field != "data") return null
    var value = if (colon >= 0) line.substring(colon + 1) else ""
    if (value.startsWith(" ")) value = value.substring(1)
    buffer += value
    return null
  }

  fun flush(): String? {
    if (buffer.isEmpty()) return null
    val payload = buffer.joinToString("\n")
    buffer.clear()
    return payload
  }
}
