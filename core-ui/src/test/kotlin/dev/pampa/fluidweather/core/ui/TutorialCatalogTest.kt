package dev.pampa.fluidweather.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il catalogo dei suggerimenti (fase 21) e' una tabella: le cose che si rompono sono un id
 * ripetuto, un'ancora che nessuno mette, e la migrazione di chi aggiorna.
 */
class TutorialCatalogTest {

  @Test
  fun `gli id non si ripetono`() {
    val ids = TutorialCatalog.all.map { it.id }
    assertEquals(ids.size, ids.toSet().size)
  }

  @Test
  fun `ogni suggerimento ha parole, un'ancora, una priorita' e una versione`() {
    TutorialCatalog.all.forEach { entry ->
      assertTrue("${entry.id} senza titolo", entry.titleRes != 0)
      assertTrue("${entry.id} senza testo", entry.textRes != 0)
      assertTrue("${entry.id} senza ancora", entry.anchorId.isNotBlank())
      assertTrue("${entry.id} senza priorita'", entry.priority > 0)
      assertTrue("${entry.id} senza versione", entry.introducedIn >= TutorialCatalog.INITIAL)
    }
  }

  @Test
  fun `in una schermata non ci sono due suggerimenti con la stessa priorita'`() {
    TutorialScreen.entries.forEach { screen ->
      val priorities = TutorialCatalog.forScreen(screen).map { it.priority }
      assertEquals("priorita' ripetute in $screen", priorities.size, priorities.toSet().size)
    }
  }

  @Test
  fun `ogni schermata dichiarata ha almeno un suggerimento`() {
    TutorialScreen.entries.forEach { screen ->
      assertTrue("$screen non spiega niente", TutorialCatalog.forScreen(screen).isNotEmpty())
    }
  }

  @Test
  fun `chi aggiorna si rivede spiegare solo le novita'`() {
    val giaViste = TutorialCatalog.introducedBefore(TutorialCatalog.WITH_ASSISTANT).map { it.id }
    val novita = TutorialCatalog.all.filter { it.id !in giaViste }
    assertTrue("l'assistente e' una novita'", novita.any { it.id == "ai_button" })
    assertTrue("la griglia c'era gia'", "home_reorder" in giaViste)
    assertTrue(
      "le novita' sono solo quelle della versione con l'assistente",
      novita.all { it.introducedIn >= TutorialCatalog.WITH_ASSISTANT },
    )
  }

  @Test
  fun `a un'installazione nuova non si salta niente`() {
    assertEquals(emptyList<TutorialEntry>(), TutorialCatalog.introducedBefore(TutorialCatalog.INITIAL))
  }
}
