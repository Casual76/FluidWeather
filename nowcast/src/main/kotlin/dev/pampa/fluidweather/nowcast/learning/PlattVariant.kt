package dev.pampa.fluidweather.nowcast.learning

import dev.pampa.fluidweather.nowcast.features.ContextTier

/**
 * La famiglia di contesto a cui appartiene una mappa di ricalibrazione.
 *
 * Un verdetto con il contesto dei provider fresco, uno con il contesto vecchio e uno senza
 * contesto sono tre modelli diversi di fatto: le feature mancano in modo diverso, quindi la
 * probabilita' grezza sbaglia in modo diverso. Una mappa sola li media, e chi e' offline si
 * ritrova la correzione stimata su chi era connesso. Una mappa per famiglia: ognuna si stima sui
 * soli casi del suo regime e si applica ai soli verdetti del suo regime.
 *
 * [NONE] raccoglie sia [ContextTier.NONE] sia [ContextTier.NONE_NOCLIMA]: senza contesto
 * la differenza e' la climatologia locale, e con due mappe i casi si dimezzerebbero per un
 * dettaglio che il grezzo gia' porta nelle sue feature.
 */
enum class PlattVariant(val key: String) {
  FRESH("fresh"),
  STALE("stale"),
  NONE("none"),

  /** Riservata al modello con il contesto arricchito (P5): oggi nessun verdetto ci finisce. */
  ENHANCED("enh"),
  ;

  companion object {

    /** Le varianti che oggi si stimano. [ENHANCED] aspetta il suo modello. */
    val FITTED: List<PlattVariant> = listOf(FRESH, STALE, NONE)

    fun of(tier: ContextTier): PlattVariant = when (tier) {
      ContextTier.FRESH -> FRESH
      ContextTier.STALE -> STALE
      ContextTier.NONE, ContextTier.NONE_NOCLIMA -> NONE
    }

    /** Il livello dal nome come sta nell'archivio (`ContextTier.name`); null se manca o e' sconosciuto. */
    fun ofTierName(name: String?): PlattVariant? =
      name?.let { n -> ContextTier.entries.firstOrNull { it.name == n } }?.let(::of)

    fun byKey(key: String): PlattVariant? = entries.firstOrNull { it.key == key }
  }
}
