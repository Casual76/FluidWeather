package dev.pampa.fluidweather.feature.assistant

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Il markdown che la card sa disegnare, e basta: **grassetto**, *corsivo*, elenchi con "-" o
 * "1.", paragrafi. Tutto il resto (titoli, tabelle, codice) e' vietato dal prompt e qui e' testo.
 * Un parser vero sarebbe una libreria in piu' per quattro segni; questo e' un centinaio di righe,
 * senza stato, che regge anche il testo a meta' arrivato dallo stream.
 */
object MarkdownLite {

  sealed interface Block {
    data class Paragraph(val text: AnnotatedString) : Block
    data class Bullet(val text: AnnotatedString, val ordinal: String?) : Block
  }

  fun blocks(markdown: String): List<Block> {
    val out = mutableListOf<Block>()
    val paragraph = StringBuilder()
    fun flush() {
      if (paragraph.isNotBlank()) out += Block.Paragraph(inline(paragraph.toString().trim()))
      paragraph.setLength(0)
    }
    markdown.lines().forEach { raw ->
      val line = raw.trimEnd()
      val trimmed = line.trimStart()
      when {
        trimmed.isEmpty() -> flush()
        trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") -> {
          flush()
          out += Block.Bullet(inline(trimmed.substring(2).trim()), null)
        }
        ORDERED.matches(trimmed) -> {
          flush()
          val match = ORDERED.find(trimmed)!!
          out += Block.Bullet(inline(match.groupValues[2].trim()), match.groupValues[1])
        }
        trimmed.startsWith("#") -> {
          flush()
          out += Block.Paragraph(inline(trimmed.trimStart('#').trim()))
        }
        else -> {
          if (paragraph.isNotEmpty()) paragraph.append(' ')
          paragraph.append(trimmed)
        }
      }
    }
    flush()
    return out
  }

  /** Grassetto e corsivo in linea; un segno aperto e mai chiuso (stream a meta') resta testo. */
  fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
      when {
        text.startsWith("**", i) -> {
          val end = text.indexOf("**", i + 2)
          if (end > i + 2) {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(i + 2, end)) }
            i = end + 2
          } else {
            append(text.substring(i))
            i = text.length
          }
        }
        text[i] == '*' || text[i] == '_' -> {
          val mark = text[i]
          val end = text.indexOf(mark, i + 1)
          if (end > i + 1 && !text[i + 1].isWhitespace()) {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
            i = end + 1
          } else {
            append(mark)
            i++
          }
        }
        text[i] == '`' -> {
          val end = text.indexOf('`', i + 1)
          if (end > i + 1) {
            append(text.substring(i + 1, end))
            i = end + 1
          } else {
            i++
          }
        }
        else -> {
          append(text[i])
          i++
        }
      }
    }
  }

  /** La stessa risposta per il sintetizzatore vocale: senza segni. */
  fun plainText(markdown: String): String = blocks(markdown).joinToString("\n") { block ->
    when (block) {
      is Block.Paragraph -> block.text.text
      is Block.Bullet -> block.text.text
    }
  }

  private val ORDERED = Regex("^(\\d{1,2})[.)]\\s+(.*)$")
}
