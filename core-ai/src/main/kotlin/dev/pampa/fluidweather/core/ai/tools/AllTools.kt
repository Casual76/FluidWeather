package dev.pampa.fluidweather.core.ai.tools

import dev.pampa.fluidweather.core.ai.data.PlaceResolver

/** Il catalogo completo, nell'ordine in cui il modello lo legge. */
object AllTools {
  fun registry(resolver: PlaceResolver): ToolRegistry = ToolRegistry(
    listOf(
      SearchPlaceTool(resolver),
      SavedPlacesTool(),
      ComparePlacesTool(resolver),
      NowcastVerdictTool(resolver),
      BarometerStateTool(resolver),
      PressureSeriesTool(resolver),
      NowcastHistoryTool(),
      NowTool(resolver),
      WeatherSummaryTool(resolver),
      HourlyForecastTool(resolver),
      DailyForecastTool(resolver),
      DayDetailTool(resolver),
      UpcomingPrecipitationTool(resolver),
      DryWindowTool(resolver),
      RadarAroundTool(resolver),
      RadarFramesTool(),
      AirQualityTool(resolver),
      SunTool(resolver),
      MoonTool(resolver),
      ProvidersAvailableTool(resolver),
      ProviderComparisonTool(resolver),
      BenchmarkTool(),
      OfficialAlertsTool(resolver),
      NotificationsStateTool(),
      SelectPlaceTool(resolver),
      OpenPageTool(),
      StartBurstTool(),
      RecordObservationTool(),
      SavePlaceTool(resolver),
      RecentObservationsTool(),
      UnitsTool(),
      RemovePlaceTool(resolver),
      DeleteObservationTool(),
      CalibrationStateTool(),
    ),
  )
}
