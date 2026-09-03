package dev.pampa.fluidweather.core.ai.tools

import dev.pampa.fluidweather.core.ai.data.PlaceResolver
import dev.pampa.fluidweather.core.ai.tools.Args.str
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject

/** Cercare un posto per nome: fino a cinque risultati del geocoding. */
class SearchPlaceTool(private val resolver: PlaceResolver) : AiTool {
  override val name = "cerca_luogo"
  override val group = ToolGroup.PLACES
  override val description = "Cerca una localita' per nome; restituisce fino a 5 risultati con regione e paese."
  override val parameters = Schema.obj(mapOf("nome" to Schema.str("nome del posto da cercare")), required = listOf("nome"))

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val query = args.str("nome") ?: return "errore: manca il nome"
    val results = resolver.search(query, limit = 5)
    if (results.isEmpty()) return "nessun risultato per \"$query\""
    return ToolText.build {
      results.forEach { place ->
        line("- ${place.name}${place.region?.let { ", $it" } ?: ""} (${"%.2f".format(java.util.Locale.ROOT, place.latitude)}, ${"%.2f".format(java.util.Locale.ROOT, place.longitude)})")
      }
    }
  }
}

/** Le localita' salvate, con quella selezionata in home. */
class SavedPlacesTool : AiTool {
  override val name = "localita_salvate"
  override val group = ToolGroup.PLACES
  override val description = "Elenca le localita' salvate dall'utente e quale e' selezionata in home."
  override val parameters = Schema.obj(emptyMap())

  override suspend fun run(args: JsonObject, ctx: ToolContext): String {
    val places = ctx.sources.savedLocations.places.first()
    val selectedId = ctx.sources.selectedPlaceStore.current()
    return ToolText.build {
      places.forEach { place ->
        val label = if (place.isGps) (ctx.selected?.takeIf { it.isGps }?.name ?: "posizione attuale (GPS)") else place.name
        val region = place.region?.takeIf { !place.isGps }?.let { ", $it" } ?: ""
        line("- $label$region${if (place.id == selectedId) " [selezionata]" else ""}")
      }
      if (places.none { !it.isGps }) line("(nessuna localita' salvata oltre alla posizione attuale)")
    }
  }
}
