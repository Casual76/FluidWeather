package dev.pampa.fluidweather.strings

import android.text.format.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Ore e date nel formato del locale: chi vive con le 12 ore vede "3:05 PM", chi vive con le 24
 * vede "15:05", senza un'impostazione in piu' (decisione 2026-09-02). Gli scheletri ICU
 * ("jm", "EEEdMMM") chiedono al sistema il pattern migliore; se il pattern che torna non piace
 * a java.time si ripiega su uno fisso, mai su un'eccezione.
 */
object TimeFormats {

  fun time(millis: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    formatter("jm", "HH:mm", locale).format(Instant.ofEpochMilli(millis).atZone(zone))

  /** Solo l'ora: "15" o "3 PM". */
  fun hour(millis: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    formatter("j", "HH", locale).format(Instant.ofEpochMilli(millis).atZone(zone))

  /** "gio 4 set" / "Thu, Sep 4". */
  fun day(millis: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    capitalize(formatter("EEEdMMM", "EEE d MMM", locale).format(Instant.ofEpochMilli(millis).atZone(zone)))

  /** "4 set" / "Sep 4". */
  fun shortDate(millis: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    formatter("dMMM", "d MMM", locale).format(Instant.ofEpochMilli(millis).atZone(zone))

  fun shortDate(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    formatter("dMMM", "d MMM", locale).format(date)

  /** "giovedì 4 settembre" / "Thursday, September 4". */
  fun longDate(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    capitalize(formatter("EEEEdMMMM", "EEEE d MMMM", locale).format(date))

  /** "4 set 15:05" / "Sep 4, 3:05 PM". */
  fun dayTime(millis: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    formatter("dMMMjm", "d MMM HH:mm", locale).format(Instant.ofEpochMilli(millis).atZone(zone))

  /** "15:05:09": i secondi servono solo alla diagnostica. */
  fun timeWithSeconds(millis: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    formatter("jms", "HH:mm:ss", locale).format(Instant.ofEpochMilli(millis).atZone(zone))

  /** L'orario di un'impostazione (ore e minuti scelti): "07:30" o "7:30 AM". */
  fun clock(hour: Int, minute: Int, locale: Locale = Locale.getDefault()): String =
    formatter("jm", "HH:mm", locale).format(java.time.LocalTime.of(hour, minute))

  fun is24Hour(locale: Locale = Locale.getDefault()): Boolean = !pattern("jm", locale).contains('a')

  /**
   * I formattatori vivono in una cache, e non e' un'ottimizzazione prematura: una pagina oraria
   * chiama `fmtHour` una volta per riga — fino a 246 — e ogni chiamata costruiva un
   * `DateTimeFormatter` nuovo **piu'** un giro in ICU per `getBestDateTimePattern`. Misurato sul
   * telefono, era una delle due voci che facevano aprire le pagine lunghe a scatti.
   *
   * `DateTimeFormatter` e' immutabile e thread-safe per contratto, quindi condividerlo e' corretto.
   * La chiave include il `Locale` perche' e' un parametro di ogni funzione (l'assistente passa il
   * suo, che puo' non essere quello di sistema); il cambio di lingua produce chiavi nuove da solo.
   * Resta un caso che la chiave non vede — l'utente che passa da 12 a 24 ore a lingua invariata —
   * e per quello c'e' [invalidate], che l'app chiama quando la configurazione cambia.
   */
  private val formatters = ConcurrentHashMap<String, DateTimeFormatter>()

  /** Svuota la cache: da chiamare quando la configurazione di sistema cambia. */
  fun invalidate() {
    formatters.clear()
    patterns.clear()
  }

  private fun formatter(skeleton: String, fallback: String, locale: Locale): DateTimeFormatter =
    formatters.getOrPut("$skeleton|$locale") {
      val pattern = pattern(skeleton, locale)
      runCatching { DateTimeFormatter.ofPattern(pattern, locale) }
        .getOrElse { DateTimeFormatter.ofPattern(fallback, locale) }
    }

  private val patterns = ConcurrentHashMap<String, String>()

  private fun pattern(skeleton: String, locale: Locale): String =
    patterns.getOrPut("$skeleton|$locale") { bestPattern(skeleton, locale) }

  private fun bestPattern(skeleton: String, locale: Locale): String =
    runCatching { DateFormat.getBestDateTimePattern(locale, skeleton) }
      // 'B' (periodi flessibili del giorno) e 'b' non esistono in java.time: si torna ad AM/PM.
      .getOrDefault("")
      .replace('B', 'a')
      .replace('b', 'a')
      .ifBlank { defaultPattern(skeleton) }

  private fun defaultPattern(skeleton: String): String = when (skeleton) {
    "jm" -> "HH:mm"
    "j" -> "HH"
    "jms" -> "HH:mm:ss"
    "EEEdMMM" -> "EEE d MMM"
    "dMMM" -> "d MMM"
    "EEEEdMMMM" -> "EEEE d MMMM"
    "dMMMjm" -> "d MMM HH:mm"
    else -> "HH:mm"
  }

  private fun capitalize(text: String): String = text.replaceFirstChar { it.uppercase() }
}
