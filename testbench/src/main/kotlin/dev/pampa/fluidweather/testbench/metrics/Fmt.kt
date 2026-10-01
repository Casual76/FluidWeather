package dev.pampa.fluidweather.testbench.metrics

import dev.pampa.fluidweather.nowcast.scoring.BootstrapSummary
import java.time.Instant
import java.util.Locale

/** I formati dei numeri nei rapporti del banco: punto decimale, trattino per cio' che non c'e'. */
object Fmt {

  fun f3(value: Double): String = if (value.isNaN()) "-" else String.format(Locale.ROOT, "%.3f", value)

  fun f4(value: Double): String = if (value.isNaN()) "-" else String.format(Locale.ROOT, "%.4f", value)

  fun sgn3(value: Double): String = if (value.isNaN()) "-" else String.format(Locale.ROOT, "%+.3f", value)

  fun sgn4(value: Double): String = if (value.isNaN()) "-" else String.format(Locale.ROOT, "%+.4f", value)

  /** L'intervallo di un bootstrap, `[basso,alto]`. */
  fun interval(summary: BootstrapSummary?): String =
    if (summary == null) "-" else "[${sgn4(summary.low)},${sgn4(summary.high)}]"

  /** Il giorno UTC di un istante, `yyyy-MM-dd`. */
  fun date(millis: Long): String = Instant.ofEpochMilli(millis).toString().substring(0, 10)

  /**
   * L'esito di una differenza di Brier (a - b; negativo = a meglio): VINCE se l'intervallo sta tutto
   * sotto zero, vince~ se solo la media, e specularmente perde~ / PERDE.
   */
  fun verdict(delta: BootstrapSummary?): String = when {
    delta == null -> "-"
    delta.high < 0 -> "VINCE"
    delta.mean < 0 -> "vince~"
    delta.low > 0 -> "PERDE"
    else -> "perde~"
  }
}
