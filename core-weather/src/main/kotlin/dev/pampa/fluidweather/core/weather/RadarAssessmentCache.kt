package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.data.DataWipeGuard
import dev.pampa.fluidweather.core.model.GeoPoint
import dev.pampa.fluidweather.core.weather.radar.RadarSample
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Small original radar inputs only. Use a dedicated directory, never the raster directory. */
class RadarAssessmentCache(
  private val directory: File,
  internal val wipeGuard: DataWipeGuard,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  data class Epoch internal constructor(val clear: Long, val wipe: Long)
  private val mutex = Mutex()
  private val generation = AtomicLong()
  private var diskEpoch = wipeGuard.generation()
  private val mutableUpdates = MutableStateFlow(0L)
  val updates: StateFlow<Long> = mutableUpdates.asStateFlow()

  fun epoch(): Epoch = Epoch(generation.get(), wipeGuard.generation())
  private fun valid(e: Epoch) = e.clear == generation.get() && e.wipe == wipeGuard.generation()

  /** Captured before loading; no network may be placed inside this class or its guard. */
  suspend fun put(point: GeoPoint, sample: RadarSample, epoch: Epoch): Boolean =
    wipeGuard.writeIfUnchanged(epoch.wipe) {
      mutex.withLock {
        if (!valid(epoch)) return@withLock false
        withContext(Dispatchers.IO) {
          resetAfterWipe()
          directory.mkdirs()
          val target = file(point)
          val temp = File(directory, target.name + ".tmp")
          try {
            val entry = RadarAssessmentCodec.Entry(point, clock(), sample)
            val bytes = RadarAssessmentCodec.encode(entry)
            require(RadarAssessmentCodec.decode(bytes) != null)
            temp.writeBytes(bytes)
            currentCoroutineContext().ensureActive()
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            directory.listFiles { f -> f.extension == "radarin" }?.sortedByDescending { it.lastModified() }
              ?.drop(16)?.forEach { if (!it.delete() && it.exists()) throw IOException("Cannot bound radar inputs") }
            mutableUpdates.value++
            true
          } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) {
            // A failed latest sample must not expose an earlier dry input.
            target.delete()
            mutableUpdates.value++
            false
          } finally { temp.delete() }
        }
      }
    } ?: false

  suspend fun read(point: GeoPoint, epoch: Epoch = epoch()): RadarSample? {
    val result = wipeGuard.writeIfUnchanged(epoch.wipe) {
      mutex.withLock {
        if (!valid(epoch)) return@withLock null
        withContext(Dispatchers.IO) {
          resetAfterWipe()
          val target = file(point)
          try {
            if (!target.isFile || target.length() > RadarAssessmentCodec.MAX_BYTES) return@withContext null
            val bytes = target.inputStream().use { it.readBytesBounded() }
            val entry = RadarAssessmentCodec.decode(bytes) ?: return@withContext null
            currentCoroutineContext().ensureActive()
            if (entry.point != point || clock() < entry.savedAt) null else entry.sample
          } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { null }
        }
      }
    }
    currentCoroutineContext().ensureActive()
    return if (valid(epoch)) result else null
  }

  /** Safe to invoke from DataWipeGuard.wipe: deliberately does not acquire that guard. */
  suspend fun clear(): Unit = mutex.withLock {
    generation.incrementAndGet()
    withContext(Dispatchers.IO) { deleteInputs(); diskEpoch = wipeGuard.generation() }
    mutableUpdates.value++
    Unit
  }

  private fun resetAfterWipe() {
    if (diskEpoch != wipeGuard.generation()) { deleteInputs(); diskEpoch = wipeGuard.generation() }
  }
  private fun deleteInputs() {
    directory.listFiles { f -> f.extension == "radarin" || f.name.endsWith(".radarin.tmp") }?.forEach {
      if (!it.delete() && it.exists()) throw IOException("Cannot clear radar inputs")
    }
  }
  private fun file(point: GeoPoint) = File(directory, "${point.latitude.toRawBits().toULong().toString(16)}_${point.longitude.toRawBits().toULong().toString(16)}.radarin")
  private fun java.io.InputStream.readBytesBounded(): ByteArray {
    val bytes = readNBytesCompat(RadarAssessmentCodec.MAX_BYTES + 1)
    require(bytes.size <= RadarAssessmentCodec.MAX_BYTES)
    return bytes
  }
  private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (output.size() < limit) {
      val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
      if (count < 0) break
      output.write(buffer, 0, count)
    }
    return output.toByteArray()
  }
}
