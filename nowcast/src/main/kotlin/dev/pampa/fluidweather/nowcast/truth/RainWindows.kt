package dev.pampa.fluidweather.nowcast.truth

import dev.pampa.fluidweather.core.model.Observation

/**
 * Una finestra dell'evento pioggia: "piove fra [fromHours] e [toHours] ore da adesso".
 *
 * L'etichetta e' la stessa delle finestre del verdetto ("0-1h", "1-3h", "3-6h"): e' la chiave con
 * cui telefono, banco, archivio delle verifiche e classifica si parlano, e una finestra che
 * cambiasse nome in un posto solo smetterebbe di essere giudicata senza che nessuno se ne accorga.
 */
data class RainWindow(val fromHours: Int, val toHours: Int) {
  init {
    require(fromHours >= 0 && toHours > fromHours) { "finestra vuota: $fromHours-$toHours" }
  }

  val label: String get() = "$fromHours-${toHours}h"

  /** Quanti slot orari chiusi la compongono: una per ora di ampiezza. */
  val slotCount: Int get() = toHours - fromHours
}

/**
 * Cio' che una persona ha visto guardando fuori, ridotto a quello che conta per la verita':
 * quando, e se pioveva. Il luogo non c'e' perche' non e' affare di questa definizione: chi giudica
 * passa solo le osservazioni fatte nel posto della previsione.
 */
data class Sighting(val timestampMillis: Long, val wet: Boolean) {
  companion object {
    fun of(observation: Observation): Sighting =
      Sighting(observation.timestampMillis, observation.condition.wet)
  }
}

/**
 * Il giudizio di una finestra.
 *
 * [sumMm] e' la somma degli slot dopo le osservazioni (uno slot visto asciutto vale zero): e' il
 * numero che finisce accanto all'esito nell'archivio delle verifiche, perche' un esito senza la
 * sua somma non si puo' ricontrollare. E' null solo quando a decidere e' stata un'osservazione
 * "bagnato" su una finestra con buchi — l'esito e' certo, la somma no.
 */
data class WindowOutcome(
  val wet: Boolean,
  val sumMm: Double?,
  /** Quante osservazioni dell'utente sono cadute dentro la finestra (0 = verdetto dei soli dati). */
  val sightings: Int,
)

/**
 * LA definizione dell'evento pioggia: una sola, identica su telefono e banco.
 *
 * Prima ce n'erano due, ed erano diverse: il banco chiamava bagnata una finestra con almeno
 * 0,2 mm accumulati, l'app un'ora qualsiasi con 0,1 mm; il banco ancorava le finestre all'ora
 * esatta dell'archivio, l'app al millisecondo del giro. Un modello addestrato su un evento e
 * giudicato su un altro e' giudicato su una domanda che non gli e' mai stata fatta. Qui la
 * domanda e' scritta una volta, e tutti — addestramento, climatologia locale, baseline,
 * classifica — la importano.
 *
 * **L'ancora.** Le finestre partono dall'ora di emissione arrotondata **per eccesso** all'ora
 * piena (un'emissione esattamente allo scoccare dell'ora la tiene). Lo slot orario T copre
 * (T-1h, T], come negli archivi Open-Meteo: la finestra (a, b) somma gli slot che si chiudono
 * da ancora+(a+1)h ad ancora+b h. Cosi' "0-1h" e' la prima ora di orologio intera *dopo*
 * l'emissione: non si sovrappone mai a cio' che il telefono ha gia' visto (il quarto d'ora e il
 * radar dell'ora in corso), e un verdetto emesso alle 10:20 non si prende il merito della pioggia
 * caduta alle 10:30 che conosceva gia'. Arrotondare per difetto avrebbe fatto il contrario: una
 * finestra "0-1h" per meta' nel passato.
 *
 * **La soglia.** Bagnata se la somma degli slot e' almeno 0,2 mm. Sotto, gli archivi orari
 * riportano tracce che nessuno chiamerebbe pioggia; contarle renderebbe il gioco piu' facile da
 * vincere e piu' bugiardo. La soglia e' sull'accumulo di finestra, non sull'ora: tre ore di
 * pioviggine da 0,1 mm fanno una finestra 1-3h bagnata, com'e' giusto — qualcuno si e' bagnato.
 *
 * **I buchi.** Uno slot mancante rende la finestra ingiudicabile (null): un "no" costruito su un
 * buco sarebbe un giudizio inventato, e un "si'" parziale truccherebbe il tasso base.
 *
 * **L'occhio dell'utente.** Un'osservazione "bagnato" dentro uno slot della finestra la rende
 * bagnata, qualunque cosa dicano i modelli (e anche con buchi: l'esito e' noto). Un'osservazione
 * "asciutto" azzera il suo slot — chi ha guardato fuori sa piu' di una mediana di modelli. Se in
 * uno stesso slot ci sono entrambe vince "bagnato": ha visto un fatto, l'altra ha visto un istante.
 * Le osservazioni seguono la stessa convenzione degli slot: quella delle 10:00 appartiene allo slot
 * (9:00, 10:00], quindi un'osservazione fatta all'emissione non entra mai nella sua finestra.
 *
 * **La finalita'.** Un valore dell'archivio si puo' giudicare solo quando non cambia piu': vedi
 * [isFinal] e [TruthPanel.FINALITY_MILLIS].
 */
object RainWindows {

  const val HOUR_MILLIS: Long = 3_600_000L

  /** La soglia dell'evento: millimetri accumulati sulla finestra. */
  const val WET_THRESHOLD_MM: Double = 0.2

  /**
   * Tolleranza del confronto con la soglia. Le somme in virgola mobile di valori a due decimali
   * possono finire un soffio sotto il valore vero (in binario 0,02 + 0,18 fa 0,19999999999999998):
   * una finestra da 0,2 mm deve essere bagnata sul telefono come sul banco, a prescindere
   * dall'ordine della somma. Un milionesimo di millimetro e' mille volte sotto la risoluzione
   * degli archivi (0,01 mm): non cambia nessun esito reale.
   */
  const val SUM_TOLERANCE_MM: Double = 1e-6

  val ZERO_ONE: RainWindow = RainWindow(0, 1)
  val ONE_THREE: RainWindow = RainWindow(1, 3)
  val THREE_SIX: RainWindow = RainWindow(3, 6)

  /** Le tre finestre del verdetto, nell'ordine del verdetto. */
  val ALL: List<RainWindow> = listOf(ZERO_ONE, ONE_THREE, THREE_SIX)

  fun byLabel(label: String): RainWindow? = ALL.firstOrNull { it.label == label }

  /** L'ancora delle finestre: l'emissione arrotondata per eccesso all'ora piena. */
  fun anchorOf(issuedAtMillis: Long): Long = ceilToHour(issuedAtMillis)

  /** La fine dello slot orario che contiene [timestampMillis]: (T-1h, T] contiene T. */
  fun slotEndOf(timestampMillis: Long): Long = ceilToHour(timestampMillis)

  /**
   * L'ultimo slot gia' chiuso a [nowMillis]: e' "l'ora appena conclusa" di chi chiede se sta
   * piovendo adesso. Allo scoccare dell'ora e' lo slot che si chiude in quell'istante.
   */
  fun lastClosedSlotEnd(nowMillis: Long): Long = Math.floorDiv(nowMillis, HOUR_MILLIS) * HOUR_MILLIS

  /** Le fini degli slot della finestra, dall'ancora data. */
  fun slotEnds(anchorMillis: Long, window: RainWindow): LongArray =
    LongArray(window.slotCount) { i -> anchorMillis + (window.fromHours + 1 + i) * HOUR_MILLIS }

  /** La fine dell'ultimo slot della finestra di un'emissione: da qui parte il conto della finalita'. */
  fun lastSlotEnd(issuedAtMillis: Long, window: RainWindow): Long =
    anchorOf(issuedAtMillis) + window.toHours * HOUR_MILLIS

  /**
   * La finestra si puo' giudicare? Solo quando e' passato [finalityMillis] dalla fine del suo
   * ultimo slot: prima, l'archivio puo' ancora riscrivere quelle ore (vedi [TruthPanel]).
   */
  fun isFinal(windowLastSlotEnd: Long, nowMillis: Long, finalityMillis: Long): Boolean =
    nowMillis >= windowLastSlotEnd + finalityMillis

  /** La soglia applicata a una somma, con la tolleranza della virgola mobile. */
  fun isWet(sumMm: Double, thresholdMm: Double = WET_THRESHOLD_MM): Boolean =
    sumMm >= thresholdMm - SUM_TOLERANCE_MM

  /**
   * Il giudizio della finestra di un'emissione.
   *
   * [slotMm] restituisce il valore di verita' dello slot che si chiude all'istante dato (mm),
   * o null se manca; [sightings] sono le osservazioni dell'utente gia' filtrate per luogo.
   * Null quando la finestra non si puo' giudicare.
   */
  fun evaluate(
    issuedAtMillis: Long,
    window: RainWindow,
    slotMm: (Long) -> Double?,
    sightings: List<Sighting> = emptyList(),
    thresholdMm: Double = WET_THRESHOLD_MM,
  ): WindowOutcome? = evaluateFromAnchor(anchorOf(issuedAtMillis), window, slotMm, sightings, thresholdMm)

  /** Solo l'esito: bagnata, asciutta, o null se ingiudicabile. */
  fun outcome(
    issuedAtMillis: Long,
    window: RainWindow,
    slotMm: (Long) -> Double?,
    sightings: List<Sighting> = emptyList(),
    thresholdMm: Double = WET_THRESHOLD_MM,
  ): Boolean? = evaluate(issuedAtMillis, window, slotMm, sightings, thresholdMm)?.wet

  /**
   * Lo stesso giudizio da un'ancora gia' fissata, senza arrotondare.
   *
   * Serve a chi lavora su una griglia oraria propria: il banco emette sempre sugli istanti dei
   * suoi record, che per gli archivi Open-Meteo sono ore piene — li' l'ancora coincide con
   * l'emissione e questo e' esattamente [evaluate]. Le osservazioni si assegnano agli slot
   * contati dall'ancora, cosi' la regola resta la stessa anche su una griglia sfasata.
   */
  fun evaluateFromAnchor(
    anchorMillis: Long,
    window: RainWindow,
    slotMm: (Long) -> Double?,
    sightings: List<Sighting> = emptyList(),
    thresholdMm: Double = WET_THRESHOLD_MM,
  ): WindowOutcome? {
    val ends = slotEnds(anchorMillis, window)
    val first = ends.first()
    val last = ends.last()

    var inside = 0
    val wetSlots = HashSet<Long>()
    val drySlots = HashSet<Long>()
    for (sighting in sightings) {
      // Lo slot dell'osservazione contato dall'ancora: (fine-1h, fine].
      val offset = sighting.timestampMillis - anchorMillis
      val end = anchorMillis + ceilDiv(offset, HOUR_MILLIS) * HOUR_MILLIS
      if (end < first || end > last) continue
      inside++
      if (sighting.wet) wetSlots += end else drySlots += end
    }
    drySlots -= wetSlots

    var sum = 0.0
    var complete = true
    for (end in ends) {
      val value = if (end in drySlots) 0.0 else slotMm(end)?.takeUnless { it.isNaN() }
      if (value == null) {
        complete = false
      } else {
        sum += value
      }
    }

    if (wetSlots.isNotEmpty()) {
      return WindowOutcome(wet = true, sumMm = if (complete) sum else null, sightings = inside)
    }
    if (!complete) return null
    return WindowOutcome(wet = isWet(sum, thresholdMm), sumMm = sum, sightings = inside)
  }

  /** Solo l'esito, dall'ancora data: e' il gancio del banco (`PrecipitationEvents`). */
  fun outcomeFromAnchor(
    anchorMillis: Long,
    window: RainWindow,
    slotMm: (Long) -> Double?,
    sightings: List<Sighting> = emptyList(),
    thresholdMm: Double = WET_THRESHOLD_MM,
  ): Boolean? = evaluateFromAnchor(anchorMillis, window, slotMm, sightings, thresholdMm)?.wet

  private fun ceilToHour(millis: Long): Long = ceilDiv(millis, HOUR_MILLIS) * HOUR_MILLIS

  private fun ceilDiv(a: Long, b: Long): Long = -Math.floorDiv(-a, b)
}
