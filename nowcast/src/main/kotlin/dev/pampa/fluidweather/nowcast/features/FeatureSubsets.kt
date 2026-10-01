package dev.pampa.fluidweather.nowcast.features

import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3 as V3

/** Il gruppo di una feature del v3: da che cosa dipende, quindi in quali livelli esiste. */
enum class FeatureGroup(val label: String) {
  /** Barometro del telefono (e il livello del mare, la normale): c'e' sempre. */
  BAROMETER("barometro"),

  /** Contesto dei provider "adesso": solo FRESH e STALE. */
  CONTEXT("contesto"),

  /** Ora e stagione: c'e' sempre. */
  TIME("tempo"),

  /** Le baseline come feature e lo stato delle tabelle. */
  PRIORS("baseline"),
}

/**
 * Quali colonne vede ogni modello, **come dato**: un modello per (livello x finestra), e i
 * coefficienti (o gli split) fuori da queste colonne valgono zero per costruzione.
 *
 * Il mascheramento e' la forma in cui i livelli sono onesti sull'ignoranza. NONE e NONE_NOCLIMA
 * non hanno contesto: le colonne del contesto sarebbero tutte NaN e il modello le imputerebbe alla
 * media, cioe' un regime mai visto spacciato per neutro — meglio non dargliele. NONE_NOCLIMA non
 * ha nemmeno trenta giorni di storia (la normale, colonne 5 e 40, e' NaN/uno per scenario) ne' le
 * tabelle del posto (la climatologia e' una costante di tutti, `clima-locale` sempre zero). Ogni
 * finestra vede solo le **sue** tre baseline: la persistenza e la regola barometrica delle altre
 * finestre sono informazione vera ma non servono a riprodurre questa baseline, e costano pesi.
 *
 * Il livello del mare (39) esce da NONE (v3.1): li' la climatologia locale dice gia' quanto piove nel
 * posto, e il livello assoluto aggiungeva solo il clima di dieci localita' (a banco toglierlo sistemava
 * reykjavik NONE, bocciata dal gate di TEST del v3-2026-09-30). Resta in NONE_NOCLIMA, dove la
 * climatologia non c'e' e il livello e' l'unica traccia della quota e del clima del posto: senza,
 * innsbruck NONE_NOCLIMA perdeva contro la costante. Il telefono deve quindi conoscere la quota di
 * riferimento anche al primo avvio (la taratura la fissa).
 *
 * Le colonne che in addestramento risultano costanti in un livello vengono tolte a posteriori
 * dall'addestratore e riportate: qui si dice solo quali *potrebbero* servire.
 */
object FeatureSubsets {

  /** Barometro: tendenze, accelerazione, anomalia, caduta, livello del mare, normale assente. */
  val BAROMETER: IntArray = intArrayOf(0, 1, 2, 3, 4, 5, 6, V3.SEA_LEVEL, V3.NORMAL_MISSING)

  /** Contesto: umidita', cielo, vento, pioggia, tendenza del provider, e le feature 20-26 del v3. */
  val CONTEXT: IntArray = (7..17).toList().toIntArray() + (V3.CONTEXT_AGE..V3.TEMPERATURE_TREND).toList().toIntArray()

  /** Tempo: ora solare, giorno dell'anno, convezione pomeridiana. */
  val TIME: IntArray = intArrayOf(18, 19, V3.DAY_SIN, V3.DAY_COS, V3.CONVECTION)

  /** Il gruppo di una colonna (0..41). */
  fun groupOf(column: Int): FeatureGroup = when (column) {
    in BAROMETER -> FeatureGroup.BAROMETER
    in CONTEXT -> FeatureGroup.CONTEXT
    in TIME -> FeatureGroup.TIME
    in V3.CLIMATOLOGY..V3.BAROMETRIC_RULE + 2, V3.LOCAL_TABLES -> FeatureGroup.PRIORS
    else -> error("colonna fuori dal contratto v3: $column")
  }

  /**
   * Le colonne che il modello di ([tier], finestra [windowIndex] in 0..2) puo' usare, in ordine
   * crescente.
   * - FRESH, STALE: barometro + contesto + tempo + le tre baseline della finestra + `clima-locale` (36).
   * - NONE: barometro senza il livello del mare + tempo + clima e regola barometrica della finestra (15).
   * - NONE_NOCLIMA: tendenze 0-4, caduta (6), livello del mare, tempo e la regola barometrica della finestra (13).
   */
  fun columns(tier: ContextTier, windowIndex: Int): IntArray {
    require(windowIndex in 0..2) { "finestra fuori da 0..2: $windowIndex" }
    val clima = V3.CLIMATOLOGY + windowIndex
    val persistence = V3.PERSISTENCE + windowIndex
    val rule = V3.BAROMETRIC_RULE + windowIndex
    val selected: List<Int> = when (tier) {
      ContextTier.FRESH, ContextTier.STALE ->
        BAROMETER.toList() + CONTEXT.toList() + TIME.toList() + listOf(clima, persistence, rule, V3.LOCAL_TABLES)

      ContextTier.NONE -> BAROMETER.filter { it != V3.SEA_LEVEL } + TIME.toList() + listOf(clima, rule)

      ContextTier.NONE_NOCLIMA ->
        listOf(0, 1, 2, 3, 4, 6, V3.SEA_LEVEL) + TIME.toList() + listOf(rule)
    }
    return selected.sorted().toIntArray()
  }

  /**
   * Le baseline che il modello puo' prendere come **ancora** (offset a coefficiente uno) durante
   * l'addestramento; `null` = nessuna ancora. Sono ipotesi da scegliere sulla validazione: FRESH
   * {nessuna, persistenza}, STALE {nessuna, persistenza, regola}, NONE e NONE_NOCLIMA {nessuna, regola}.
   */
  fun anchorCandidates(tier: ContextTier, windowIndex: Int): List<Int?> {
    require(windowIndex in 0..2) { "finestra fuori da 0..2: $windowIndex" }
    return when (tier) {
      ContextTier.FRESH -> listOf(null, V3.PERSISTENCE + windowIndex)
      ContextTier.STALE -> listOf(null, V3.PERSISTENCE + windowIndex, V3.BAROMETRIC_RULE + windowIndex)
      ContextTier.NONE, ContextTier.NONE_NOCLIMA -> listOf(null, V3.BAROMETRIC_RULE + windowIndex)
    }
  }
}
