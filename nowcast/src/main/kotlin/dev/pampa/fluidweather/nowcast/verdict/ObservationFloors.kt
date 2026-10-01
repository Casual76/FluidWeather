package dev.pampa.fluidweather.nowcast.verdict

import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.learning.RainObservation
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import java.util.Locale

/**
 * Un gradino dei pavimenti "sta piovendo adesso": dalla pioggia dell'ultima ora chiusa [fromMm] in su,
 * nessuna finestra di [floors] puo' stare sotto il suo valore.
 */
data class RainNowFloor(
  val fromMm: Double,
  /** finestra -> probabilita' minima. */
  val floors: Map<String, Double>,
) {
  init {
    require(fromMm >= 0.0) { "soglia negativa: $fromMm" }
    require(floors.values.all { it in 0.0..1.0 }) { "pavimento fuori da [0, 1]: $floors" }
  }
}

/**
 * Il pavimento del radar: se nella finestra [window] il radar vede almeno [minDbz] con confidenza
 * [minConfidence], la probabilita' non scende sotto [floor]. Il banco non puo' rigiocare il radar, quindi
 * questo pavimento non e' mai certificato dal gate: esiste come dato, spento di default.
 */
data class RadarFloor(
  val window: String = "0-1h",
  val minDbz: Double = 20.0,
  val minConfidence: Double = 0.4,
  val floor: Double,
)

/**
 * La politica dei pavimenti dell'osservazione per un livello del v3, come dato.
 *
 * Il v2 aveva un solo pavimento, uguale per tutti ([RainObservation.FLOORS_WHEN_RAINING]): con la
 * pioggia in corso 0-1h non sotto 0,65, 1-3h non sotto 0,60, 3-6h non sotto 0,50. Nel v3 il modello
 * vede "piove adesso", quanto ha piovuto e da quanto ([dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3]):
 * un pavimento sopra un modello che sa gia' della pioggia rischia di essere una seconda persistenza,
 * piu' rozza, che corregge al rialzo chi aveva ragione. Se un pavimento resta lo decide il banco
 * (`floors-v3 validation`), livello per livello e finestra per finestra.
 *
 * I gradini di [whenRainingNow] sono ordinati per soglia; vale il piu' alto che la pioggia raggiunge.
 * Una politica vuota non alza niente.
 */
data class ObservationFloors(
  val tier: ContextTier,
  val whenRainingNow: List<RainNowFloor> = emptyList(),
  val radar: RadarFloor? = null,
) {

  init {
    require(whenRainingNow.zipWithNext().all { (a, b) -> a.fromMm < b.fromMm }) { "gradini fuori ordine" }
    require(tier.hasContext || whenRainingNow.isEmpty()) { "senza contesto non si sa se piove: niente gradini in $tier" }
  }

  /** I pavimenti per la pioggia dell'ultima ora chiusa [rainNowMm]; vuoti se non piove o non si sa. */
  fun floorsFor(rainNowMm: Double?): Map<String, Double> {
    if (rainNowMm == null || rainNowMm.isNaN()) return emptyMap()
    return whenRainingNow.lastOrNull { rainNowMm >= it.fromMm }?.floors ?: emptyMap()
  }

  /**
   * L'osservazione da dare al motore ([dev.pampa.fluidweather.nowcast.learning.NowcastEngine]) per la
   * pioggia [rainNowMm]: la stessa forma di sempre, con i pavimenti di questa politica. Null se non si sa.
   */
  fun observation(rainNowMm: Double?, source: String): RainObservation? {
    if (rainNowMm == null || rainNowMm.isNaN()) return null
    return RainObservation(
      rainingNow = RainWindows.isWet(rainNowMm),
      intensityMmPerHour = rainNowMm,
      floors = floorsFor(rainNowMm),
      source = source,
    )
  }

  /** Una riga di testo: stessa politica, stessa riga (entra nell'impronta dell'artefatto). */
  fun encode(): String = buildString {
    append(tier.name).append(':')
    whenRainingNow.joinTo(this, ";") { step ->
      String.format(Locale.ROOT, ">=%s{%s}", step.fromMm, RainWindows.ALL.mapNotNull { w -> step.floors[w.label]?.let { "${w.label}=$it" } }.joinToString(","))
    }
    append("|radar=")
    append(radar?.let { "${it.window},${it.minDbz},${it.minConfidence},${it.floor}" } ?: "off")
  }

  companion object {
    /** Nessun pavimento in nessun livello. */
    fun none(): Map<ContextTier, ObservationFloors> = ContextTier.entries.associateWith { ObservationFloors(it) }

    /** Le righe di [encode] di tutti i livelli, nell'ordine dei livelli. */
    fun encodeAll(policies: Map<ContextTier, ObservationFloors>): String =
      ContextTier.entries.joinToString("\n") { (policies[it] ?: ObservationFloors(it)).encode() }

    /** Rilegge le righe di [encodeAll]; null se una riga non si legge. */
    fun decodeAll(text: String): Map<ContextTier, ObservationFloors>? {
      val result = LinkedHashMap<ContextTier, ObservationFloors>()
      for (line in text.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
        val tierName = line.substringBefore(':')
        val tier = ContextTier.entries.firstOrNull { it.name == tierName } ?: return null
        val body = line.substringAfter(':')
        val stepsText = body.substringBefore("|radar=")
        val radarText = body.substringAfter("|radar=", "off")
        val steps = if (stepsText.isEmpty()) {
          emptyList()
        } else {
          stepsText.split(';').map { part ->
            val from = part.removePrefix(">=").substringBefore('{').toDoubleOrNull() ?: return null
            val inner = part.substringAfter('{').removeSuffix("}")
            val floors = if (inner.isEmpty()) {
              emptyMap()
            } else {
              inner.split(',').associate { kv ->
                val value = kv.substringAfter('=').toDoubleOrNull() ?: return null
                kv.substringBefore('=') to value
              }
            }
            RainNowFloor(from, floors)
          }
        }
        val radar = if (radarText == "off") {
          null
        } else {
          val p = radarText.split(',')
          if (p.size != 4) return null
          RadarFloor(p[0], p[1].toDoubleOrNull() ?: return null, p[2].toDoubleOrNull() ?: return null, p[3].toDoubleOrNull() ?: return null)
        }
        result[tier] = ObservationFloors(tier, steps, radar)
      }
      return if (result.keys == ContextTier.entries.toSet()) result else null
    }
  }
}
