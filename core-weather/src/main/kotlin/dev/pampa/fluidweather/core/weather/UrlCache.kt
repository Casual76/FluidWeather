package dev.pampa.fluidweather.core.weather

import java.io.File
import java.security.MessageDigest

/**
 * Cache per-URL su file, con TTL deciso dal chiamante: 30 minuti per una previsione, giorni per
 * un lookup di griglia che non cambia mai. Vive nella cacheDir, che il sistema puo' svuotare
 * quando vuole — ed e' giusto cosi': una cache che non si puo' perdere non e' una cache.
 */
class UrlCache(
  private val directory: File,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  fun read(url: String, maxAgeMillis: Long): String? {
    val file = fileFor(url)
    if (!file.exists()) return null
    if (clock() - file.lastModified() > maxAgeMillis) return null
    return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
  }

  fun write(url: String, body: String) {
    runCatching {
      directory.mkdirs()
      val file = fileFor(url)
      file.writeText(body)
      file.setLastModified(clock())
    }
  }

  private fun fileFor(url: String): File {
    val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
    val name = digest.joinToString("") { "%02x".format(it) }.take(24)
    return File(directory, "$name.json")
  }
}
