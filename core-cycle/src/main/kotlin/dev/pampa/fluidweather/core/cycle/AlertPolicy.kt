package dev.pampa.fluidweather.core.cycle

import dev.pampa.fluidweather.core.model.AppNotification
import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.NotificationChannelKind
import dev.pampa.fluidweather.core.model.NotificationLedger
import dev.pampa.fluidweather.core.model.NotificationSettings
import dev.pampa.fluidweather.core.model.NotificationUrgency
import dev.pampa.fluidweather.core.model.NotificationLocation
import dev.pampa.fluidweather.core.model.OfficialAlert
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import java.time.ZoneId
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
  /** Sta piovendo adesso, secondo cio' che si e' visto (quarto d'ora, radar). */
  val rainingNow: Boolean = false,
  /** A remote fallback is still the same chosen place, explicitly identified in its alert. */
  val location: NotificationLocation? = null,
  val locationName: String? = null,
)

/** Cosa consegnare, cosa ritirare, e la memoria aggiornata da scrivere. */
class AlertDecision internal constructor(
  val notifications: List<AppNotification>,
  /** L'allerta del barometro e' rientrata: la notifica in tendina va tolta. */
  val cancelNowcastAlert: Boolean,
  val ledger: NotificationLedger,
  private val deliveryBase: NotificationLedger,
  private val deliveryEffects: List<(NotificationLedger) -> NotificationLedger>,
) {
  /** Commit only the effects of accepted posts, independently for every official alert. */
  fun deliver(post: (AppNotification) -> Boolean): AlertDeliveryResult {
    var confirmed = deliveryBase
    val delivered = mutableListOf<AppNotification>()
    notifications.forEachIndexed { index, notification ->
      if (post(notification)) {
        delivered += notification
        confirmed = deliveryEffects[index](confirmed)
      }
    }
    return AlertDeliveryResult(delivered, confirmed, delivered.size != notifications.size)
  }
}

data class AlertDeliveryResult(
  val delivered: List<AppNotification>,
  val ledger: NotificationLedger,
  val pending: Boolean,
)

/**
 * La politica delle notifiche: pura, deterministica, con la memoria esplicita. Le parole le
 * porta [NotificationTexts] (fase 17): qui si decide, non si scrive. Decide per tre
 * dei quattro canali (il riepilogo ha il suo orologio, vedi [DailySummary]) e rispetta le
 * impostazioni PRIMA di guardare i dati: un canale spento non produce niente, nemmeno memoria.
 *
 * Le regole anti-rumore, tutte dichiarate:
 *  - allerta del barometro: solo al livello ALLERTA (decisione 2026-09-02); si ripete solo se
 *    sono passate [NOWCAST_REPEAT_MILLIS] o se la probabilita' e' salita di [NOWCAST_ESCALATION];
 *    quando il livello rientra, la notifica si ritira. E tace del tutto in due casi: quando sta
 *    gia' piovendo — annunciare pioggia a chi la sta prendendo e' rumore, non un servizio — e
 *    quando la notifica di inizio precipitazione ha gia' detto la stessa cosa per lo stesso
 *    periodo, con in piu' l'ora esatta che il barometro non sa;
 *  - inizio/fine precipitazione: un evento si annuncia una volta; un altro evento e' tale se
 *    dista piu' di [EVENT_DISTINCT_MILLIS] da quello gia' annunciato;
 *  - allerte ufficiali: ogni identificativo una volta sola, al massimo [MAX_OFFICIAL_PER_ROUND]
 *    per giro (le altre al giro dopo: non e' un bollettino, e' una tendina).
 */
object AlertPolicy {

  const val NOWCAST_REPEAT_MILLIS: Long = 3 * 3_600_000L
  const val NOWCAST_ESCALATION: Double = 0.20
  const val EVENT_DISTINCT_MILLIS: Long = 2 * 3_600_000L

  /** L'orizzonte del verdetto: oltre le sei ore le due notifiche non parlano piu' della stessa cosa. */
  const val NOWCAST_HORIZON_MILLIS: Long = 6 * 3_600_000L
  const val MAX_OFFICIAL_PER_ROUND: Int = 3

  const val NOWCAST_ID = 100
  const val PRECIPITATION_ID = 200
  const val OFFICIAL_ID_BASE = 300
  const val SUMMARY_ID = 400

  /**
   * Il canale delle precipitazioni ha gia' annunciato l'inizio di questa pioggia?
   *
   * I provider dicono "pioggia alle 16:40", il barometro dice "probabile fra una e tre ore":
   * sono la stessa notizia, e la prima e' piu' utile. Se e' gia' partita per un momento che cade
   * dentro l'orizzonte del verdetto, il barometro non la ripete.
   */
  private fun alreadyAnnounced(inputs: AlertInputs): Boolean {
    if (!inputs.settings.precipitation) return false
    val onset = inputs.ledger.precipitationOnsetMillis ?: return false
    return onset in inputs.nowMillis..(inputs.nowMillis + NOWCAST_HORIZON_MILLIS)
  }

  fun decide(inputs: AlertInputs, texts: NotificationTexts): AlertDecision {
    val notifications = mutableListOf<AppNotification>()
    var ledger = inputs.ledger.atLocation(inputs.location)
    var cancelNowcast = inputs.ledger.nowcastAlertActive && inputs.location != null &&
      inputs.ledger.location?.matches(inputs.location) != true
    val effects = mutableListOf<(NotificationLedger) -> NotificationLedger>()
    fun offer(notification: AppNotification, effect: (NotificationLedger) -> NotificationLedger) {
      notifications += notification
      effects += effect
      ledger = effect(ledger)
    }

    // ------------------------------------------------------------- allerta del barometro
    val verdict = inputs.verdict
    if (inputs.settings.nowcastAlert) {
      if (verdict != null && verdict.level == AlertLevel.ALLERTA && !inputs.rainingNow && !alreadyAnnounced(inputs.copy(ledger = ledger))) {
        val strongest = verdict.windows.maxByOrNull { it.probability }
        if (strongest != null) {
          val lastAt = ledger.nowcastAlertAtMillis
          val lastProbability = ledger.nowcastAlertProbability ?: 0.0
          val due = lastAt == null ||
            inputs.nowMillis - lastAt >= NOWCAST_REPEAT_MILLIS ||
            strongest.probability >= lastProbability + NOWCAST_ESCALATION
          if (due) {
            val factors = strongest.topFactors.take(2).joinToString(", ") { factor ->
              texts.factorLabel(factor.name) + if (factor.contribution > 0) " ▲" else " ▼"
            }
            val notification = AppNotification(
              channel = NotificationChannelKind.NOWCAST_ALERT,
              id = NOWCAST_ID,
              title = texts.nowcastTitle(),
              text = texts.nowcastText(strongest.window, (strongest.probability * 100).toInt()),
              bigText = texts.nowcastBigText(
                window = strongest.window,
                probabilityPercent = (strongest.probability * 100).toInt(),
                lowPercent = (strongest.probabilityLow * 100).toInt(),
                highPercent = (strongest.probabilityHigh * 100).toInt(),
                factors = factors.takeIf { it.isNotBlank() },
              ),
              urgency = NotificationUrgency.ALARM,
            )
            offer(notification) { it.copy(
              nowcastAlertAtMillis = inputs.nowMillis, nowcastAlertProbability = strongest.probability,
              nowcastAlertActive = true,
            ) }
          }
        }
      } else if (ledger.nowcastAlertActive) {
        cancelNowcast = true
        ledger = ledger.copy(nowcastAlertActive = false)
      }
    } else if (ledger.nowcastAlertActive) {
      cancelNowcast = true
      ledger = ledger.copy(nowcastAlertActive = false)
    }
    // Cancellation changes are independent of accepted posts. Offers are replayed onto this base.
    val deliveryBase = inputs.ledger.atLocation(inputs.location).let {
      if (cancelNowcast) it.copy(nowcastAlertActive = false) else it
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
          val time = texts.time(transition.atMillis, inputs.zone)
          val notification = when (transition.kind) {
            TransitionKind.ONSET -> AppNotification(
              channel = NotificationChannelKind.PRECIPITATION,
              id = PRECIPITATION_ID,
              title = texts.onsetTitle(transition.weatherKind),
              text = inputs.locationName?.let { texts.onsetText(time, it) } ?: texts.onsetText(time),
              urgency = NotificationUrgency.WATCH,
            )
            TransitionKind.END -> AppNotification(
              channel = NotificationChannelKind.PRECIPITATION,
              id = PRECIPITATION_ID,
              title = texts.endTitle(transition.weatherKind),
              text = inputs.locationName?.let { texts.endText(time, it) } ?: texts.endText(time),
              urgency = NotificationUrgency.CLEAR,
            )
          }
          offer(notification) { confirmed -> when (transition.kind) {
            TransitionKind.ONSET -> confirmed.copy(precipitationOnsetMillis = transition.atMillis)
            TransitionKind.END -> confirmed.copy(precipitationEndMillis = transition.atMillis)
          } }
        }
      }
    }

    // ------------------------------------------------------------------ allerte ufficiali
    if (inputs.settings.officialAlerts) {
      val fresh = inputs.officialAlerts
        .filter { it.isActive(inputs.nowMillis) && it.id !in ledger.officialAlertIds }
        .distinctBy { it.id }
      fresh.take(MAX_OFFICIAL_PER_ROUND).forEach { alert ->
        val notification = AppNotification(
          channel = NotificationChannelKind.OFFICIAL_ALERTS,
          id = OFFICIAL_ID_BASE + (alert.id.hashCode() and 0xFFFF),
          title = alert.event,
          text = alert.headline ?: alert.areaDescription ?: alert.source.label,
          bigText = buildString {
            alert.headline?.let { append(it).append("\n\n") }
            alert.description?.let { append(it).append("\n\n") }
            alert.instruction?.let { append(it).append("\n\n") }
            alert.areaDescription?.let { append(texts.officialArea()).append(it).append("\n") }
            append(texts.officialSource()).append(alert.source.label)
            alert.sender?.let { append(" · ").append(it) }
            append(texts.officialVerbatim())
          },
          urgency = NotificationUrgency.ALARM,
        )
        offer(notification) { confirmed -> confirmed.copy(
          officialAlertIds = (confirmed.officialAlertIds + alert.id).takeLast(NotificationLedger.MAX_OFFICIAL_IDS),
        ) }
      }
    }

    return AlertDecision(notifications, cancelNowcast, ledger, deliveryBase, effects)
  }
}
