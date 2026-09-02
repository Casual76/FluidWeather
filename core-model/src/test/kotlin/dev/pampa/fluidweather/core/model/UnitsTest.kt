package dev.pampa.fluidweather.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {

  @Test
  fun `le conversioni tornano sui valori da manuale`() {
    assertEquals(212.0, UnitMath.temperature(100.0, TemperatureUnit.FAHRENHEIT), 1e-9)
    assertEquals(-40.0, UnitMath.temperature(-40.0, TemperatureUnit.FAHRENHEIT), 1e-9)
    assertEquals(18.0, UnitMath.temperatureDelta(10.0, TemperatureUnit.FAHRENHEIT), 1e-9)
    assertEquals(10.0, UnitMath.wind(36.0, WindUnit.MS), 1e-9)
    assertEquals(62.137, UnitMath.wind(100.0, WindUnit.MPH), 1e-3)
    assertEquals(10.0, UnitMath.wind(18.52, WindUnit.KNOTS), 1e-9)
    assertEquals(760.0, UnitMath.pressure(1013.25, PressureUnit.MMHG), 0.01)
    assertEquals(29.92, UnitMath.pressure(1013.25, PressureUnit.INHG), 0.005)
    assertEquals(1013.25, UnitMath.pressure(1013.25, PressureUnit.MBAR), 1e-9)
    assertEquals(1.0, UnitMath.precipitation(25.4, PrecipitationUnit.INCH), 1e-9)
    assertEquals(1.0, UnitMath.distance(1.609344, DistanceUnit.MILES), 1e-9)
  }

  @Test
  fun `la scala di Beaufort rispetta le soglie OMM`() {
    assertEquals(0, UnitMath.beaufort(0.5))
    assertEquals(1, UnitMath.beaufort(3.0))
    assertEquals(3, UnitMath.beaufort(15.0))
    assertEquals(5, UnitMath.beaufort(35.0))
    assertEquals(8, UnitMath.beaufort(70.0))
    assertEquals(11, UnitMath.beaufort(110.0))
    assertEquals(12, UnitMath.beaufort(150.0))
    assertEquals(3.0, UnitMath.wind(15.0, WindUnit.BEAUFORT), 1e-9)
  }

  @Test
  fun `i default seguono il paese`() {
    val us = UnitPreferences.forCountry("US")
    assertEquals(TemperatureUnit.FAHRENHEIT, us.temperature)
    assertEquals(WindUnit.MPH, us.wind)
    assertEquals(PressureUnit.INHG, us.pressure)
    assertEquals(PrecipitationUnit.INCH, us.precipitation)
    assertEquals(DistanceUnit.MILES, us.distance)

    val uk = UnitPreferences.forCountry("gb")
    assertEquals(TemperatureUnit.CELSIUS, uk.temperature)
    assertEquals(WindUnit.MPH, uk.wind)
    assertEquals(PressureUnit.HPA, uk.pressure)
    assertEquals(PrecipitationUnit.MM, uk.precipitation)
    assertEquals(DistanceUnit.MILES, uk.distance)

    assertEquals(UnitPreferences.METRIC, UnitPreferences.forCountry("IT"))
    assertEquals(UnitPreferences.METRIC, UnitPreferences.forCountry(null))
  }

  @Test
  fun `le scelte esplicite vincono sul locale una per una`() {
    val overrides = UnitOverrides(pressure = PressureUnit.MMHG)
    val resolved = overrides.resolve("US")
    assertEquals(PressureUnit.MMHG, resolved.pressure)
    assertEquals(TemperatureUnit.FAHRENHEIT, resolved.temperature)
    assertEquals(UnitPreferences.METRIC.copy(pressure = PressureUnit.MMHG), overrides.resolve("IT"))
  }

  @Test
  fun `i decimali crescono dove l'unita' e' grossa`() {
    assertEquals(2, UnitMath.pressureDecimals(PressureUnit.INHG, 0))
    assertEquals(1, UnitMath.pressureDecimals(PressureUnit.HPA, 1))
    assertEquals(2, UnitMath.precipitationDecimals(PrecipitationUnit.INCH, 1))
    assertEquals(1, UnitMath.windDecimals(WindUnit.MS, 0))
    assertEquals(0, UnitMath.windDecimals(WindUnit.BEAUFORT, 1))
  }
}
