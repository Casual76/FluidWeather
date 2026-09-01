package dev.pampa.fluidweather.core.model

/**
 * La calibrazione del barometro di *questo* dispositivo.
 *
 * La letteratura (Mass & Madaus; JTECH 2018) e' chiara: l'errore dei barometri da smartphone non
 * sta nel sensore ma nel bias fisso per-dispositivo, che puo' valere qualche hPa ed e' stabile
 * nel tempo. Si stima confrontando le proprie letture con la stazione di riferimento piu' vicina
 * (livello provider, fase 6-7; prima raffica dell'onboarding, fase 15). Finche' non c'e' una
 * stima, bias zero e confidenza zero: il segnale resta internamente coerente — per il nowcast
 * conta la *tendenza*, e un offset costante non la tocca.
 */
data class DeviceCalibration(
  /** Da sottrarre alle letture: lettura_vera = lettura_sensore - bias. */
  val biasHpa: Double = 0.0,
  /** 0 = mai stimato; cresce con le verifiche contro la stazione di riferimento. */
  val confidence: Double = 0.0,
)
