package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.nowcast.climatology.LocalPriors
import dev.pampa.fluidweather.nowcast.climatology.PooledPriors
import dev.pampa.fluidweather.nowcast.verdict.TrainedNowcastV3

/**
 * Le tabelle che il v3 legge come feature: la climatologia e le baseline del posto quando la cella e'
 * scaricata, sopra il riferimento di tutti i posti con cui il modello e' stato addestrato
 * ([TrainedNowcastV3.POOLED]). E' lo stesso riferimento che il gate usa per NONE_NOCLIMA: il telefono
 * al primo avvio offline parla con le stesse tabelle del banco.
 */
object V3Priors {

  /** Il riferimento di tutti i posti, dall'artefatto: se non si legge e' un artefatto rotto, non un caso. */
  val pooled: PooledPriors by lazy {
    checkNotNull(PooledPriors.decode(TrainedNowcastV3.POOLED)) { "il riferimento di tutti i posti dell'artefatto v3 non si legge" }
  }

  val pooledOnly: LocalPriors by lazy { LocalPriors.pooledOnly(pooled) }

  fun of(climate: LocalClimate?): LocalPriors =
    climate?.let { LocalPriors.local(it.climatology, it.baselines, pooled) } ?: pooledOnly
}
