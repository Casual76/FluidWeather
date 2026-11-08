package dev.pampa.fluidweather

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.*

  // Intrinsic text advances are fractional while Compose bounds round to Int pixels. A one-
  // fraction advance overflow is not clipped ink. Inspect the actual painted glyphs instead.
internal fun assertGlanceTextPaintedFits(layout: TextLayoutResult) {
    val margin = 64
    val bitmap = androidx.compose.ui.graphics.ImageBitmap(layout.size.width + margin * 2, layout.size.height + margin * 2)
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
    canvas.translate(margin.toFloat(), margin.toFloat())
    layout.multiParagraph.paint(canvas, color = androidx.compose.ui.graphics.Color.White)
    val pixels = bitmap.asAndroidBitmap()
    var found = false
    for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
      if ((pixels.getPixel(x, y) ushr 24) != 0) {
        found = true
        assertTrue("Clipped glyph ${layout.layoutInput.text.text} at $x,$y in ${layout.size}",
          x >= margin && x < margin + layout.size.width && y >= margin && y < margin + layout.size.height)
      }
    }
    pixels.recycle()
    assertTrue("Text must paint visible glyphs", found)
    assertFalse("Text may not be truncated", layout.multiParagraph.didExceedMaxLines)
  }
