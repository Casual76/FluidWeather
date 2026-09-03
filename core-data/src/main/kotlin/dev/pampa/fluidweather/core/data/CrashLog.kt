package dev.pampa.fluidweather.core.data

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Un errore registrato: quando, dove, e la traccia per intero. */
data class CrashRecord(
  val atMillis: Long,
  val label: String,
  val summary: String,
  val stackTrace: String,
) {
  val time: String get() = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(atMillis))
}

/**
 * Il quaderno degli errori dell'app.
 *
 * Esiste perche' un'app in prova su un telefono, senza un computer attaccato, quando cade non
 * lascia niente: chi la usa puo' solo dire "e' crashata". Qui la traccia finisce su un file nella
 * cartella privata dell'app, e la Diagnostica la mostra e la fa copiare. Tiene gli ultimi
 * [MAX_RECORDS]: piu' di cosi' non serve a nessuno, e un file che cresce e' un altro problema.
 *
 * Registra anche gli errori **catturati** ([record]) — quelli che non fanno cadere l'app ma
 * lasciano un'operazione a meta': una verifica di chiave che va storta racconta di piu' di un
 * "verifica non riuscita" sullo schermo.
 */
class CrashLog(private val file: File) {

  private val state = MutableStateFlow<List<CrashRecord>>(emptyList())
  val records: StateFlow<List<CrashRecord>> = state

  /** Da chiamare per primo in Application.onCreate: prende il posto del gestore di sistema. */
  fun install(versionName: String) {
    load()
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
      // Scritto in modo sincrono: fra un attimo il processo non c'e' piu'.
      runCatching { write(record(error, "$versionName · ${thread.name}", persist = false)) }
      previous?.uncaughtException(thread, error)
    }
  }

  /** Registra un errore catturato, con l'etichetta di dove e' successo. */
  fun record(error: Throwable, label: String, persist: Boolean = true): List<CrashRecord> {
    val trace = StringWriter().also { writer -> error.printStackTrace(PrintWriter(writer)) }.toString()
    val entry = CrashRecord(
      atMillis = System.currentTimeMillis(),
      label = label,
      summary = "${error::class.java.simpleName}: ${error.message ?: "-"}",
      stackTrace = trace.take(MAX_TRACE_CHARS),
    )
    val updated = (listOf(entry) + state.value).take(MAX_RECORDS)
    state.value = updated
    if (persist) runCatching { write(updated) }
    return updated
  }

  /** Vero se c'e' un errore che l'utente non ha ancora visto in Diagnostica. */
  fun unseen(sinceMillis: Long): CrashRecord? = state.value.firstOrNull { it.atMillis > sinceMillis }

  fun clear() {
    state.value = emptyList()
    runCatching { if (file.exists()) file.delete() }
  }

  private fun load() {
    val text = runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
    if (text.isBlank()) return
    state.value = text.split(SEPARATOR).mapNotNull { block ->
      val lines = block.trim().lines()
      if (lines.size < 3) return@mapNotNull null
      val at = lines[0].toLongOrNull() ?: return@mapNotNull null
      CrashRecord(at, lines[1], lines[2], lines.drop(3).joinToString("\n"))
    }
  }

  private fun write(records: List<CrashRecord>) {
    file.parentFile?.mkdirs()
    file.writeText(
      records.joinToString(SEPARATOR) { record ->
        listOf(record.atMillis.toString(), record.label, record.summary, record.stackTrace).joinToString("\n")
      },
    )
  }

  companion object {
    const val MAX_RECORDS = 5
    private const val MAX_TRACE_CHARS = 8_000
    private const val SEPARATOR = "\n===fluidweather-crash===\n"
  }
}
