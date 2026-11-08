package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.data.DataWipeGuard
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.weather.radar.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.EmptyCoroutineContext

/** Application-owned input cache. Every consumption runs the pure temporal policy again. */
class WeatherBriefService(
  private val scope: CoroutineScope,
  private val radarLoader: suspend (Double, Double) -> RadarSample,
  private val clock: () -> Long = System::currentTimeMillis,
  private val evaluator: LocalPrecipitationEvaluator = LocalPrecipitationEvaluator(),
  private val loadTimeoutMillis: Long = 2_500L,
  private val inputTtlMillis: Long = 60_000L,
  private val maxEntries: Int = 16,
  private val radarCache: RadarAssessmentCache? = null,
  wipeGuard: DataWipeGuard? = null,
) : CachedWeatherBriefSource {
  private val guard = wipeGuard ?: radarCache?.wipeGuard
  init {
    require(loadTimeoutMillis > 0 && inputTtlMillis > 0 && maxEntries > 0)
    require(wipeGuard == null || radarCache == null || wipeGuard === radarCache.wipeGuard)
  }
  private data class Cached(val point: GeoPoint, val sample: RadarSample, val loadedAt: Long)
  private data class Pending(val point: GeoPoint, val task: Deferred<RadarSample>)
  private data class Epoch(val clear: Long, val wipe: Long?, val disk: RadarAssessmentCache.Epoch?, val origin: WeatherWipeEpoch?)
  private val mutex = Mutex()
  private val publicationMutex = Mutex()
  private var loadSerial = 0L
  private val latestLoad = mutableMapOf<GeoPoint, Long>()
  private val cache = linkedMapOf<String, Cached>()
  private val pending = mutableMapOf<String, Pending>()
  private val localUpdates = MutableStateFlow(0L)
  override val cachedInputUpdates: StateFlow<Long> = radarCache?.updates ?: localUpdates.asStateFlow()
  @Volatile private var generation = 0L
  private var memoryWipe = guard?.generation()
  private var memoryGuard = guard
  private suspend fun epoch(): Epoch {
    val origin = weatherWipeEpoch(guard)
    origin?.checkCurrent()
    return Epoch(generation, origin?.observed, radarCache?.epoch(), origin).also { check(it) }
  }
  private fun check(e: Epoch) {
    if (e.clear != generation) throw CancellationException("Assessment invalidated")
    e.origin?.checkCurrent()
  }
  // Global lock order is guard -> service mutex. clear takes only service -> disk mutex,
  // allowing it to be called inside wipe without reentering the shared guard.
  private suspend fun <T : Any> publish(e: Epoch, block: suspend () -> T): T {
    suspend fun locked(): T = mutex.withLock {
      check(e)
      if (memoryWipe != e.wipe || memoryGuard !== e.origin?.guard) {
        pending.values.forEach { it.task.cancel() }; pending.clear(); cache.clear(); memoryWipe = e.wipe
        memoryGuard = e.origin?.guard
      }
      currentCoroutineContext().ensureActive()
      block()
    }
    return if (e.origin == null) locked() else e.origin.write { locked() }
  }

  suspend fun brief(place: Place, coordinates: GeoPoint?, snapshot: WeatherSnapshot?): WeatherBrief {
    val e = epoch()
    val sample = coordinates?.let { load(it, LocalPrecipitationEvaluator.revision(place, it, snapshot), e) }
    return publish(e) { evaluator.evaluate(place, coordinates, snapshot, sample, clock()) }
  }

  /** Existing nowcast callback can reuse this path and the pure RadarObservations.of(sample). */
  suspend fun radarSample(latitude: Double, longitude: Double): RadarSample {
    val point = GeoPoint(latitude, longitude)
    val e = epoch()
    val result = load(point, "radar:${latitude.toRawBits()}:${longitude.toRawBits()}", e, sharePoint = true)
    return publish(e) { result }
  }

  override suspend fun cachedBrief(place: Place, coordinates: GeoPoint?, snapshot: WeatherSnapshot?): WeatherBrief {
    val e = epoch()
    val disk = coordinates?.let { radarCache?.read(it, e.disk!!) }
    return publish(e) {
      val memory = cache.values.lastOrNull { it.point == coordinates }
        ?.takeIf { clock() - it.loadedAt in 0..inputTtlMillis }?.sample
      evaluator.evaluate(place, coordinates, snapshot, if (radarCache != null) disk else memory, clock())
    }
  }

  private suspend fun load(point: GeoPoint, key: String, e: Epoch, sharePoint: Boolean = false): RadarSample {
    currentCoroutineContext().ensureActive()
    var immediate: RadarSample? = null
    var task: Deferred<RadarSample>? = null
    publish(e) {
      val now = clock()
      cache.entries.removeAll { now - it.value.loadedAt !in 0..inputTtlMillis }
      immediate = (cache[key] ?: cache.entries.lastOrNull { it.value.point == point && (sharePoint || it.key.startsWith("radar:")) }?.value)?.sample
      if (immediate == null) {
        task = pending[key]?.task ?: pending.entries.firstOrNull { it.value.point == point && (sharePoint || it.key.startsWith("radar:")) }?.value?.task
        if (task == null && pending.size < maxEntries) {
          latestLoad.keys.retainAll(pending.values.map { it.point }.toSet() + cache.values.map { it.point }.toSet())
          val serial = ++loadSerial
          latestLoad[point] = serial
          val created = scope.async(start = CoroutineStart.LAZY) {
            try {
              // The application-owned job must retain the caller's originating refresh epoch.
              // A new coroutine must not renew stale work after a wipe in a nested HTTP/tile load.
              val result = withContext(e.origin ?: EmptyCoroutineContext) {
                check(e)
                try {
                  withTimeoutOrNull(loadTimeoutMillis) { radarLoader(point.latitude, point.longitude) }
                    ?: RadarSample.Unavailable("assessment-timeout")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { RadarSample.Unavailable("assessment-load-failed") }
              }
              currentCoroutineContext().ensureActive()
              check(e)
              publicationMutex.withLock {
                // A newer request supersedes older in-flight work for persistence only;
                // its original caller still receives its own correctly matched assessment.
                val latest = publish(e) { latestLoad[point] == serial }
                if (latest) {
                  radarCache?.put(point, result, e.disk!!)
                  publish(e) {
                    if (latestLoad[point] == serial) {
                      cache.entries.removeAll { it.value.point == point }
                      if (result is RadarSample.Ok) {
                        cache[key] = Cached(point, result, clock())
                        while (cache.size > maxEntries) cache.remove(cache.keys.first())
                      }
                      if (radarCache == null) localUpdates.value++
                    }
                  }
                }
              }
              result
            } finally {
              withContext(NonCancellable) { mutex.withLock { if (generation == e.clear && memoryWipe == e.wipe) pending.remove(key) } }
            }
          }
          pending[key] = Pending(point, created)
          task = created
        }
      }
    }
    task?.start()
    return immediate ?: if (task != null) {
      withTimeoutOrNull(loadTimeoutMillis) { task!!.await() } ?: RadarSample.Unavailable("assessment-timeout")
    } else RadarSample.Unavailable("assessment-capacity")
  }

  /** No guard acquisition here: safe inside the parent's shared wipe callback. */
  suspend fun clear(): Unit = mutex.withLock {
    generation++
    pending.values.forEach { it.task.cancel(CancellationException("Assessment invalidated")) }
    pending.clear(); cache.clear(); latestLoad.clear()
    radarCache?.clear()
    if (radarCache == null) localUpdates.value++
  }
}
