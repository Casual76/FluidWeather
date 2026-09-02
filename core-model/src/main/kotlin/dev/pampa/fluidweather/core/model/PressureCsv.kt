package dev.pampa.fluidweather.core.model

import java.time.Instant
import java.util.Locale

/**
 * L'archivio barometrico come CSV piatto: e' il formato che il banco di prova rigioca
 * ("registrazioni del telefono esportate", piano §5) e che qualunque foglio di calcolo apre.
 * Colonne fisse, una riga per lettura, virgola come separatore, punto decimale, UTC.
 */
object PressureCsv {

  const val HEADER: String =
    "timestamp_millis,iso_utc,pressure_hpa,source,burst_id,altitude_m,latitude,longitude,activity,activity_confidence"

  fun line(sample: PressureSample): String = listOf(
    sample.timestampMillis.toString(),
    Instant.ofEpochMilli(sample.timestampMillis).toString(),
    String.format(Locale.ROOT, "%.3f", sample.pressureHpa),
    sample.source.name,
    sample.burstId ?: "",
    sample.altitudeMeters?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "",
    sample.latitude?.let { String.format(Locale.ROOT, "%.5f", it) } ?: "",
    sample.longitude?.let { String.format(Locale.ROOT, "%.5f", it) } ?: "",
    sample.activity.name,
    sample.activityConfidence?.toString() ?: "",
  ).joinToString(",")

  fun render(samples: List<PressureSample>): String = buildString {
    append(HEADER).append('\n')
    samples.sortedBy { it.timestampMillis }.forEach { append(line(it)).append('\n') }
  }
}
