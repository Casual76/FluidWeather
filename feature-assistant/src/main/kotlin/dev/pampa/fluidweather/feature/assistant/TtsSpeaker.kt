package dev.pampa.fluidweather.feature.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * La voce di sistema (gratis, offline, IT/EN). Legge le frasi man mano che lo stream le chiude,
 * accodandole; un tocco sulla card o una domanda nuova la zittisce. Spenta di default: e' un
 * interruttore nelle impostazioni, e vale solo in modalita' vocale.
 */
class TtsSpeaker(context: Context) {

  private var ready = false
  private var pending = mutableListOf<String>()
  private var spokenChars = 0
  private var utterance = 0
  private val engine = TextToSpeech(context.applicationContext) { status ->
    ready = status == TextToSpeech.SUCCESS
    if (ready) {
      engineLanguage()
      val queued = pending.toList()
      pending.clear()
      queued.forEach { speak(it) }
    }
  }

  private fun engineLanguage() {
    val locale = Locale.getDefault()
    val result = engine.setLanguage(locale)
    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
      engine.setLanguage(Locale.ENGLISH)
    }
  }

  /** Le frasi nuove dentro [fullText] rispetto all'ultima volta, e le accoda. Chiama con lo stream parziale. */
  fun speakNewSentences(fullText: String, final: Boolean) {
    val plain = MarkdownLite.plainText(fullText)
    if (plain.length <= spokenChars) return
    val tail = plain.substring(spokenChars)
    val sentences = mutableListOf<String>()
    var start = 0
    SENTENCE_END.findAll(tail).forEach { match ->
      val end = match.range.last + 1
      sentences += tail.substring(start, end).trim()
      start = end
    }
    if (final && start < tail.length) sentences += tail.substring(start).trim()
    if (sentences.isEmpty()) return
    val consumed = if (final) tail.length else start
    spokenChars += consumed
    sentences.filter { it.isNotBlank() }.forEach { speak(it) }
  }

  fun restart() {
    stop()
    spokenChars = 0
  }

  private fun speak(text: String) {
    if (!ready) {
      pending += text
      return
    }
    engine.speak(text, TextToSpeech.QUEUE_ADD, null, "ai-${utterance++}")
  }

  fun stop() {
    pending.clear()
    runCatching { engine.stop() }
  }

  fun release() {
    stop()
    runCatching { engine.shutdown() }
  }

  private companion object {
    /** Fine di frase: punto, punto esclamativo, interrogativo o a capo, seguiti da spazio o fine. */
    val SENTENCE_END = Regex("[.!?…]+(?=\\s|$)|\\n")
  }
}
