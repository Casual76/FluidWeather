package dev.pampa.fluidweather.core.ai.radar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.antigravity.fluidengine.net.EngineHttp
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Da PNG a raster ARGB; separato per poterlo sostituire nei test (android.jar non decodifica). */
fun interface TileDecoder {
  fun decode(png: ByteArray): Raster?
}

/** `BitmapFactory` senza scalature ne' premoltiplicazione: i colori devono restare quelli della palette. */
object BitmapTileDecoder : TileDecoder {
  override fun decode(png: ByteArray): Raster? {
    val options = BitmapFactory.Options().apply {
      inPreferredConfig = Bitmap.Config.ARGB_8888
      inScaled = false
      inPremultiplied = false
    }
    val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size, options) ?: return null
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val raster = Raster(bitmap.width, bitmap.height, pixels)
    bitmap.recycle()
    return raster
  }
}

/**
 * Le tile del radar su disco, per URL: sono immutabili (il path porta l'ora del fotogramma),
 * quindi non scadono, si potano per eta' e per numero. Un 404 lascia un segnaposto vuoto cosi'
 * la stessa tile non si richiede a ogni domanda.
 */
class RadarTileStore(
  private val directory: File,
  private val http: EngineHttp,
  private val decoder: TileDecoder = BitmapTileDecoder,
  private val clock: () -> Long = System::currentTimeMillis,
) : TileSource {

  override suspend fun raster(url: String): TileResult = withContext(Dispatchers.IO) {
    directory.mkdirs()
    val name = sha256(url)
    val file = File(directory, "$name.png")
    val empty = File(directory, "$name.404")
    if (empty.exists()) return@withContext TileResult.Empty
    if (!file.exists() || file.length() == 0L) {
      val download = runCatching { http.download(url, file) }
      if (download.isFailure) {
        val message = download.exceptionOrNull()?.message.orEmpty()
        if ("404" in message) {
          empty.writeText("")
          return@withContext TileResult.Empty
        }
        return@withContext TileResult.Missing
      }
    }
    val raster = runCatching { decoder.decode(file.readBytes()) }.getOrNull()
    if (raster == null) {
      file.delete()
      return@withContext TileResult.Missing
    }
    TileResult.Ok(raster)
  }

  /** Via le tile piu' vecchie di [maxAgeMillis] e, se sono ancora troppe, le meno recenti. */
  fun prune(maxAgeMillis: Long = 3 * 3_600_000L, maxFiles: Int = 300) {
    val files = directory.listFiles()?.toList() ?: return
    val now = clock()
    files.filter { now - it.lastModified() > maxAgeMillis }.forEach { it.delete() }
    val remaining = directory.listFiles()?.sortedByDescending { it.lastModified() } ?: return
    remaining.drop(maxFiles).forEach { it.delete() }
  }

  private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(32)
}
