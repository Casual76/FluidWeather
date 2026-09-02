package dev.pampa.fluidweather.feature.settings

import dev.pampa.fluidweather.core.model.GlassLevel
import dev.pampa.fluidweather.core.model.SamplingMode

/** Le etichette delle modalita' di campionamento: le usano diagnostica, onboarding e impostazioni. */
internal fun SamplingMode.label(): String = when (this) {
  SamplingMode.MASSIMA -> "Massima"
  SamplingMode.BILANCIATA -> "Bilanciata"
  SamplingMode.RISPARMIO -> "Risparmio"
  SamplingMode.MINIMA -> "Minima"
}

internal fun SamplingMode.description(): String = when (this) {
  SamplingMode.MASSIMA -> "Ogni 5 min, raffica 30 s — allarmi esatti, piu' batteria"
  SamplingMode.BILANCIATA -> "Ogni 15 min, raffica 30 s — il compromesso suggerito"
  SamplingMode.RISPARMIO -> "Ogni 30 min, raffica 15 s — qualita' in calo dichiarata"
  SamplingMode.MINIMA -> "Ogni 20 min, lettura secca — consumo ~nullo, accuratezza scarsa"
}

internal fun GlassLevel.label(): String = when (this) {
  GlassLevel.FULL -> "Pieno"
  GlassLevel.REDUCED -> "Ridotto"
  GlassLevel.OFF -> "Spento"
}

internal fun GlassLevel.description(): String = when (this) {
  GlassLevel.FULL -> "Vetro con rifrazione e scena animata: per i dispositivi capaci"
  GlassLevel.REDUCED -> "Vetro semplificato e scena leggera"
  GlassLevel.OFF -> "Superfici opache e cielo fermo: il minimo indispensabile"
}
