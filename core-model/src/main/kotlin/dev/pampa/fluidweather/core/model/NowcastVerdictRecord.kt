package dev.pampa.fluidweather.core.model

/**
 * Un verdetto del nowcast com'era in un istante: e' lo "storico" della pagina del nowcast, e la
 * materia prima della pagella (fase 13) e della ricalibrazione (fase 16). Primitive e stringhe:
 * finisce in Room e deve sopravvivere ai refactor del modello.
 */
data class NowcastVerdictRecord(
  val timestampMillis: Long,
  val probability01: Double,
  val probability13: Double,
  val probability36: Double,
  /** Il name() del livello (QUIETE, SORVEGLIANZA, ALLERTA). */
  val level: String,
)
