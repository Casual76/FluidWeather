package dev.pampa.fluidweather.core.model

/**
 * L'ultimo punto GPS fresco che il telefono ha visto, con l'istante del fix (quello del satellite,
 * non quello in cui lo abbiamo salvato).
 *
 * Serve ai giri in background che non riescono a ottenere un fix nuovo: se l'ultimo ha meno di tre
 * ore e da allora non ci si e' mossi in auto o in bici, e' ancora "qui" e il giro puo' contare
 * come giro del barometro. E' un dato di posizione: "Dati e privacy" lo cancella con tutto il resto.
 */
data class LastFix(
  val latitude: Double,
  val longitude: Double,
  val fixedAtMillis: Long,
)
