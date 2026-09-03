package dev.pampa.fluidweather.core.ai.speech

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Un microfono finto che suona una partitura: tratti di silenzio e di "parlato" (seno a un dato RMS). */
private class ScriptedPcm(private val segments: List<Pair<Long, Double>>, private val sampleRate: Int = 16_000) : PcmSource {
  private var position = 0L
  private val total = segments.sumOf { it.first } * sampleRate / 1000
  var stopped = false

  override fun start(sampleRate: Int): Boolean = true

  override fun read(frame: ShortArray): Int {
    for (i in frame.indices) {
      val t = position + i
      frame[i] = if (t >= total) 0 else sampleAt(t)
    }
    position += frame.size
    return frame.size
  }

  private fun sampleAt(t: Long): Short {
    var offset = 0L
    for ((millis, rms) in segments) {
      val samples = millis * sampleRate / 1000
      if (t < offset + samples) {
        val amplitude = rms * Math.sqrt(2.0)
        return (amplitude * sin(2 * PI * 440.0 * t / sampleRate)).toInt().toShort()
      }
      offset += samples
    }
    return 0
  }

  override fun stop() {
    stopped = true
  }
}

class SpeechCaptureTest {

  private fun target() = File.createTempFile("ask", ".wav").apply { deleteOnExit() }

  @Test
  fun `silenzio e basta - non ho sentito nulla`() = runBlocking<Unit> {
    val source = ScriptedPcm(listOf(5_000L to 60.0))
    val events = SpeechCapture(source, SpeechCapture.VadConfig(maxDurationMillis = 4_000)).record(target()).toList()
    assertTrue(events.last() is SpeechCapture.Event.Empty)
    assertEquals(SpeechCapture.EmptyReason.NOTHING_HEARD, (events.last() as SpeechCapture.Event.Empty).reason)
    assertTrue(source.stopped)
  }

  @Test
  fun `parlato di due secondi poi silenzio - finisce da solo e il WAV e' rifilato`() = runBlocking<Unit> {
    val file = target()
    val source = ScriptedPcm(listOf(300L to 60.0, 2_000L to 3_000.0, 3_000L to 60.0))
    val events = SpeechCapture(source).record(file).toList()
    val started = events.indexOfFirst { it is SpeechCapture.Event.SpeechStarted }
    assertTrue("parlato non rilevato", started > 0)
    val levelBefore = events.subList(0, started).filterIsInstance<SpeechCapture.Event.Level>().last()
    assertTrue("inizio atteso attorno a 360 ms, avuto ${levelBefore.elapsedMillis}", levelBefore.elapsedMillis in 300..500)
    val finished = events.last() as SpeechCapture.Event.Finished
    assertEquals(SpeechCapture.EndReason.SILENCE, finished.reason)
    assertTrue("durata ${finished.durationMillis}", finished.durationMillis in 2_400..2_800)
    val bytes = file.readBytes()
    val header = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
    assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
    assertEquals(16, header.getInt(16))
    assertEquals(1, header.getShort(20).toInt())
    assertEquals(1, header.getShort(22).toInt())
    assertEquals(16_000, header.getInt(24))
    assertEquals(32_000, header.getInt(28))
    assertEquals(2, header.getShort(32).toInt())
    assertEquals(16, header.getShort(34).toInt())
    assertEquals(bytes.size - 44, header.getInt(40))
    assertEquals(bytes.size - 8, header.getInt(4))
  }

  @Test
  fun `parlato troppo breve - troppo corto`() = runBlocking<Unit> {
    val source = ScriptedPcm(listOf(300L to 60.0, 250L to 3_000.0, 3_000L to 60.0))
    val events = SpeechCapture(source, SpeechCapture.VadConfig(maxDurationMillis = 4_000)).record(target()).toList()
    val last = events.last()
    assertTrue("atteso Empty, avuto $last", last is SpeechCapture.Event.Empty)
  }

  @Test
  fun `chi parla subito viene sentito lo stesso`() = runBlocking<Unit> {
    val source = ScriptedPcm(listOf(1_500L to 3_000.0, 2_000L to 60.0))
    val events = SpeechCapture(source).record(target()).toList()
    assertTrue(events.any { it is SpeechCapture.Event.SpeechStarted })
    assertTrue(events.last() is SpeechCapture.Event.Finished)
  }

  @Test
  fun `il tetto dei trenta secondi e lo stop manuale`() = runBlocking<Unit> {
    val long = ScriptedPcm(listOf(40_000L to 3_000.0))
    val capped = SpeechCapture(long, SpeechCapture.VadConfig(maxDurationMillis = 3_000)).record(target()).toList().last() as SpeechCapture.Event.Finished
    assertEquals(SpeechCapture.EndReason.MAX_DURATION, capped.reason)
    assertTrue(capped.durationMillis <= 3_000)

    val capture = SpeechCapture(ScriptedPcm(listOf(40_000L to 3_000.0)))
    val events = mutableListOf<SpeechCapture.Event>()
    capture.record(target()).collect { event ->
      events += event
      if (event is SpeechCapture.Event.Level && event.elapsedMillis >= 2_000) capture.stopNow()
    }
    assertEquals(SpeechCapture.EndReason.MANUAL, (events.last() as SpeechCapture.Event.Finished).reason)
  }

  @Test
  fun `rumore di fondo alto alza la soglia (regola del triplo)`() = runBlocking<Unit> {
    val quietSpeech = ScriptedPcm(listOf(300L to 400.0, 2_000L to 1_000.0, 2_000L to 400.0))
    val ignored = SpeechCapture(quietSpeech, SpeechCapture.VadConfig(maxDurationMillis = 4_500)).record(target()).toList()
    assertTrue("parlato sotto il triplo del rumore non deve contare", ignored.none { it is SpeechCapture.Event.SpeechStarted })
    val loudSpeech = ScriptedPcm(listOf(300L to 400.0, 2_000L to 2_500.0, 2_000L to 400.0))
    val heard = SpeechCapture(loudSpeech, SpeechCapture.VadConfig(maxDurationMillis = 4_500)).record(target()).toList()
    assertTrue(heard.any { it is SpeechCapture.Event.SpeechStarted })
  }
}
