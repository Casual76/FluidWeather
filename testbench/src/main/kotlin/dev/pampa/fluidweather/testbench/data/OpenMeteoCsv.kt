package dev.pampa.fluidweather.testbench.data

/**
 * Una riga di una risposta CSV di Open-Meteo. I valori restano stringhe: "" vuol dire assente
 * (l'API scrive "NaN" dove un modello non ha il dato). Tenerli stringa evita di riformattare un
 * numero che il provider ha gia' scritto come voleva, e il file salvato dice quello che e' arrivato.
 */
class OpenMeteoRow(
  /** Presente solo nelle risposte a piu' localita' (`location_id`, 0-based nell'ordine richiesto). */
  val locationId: Int?,
  val epochSeconds: Long,
  val cells: List<String>,
) {
  fun number(index: Int): Double? = cells.getOrNull(index)?.toDoubleOrNull()?.takeUnless { it.isNaN() }
}

/**
 * Una risposta `format=csv` di Open-Meteo, letta senza indovinare: righe di metadati, una riga
 * vuota, l'intestazione (`time,...` oppure `location_id,time,...`), le righe.
 *
 * I nomi di colonna sono quelli dell'API senza l'unita' fra parentesi ("precipitation (mm)" ->
 * "precipitation"). Per le risposte a piu' modelli l'API aggiunge "_<modello>" a ogni variabile:
 * chi sa di aver chiesto un solo modello lo passa a [parse] e il suffisso sparisce; chi ne ha chiesti
 * piu' di uno lo lascia e cerca con [columnIndex].
 */
class OpenMeteoCsv(
  val columns: List<String>,
  val rows: List<OpenMeteoRow>,
  val hasLocationId: Boolean,
) {

  /**
   * Indice di una variabile fra le colonne (senza contare `time`): il nome esatto se c'e',
   * altrimenti "<variabile>_<modello>". Niente corrispondenze per prefisso: "precipitation" non
   * deve mai pescare "precipitation_probability".
   */
  fun columnIndex(variable: String, model: String? = null): Int {
    val exact = columns.indexOf(variable)
    if (exact >= 0) return exact
    if (model != null) {
      val suffixed = columns.indexOf("${variable}_$model")
      if (suffixed >= 0) return suffixed
    }
    error("colonna '$variable' assente (modello ${model ?: "-"}); colonne: $columns")
  }

  companion object {

    private val UNIT_SUFFIX = Regex("""\s*\([^)]*\)\s*$""")

    /** "precipitation (mm)" -> "precipitation"; "pressure_msl_ukmo_seamless (hPa)" resta col suffisso. */
    fun stripUnit(raw: String): String = raw.trim().replace(UNIT_SUFFIX, "")

    fun parse(text: String, model: String? = null): OpenMeteoCsv {
      val lines = text.lineSequence().map { it.trimEnd('\r') }.toList()
      val headerIndex = lines.indexOfFirst { line ->
        val cells = line.split(",")
        cells[0] == "time" || (cells[0] == "location_id" && cells.getOrNull(1) == "time")
      }
      require(headerIndex >= 0) { "intestazione 'time' mancante: ${text.take(200)}" }

      val header = lines[headerIndex].split(",")
      val hasLocation = header[0] == "location_id"
      val first = if (hasLocation) 2 else 1
      val columns = header.drop(first).map { raw ->
        val name = stripUnit(raw)
        if (model != null && name.endsWith("_$model")) name.removeSuffix("_$model") else name
      }

      val rows = ArrayList<OpenMeteoRow>()
      for (index in headerIndex + 1 until lines.size) {
        val line = lines[index]
        if (line.isBlank()) continue
        val cells = line.split(",")
        // Un secondo blocco di metadati (le vecchie risposte a piu' localita' ne ripetevano uno
        // per localita') non ha un istante numerico in prima colonna: lo si salta.
        val timeCell = cells.getOrNull(if (hasLocation) 1 else 0) ?: continue
        val time = timeCell.toLongOrNull() ?: continue
        val location = if (hasLocation) cells[0].toIntOrNull() ?: continue else null
        val values = cells.drop(first).map { cell ->
          val number = cell.trim().toDoubleOrNull()
          if (number == null || number.isNaN()) "" else cell.trim()
        }
        rows += OpenMeteoRow(location, time, values)
      }
      return OpenMeteoCsv(columns, rows, hasLocation)
    }
  }
}
