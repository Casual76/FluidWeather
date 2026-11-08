package dev.pampa.fluidweather.core.ai.tools

import dev.antigravity.fluidengine.ai.tools.Schema
import dev.antigravity.fluidengine.ai.tools.Args.str
import kotlinx.serialization.json.JsonObject

/** Curated explanations remain available without location, provider prose or invented weather. */
class WeatherExplanationTool : WeatherTool() {
  override val name = "spiega_meteo"
  override val group = ToolGroup.HOURLY
  override val description = "Spiegazioni generali senza dati locali: radar, probabilita, sensore, previsioni, aria, cielo. Non descrive il meteo attuale."
  override val parameters = Schema.obj(mapOf("tema" to Schema.str("radar, probabilita, sensore, previsioni, aria, cielo")))
  override suspend fun answer(args: JsonObject, ctx: ToolContext): String {
    val italian = ctx.locale.language == "it"
    val pair = when (args.str("tema")) {
      "radar" -> "Il radar stima gli echi nelle immagini, non misura direttamente la pioggia al suolo. Il movimento delle celle e il loro sviluppo limitano le stime di arrivo e fine." to
        "Radar estimates echoes in images, rather than directly measuring rain at ground level. Cell motion and development limit arrival and end estimates."
      "probabilita" -> "La probabilita di precipitazione esprime incertezza del modello per un intervallo; non indica quanti minuti piovera o quanto sara intensa la pioggia." to
        "Precipitation probability expresses model uncertainty for an interval; it does not state how many minutes rain will last or how intense it will be."
      "sensore" -> "Il barometro misura la pressione sul telefono. Le tendenze alimentano un modello locale: non sono una misura della pioggia e non descrivono una localita lontana." to
        "The barometer measures pressure at the phone. Trends feed a local model: they do not measure rain or describe a distant location."
      "aria" -> "Gli indici di qualita dell'aria dipendono dagli inquinanti e dalla scala usata. I dati del modello possono differire dalle misure locali; consulta i dettagli della fonte e le indicazioni ufficiali." to
        "Air quality indices depend on pollutants and the scale used. Model data can differ from local measurements; consult source details and official guidance."
      "cielo" -> "Alba, tramonto e fase lunare sono calcoli astronomici per luogo e data. La copertura nuvolosa e invece una previsione meteorologica e puo impedire la visibilita." to
        "Sunrise, sunset and moon phase are astronomical calculations for a place and date. Cloud cover is a weather forecast and can prevent visibility."
      else -> "Le previsioni descrivono intervalli e zone del modello. Un campione orario non determina un inizio o una fine al minuto; dati mancanti non significano assenza di pioggia." to
        "Forecasts describe model intervals and areas. An hourly sample does not establish minute-level onset or end; missing data does not mean no rain."
    }
    return (if (italian) "Spiegazione generale, non valutazione del meteo locale: " else "General explanation, not an assessment of local weather: ") + if (italian) pair.first else pair.second
  }
}
