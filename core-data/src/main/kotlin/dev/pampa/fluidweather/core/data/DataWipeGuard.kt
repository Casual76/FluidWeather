package dev.pampa.fluidweather.core.data

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * La mutua esclusione fra "cancella tutti i dati" e chi scrive in background (registratore dei
 * giri, giudice della verita', ristima di Platt, climatologia locale).
 *
 * Il problema: chi scrive legge lo stato, fa un lavoro lungo (una rete, un calcolo) e poi scrive.
 * Se nel frattempo l'utente cancella tutto, la scrittura arriva dopo la cancellazione e resuscita
 * dati che la persona ha chiesto di perdere: uno stato che nessuno vede ma che e' stato salvato.
 * Qui ogni scrittore fotografa la [generation] prima del lavoro e scrive con [writeIfUnchanged]:
 * se nel frattempo e' passata una cancellazione la scrittura si salta, e chi chiama riparte da zero.
 *
 * **Il lucchetto copre solo le scritture, mai la rete.** Un giro di rete sotto il mutex
 * bloccherebbe la cancellazione (e l'utente che la chiede) per tutta la sua durata. Il mutex non e'
 * rientrante: nessun blocco protetto ne chiama un altro, altrimenti si blocca da solo.
 */
class DataWipeGuard {

  private val generation = AtomicLong(0L)
  private val mutex = Mutex()

  /** La generazione di adesso: da leggere PRIMA del lavoro che precede una scrittura. */
  fun generation(): Long = generation.get()

  /**
   * Esegue [write] se, da quando e' stata letta [observed], nessuna cancellazione e' passata;
   * altrimenti ritorna null senza scrivere. Attende una cancellazione in corso.
   */
  suspend fun <T> writeIfUnchanged(observed: Long, write: suspend () -> T): T? =
    mutex.withLock {
      if (generation.get() != observed) null else write()
    }

  /** Cancella: alza la generazione (chi scriveva con quella vecchia si fermera') e poi esegue [clear]. */
  suspend fun wipe(clear: suspend () -> Unit) {
    mutex.withLock {
      generation.incrementAndGet()
      clear()
    }
  }
}
