package dev.pampa.fluidweather.feature.benchmark

import java.util.Locale

/**
 * Un numero con la virgola o il punto della lingua del telefono: 0,053 in italiano, 0.053 in
 * inglese. La classifica pioggia e' fatta di numeri piccoli (Brier 0,053 ± 0,012) e scritti col
 * punto su un telefono italiano sembrerebbero di un altro mondo; a differenza del CSV, che e' per
 * i fogli di calcolo e usa sempre il punto, questa e' una pagina da leggere.
 *
 * Sta in un file suo, senza Compose, perche' si verifichi sulla JVM.
 */
internal fun fmtDecimals(value: Double, decimals: Int, locale: Locale = Locale.getDefault()): String =
  String.format(locale, "%.${decimals}f", value)
