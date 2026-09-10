package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.nowcast.learning.RainObservation

/**
 * Da cosa il telefono puo' sapere che sta piovendo, adesso, senza chiedere niente in piu'.
 *
 * Open-Meteo serve `minutely_15` sulla stessa chiamata che l'app fa gia': il quarto d'ora appena
 * concluso e' l'unica riga della risposta che parla del presente. La riga oraria, per come e'
 * definita, non puo': copre un'ora intera, e finche' l'ora non e' finita non c'e'.
 *
 * Il radar (RainViewer) e' l'altra fonte, e vive altrove perche' costa tile da scaricare: chi
 * ce l'ha lo unisce a questa con [merge].
 */
object RainObservations {

  /** Il quarto d'ora appena concluso, riportato a millimetri all'ora. */
  fun fromMinutely(bundle: ForecastBundle?, nowMillis: Long): RainObservation? {
    val quarter = bundle?.rainNowMm(nowMillis) ?: return null
    val intensity = quarter * 4.0
    return if (intensity >= RainObservation.RAINING_FROM_MM_PER_HOUR) {
      RainObservation.raining(intensity, SOURCE_MINUTELY)
    } else {
      RainObservation.dry(SOURCE_MINUTELY)
    }
  }

  /**
   * Due osservazioni della stessa cosa: vince chi vede la pioggia.
   *
   * Non e' pigrizia, e' asimmetria vera: il quarto d'ora e' una stima di modello su una cella di
   * qualche chilometro e puo' non vedere un rovescio locale; il radar puo' avere un buco di
   * copertura o un fotogramma vecchio. Una fonte che *vede* pioggia porta informazione; una che
   * non la vede porta soprattutto il proprio limite.
   */
  fun merge(a: RainObservation?, b: RainObservation?): RainObservation? = when {
    a == null -> b
    b == null -> a
    a.rainingNow && b.rainingNow -> {
      val stronger = if ((a.intensityMmPerHour ?: 0.0) >= (b.intensityMmPerHour ?: 0.0)) a else b
      stronger.copy(source = "${a.source}+${b.source}")
    }
    a.rainingNow -> a
    b.rainingNow -> b
    else -> a.copy(source = "${a.source}+${b.source}")
  }

  const val SOURCE_MINUTELY = "quarto d'ora"
  const val SOURCE_RADAR = "radar"
}
