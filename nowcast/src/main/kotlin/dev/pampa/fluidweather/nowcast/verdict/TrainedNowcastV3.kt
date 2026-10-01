package dev.pampa.fluidweather.nowcast.verdict

import dev.pampa.fluidweather.nowcast.features.ContextTier

/**
 * GENERATO da `gradlew :testbench:run --args="train-v3 emit --from release"` — non modificare a mano.
 * TRAIN+VALIDATION 2022-11-24..2025-08-31 (scenari estratti), iperparametri congelati da v3-candidato-2026-10-01.
 * Profilo di campionamento: TELEFONO; etichetta: PANNELLO (panel-1); tabelle di baseline dagli altri anni (jackknife).
 * Dati: Open-Meteo.com (CC BY 4.0), uso non commerciale. Metriche in reports/training-v3*.txt e reports/gate-*-v3*.txt.
 *
 * L'indice del v3: versione, famiglia scelta da D4, nomi delle feature (il contratto), il riferimento di tutti
 * i posti che viaggia col modello (PP1: NONE_NOCLIMA e ripiego delle tabelle locali), le risorse degli alberi
 * con la loro impronta SHA-256 (controllata al caricamento: se non torna parla la logistica) e gli iperparametri.
 */
object TrainedNowcastV3 {
  const val VERSION: String = "v3-2026-10-01"

  /** La famiglia che il telefono carica: la logistica. */
  const val FAMILY: String = "LOGISTICA"

  const val FEATURES_VERSION: String = "features-v3"

  const val PRIORS_FORMATS: String = "WC1+LB2+PP1"

  /** Il riferimento di tutti i posti (PP1) sulla storia delle baseline del periodo di prova. */
  const val POOLED: String = "PP1|40|C0-1h:21278/163892|C1-3h:28619/163872|C3-6h:32535/163842|P0-1h@0:20960/290543,7036/14875,10010/15822,4550/6514|P0-1h@1:23780/290518,6313/14876,8669/15822,3794/6514|P0-1h@2:40263/435738,8018/22316,11034/23727,4519/9769|P0-1h@3:90788/871305,12929/44634,17275/47463,6676/19536|P1-3h@0:33652/290503,8241/14875,10696/15822,4649/6514|P1-3h@1:36459/290483,7334/14876,9483/15818,3962/6513|P1-3h@2:59041/435683,9433/22314,12428/23725,4955/9768|P1-3h@3:127256/871187,16006/44632,20641/47463,7811/19536|P3-6h@0:43134/290450,7826/14874,9983/15818,4127/6512|P3-6h@1:45502/290428,6986/14874,8995/15816,3587/6512|P3-6h@2:71313/435594,9466/22314,12168/23724,4658/9768|P3-6h@3:148936/871022,16986/44624,21238/47457,8050/19535|T0-1h:968/2993,3728/17385,5783/43512,3374/32664,4664/47899,2761/19403|T1-3h:1155/2993,4873/17385,8043/43509,4706/32660,6397/47887,3445/19402|T3-6h:1223/2993,5264/17380,9462/43504,5454/32653,7561/47876,3571/19400"

  val FEATURE_NAMES: List<String> = listOf(
    "tendenza-1h",
    "tendenza-3h",
    "tendenza-6h",
    "tendenza-12h",
    "accelerazione-3h",
    "anomalia-livello",
    "caduta-3h",
    "caduta-con-aria-umida",
    "umidita",
    "spread-rugiada",
    "copertura",
    "cielo-coperto",
    "aria-satura",
    "vento",
    "rotazione-vento-3h",
    "pioggia-ultima-ora",
    "pioggia-ultime-3h",
    "tendenza-provider-3h",
    "ora-sin",
    "ora-cos",
    "eta-contesto-ore",
    "piove-adesso",
    "pioggia-ultime-6h",
    "ore-da-ultima-pioggia",
    "tendenza-nuvole-3h",
    "tendenza-rugiada-3h",
    "tendenza-temperatura-3h",
    "giorno-sin",
    "giorno-cos",
    "convezione-pomeridiana",
    "clima-0-1h",
    "clima-1-3h",
    "clima-3-6h",
    "persistenza-0-1h",
    "persistenza-1-3h",
    "persistenza-3-6h",
    "regola-barometrica-0-1h",
    "regola-barometrica-1-3h",
    "regola-barometrica-3-6h",
    "livello-mare",
    "normale-assente",
    "clima-locale",
  )

  val GBM_RESOURCES: List<String> = listOf(
  )

  val GBM_SHA256: Map<String, String> = mapOf(
  )

  /** Gli iperparametri per (livello.finestra): logistica (ancora, lambda, peso fuori Europa) e alberi (se ci sono). */
  val HYPERPARAMETERS: Map<String, String> = mapOf(
    "FRESH.0-1h.logistic" to "anchor=33;lambda=1.0E-4;wne=0.0",
    "FRESH.1-3h.logistic" to "anchor=34;lambda=1.0E-4;wne=0.0",
    "FRESH.3-6h.logistic" to "anchor=35;lambda=3.0E-4;wne=0.0",
    "NONE.0-1h.logistic" to "anchor=36;lambda=0.01;wne=0.0",
    "NONE.1-3h.logistic" to "anchor=37;lambda=1.0E-4;wne=0.0",
    "NONE.3-6h.logistic" to "anchor=38;lambda=1.0E-4;wne=0.0",
    "NONE_NOCLIMA.0-1h.logistic" to "anchor=-1;lambda=0.003;wne=0.0",
    "NONE_NOCLIMA.1-3h.logistic" to "anchor=-1;lambda=1.0E-4;wne=0.5",
    "NONE_NOCLIMA.3-6h.logistic" to "anchor=38;lambda=3.0E-4;wne=0.0",
    "STALE.0-1h.logistic" to "anchor=33;lambda=0.003;wne=0.0",
    "STALE.1-3h.logistic" to "anchor=34;lambda=3.0E-4;wne=0.0",
    "STALE.3-6h.logistic" to "anchor=-1;lambda=3.0E-4;wne=0.0",
  )

  const val TRAINING: String = "TRAIN+VALIDATION 2022-11-24..2025-08-31 (scenari estratti), iperparametri congelati da v3-candidato-2026-10-01"

  /** Le quattro tabelle logistiche come modelli. */
  fun logisticTables(): Map<ContextTier, NowcastModel> = mapOf(
    ContextTier.FRESH to NowcastModel(TrainedNowcastV3Fresh.means, TrainedNowcastV3Fresh.sds, TrainedNowcastV3Fresh.windows, FEATURE_NAMES),
    ContextTier.STALE to NowcastModel(TrainedNowcastV3Stale.means, TrainedNowcastV3Stale.sds, TrainedNowcastV3Stale.windows, FEATURE_NAMES),
    ContextTier.NONE to NowcastModel(TrainedNowcastV3None.means, TrainedNowcastV3None.sds, TrainedNowcastV3None.windows, FEATURE_NAMES),
    ContextTier.NONE_NOCLIMA to NowcastModel(TrainedNowcastV3NoneNoClima.means, TrainedNowcastV3NoneNoClima.sds, TrainedNowcastV3NoneNoClima.windows, FEATURE_NAMES),
  )
}
