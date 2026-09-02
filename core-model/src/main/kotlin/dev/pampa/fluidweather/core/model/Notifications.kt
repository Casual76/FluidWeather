package dev.pampa.fluidweather.core.model

/**
 * I quattro canali Android del piano (§1, "Notifiche"), coi loro default. Gli id sono stabili:
 * Android li usa per ricordare le scelte dell'utente nelle impostazioni di sistema, e cambiarli
 * significherebbe azzerarle.
 */
enum class NotificationChannelKind(
  val id: String,
  val defaultEnabled: Boolean,
  /** Importanza alta = suono e heads-up; altrimenti default (suono, niente heads-up). */
  val highImportance: Boolean,
) {
  NOWCAST_ALERT(
    id = "nowcast-alert",
    defaultEnabled = true,
    highImportance = true,
  ),
  PRECIPITATION(
    id = "precipitation",
    defaultEnabled = true,
    highImportance = false,
  ),
  OFFICIAL_ALERTS(
    id = "official-alerts",
    defaultEnabled = false,
    highImportance = true,
  ),
  DAILY_SUMMARY(
    id = "daily-summary",
    defaultEnabled = false,
    highImportance = false,
  ),
}

/** Le scelte dell'utente sui canali; i default sono quelli della tabella del piano. */
data class NotificationSettings(
  val nowcastAlert: Boolean = NotificationChannelKind.NOWCAST_ALERT.defaultEnabled,
  val precipitation: Boolean = NotificationChannelKind.PRECIPITATION.defaultEnabled,
  val officialAlerts: Boolean = NotificationChannelKind.OFFICIAL_ALERTS.defaultEnabled,
  val dailySummary: Boolean = NotificationChannelKind.DAILY_SUMMARY.defaultEnabled,
  /** L'ora del riepilogo, scelta dall'utente: le 7:30 sono "prima di uscire". */
  val summaryHour: Int = 7,
  val summaryMinute: Int = 30,
) {
  fun enabled(kind: NotificationChannelKind): Boolean = when (kind) {
    NotificationChannelKind.NOWCAST_ALERT -> nowcastAlert
    NotificationChannelKind.PRECIPITATION -> precipitation
    NotificationChannelKind.OFFICIAL_ALERTS -> officialAlerts
    NotificationChannelKind.DAILY_SUMMARY -> dailySummary
  }
}

/**
 * Cosa e' gia' stato detto: la memoria che impedisce di ripetersi. Vive su disco perche' il
 * ciclo gira in un processo che puo' nascere e morire fra una passata e l'altra.
 */
data class NotificationLedger(
  /** L'ultima allerta del barometro: quando, con che probabilita', e se e' ancora in piedi. */
  val nowcastAlertAtMillis: Long? = null,
  val nowcastAlertProbability: Double? = null,
  val nowcastAlertActive: Boolean = false,
  /** L'inizio e la fine di precipitazione gia' annunciati (ora dell'evento, non dell'avviso). */
  val precipitationOnsetMillis: Long? = null,
  val precipitationEndMillis: Long? = null,
  /** Gli identificativi delle allerte ufficiali gia' notificate, dalla piu' vecchia. */
  val officialAlertIds: List<String> = emptyList(),
  /** Il giorno (epoch day locale) dell'ultimo riepilogo inviato. */
  val summaryEpochDay: Long? = null,
  /** L'ultimo ciclo in background: quando e cosa ha fatto, per la diagnostica. */
  val lastCycleAtMillis: Long? = null,
  val lastCycleNote: String? = null,
) {
  companion object {
    /** Oltre questi identificativi si dimenticano i piu' vecchi: le allerte scadono comunque. */
    const val MAX_OFFICIAL_IDS = 60
  }
}

/** Una notifica pronta da consegnare: chi la consegna (sistema o banner in-app) decide come. */
data class AppNotification(
  val channel: NotificationChannelKind,
  /** Stabile per canale, cosi' un aggiornamento sostituisce la precedente invece di affiancarla. */
  val id: Int,
  val title: String,
  val text: String,
  val bigText: String? = null,
)

enum class OfficialAlertSource(val label: String) {
  NWS("National Weather Service (NOAA)"),
  METEOALARM("Meteoalarm"),
}

/**
 * Un'allerta ufficiale cosi' com'e' stata emessa: testo non reinterpretato, fonte in chiaro.
 * [id] e' l'identificativo del servizio, stabile per la vita dell'avviso.
 */
data class OfficialAlert(
  val id: String,
  val source: OfficialAlertSource,
  val event: String,
  val severity: String?,
  val headline: String?,
  val description: String?,
  val instruction: String?,
  val areaDescription: String?,
  val sender: String?,
  val onsetMillis: Long?,
  val expiresMillis: Long?,
  val link: String?,
) {
  fun isActive(nowMillis: Long): Boolean = expiresMillis == null || expiresMillis > nowMillis
}
