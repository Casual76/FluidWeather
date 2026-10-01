package dev.pampa.fluidweather.nowcast.verdict

/**
 * Chi sa dare un verdetto da un vettore di feature: la logistica a comitato del v2 e del v3
 * ([NowcastModel]) e gli alberi del v3 ([TreeNowcastModel]).
 *
 * E' l'unica cosa che il motore deve sapere di un modello. Il v3 ne ha uno per livello di
 * contesto ([TieredNowcastModel.forTier]); P2 fara' parlare il motore con questo tipo al posto di
 * [NowcastModel], e fino ad allora il telefono resta sul v2.
 */
fun interface RainModel {
  fun verdict(features: DoubleArray): NowcastVerdict
}
