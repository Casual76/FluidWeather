package dev.pampa.fluidweather.feature.settings

import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.ui.haptics.FluidHapticPatterns
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'aptica della fase 20 ha due cose che si possono provare senza un telefono: che ogni evento
 * del vocabolario arrivi nella pagina "Prova i feedback" con le sue parole, e che nell'app non
 * resti nessuna vibrazione scritta a mano fuori dal design system.
 */
class HapticsTest {

  @Test
  fun `ogni evento ha nome e descrizione nella pagina di prova`() {
    FluidHapticEvent.entries.forEach { event ->
      assertNotEquals("evento senza nome: $event", 0, event.labelRes())
      assertNotEquals("evento senza descrizione: $event", 0, event.descriptionRes())
    }
  }

  @Test
  fun `nomi e descrizioni non si ripetono fra eventi diversi`() {
    val labels = FluidHapticEvent.entries.map { it.labelRes() }
    val descriptions = FluidHapticEvent.entries.map { it.descriptionRes() }
    assertEquals(labels.size, labels.toSet().size)
    assertEquals(descriptions.size, descriptions.toSet().size)
  }

  @Test
  fun `i controlli restano secchi anche dopo una taratura sul telefono`() {
    FluidHapticPatterns.controlEvents.forEach { event ->
      assertTrue("$event dura troppo", FluidHapticPatterns.durationMillis(event) <= 120)
    }
  }

  /**
   * Il feedback tattile passa tutto dal vocabolario dell'engine: un `LocalHapticFeedback` in una
   * schermata dell'app sarebbe una vibrazione fuori dall'interruttore, dal risparmio energetico e
   * dal carattere deciso una volta sola. Il test lo trova prima che lo trovi un dito.
   */
  @Test
  fun `nessuna schermata dell'app usa l'aptica di Compose a mano`() {
    val root = File("..").canonicalFile
    val offenders = root.walkTopDown()
      .onEnter { dir ->
        // L'engine e' un altro repo (e' lui a definire il vocabolario); build e .git non sono codice.
        dir.name !in setOf("engine", "build", ".git", ".gradle", ".idea")
      }
      // Solo il codice che finisce nell'app: questo test stesso nomina le due cose che vieta.
      .filter { it.isFile && it.extension == "kt" && "src${File.separator}main" in it.path }
      .filter { file ->
        val text = file.readText()
        "LocalHapticFeedback" in text || "performHapticFeedback" in text
      }
      .map { it.relativeTo(root).path }
      .toList()
    assertEquals("aptica a mano fuori dall'engine: $offenders", emptyList<String>(), offenders)
  }
}
