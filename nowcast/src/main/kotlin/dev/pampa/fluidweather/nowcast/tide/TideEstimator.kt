package dev.pampa.fluidweather.nowcast.tide

import dev.pampa.fluidweather.nowcast.cleaning.CleanPoint

/**
 * La cascata dei modelli di marea, nello stesso spirito della fusione provider: prima il fit
 * locale se l'archivio lo regge, poi il prior climatologico se almeno si sa dove si e', e in
 * fondo lo zero dichiarato.
 *
 * Il fit chiede giorni di dati *distribuiti*: cinque giorni di copertura e almeno 16 delle 24
 * ore solari popolate. Un archivio fatto solo di letture d'ufficio (9-18) non puo' vincolare
 * una sinusoide su 24 ore — l'estensore se ne accorge dai bin vuoti e resta sul prior.
 */
class TideEstimator(
  private val minFitSpanDays: Double = 5.0,
  private val minPopulatedHourBins: Int = 16,
  /** Un'ampiezza fittata sopra questa soglia non e' marea, e' un fit andato male. */
  private val maxCredibleAmplitudeHpa: Double = 3.0,
) {

  private val fitter = HarmonicFitter()

  fun estimate(points: List<CleanPoint>): TidalModel {
    val latitude = median(points.mapNotNull { it.latitude }) ?: return ZeroTide
    val longitude = median(points.mapNotNull { it.longitude }) ?: return ZeroTide
    val reference = points.maxOf { it.timestampMillis }

    tryFit(points, longitude)?.let { return it }
    return ClimatologicalTide(latitude, longitude, reference)
  }

  private fun tryFit(points: List<CleanPoint>, longitude: Double): FittedTide? {
    if (points.isEmpty()) return null
    val spanDays = (points.maxOf { it.timestampMillis } - points.minOf { it.timestampMillis }) /
      86_400_000.0
    if (spanDays < minFitSpanDays) return null

    val populatedBins = points
      .map { SolarTime.solarHours(it.timestampMillis, longitude).toInt() }
      .toSet()
      .size
    if (populatedBins < minPopulatedHourBins) return null

    val fit = fitter.fit(
      points.map { HarmonicFitter.Sample(it.timestampMillis, it.seaLevelPressureHpa) },
      longitude,
    ) ?: return null

    val model = FittedTide(longitude, fit.a1, fit.b1, fit.a2, fit.b2)
    val credible = model.s1AmplitudeHpa <= maxCredibleAmplitudeHpa &&
      model.s2AmplitudeHpa <= maxCredibleAmplitudeHpa
    return if (credible) model else null
  }

  private fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
      sorted[middle]
    } else {
      (sorted[middle - 1] + sorted[middle]) / 2.0
    }
  }
}
