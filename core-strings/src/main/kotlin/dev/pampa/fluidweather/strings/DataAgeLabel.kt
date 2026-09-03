package dev.pampa.fluidweather.strings

import android.content.res.Resources
import dev.pampa.fluidweather.core.model.DataAge
import dev.pampa.fluidweather.core.model.DataFreshness

/**
 * La riga che dice quanto sono vecchi i dati dei provider, o null quando non c'e' niente da dire.
 *
 * Sta qui e non nella schermata perche' la dicono in due — la testata della home e il widget di
 * sistema — e due frasi diverse per lo stesso fatto sarebbero due bugie a meta'.
 *
 * Tre scelte, tutte deliberate:
 *
 * - **Fresco non dice niente** (null). Una riga "aggiornato" sarebbe rumore nel 95% dei casi, e
 *   insegnerebbe a non leggerla proprio il giorno che conta. L'assenza *e'* lo stato fresco.
 * - **Mentre carica non dice niente.** Altrimenti ogni avvio a freddo lampeggerebbe "nessuna
 *   previsione" per un istante, che e' falso e allarma.
 * - **Relativo fino a sei ore, orario oltre.** "9 ore fa" costringe a fare i conti; "09:20" no. E
 *   passate alcune ore chi guarda vuole sapere di *quale momento* si parla, non da quanto aspetta.
 */
fun dataAgeLabel(
  resources: Resources,
  dataAtMillis: Long?,
  nowMillis: Long,
  loading: Boolean = false,
): String? {
  if (loading) return null
  return when (DataAge.of(dataAtMillis, nowMillis)) {
    DataFreshness.FRESH -> null
    DataFreshness.NONE -> resources.getString(R.string.home_age_none)
    DataFreshness.STALE -> {
      val minutes = ((DataAge.ageMillis(dataAtMillis, nowMillis) ?: 0L) / 60_000L).toInt()
      // Sotto le due ore i minuti dicono qualcosa ("aggiornato 95 min fa" e' un'informazione);
      // sopra diventano un numero da dividere a mente, e le ore bastano.
      if (minutes < 120) {
        resources.getString(R.string.home_age_minutes, minutes)
      } else {
        resources.getString(R.string.home_age_hours, minutes / 60)
      }
    }
    DataFreshness.VERY_STALE -> {
      val at = dataAtMillis ?: return null
      val sameDay = TimeFormats.shortDate(at) == TimeFormats.shortDate(nowMillis)
      val moment = if (sameDay) TimeFormats.time(at) else TimeFormats.dayTime(at)
      resources.getString(R.string.home_age_at, moment)
    }
  }
}
