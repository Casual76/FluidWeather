package dev.pampa.fluidweather.core.weather.radar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.antigravity.fluidengine.net.EngineHttp
import dev.pampa.fluidweather.core.data.DataWipeGuard
import dev.pampa.fluidweather.core.weather.WeatherWipeEpoch
import dev.pampa.fluidweather.core.weather.withWeatherWipeEpoch
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
  private val wipeGuard: DataWipeGuard? = null,
) : TileSource {
  constructor(directory: File, http: EngineHttp, decoder: TileDecoder, clock: () -> Long) :
    this(directory, http, decoder, clock, null)

  private data class Epoch(val clear: Long, val origin: WeatherWipeEpoch?)
  private sealed interface Cached {
    data object Missing : Cached
    data object Empty : Cached
    data class Bytes(val png: ByteArray, val downloadedAt: Long?) : Cached
  }
  private val generation = AtomicLong()
  private val mutex = Mutex()
  private val filesLock = Any() // also coordinates the existing non-suspending, deletion-only prune.
  private val transfers = mutableSetOf<File>()
  private var disabled = false

  private fun check(e: Epoch) {
    if (generation.get() != e.clear) throw CancellationException("Radar cache invalidated")
    e.origin?.checkCurrent()
    if (disabled) throw IOException("Radar cache deletion did not complete")
  }

  /** Short local IO only, always guard -> cache lock. No decoder or network under either lock. */
  private suspend fun <T : Any> transaction(e: Epoch, block: () -> T): T {
    val context = currentCoroutineContext()
    suspend fun locked(): T = mutex.withLock {
      synchronized(filesLock) { context.ensureActive(); check(e); block() }
    }
    return if (e.origin == null) locked() else e.origin.write { locked() }
  }

  override suspend fun raster(url: String): TileResult = withWeatherWipeEpoch(wipeGuard) { origin ->
    val epoch = Epoch(generation.get(), origin)
    withContext(Dispatchers.IO) { rasterAt(url, epoch) }
  }

  private suspend fun rasterAt(url: String, epoch: Epoch): TileResult {
    val name = sha256(url)
    val file = File(directory, "$name.png")
    val empty = File(directory, "$name.404")
    val metadata = File(directory, "$name.downloaded")
    val coverage = "/v2/coverage/" in url
    val cached = transaction(epoch) {
      directory.mkdirs()
      if (coverage && file.exists() && clock() - file.lastModified() > 86_400_000L) {
        file.delete(); metadata.delete()
      }
      if (empty.exists() && clock() - empty.lastModified() > 300_000L) empty.delete()
      when {
        empty.exists() -> Cached.Empty
        !file.exists() || file.length() == 0L -> Cached.Missing
        else -> Cached.Bytes(file.readBytes(), metadata.takeIf { it.exists() }?.readText()?.toLongOrNull())
      }
    }
    when (cached) {
      Cached.Empty -> return transaction(epoch) { TileResult.Empty }
      is Cached.Bytes -> {
        val raster = decode(cached.png)
        return transaction(epoch) {
          if (raster != null) TileResult.Ok(raster, cached.downloadedAt) else {
            file.delete(); metadata.delete()
            if (coverage) empty.writeText("")
            TileResult.Missing
          }
        }
      }
      Cached.Missing -> Unit
    }

    // EngineHttp owns <target>.part. Neither target may be a durable URL cache name until
    // successful decode and a final epoch check. The unique transfer lives outside the cache dir.
    val transfer = transaction(epoch) {
      File.createTempFile("fluid-radar-", ".transfer", directory.absoluteFile.parentFile).also { transfers += it }
    }
    try {
      val failure = try { http.download(url, transfer); null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { error }
      currentCoroutineContext().ensureActive()
      if (failure != null) return transaction(epoch) {
        if ("404" in failure.message.orEmpty()) {
          empty.writeText(""); TileResult.Empty
        } else {
          if (coverage) empty.writeText("")
          TileResult.Missing
        }
      }
      val downloadedAt = clock()
      val raster = decode(transfer.readBytes())
      return transaction(epoch) {
        if (raster == null) {
          if (coverage) empty.writeText("")
          TileResult.Missing
        } else {
          Files.move(transfer.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
          metadata.writeText(downloadedAt.toString())
          file.setLastModified(downloadedAt)
          empty.delete()
          TileResult.Ok(raster, downloadedAt)
        }
      }
    } finally {
      withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock { synchronized(filesLock) {
          transfer.delete(); File(transfer.parentFile, transfer.name + ".part").delete(); transfers -= transfer
        } }
      }
    }
  }

  private fun decode(png: ByteArray): Raster? = try { decoder.decode(png) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

  /** Called inside the application wipe guard; deliberately does not acquire it again. */
  suspend fun clear() {
    generation.incrementAndGet()
    withContext(NonCancellable + Dispatchers.IO) {
      mutex.withLock { synchronized(filesLock) {
        disabled = true
        transfers.forEach { transfer ->
          for (part in listOf(transfer, File(transfer.parentFile, transfer.name + ".part"))) {
            if (part.exists() && !part.delete()) throw IOException("Cannot clear radar transfer")
          }
        }
        if (directory.exists() && !directory.deleteRecursively()) throw IOException("Cannot clear radar tiles")
        disabled = false
      } }
    }
    currentCoroutineContext().ensureActive()
  }

  /** Via le tile piu' vecchie di [maxAgeMillis] e, se sono ancora troppe, le meno recenti. */
  fun prune(maxAgeMillis: Long = 3 * 3_600_000L, maxFiles: Int = 300) = synchronized(filesLock) {
    val files = directory.listFiles()?.toList() ?: return@synchronized
    val now = clock()
    files.filter { now - it.lastModified() > maxAgeMillis }.forEach { it.delete() }
    val remaining = directory.listFiles()?.sortedByDescending { it.lastModified() } ?: return@synchronized
    remaining.drop(maxFiles).forEach { it.delete() }
  }

  private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(32)
}
