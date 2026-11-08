package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.data.DataWipeGuard
import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.weather.radar.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class RadarAssessmentCacheTest {
  @get:Rule val temporary = TemporaryFolder()
  private val f = AssessmentFixture()

  @Test fun `disk recreation retains every original metadata field and full precision point`() = runTest {
    val dir = temporary.newFolder()
    val guard = DataWipeGuard()
    val point = GeoPoint(44.000000000001, 11.000000000001)
    val original = (f.radar(30, Eta(null, null, 10, 60)) as RadarSample.Ok).let { it.copy(reading = it.reading.copy(
      nearest = NearestEcho(0.125, 75, 40), sampledAtMillis = f.noon + 20,
      manifestDownloadedAtMillis = f.noon - 30, tilesDownloadedAtMillis = f.noon - 40,
      coverageDownloadedAtMillis = f.noon - 50, spatialResolutionMeters = 880.123456789,
      notes = listOf("original coverage"))) }
    val cache = RadarAssessmentCache(dir, guard) { f.noon }
    assertTrue(cache.put(point, original, cache.epoch()))
    val reopened = RadarAssessmentCache(dir, guard) { f.noon + 1000 }
    assertEquals(original, reopened.read(point))
    assertNull(reopened.read(f.point))
    assertEquals(1, dir.listFiles()!!.size)
  }

  @Test fun `codec refuses truncation unknown versions enums flags oversized and trailing data`() {
    val entry = RadarAssessmentCodec.Entry(f.point, f.noon, f.radar(0))
    val bytes = RadarAssessmentCodec.encode(entry)
    for (length in bytes.indices) assertNull("prefix $length", RadarAssessmentCodec.decode(bytes.copyOf(length)))
    assertNull(RadarAssessmentCodec.decode(bytes.copyOf().also { it[7] = 99 }))
    assertNull(RadarAssessmentCodec.decode(bytes + byteArrayOf(0)))
    assertNull(RadarAssessmentCodec.decode(ByteArray(RadarAssessmentCodec.MAX_BYTES + 1)))
    val unknown = RadarAssessmentCodec.encode(RadarAssessmentCodec.Entry(f.point, f.noon,
      RadarSample.Unavailable("offline", GeographicCoverage.UNKNOWN)))
    val text = unknown.toString(Charsets.ISO_8859_1).replace("UNKNOWN", "BOGUSXX")
    assertNull(RadarAssessmentCodec.decode(text.toByteArray(Charsets.ISO_8859_1)))
    assertNull(RadarAssessmentCodec.decode(bytes.copyOf().also { it[it.lastIndex] = 2 }))
  }

  @Test fun `unknown metadata remains unknown and latest unavailable replaces dry across recreation`() = runTest {
    val dir = temporary.newFolder(); val guard = DataWipeGuard()
    val cache = RadarAssessmentCache(dir, guard) { f.noon }
    val unknown = (f.radar(0) as RadarSample.Ok).let { it.copy(reading = it.reading.copy(
      geographicCoverage = GeographicCoverage.UNKNOWN, complete = false, tilesDownloadedAtMillis = null)) }
    cache.put(f.point, unknown, cache.epoch())
    assertEquals(unknown, cache.read(f.point))
    assertEquals(PrecipitationState.UNAVAILABLE,
      LocalPrecipitationEvaluator().evaluate(f.place, f.point, null, cache.read(f.point), f.noon).precipitation.state)
    cache.put(f.point, f.radar(0), cache.epoch())
    val unavailable = RadarSample.Unavailable("latest unavailable", GeographicCoverage.OUTSIDE)
    cache.put(f.point, unavailable, cache.epoch())
    assertEquals(unavailable, RadarAssessmentCache(dir, guard) { f.noon }.read(f.point))
    dir.listFiles()!!.single().writeBytes(byteArrayOf(1, 2, 3))
    assertNull(cache.read(f.point))
  }

  @Test fun `clear and shared wipe reject captured read and write epochs and allow fresh work`() = runTest {
    val dir = temporary.newFolder(); val guard = DataWipeGuard()
    val cache = RadarAssessmentCache(dir, guard) { f.noon }
    val beforeClear = cache.epoch()
    cache.put(f.point, f.radar(0), beforeClear)
    cache.clear()
    assertFalse(cache.put(f.point, f.radar(30), beforeClear))
    assertNull(cache.read(f.point, beforeClear))
    val beforeWipe = cache.epoch()
    cache.put(f.point, f.radar(0), beforeWipe)
    // clear is safe within wipe: this would deadlock if clear reentered the guard.
    guard.wipe { cache.clear() }
    assertFalse(cache.put(f.point, f.radar(30), beforeWipe))
    assertNull(cache.read(f.point, beforeWipe))
    assertTrue(cache.put(f.point, f.radar(30), cache.epoch()))
    assertEquals(f.radar(30), cache.read(f.point))
    assertTrue(cache.updates.value >= 4)
  }

  @Test fun `read queued behind guard cannot publish across local clear`() = runTest {
    val guard = DataWipeGuard(); val cache = RadarAssessmentCache(temporary.newFolder(), guard) { f.noon }
    cache.put(f.point, f.radar(0), cache.epoch())
    val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
    val holder = launch { guard.writeIfUnchanged(guard.generation()) { entered.complete(Unit); release.await() } }
    entered.await()
    val read = async { cache.read(f.point) }; runCurrent()
    cache.clear()
    release.complete(Unit); holder.join()
    assertNull(read.await())
    assertNull(cache.read(f.point))
  }

  @Test fun `disk input count is bounded and clock rollback cannot renew an input`() = runTest {
    var now = f.noon; val dir = temporary.newFolder()
    val cache = RadarAssessmentCache(dir, DataWipeGuard()) { now }
    for (n in 1..20) cache.put(GeoPoint(40.0 + n / 100.0, 11.0), f.radar(0), cache.epoch())
    assertEquals(16, dir.listFiles()!!.size)
    val rollback = RadarAssessmentCache(temporary.newFolder(), DataWipeGuard()) { now }
    rollback.put(f.point, f.radar(0), rollback.epoch())
    assertNotNull(rollback.read(f.point))
    now--
    assertNull(rollback.read(f.point))
  }
}
