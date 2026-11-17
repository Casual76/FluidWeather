package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.HorizonBucket
import dev.pampa.fluidweather.core.model.VerificationStore
import kotlin.math.pow

/** Da dove arriva il peso di un provider: la classifica lo dichiara sempre. */
enum class WeightSource { LEARNED, PRIOR }

data class ProviderWeight(
  val providerId: String,
  val weight: Double,
  val source: WeightSource,
  /** MAE decaduto quando il peso e' appreso; NaN quando e' un prior. */
  val decayedMae: Double,
  val verificationCount: Int,
)

/**
 * La cascata dei pesi promessa dal piano, per variabile e fascia di orizzonte:
 *
 *  1. APPRESO — con almeno [minVerifications] giudizi locali, il peso e' 1/(MAE+eps)²: chi
 *     sbaglia il doppio pesa (circa) un quarto. Il MAE e' decaduto esponenzialmente
 *     (dimezzamento a [halfLifeDays] giorni): il provider che era bravo l'anno scorso e ora
 *     sbanda viene raggiunto dai fatti in fretta.
 *  2. PRIOR — sotto soglia si usa l'editoriale per macro-regione ([RegionalPriors]).
 *
 * Locale significa entro 3 km, con la versione corrente del consenso di analisi. Nessun fallback
 * globale: righe legacy/senza luogo e giudizi di altre citta' non allenano questo punto.
 *
 * Il "provider predefinito" del piano e' il gradino zero: anche senza priori nominati, il
 * floor dei priori garantisce che open-meteo (e chiunque altro) abbia un peso non nullo.
 */
class ProviderScoreboard(
  private val store: VerificationStore,
  private val minVerifications: Int = LocalProviderLearning.MIN_VERIFICATIONS,
  private val halfLifeDays: Double = LocalProviderLearning.HALF_LIFE_DAYS,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  suspend fun weights(
    variable: String,
    bucket: HorizonBucket,
    latitude: Double,
    longitude: Double,
    providerIds: List<String>,
  ): Map<String, ProviderWeight> {
    val region = MacroRegion.of(latitude, longitude)
    val now = clock()
    val verifications = LocalProviderLearning.eligible(store.verificationsFor(variable, bucket), latitude, longitude, now)
      .groupBy { it.providerId }

    return providerIds.associateWith { providerId ->
      val own = verifications[providerId].orEmpty()
      val stats = LocalProviderLearning.statistics(own, now, minVerifications, halfLifeDays)
      if (stats.learned) {
        val mae = stats.mae
        ProviderWeight(
          providerId = providerId,
          weight = 1.0 / (mae + MAE_EPSILON).pow(2),
          source = WeightSource.LEARNED,
          decayedMae = mae,
          verificationCount = own.size,
        )
      } else {
        ProviderWeight(
          providerId = providerId,
          weight = RegionalPriors.prior(region, providerId),
          source = WeightSource.PRIOR,
          decayedMae = Double.NaN,
          verificationCount = own.size,
        )
      }
    }
  }

  private companion object {
    /**
     * Evita il peso infinito del provider perfetto e fissa la scala: con eps pari al rumore
     * tipico della variabile piu' fine (0,3), un MAE nullo vale ~11 e un MAE di 1,0 vale ~0,6.
     */
    const val MAE_EPSILON = LocalProviderLearning.MAE_EPSILON
  }
}
