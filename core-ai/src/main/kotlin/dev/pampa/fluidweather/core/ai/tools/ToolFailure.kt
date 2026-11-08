package dev.pampa.fluidweather.core.ai.tools

/** Displayable failures are fixed app text. Never interpolate a model argument or exception. */
internal enum class ToolFailure(private val english: String, private val italian: String) {
  INVALID_DATE("Date not understood. Use YYYY-MM-DD, today or tomorrow.", "Data non capita. Usa AAAA-MM-GG, oggi o domani."),
  MISSING_NAME("A place name is required.", "Serve il nome di una località."),
  SEARCH_EMPTY("No matching place was found.", "Nessuna località corrispondente trovata."),
  NOT_SAVED("No matching saved place was found.", "Nessuna località salvata corrispondente trovata."),
  OBSERVATION_MISSING("No matching observation was found.", "Nessuna osservazione corrispondente trovata."),
  ALERTS_UNAVAILABLE("Official alerts are unavailable right now.", "Le allerte ufficiali non sono disponibili adesso.");

  fun text(ctx: ToolContext): String = if (ctx.locale.language == "it") italian else english
}
