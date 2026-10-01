package dev.pampa.fluidweather.core.weather

import java.io.File
import java.security.MessageDigest

/**
 * Un corpo letto dalla cache, con l'istante in cui e' stato scaricato davvero: il timbro del file.
 *
 * Serve a non far sembrare fresco un dato vecchio. Prima un colpo di cache restituiva solo il testo,
 * e il client ci metteva sopra "adesso": un contesto di venti minuti fa passava per appena scaricato,
 * e un'eta' sbagliata sposta il livello del contesto (fresco o vecchio) e la finalita' della verita'.
 */
data class CachedText(val text: String, val writtenAtMillis: Long)

/**
 * Cache per-URL su file, con TTL deciso dal chiamante: 30 minuti per una previsione, giorni per
 * un lookup di griglia che non cambia mai. Vive nella cacheDir, che il sistema puo' svuotare
 * quando vuole — ed e' giusto cosi': una cache che non si puo' perdere non e' una cache.
 */
class UrlCache(
  private val directory: File,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  fun read(url: String, maxAgeMillis: Long): String? = readTimed(url, maxAgeMillis)?.text

  /**
   * Il corpo e l'istante in cui e' stato scritto, se il file e' entro [maxAgeMillis].
   *
   * L'istante e' la data di modifica del file, che [write] mette uguale all'ora del download. Alcuni
   * filesystem la tengono al secondo: il timbro puo' risultare fino a un secondo *prima* del vero,
   * cioe' il dato sembra appena piu' vecchio — l'errore dalla parte giusta.
   */
  fun readTimed(url: String, maxAgeMillis: Long): CachedText? {
    val file = fileFor(url)
    if (!file.exists()) return null
    val writtenAt = file.lastModified()
    if (clock() - writtenAt > maxAgeMillis) return null
    val text = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
    return CachedText(text, writtenAt)
  }

  /**
   * Scrive il corpo e restituisce il timbro con cui l'ha scritto. Il timbro si prende prima di
   * provare a scrivere: il download e' avvenuto adesso anche se il disco e' pieno, e chi ha appena
   * scaricato deve poterlo dire.
   */
  fun write(url: String, body: String): Long {
    val stamp = clock()
    runCatching {
      directory.mkdirs()
      val file = fileFor(url)
      file.writeText(body)
      file.setLastModified(stamp)
    }
    return stamp
  }

  private fun fileFor(url: String): File {
    val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
    val name = digest.joinToString("") { "%02x".format(it) }.take(24)
    return File(directory, "$name.json")
  }
}
