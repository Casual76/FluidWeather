package dev.pampa.fluidweather.core.cycle

import dev.pampa.fluidweather.core.model.AppNotification
import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.NotificationChannelKind
import dev.pampa.fluidweather.core.model.NotificationLedger
import dev.pampa.fluidweather.core.model.NotificationSettings
import dev.pampa.fluidweather.core.model.OfficialAlert
import dev.pampa.fluidweather.core.model.WeatherKindLabels
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Tutto quello che la politica guarda: lo stato del mondo e la memoria di cio' che ha detto. */
data class AlertInputs(
  val nowMillis: Long,
  val zone: ZoneId,
  val settings: NotificationSettings,
  val ledger: NotificationLedger,
  val verdict: NowcastVerdict?,
  val fusedHours: List<FusedHour>,
  val officialAlerts: List<OfficialAlert>,
)

/** Cosa consegnare, cosa ritirare, e la memoria aggiornata da scrivere. */
data class AlertDecision(
  val notifications: List<AppNotification>,
  /** L'allerta del barometro e' rientrata: la notifica in tendina va tolta. */
  val cancelNowcastAlert: Boolean,
  val ledger: NotificationLedger,
)

/**
 * La politica delle notifiche: pura, deterministica, con la memoria esplicita. Decide per tre
 * dei quattro canali (il riepilogo ha il suo orologio, vedi [DailySummary]) e rispetta le
 * impostazioni PRIMA di guardare i dati: un canale spento non produce niente, nemmeno memoria.
 *
 * Le regole anti-rumore, tutte dichiarate:
 *  - allerta del barometro: solo al livello ALLERTA (decisione 2026-09-02); si ripete solo se
 *    sono passate [NOWCAST_REPEAT_MILLIS] o se la probabilita' e' salita di [NOWCAST_ESCALATION];
 *    quando il livello rientra, la notifica si ritira;
 *  - inizio/fine precipitazione: un evento si annuncia una volta; un altro evento e' tale se
 *    dista piu' di [EVENT_DISTINCT_MILLIS] da quello gia' annunciato;
 *  - allerte ufficiali: ogni identificativo una volta sola, al massimo [MAX_OFFICIAL_PER_ROUND]
 *    per giro (le altre al giro dopo: non e' un bollettino, e' una tendina).
 */
object AlertPolicy {

  const val NOWCAST_REPEAT_MILLIS: Long = 3 * 3_600_000L
  const val NOWCAST_ESCALATION: Double = 0.20
  const val EVENT_DISTINCT_MILLIS: Long = 2 * 3_600_000L
  const val MAX_OFFICIAL_PER_ROUND: Int = 3

  const val NOWCAST_ID = 100
  const val PRECIPITATION_ID = 200
  const val OFFICIAL_ID_BASE = 300
  const val SUMMARY_ID = 400

  fun decide(inputs: AlertInputs): AlertDecision {
    val notifications = mutableListOf<AppNotification>()
    var ledger = inputs.ledger
    var cancelNowcast = false

    // ------------------------------------------------------------- allerta del barometro
    val verdict = inputs.verdict
    if (inputs.settings.nowcastAlert) {
      if (verdict != null && verdict.level == AlertLevel.ALLERTA) {
        val strongest = verdict.windows.maxByOrNull { it.probability }
        if (strongest != null) {
          val lastAt = ledger.nowcastAlertAtMillis
          val lastProbability = ledger.nowcastAlertProbability ?: 0.0
          val due = lastAt == null ||
            inputs.nowMillis - lastAt >= NOWCAST_REPEAT_MILLIS ||
            strongest.probability >= lastProbability + NOWCAST_ESCALATION
          if (due) {
            val factors = strongest.topFactors.take(2).joinToString(", ") { factor ->
              factor.name + if (factor.contribution > 0) " ▲" else " ▼"
            }
            notifications += AppNotification(
              channel = NotificationChannelKind.NOWCAST_ALERT,
              id = NOWCAST_ID,
              title = "Allerta del barometro",
              text = "Pioggia probabile ${windowPhrase(strongest.window)}: ${(strongest.probability * 100).toInt()}%",
              bigText = buildString {
                append("Il barometro del telefono vede arrivare la pioggia ")
                append(windowPhrase(strongest.window))
                append(" (probabilita' ${(strongest.probability * 100).toInt()}%, banda ")
                append("${(strongest.probabilityLow * 100).toInt()}-${(strongest.probabilityHigh * 100).toInt()}%).")
                if (factors.isNotBlank()) append("\nFattori: $factors.")
              },
            )
            ledger = ledger.copy(
              nowcastAlertAtMillis = inputs.nowMillis,
              nowcastAlertProbability = strongest.probability,
              nowcastAlertActive = true,
            )
          }
        }
      } else if (ledger.nowcastAlertActive) {
        cancelNowcast = true
        ledger = ledger.copy(nowcastAlertActive = false)
      }
    }

    // ------------------------------------------------------ inizio/fine precipitazione
    if (inputs.settings.precipitation) {
      val transition = PrecipitationTransitions.next(inputs.fusedHours, inputs.nowMillis)
      if (transition != null) {
        val already = when (transition.kind) {
          TransitionKind.ONSET -> ledger.precipitationOnsetMillis
          TransitionKind.END -> ledger.precipitationEndMillis
        }
        val isNewEvent = already == null || abs(transition.atMillis - already) > EVENT_DISTINCT_MILLIS
        if (isNewEvent) {
          val time = HourMinute.withZone(inputs.zone).format(Instant.ofEpochMilli(transition.atMillis))
          val noun = WeatherKindLabels.precipitationNoun(transition.weatherKind)
          notifications += when (transition.kind) {
            TransitionKind.ONSET -> AppNotification(
              channel = NotificationChannelKind.PRECIPITATION,
              id = PRECIPITATION_ID,
              title = "$noun in arrivo",
              text = "Inizia verso le $time dove sei.",
            )
            TransitionKind.END -> AppNotification(
              channel = NotificationChannelKind.PRECIPITATION,
              id = PRECIPITATION_ID,
              title = "$noun in esaurimento",
              text = "Smette verso le $time.",
            )
          }
          ledger = when (transition.kind) {
            TransitionKind.ONSET -> ledger.copy(precipitationOnsetMillis = transition.atMillis)
            TransitionKind.END -> ledger.copy(precipitationEndMillis = transition.atMillis)
          }
        }
      }
    }

    // ------------------------------------------------------------------ allerte ufficiali
    if (inputs.settings.officialAlerts) {
      val fresh = inputs.officialAlerts
        .filter { it.isActive(inputs.nowMillis) && it.id !in ledger.officialAlertIds }
        .distinctBy { it.id }
      fresh.take(MAX_OFFICIAL_PER_ROUND).forEach { alert ->
        notifications += AppNotification(
          channel = NotificationChannelKind.OFFICIAL_ALERTS,
          id = OFFICIAL_ID_BASE + (alert.id.hashCode() and 0xFFFF),
          title = alert.event,
          text = alert.headline ?: alert.areaDescription ?: alert.source.label,
          bigText = buildString {
            alert.headline?.let { append(it).append("\n\n") }
            alert.description?.let { append(it).append("\n\n") }
            alert.instruction?.let { append(it).append("\n\n") }
            alert.areaDescription?.let { append("Area: ").append(it).append("\n") }
            append("Fonte: ").append(alert.source.label)
            alert.sender?.let { append(" · ").append(it) }
            append("\nTesto riportato com'e' stato emesso, senza reinterpretazione.")
          },
        )
      }
      if (fresh.isNotEmpty()) {
        ledger = ledger.copy(
          officialAlertIds = (ledger.officialAlertIds + fresh.take(MAX_OFFICIAL_PER_ROUND).map { it.id })
            .takeLast(NotificationLedger.MAX_OFFICIAL_IDS),
        )
      }
    }

    return AlertDecision(notifications, cancelNowcast, ledger)
  }

  /** Le finestre del verdetto, in italiano parlato. */
  fun windowPhrase(window: String): String = when (window) {
    "0-1h" -> "entro un'ora"
    "1-3h" -> "fra una e tre ore"
    "3-6h" -> "fra tre e sei ore"
    else -> "nelle prossime ore"
  }

  private val HourMinute: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
}
