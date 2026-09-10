package dev.pampa.fluidweather.nowcast.learning

/**
 * Cosa sta succedendo **adesso**, misurato e non previsto.
 *
 * Il verdetto barometrico e' una previsione, e una previsione puo' sbagliare; ma non puo'
 * sbagliare *su cio' che si vede dalla finestra*. Con la pioggia in corso da due ore il motore
 * dava la finestra 1-3 h come la piu' probabile, perche' nessuno gli aveva mai detto che stava
 * piovendo: il contesto arrivava da una riga oraria che poteva avere un'ora e mezza, e il
 * quarto d'ora e il radar — che l'app scarica gia' — non entravano nel verdetto.
 *
 * Questo non e' un modello e non e' addestrato: e' un pavimento dichiarato. Se il quarto d'ora
 * o il radar dicono che sta piovendo, la probabilita' a breve non puo' stare sotto quel valore.
 */
data class RainObservation(
  val rainingNow: Boolean,
  /** Intensita' stimata adesso (mm/h); null quando l'osservazione non la sa quantificare. */
  val intensityMmPerHour: Double? = null,
  /** finestra -> probabilita' minima che l'osservazione impone. */
  val floors: Map<String, Double> = emptyMap(),
  /** Chi l'ha vista: il quarto d'ora del provider, il radar, o entrambi. */
  val source: String = "",
) {

  fun floorFor(window: String): Double = floors[window] ?: 0.0

  companion object {
    /**
     * Sta piovendo adesso: quanto e' probabile che piova poi?
     *
     * Misurato, non scelto. `gradlew :testbench:run --args=stages` conta, su quattro anni e dieci
     * localita', quante volte un'ora bagnata e' seguita da una finestra bagnata:
     *
     *     finestra   media   peggiore localita'
     *     0-1h       0,726   0,649 (denver)
     *     1-3h       0,726   0,627 (denver)
     *     3-6h       0,665   0,543 (denver)
     *
     * I pavimenti stanno al *peggiore*, non alla media: un pavimento e' una promessa che deve
     * valere anche dove il clima e' meno persistente, altrimenti non e' un pavimento, e' una
     * previsione travestita. Due cose che questi numeri dicono e che il motore non sapeva: che
     * la pioggia in corso vale quanto per la finestra 1-3 h quanto per quella 0-1 h — le piogge
     * durano — e che la vecchia costante di persistenza del banco, 0,85, era ottimista.
     */
    val FLOORS_WHEN_RAINING: Map<String, Double> = mapOf(
      "0-1h" to 0.65,
      "1-3h" to 0.60,
      "3-6h" to 0.50,
    )

    /** Sotto questa intensita' e' una traccia che nessuno chiamerebbe pioggia. */
    const val RAINING_FROM_MM_PER_HOUR: Double = 0.2

    /** Sta piovendo, con la sua intensita': i pavimenti misurati e nient'altro. */
    fun raining(intensityMmPerHour: Double, source: String): RainObservation = RainObservation(
      rainingNow = true,
      intensityMmPerHour = intensityMmPerHour,
      floors = FLOORS_WHEN_RAINING,
      source = source,
    )

    /** Si e' guardato, e non pioveva. Nessun pavimento: non vedere non autorizza a escludere. */
    fun dry(source: String): RainObservation = RainObservation(rainingNow = false, source = source)
  }
}
