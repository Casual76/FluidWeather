package dev.pampa.fluidweather.core.model

import java.time.Instant
import java.util.Locale

/**
 * Gli id delle righe locali e di riferimento della classifica pioggia: una sola fonte.
 *
 * Erano due, in due moduli (`ForecastVerifier.kt` e `Labels.kt`), scritte a mano e prive di
 * qualunque legame: bastava un refuso in una sola per avere un barometro giudicato e mai mostrato.
 * Stanno in :core-model, e non in :nowcast, perche' core-model non vede :nowcast e chi scrive le
 * righe (core-weather), chi le legge (core-data) e chi le traduce (core-strings) devono poter
 * nominarle senza dipendere l'uno dall'altro.
 */
object RainBoardIds {
  /** Il barometro del telefono: il verdetto finale e indipendente, quello che l'utente vede. */
  const val BAROMETER = "barometro"

  /** Ombra: la stessa pipeline col contesto dei provider tolto. Non entra in classifica. */
  const val BAROMETER_SOLO = "barometro-solo"

  /** Riferimento: "non piove mai". Chi non lo batte non sa niente. */
  const val ALWAYS_ZERO = "sempre-0"

  /** Riferimento: quanto piove di solito, li', in questa stagione e a quest'ora del sole. */
  const val CLIMATOLOGY = "climatologia"

  val REFERENCES: Set<String> = setOf(ALWAYS_ZERO, CLIMATOLOGY)
  val SHADOWS: Set<String> = setOf(BAROMETER_SOLO)
}

/**
 * Una previsione di pioggia in attesa del suo giudice: chi, dove, quando, cosa ha detto.
 *
 * Porta il **suo** posto (latitudine e longitudine arrotondate al centinaio di metri) perche' la
 * verita' si scarica li', e non dove l'utente si trova il giorno dopo. [roundId] e' l'istante del
 * giro che l'ha registrata: tutte le righe dello stesso giro condividono il [roundId] e
 * l'[issuedAtMillis], ed e' cio' che le rende appaiate nella classifica.
 */
data class RainEventPending(
  val placeKey: String,
  val latitude: Double,
  val longitude: Double,
  val roundId: Long,
  val providerId: String,
  /** L'etichetta della finestra ("0-1h", "1-3h", "3-6h"): RainWindow.label. */
  val window: String,
  val issuedAtMillis: Long,
  /** In [0, 1]. */
  val probability: Double,
  /** ModelVersions.TAG al momento della registrazione. */
  val modelVersion: String,
  /** ContextTier.name del giro; null sui dispositivi senza barometro. */
  val tier: String?,
)

/** Una previsione giudicata: la pendente, l'esito, e cio' che l'ha deciso (perche' si possa rifare). */
data class RainEventVerification(
  val prediction: RainEventPending,
  val rained: Boolean,
  /** Somma degli slot dopo le osservazioni; null se ha deciso un "bagnato" su una finestra coi buchi. */
  val truthSumMm: Double?,
  /** Quanti giudici erano presenti in TUTTI gli slot della finestra (0..3). */
  val truthVoters: Int,
  /** TruthPanel.VERSION ("panel-1") o "osservazione". */
  val truthSource: String,
  val settledAtMillis: Long,
)

/**
 * Il magazzino delle previsioni di pioggia: Room sul telefono, una mappa in memoria nei test.
 * La chiave di una riga e' (posto, giro, id, finestra): la stessa chiave scritta due volte e'
 * la stessa previsione, non due.
 */
interface RainEventStore {
  /** Aggiunge le pendenti; una chiave gia' presente e' ignorata (il giro non si riscrive). */
  suspend fun addPending(rows: List<RainEventPending>)

  /** Le pendenti emesse al piu' a [issuedBeforeMillis] (estremo incluso), dalla piu' vecchia. */
  suspend fun pendingIssuedBefore(issuedBeforeMillis: Long): List<RainEventPending>

  /** Scrive i giudizi (una seconda volta non duplica) e toglie le loro pendenti. */
  suspend fun settle(verifications: List<RainEventVerification>)

  /** Butta le pendenti emesse prima di [issuedBeforeMillis] (estremo escluso); quante. */
  suspend fun expirePending(issuedBeforeMillis: Long): Int

  /** I giudizi di una versione emessi da [sinceMillis] in poi (estremo incluso). */
  suspend fun verifications(modelVersion: String, sinceMillis: Long): List<RainEventVerification>

  /** Tutti i giudizi, di ogni versione. */
  suspend fun allVerifications(): List<RainEventVerification>

  /**
   * Una pagina di tutti i giudizi, nell'ordine della chiave (posto, giro, id, finestra): e' la
   * lettura dell'export. Sei mesi di giri orari sono oltre centomila righe, e caricarle tutte
   * insieme — piu' il loro CSV in una stringa sola — e' un'esportazione che finisce la memoria.
   */
  suspend fun verificationsPage(offset: Int, limit: Int): List<RainEventVerification>

  suspend fun pendingCount(): Int
  suspend fun verificationCount(): Int

  /** Toglie cio' che la ritenzione non tiene piu'. */
  suspend fun prune(nowMillis: Long)

  /** Dati e privacy: via tutto, pendenti comprese. */
  suspend fun clear()
}

/**
 * L'export CSV dei giudizi della pioggia: una riga per finestra giudicata, con la previsione,
 * l'esito e cio' che l'ha deciso. Colonne fisse, virgola, punto decimale, UTC.
 *
 * `Locale.ROOT` sempre: su un telefono italiano `%.4f` scriverebbe la virgola, e il foglio di
 * calcolo leggerebbe due colonne dove ce n'e' una.
 */
object RainVerificationCsv {

  const val HEADER: String =
    "round_id,round_iso_utc,place_key,latitude,longitude,provider_id,window,issued_at_millis," +
      "probability,model_version,tier,outcome,truth_sum_mm,truth_voters,truth_source,settled_at_millis"

  fun line(v: RainEventVerification): String {
    val p = v.prediction
    return listOf(
      p.roundId.toString(),
      Instant.ofEpochMilli(p.roundId).toString(),
      p.placeKey,
      String.format(Locale.ROOT, "%.3f", p.latitude),
      String.format(Locale.ROOT, "%.3f", p.longitude),
      p.providerId,
      p.window,
      p.issuedAtMillis.toString(),
      String.format(Locale.ROOT, "%.4f", p.probability),
      p.modelVersion,
      p.tier ?: "",
      if (v.rained) "1" else "0",
      v.truthSumMm?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "",
      v.truthVoters.toString(),
      v.truthSource,
      v.settledAtMillis.toString(),
    ).joinToString(",") { csvField(it) }
  }

  fun render(rows: List<RainEventVerification>): String = buildString {
    append(HEADER).append('\n')
    rows
      .sortedWith(
        compareBy<RainEventVerification>({ it.prediction.roundId }, { it.prediction.providerId }, { it.prediction.window }),
      )
      .forEach { append(line(it)).append('\n') }
  }

  /**
   * Lo stesso CSV scritto a pagine su [out], senza mai tenerlo tutto in memoria: [page] da' le
   * righe dalla posizione data, al piu' [pageSize], nell'ordine della chiave (cioe' per giro, id e
   * finestra, come [render]); una pagina corta e' l'ultima. Restituisce quante righe ha scritto.
   */
  suspend fun write(
    out: Appendable,
    pageSize: Int = PAGE_SIZE,
    page: suspend (offset: Int, limit: Int) -> List<RainEventVerification>,
  ): Int {
    require(pageSize > 0) { "pagina vuota" }
    out.append(HEADER).append('\n')
    var written = 0
    while (true) {
      val rows = page(written, pageSize)
      rows.forEach { out.append(line(it)).append('\n') }
      written += rows.size
      if (rows.size < pageSize) return written
    }
  }

  /** Righe per pagina dell'export: un megabyte o poco piu' alla volta, qualunque sia l'archivio. */
  const val PAGE_SIZE: Int = 5_000
}

/**
 * L'archivio dell'apprendimento come CSV: i verdetti del barometro con le probabilita' grezze,
 * le feature e gli esiti. E' cio' da cui il telefono impara, e lo stesso foglio che il banco di
 * prova sa rileggere.
 *
 * Le feature sono colonne `f_<nome>`: i nomi li passa chi chiama (vengono da `FeatureExtractor`
 * in :nowcast, che core-model non vede). Un vettore piu' corto dei nomi — una riga scritta quando
 * le feature erano sedici — si completa di vuoti; uno piu' lungo ha colonne `f_<indice>` oltre i
 * nomi. NaN e mancante sono entrambi un campo vuoto, come l'esito non ancora noto.
 */
object LearningCsv {

  /** Il prefisso fisso; poi una colonna f_<nome> per nome passato, f_<indice> oltre (vettori piu' lunghi). */
  const val HEADER_PREFIX: String =
    "issued_at_millis,issued_iso_utc,round_id,model_version,tier,raw_0_1h,raw_1_3h,raw_3_6h," +
      "rained_0_1h,rained_1_3h,rained_3_6h"

  private val WINDOW_LABELS = listOf("0-1h", "1-3h", "3-6h")

  fun render(
    issues: List<NowcastIssueRecord>,
    outcomes: List<NowcastOutcomeRecord>,
    featureNames: List<String>,
  ): String {
    val width = maxOf(featureNames.size, issues.maxOfOrNull { it.features.size } ?: 0)
    val columns = (0 until width).map { "f_" + (featureNames.getOrNull(it) ?: it.toString()) }
    val rained = outcomes.associate { (it.issuedAtMillis to it.window) to it.rained }
    return buildString {
      append((listOf(HEADER_PREFIX) + columns).joinToString(",")).append('\n')
      for (issue in issues.sortedBy { it.issuedAtMillis }) {
        val fields = mutableListOf(
          issue.issuedAtMillis.toString(),
          Instant.ofEpochMilli(issue.issuedAtMillis).toString(),
          issue.roundId.toString(),
          issue.modelVersion,
          issue.tier ?: "",
          String.format(Locale.ROOT, "%.4f", issue.rawProbability01),
          String.format(Locale.ROOT, "%.4f", issue.rawProbability13),
          String.format(Locale.ROOT, "%.4f", issue.rawProbability36),
        )
        for (window in WINDOW_LABELS) {
          fields += when (rained[issue.outcomeKey to window]) {
            true -> "1"
            false -> "0"
            null -> ""
          }
        }
        for (index in 0 until width) {
          val value = issue.features.getOrNull(index)
          fields += if (value == null || value.isNaN()) "" else String.format(Locale.ROOT, "%.5f", value)
        }
        append(fields.joinToString(",") { csvField(it) }).append('\n')
      }
    }
  }
}

/** Un campo CSV: fra virgolette solo se ne ha bisogno (virgola, virgolette, a capo). */
private fun csvField(value: String): String =
  if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
    "\"" + value.replace("\"", "\"\"") + "\""
  } else {
    value
  }
