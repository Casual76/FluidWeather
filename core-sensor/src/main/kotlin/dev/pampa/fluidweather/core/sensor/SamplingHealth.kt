package dev.pampa.fluidweather.core.sensor

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.pampa.fluidweather.core.data.PressureRepository
import dev.pampa.fluidweather.core.data.SamplingSettingsStore
import dev.pampa.fluidweather.core.model.SamplingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Checks local sampling history and scheduling evidence, then requests recovery when needed.
 * A stale history establishes missing recent samples, not why a particular device stopped.
 * Exact-alarm PendingIntent identity is only weak evidence; see the sensor recovery contract.
 */
class SamplingHealth(
  private val context: Context,
  private val settingsStore: SamplingSettingsStore,
  private val repository: PressureRepository,
  private val scheduler: SamplingScheduler,
) {

  /**
   * Cosa si e' visto all'ultimo controllo. Non e' telemetria: la Diagnostica la mostra, ed e' la
   * differenza fra "l'app e' rotta" e "il campionamento e' fermo da martedi', ecco perche'".
   */
  data class Report(
    val checkedAtMillis: Long,
    val mode: SamplingMode?,
    val lastSampleAtMillis: Long?,
    /** Scheduling evidence only: an alarm PendingIntent does not prove a future armed alarm. */
    val scheduled: Boolean,
    /** L'archivio non riceve un campione da troppo tempo, qualunque cosa dica lo scheduler. */
    val silent: Boolean,
    /** Si e' dovuto rimettere in piedi qualcosa. */
    val rescheduled: Boolean,
    /** Il controllo stesso e' fallito: e' un sintomo, non un dettaglio da nascondere. */
    val failure: String? = null,
  )

  private val _last = MutableStateFlow<Report?>(null)

  val last: StateFlow<Report?> = _last.asStateFlow()

  /**
   * Propaga la cancellazione; gli altri errori diventano Report.failure. Un controllo che fa cadere il chiamante
   * e' il primo posto in cui guardare quando qualcosa non torna.
   *
   * [foreground] = true lo chiama l'app aperta davanti all'utente: li', e solo li', un silenzio si
   * cura ripartendo da capo e con una passata subito. Dal worker no — cancellare e rimettere in
   * coda il lavoro periodico da dentro il lavoro stesso vorrebbe dire cancellare la passata in
   * corso; e all'avvio del processo nemmeno, perche' il processo puo' essere nato proprio per
   * quel worker.
   */
  suspend fun check(nowMillis: Long = System.currentTimeMillis(), foreground: Boolean = false): Report {
    val report = runCatching { inspect(nowMillis, foreground) }.getOrElse { error ->
      if (error is kotlinx.coroutines.CancellationException) throw error
      Report(
        checkedAtMillis = nowMillis,
        mode = null,
        lastSampleAtMillis = null,
        scheduled = false,
        silent = true,
        rescheduled = false,
        failure = error.message ?: error::class.java.simpleName,
      )
    }
    _last.value = report
    return report
  }

  private suspend fun inspect(nowMillis: Long, foreground: Boolean): Report {
    val mode = settingsStore.current().mode
    val lastSample = optionalContext { repository.latestSampleMillis() }
    val scheduled = isArmed(mode)
    // Tre cadenze e non una: una passata saltata e' Doze che fa il suo mestiere, tre di fila no.
    // Con l'archivio vuoto non si giudica il silenzio — non c'e' ancora niente da cui misurarlo.
    val silent = lastSample != null &&
      nowMillis - lastSample > SILENCE_FACTOR * maxOf(mode.cadenceMinutes, MIN_CADENCE_MINUTES) * 60_000L
    val rescheduled = when {
      // L'app e' aperta e l'archivio tace: il lavoro "c'e'" ma non gira (sospensione, bucket).
      // Si riparte da capo e si campiona adesso, cosi' la storia riprende da questo momento.
      foreground && silent -> {
        scheduler.apply(mode, restart = true)
        scheduler.passNow()
        true
      }
      !scheduled || silent -> { scheduler.apply(mode); true }
      else -> false
    }
    return Report(
      checkedAtMillis = nowMillis,
      mode = mode,
      lastSampleAtMillis = lastSample,
      scheduled = scheduled,
      silent = silent,
      rescheduled = rescheduled,
    )
  }

  /**
   * Il meccanismo giusto per la modalita' e' ancora armato.
   *
   * Sono due meccanismi diversi e due domande diverse: MASSIMA vive su una catena di allarmi
   * esatti (il PendingIntent e' solo un token, non prova di un allarme futuro), tutte le
   * altre sul lavoro periodico di WorkManager.
   */
  private suspend fun isArmed(mode: SamplingMode): Boolean {
    if (mode == SamplingMode.MASSIMA && MaximaAlarm.canSchedule(context)) return maximaAlarmExists()
    val infos = WorkManager.getInstance(context)
      .getWorkInfosForUniqueWorkFlow(SamplingScheduler.WORK_NAME)
      .first()
    return infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
  }

  private fun maximaAlarmExists(): Boolean = PendingIntent.getBroadcast(
    context,
    0,
    Intent(context, MaximaAlarmReceiver::class.java),
    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
  ) != null

  private companion object {
    /** Sotto questa cadenza non si scende comunque: e' il minimo periodico di WorkManager. */
    const val MIN_CADENCE_MINUTES = 15

    const val SILENCE_FACTOR = 3L
  }
}
