package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.GeoPoint
import dev.pampa.fluidweather.core.weather.radar.*
import java.io.*

/** Strict positional versioned wire format: missing fields, unknown tags and trailing bytes fail closed. */
internal object RadarAssessmentCodec {
  const val MAX_BYTES = 32_768
  data class Entry(val point: GeoPoint, val savedAt: Long, val sample: RadarSample)

  fun encode(entry: Entry): ByteArray = ByteArrayOutputStream().also { bytes ->
    DataOutputStream(bytes).use { o ->
      o.writeInt(0x52414348); o.writeInt(1)
      o.writeDouble(entry.point.latitude); o.writeDouble(entry.point.longitude); o.writeLong(entry.savedAt)
      when (val s = entry.sample) {
        is RadarSample.Unavailable -> { o.writeByte(0); o.writeUTF(s.reason); o.writeUTF(s.geographicCoverage.name) }
        is RadarSample.Ok -> with(s.reading) {
          o.writeByte(1)
          o.writeLong(frameTimeMillis); o.writeInt(frameAgeMin); o.writeInt(boxKm); o.writeInt(atPointDbz)
          o.optional(nearest) { writeDouble(it.km); writeInt(it.bearingDeg); writeInt(it.maxDbz) }
          o.writeDouble(searchedKm); o.writeInt(areaRainingPct); o.writeInt(maxDbz)
          o.writeUTF(trend.name); o.writeDouble(trendDbzPer10Min)
          o.optional(motion) { m ->
            writeDouble(m.speedKmh); writeDouble(m.fromDeg); writeDouble(m.towardDeg); writeInt(m.pairsUsed)
            writeDouble(m.meanPsr); writeBoolean(m.agreement); writeBoolean(m.atSearchEdge)
            writeDouble(m.dxPxPer10Min); writeDouble(m.dyPxPer10Min)
          }
          o.optional(eta) { writeNullableInt(it.arrivesInMin); writeNullableInt(it.expectedDbz); writeNullableInt(it.endsInMin); writeInt(it.horizonMin) }
          o.writeInt(nowcastAtPoint.size); nowcastAtPoint.forEach { o.writeInt(it.first); o.writeInt(it.second) }
          o.writeDouble(coverage); o.writeDouble(confidence)
          o.writeInt(notes.size); notes.forEach(o::writeUTF)
          o.writeUTF(geographicCoverage.name)
          listOf(sampledAtMillis, manifestDownloadedAtMillis, tilesDownloadedAtMillis, coverageDownloadedAtMillis).forEach { n -> o.optional(n) { writeLong(it) } }
          o.optional(spatialResolutionMeters) { writeDouble(it) }; o.writeLong(temporalResolutionMillis); o.writeBoolean(complete)
        }
      }
    }
  }.toByteArray().also { require(it.size <= MAX_BYTES) }

  fun decode(bytes: ByteArray): Entry? = try {
    require(bytes.size <= MAX_BYTES)
    DataInputStream(ByteArrayInputStream(bytes)).use { i ->
      require(i.readInt() == 0x52414348 && i.readInt() == 1)
      val point = GeoPoint(i.number(), i.number())
      require(point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0)
      val saved = i.readLong()
      val sample = when (i.readUnsignedByte()) {
        0 -> RadarSample.Unavailable(i.text(), GeographicCoverage.valueOf(i.text()))
        1 -> RadarSample.Ok(RadarReading(
          i.readLong(), i.readInt(), i.readInt(), i.readInt(),
          i.optional { NearestEcho(number(), readInt(), readInt()) },
          i.number(), i.readInt(), i.readInt(), RadarTrend.valueOf(i.text()), i.number(),
          i.optional { Motion(number(), number(), number(), readInt(), number(), flag(), flag(), number(), number()) },
          i.optional { Eta(nullableInt(), nullableInt(), nullableInt(), readInt()) },
          List(i.count()) { i.readInt() to i.readInt() }, i.number(), i.number(),
          List(i.count()) { i.text() }, GeographicCoverage.valueOf(i.text()),
          i.optional { readLong() }, i.optional { readLong() }, i.optional { readLong() }, i.optional { readLong() },
          i.optional { number() }, i.readLong(), i.flag(),
        ))
        else -> error("Unknown sample")
      }
      (sample as? RadarSample.Ok)?.reading?.let { r ->
        require(r.frameTimeMillis >= 0 && r.frameAgeMin >= -1 && r.boxKm > 0)
        require(r.atPointDbz in -1..100 && r.maxDbz in -1..100 && r.areaRainingPct in 0..100)
        require(r.searchedKm >= 0 && r.coverage in 0.0..1.0 && r.confidence in 0.0..1.0)
        require(r.temporalResolutionMillis > 0 && (r.spatialResolutionMeters == null || r.spatialResolutionMeters > 0))
      }
      require(i.available() == 0)
      Entry(point, saved, sample)
    }
  } catch (_: Exception) { null }

  private fun DataInputStream.number() = readDouble().also { require(it.isFinite()) }
  private fun DataInputStream.text() = readUTF().also { require(it.length <= 2048) }
  private fun DataInputStream.count() = readInt().also { require(it in 0..64) }
  private fun DataInputStream.flag() = when (readUnsignedByte()) { 0 -> false; 1 -> true; else -> error("Invalid flag") }
  private fun <T> DataInputStream.optional(readField: DataInputStream.() -> T): T? = if (flag()) readField(this) else null
  private fun DataInputStream.nullableInt() = optional { readInt() }
  private fun <T> DataOutputStream.optional(value: T?, writeField: DataOutputStream.(T) -> Unit) { writeBoolean(value != null); if (value != null) writeField(this, value) }
  private fun DataOutputStream.writeNullableInt(value: Int?) = optional(value) { writeInt(it) }
}
