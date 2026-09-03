package dev.pampa.fluidweather.core.ai.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {

  @Test
  fun `un evento arriva alla riga vuota e i commenti si ignorano`() {
    val parser = SseParser()
    assertNull(parser.feed(": OPENROUTER PROCESSING"))
    assertNull(parser.feed("data: {\"a\":1}"))
    assertEquals("{\"a\":1}", parser.feed(""))
    assertNull(parser.feed(""))
  }

  @Test
  fun `piu' righe data si uniscono con newline e i campi estranei non contano`() {
    val parser = SseParser()
    parser.feed("event: message")
    parser.feed("id: 7")
    parser.feed("data: prima")
    parser.feed("data:seconda")
    parser.feed("retry: 1000")
    assertEquals("prima\nseconda", parser.feed(""))
  }

  @Test
  fun `il ritorno carrello finale non entra nel payload`() {
    val parser = SseParser()
    parser.feed("data: x\r")
    assertEquals("x", parser.feed("\r"))
  }

  @Test
  fun `flush recupera l'ultimo evento senza riga vuota`() {
    val parser = SseParser()
    parser.feed("data: [DONE]")
    assertEquals("[DONE]", parser.flush())
    assertNull(parser.flush())
  }

  @Test
  fun `uno spazio solo dopo i due punti viene tolto, gli altri restano`() {
    val parser = SseParser()
    parser.feed("data:   spaziato")
    assertEquals("  spaziato", parser.feed(""))
  }
}
