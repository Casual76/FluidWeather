package dev.pampa.fluidweather.core.model

/** Quanto e' vecchio l'ultimo giro dei provider che sta in scena. */
enum class DataFreshness {
  /** Di adesso: non c'e' niente da dire. */
  FRESH,

  /** Vecchio ma ancora informativo: si dice da quanto. */
  STALE,

  /** Cosi' vecchio che "adesso" era un'altra parte della giornata: si dice di quando e'. */
  VERY_STALE,

  /** Non c'e' proprio niente da mostrare. */
  NONE,
}

/**
 * L'eta' dei dati che vengono dalla rete, e le due soglie che la rendono una frase.
 *
 * Sta in `core-model` e non in `core-ui` perche' la stessa decisione serve a tre posti diversi: la
 * testata della home, le notifiche (`core-cycle`, che non conosce Compose e non deve iniziare a
 * conoscerlo per decidere se annunciare la pioggia) e il widget di sistema. Un solo numero, o
 * l'app e il centro notifiche finiscono per non essere d'accordo su cosa vuol dire "fresco".
 *
 * Non dice **mai** "offline": non lo sappiamo. I provider possono essere raggiungibili e
 * rispondere tutti 500, o limitare la frequenza, o essere bloccati da un firewall. La conseguenza
 * per chi guarda e' la stessa in ogni caso — nessun dato nuovo — e c'e' una sola frase onesta per
 * tutti, che e' quella sull'eta'.
 */
object DataAge {

  /**
   * Novanta minuti.
   *
   * E' il tempo di **almeno tre giri saltati in qualunque modalita' di campionamento**: le cadenze
   * sono 5, 15, 20 e 30 minuti, quindi anche la piu' lenta ha avuto tre occasioni. Un dato che ha
   * mancato tre appuntamenti non e' un ritardo, e' un'assenza. E' lo stesso numero di
   * [NOW_WINDOW_MILLIS] per la stessa ragione, ma risponde a un'altra domanda: quello dice se
   * un'ora prevista e' ancora "adesso", questo se il giro e' ancora fresco.
   */
  const val STALE_AFTER_MILLIS: Long = 90 * 60_000L

  /**
   * Sei ore.
   *
   * Oltre, l'ora che la testata chiama "adesso" e' una previsione fatta in un'altra parte della
   * giornata — stamattina per il pomeriggio, o ieri sera per stamattina. Li' "aggiornato 9 ore fa"
   * costringe chi legge a fare i conti, mentre un orario non lo fa: quindi da qui in poi si dice
   * *quando*, non *da quanto*.
   */
  const val VERY_STALE_AFTER_MILLIS: Long = 6 * 3_600_000L

  /**
   * Oltre quanto un'istantanea non si mostra nemmeno come ripiego: **sette giorni**.
   *
   * Era dodici ore, scritta due volte (la home e il ciclo in background) e privata in tutte e due:
   * un giorno senza rete e l'app non mostrava piu' niente, proprio l'app che nasce per funzionare
   * senza rete. Ma un taglio muto e' la cosa sbagliata da fare in ogni caso: qui c'e' gia' una
   * macchina che dichiara l'eta' — [of], e la frase che ne esce — ed e' quella che deve decidere
   * cosa vale la pena guardare, non una soglia che fa sparire tutto senza spiegare.
   *
   * Sette giorni e non "mai" perche' una previsione oraria della settimana scorsa non descrive
   * piu' niente: le ore che conteneva sono tutte passate, e la testata non avrebbe nemmeno un
   * "adesso" da mostrare.
   */
  const val SHOWABLE_AGE_MILLIS: Long = 7 * 24 * 3_600_000L

  fun of(dataAtMillis: Long?, nowMillis: Long): DataFreshness {
    val age = ageMillis(dataAtMillis, nowMillis) ?: return DataFreshness.NONE
    return when {
      age < STALE_AFTER_MILLIS -> DataFreshness.FRESH
      age < VERY_STALE_AFTER_MILLIS -> DataFreshness.STALE
      else -> DataFreshness.VERY_STALE
    }
  }

  /**
   * L'eta' in millisecondi, mai negativa.
   *
   * Il taglio a zero non e' pignoleria: l'orologio del telefono si sposta (fuso, rete, mano
   * dell'utente), e un'eta' negativa diventerebbe "aggiornato fra due ore".
   */
  fun ageMillis(dataAtMillis: Long?, nowMillis: Long): Long? =
    dataAtMillis?.let { (nowMillis - it).coerceAtLeast(0L) }

  /**
   * Le ore su cui si puo' decidere **da soli**: nessuna, se il giro non e' fresco.
   *
   * Mostrare una previsione vecchia dichiarandola e' onesto; farci nascere una notifica non lo e'.
   * Annunciare "la pioggia inizia alle 16" leggendo un bundle di cinque ore fa e' una frase giusta
   * con l'orologio sbagliato, e chi la riceve non ha modo di accorgersene. Il barometro invece non
   * passa di qui: quello e' il sensore di questo telefono, non aspetta nessuno, e proprio senza
   * rete e' la cosa che serve di piu'.
   */
  fun hoursForDecisions(hours: List<FusedHour>, dataAtMillis: Long?, nowMillis: Long): List<FusedHour> =
    if (of(dataAtMillis, nowMillis) == DataFreshness.FRESH) hours else emptyList()
}
