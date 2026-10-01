package dev.pampa.fluidweather.nowcast.verdict

import dev.pampa.fluidweather.nowcast.truth.TruthPanel

/**
 * Le tre versioni che rendono confrontabili due numeri: il modello che ha parlato, le regole con
 * cui e' stato giudicato, le regole della classifica.
 *
 * Fin qui `TrainedNowcastV1.VERSION` esisteva e nessuno lo leggeva: le verifiche del modello v1,
 * quelle del v2 e la loro taratura personale finivano nello stesso mucchio, e la classifica
 * mediava un modello che non c'era piu' con quello nuovo — la Platt contaminata del telefono
 * viene da li'. D5: tutto cio' che si registra (previsione, emissione, verifica) porta [TAG], e
 * classifica e ricalibrazione leggono solo cio' che ha l'etichetta corrente. Cambiare una delle
 * tre parti vuol dire ricominciare a contare, ed e' giusto: un Brier del pannello non e' un Brier
 * del vecchio consenso, e un modello nuovo non eredita la taratura del vecchio.
 *
 * - [CURRENT_MODEL]: il v3, da quando il motore dell'app parla con lui (P2b); prima era il v2.
 * - [TRUTH]: la versione del pannello dei giudici e della definizione dell'evento.
 * - [BOARD_RULES]: le regole della classifica (Brier, casi appaiati, riferimenti). "board-2"
 *   perche' la classifica per errore medio sulla verita' di consenso era la prima.
 */
object ModelVersions {

  const val CURRENT_MODEL: String = TrainedNowcastV3.VERSION

  /**
   * Quante feature ha un vettore del modello corrente: la ricalibrazione personale impara solo da
   * vettori di questa lunghezza (un vettore del v2 ha venti colonne e un altro significato).
   */
  const val CURRENT_FEATURE_COUNT: Int = dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3.COUNT

  /**
   * La versione del v3 ("Il tuo barometro", un modello per livello: [TieredNowcastModel]). Diventera'
   * [CURRENT_MODEL] **nella stessa versione dell'app** in cui il motore parlera' con il v3 (P2): prima,
   * i verdetti del v2 finirebbero registrati con l'etichetta del v3, e classifica e ricalibrazione
   * mescolerebbero di nuovo due modelli — lo sbaglio che [TAG] esiste per non ripetere.
   */
  const val V3_MODEL: String = TrainedNowcastV3.VERSION

  /** L'etichetta con cui il banco registra (e il gate giudica) il v3. */
  const val V3_TAG: String = "$V3_MODEL+${TruthPanel.VERSION}+board-2"
  const val TRUTH: String = TruthPanel.VERSION
  const val BOARD_RULES: String = "board-2"

  /** Il separatore dell'etichetta: non compare nelle versioni, ne' rompe un CSV. */
  const val SEPARATOR: String = "+"

  /** L'etichetta di tutto cio' che si registra adesso, per esempio `v2-2026-09-10+panel-1+board-2`. */
  const val TAG: String = "$CURRENT_MODEL$SEPARATOR$TRUTH$SEPARATOR$BOARD_RULES"

  /** L'etichetta di una combinazione qualsiasi: per il banco, che confronta modelli diversi. */
  fun tag(model: String = CURRENT_MODEL, truth: String = TRUTH, boardRules: String = BOARD_RULES): String {
    require(listOf(model, truth, boardRules).none { SEPARATOR in it || it.isEmpty() }) {
      "versione non etichettabile: $model / $truth / $boardRules"
    }
    return "$model$SEPARATOR$truth$SEPARATOR$boardRules"
  }

  /** Le tre parti di un'etichetta; null se non e' un'etichetta. */
  fun parse(tag: String): VersionTag? {
    val parts = tag.split(SEPARATOR)
    if (parts.size != 3 || parts.any { it.isEmpty() }) return null
    return VersionTag(model = parts[0], truth = parts[1], boardRules = parts[2])
  }

  /** L'etichetta e' quella corrente? E' il filtro di classifica e ricalibrazione. */
  fun isCurrent(tag: String?): Boolean = tag == TAG
}

/** Un'etichetta di versione scomposta. */
data class VersionTag(val model: String, val truth: String, val boardRules: String) {
  override fun toString(): String = ModelVersions.tag(model, truth, boardRules)
}
