package dev.pampa.fluidweather.testbench.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationDatasetTest {

  private val location = BenchLocation("prova", 43.83, 11.20, "fixture")

  @Test
  fun `il formato dell'archivio si legge cosi' com'e' arrivato`() {
    val lines = listOf(
      "latitude,longitude,elevation,utc_offset_seconds,timezone,timezone_abbreviation",
      "43.83128,11.164902,59.0,0,GMT,GMT",
      "",
      "time,temperature_2m (°C),relative_humidity_2m (%),dew_point_2m (°C),surface_pressure (hPa),pressure_msl (hPa),precipitation (mm),cloud_cover (%),wind_speed_10m (km/h),wind_direction_10m (°)",
      "1704067200,10.8,94,10.0,1004.3,1011.4,1.00,82,10.2,225",
      "1704070800,11.2,94,10.2,NaN,1011.4,0.30,79,13.0,222",
    )
    val dataset = StationDataset.parse(lines, location)

    assertEquals(59.0, dataset.elevationMeters, 1e-9)
    assertEquals(2, dataset.records.size)

    val first = dataset.records.first()
    assertEquals(1_704_067_200_000L, first.timestampMillis)
    assertEquals(10.8, first.temperatureC!!, 1e-9)
    assertEquals(1004.3, first.surfacePressureHpa!!, 1e-9)
    assertEquals(1011.4, first.pressureMslHpa!!, 1e-9)
    assertEquals(1.0, first.precipitationMm!!, 1e-9)

    // I NaN dell'archivio diventano null, non numeri finti.
    assertNull(dataset.records[1].surfacePressureHpa)
  }
}
