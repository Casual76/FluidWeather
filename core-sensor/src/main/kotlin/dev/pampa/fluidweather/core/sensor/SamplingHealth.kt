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
 * Il campionamento e' ancora vivo?
 *
 * Nasce da un baco vero: dopo qualche giorno l'app tornava a dire "da tarare" e la barra restava a
 * "0 ore di 13" per sempre. Quel numero non e' la taratura — e' l'ampiezza della storia
 * barometrica pulita nelle ultime 24 ore, e **non e' salvato da nessuna parte**. Se il
 * campionamento si ferma (un gestore di batteria che cancella il lavoro periodico, un DataStore
 * corrotto che fa fallire `applyCurrentMode`, un allarme esatto revocato), dopo un giorno la
 * finestra si svuota e quel numero torna a zero e non risale piu'. Svuotare la cache non serve —
 * WorkManager, Room e le preferenze stanno nei *dati* — e l'unico rimedio rimasto e' cancellare i
 * dati dell'app. E' esattamente quello che e' successo.
 *
 * Nessuno, prima, verificava che il lavoro schedulato esistesse ancora. Questo lo fa: a ogni
 * avvio e a ogni giro del ciclo, due domande e una rischedulazione.
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
    /** Il meccanismo previsto dalla modalita' risulta ancora armato. */
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
   * Non lancia mai: e' un controllo di salute, e un controllo di salute che fa cadere il chiamante
   * e' il primo posto in cui guardare quando qualcosa non torna.
   */
  suspend fun check(nowMillis: Long = System.currentTimeMillis()): Report {
    val report = runCatching { inspect(nowMillis) }.getOrElse { error ->
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

  private suspend fun inspect(nowMillis: Long): Report {
    val mode = settingsStore.current().mode
    val lastSample = runCatching { repository.latestSampleMillis() }.getOrNull()
    val scheduled = isArmed(mode)
    // Tre cadenze e non una: una passata saltata e' Doze che fa il suo mestiere, tre di fila no.
    // Con l'archivio vuoto non si giudica il silenzio — non c'e' ancora niente da cui misurarlo.
    val silent = lastSample != null &&
      nowMillis - lastSample > SILENCE_FACTOR * maxOf(mode.cadenceMinutes, MIN_CADENCE_MINUTES) * 60_000L
    val rescheduled = if (!scheduled || silent) {
      runCatching { scheduler.apply(mode) }.isSuccess
    } else {
      false
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
   * esatti (e un `PendingIntent` con `FLAG_NO_CREATE` dice se ne esiste ancora uno), tutte le
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
