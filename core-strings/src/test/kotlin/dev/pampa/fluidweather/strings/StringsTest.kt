package dev.pampa.fluidweather.strings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le due lingue si muovono insieme (fase 17) e i suggerimenti stanno in una riga (fase 21). Sono
 * due cose che si rompono in silenzio: una stringa aggiunta solo in inglese si vede solo con il
 * telefono in italiano, e una frase troppo lunga si vede solo quando il callout ha gia' coperto
 * mezzo schermo.
 */
class StringsTest {

  private val english = parse("src/main/res/values/strings.xml")
  private val italian = parse("src/main/res/values-it/strings.xml")

  private fun parse(path: String): Map<String, String> {
    val text = File(path).readText()
    return Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
      .findAll(text)
      .associate { match -> match.groupValues[1] to match.groupValues[2] }
  }

  @Test
  fun `le due lingue hanno le stesse chiavi`() {
    assertEquals("chiavi solo in inglese", emptySet<String>(), english.keys - italian.keys)
    assertEquals("chiavi solo in italiano", emptySet<String>(), italian.keys - english.keys)
  }

  @Test
  fun `un suggerimento sta in una frase e in due parole di titolo`() {
    listOf("titoli inglesi" to english, "titoli italiani" to italian).forEach { (lingua, strings) ->
      strings.filterKeys { it.startsWith("tut_") && it.endsWith("_title") }.forEach { (key, value) ->
        val parole = value.trim().split(" ").size
        assertTrue("$lingua: $key ha $parole parole ($value)", parole <= 4)
      }
      strings.filterKeys { it.startsWith("tut_") && it.endsWith("_text") }.forEach { (key, value) ->
        assertTrue("$lingua: $key e' lungo ${value.length} caratteri", value.length <= 90)
        assertTrue("$lingua: $key contiene piu' di una frase", value.trimEnd('.').count { it == '.' } == 0)
      }
    }
  }

  @Test
  fun `ogni evento aptico e ogni suggerimento hanno le loro parole in tutte e due le lingue`() {
    val prefixes = listOf("hapt_ev_", "tut_")
    prefixes.forEach { prefix ->
      val keys = english.keys.filter { it.startsWith(prefix) }
      assertTrue("nessuna stringa con prefisso $prefix", keys.isNotEmpty())
      keys.forEach { key ->
        assertTrue("$key vuota in inglese", english.getValue(key).isNotBlank())
        assertTrue("$key vuota in italiano", italian.getValue(key).isNotBlank())
      }
    }
  }
}
