package dev.pampa.fluidweather

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppGraphWiringTest {
  @Test
  fun applicationGraphSharesRadarSamplerWithAssistantAndDataSources() {
    val app = InstrumentationRegistry.getInstrumentation()
      .targetContext.applicationContext as FluidWeatherApp
    val graph = app.graph

    assertSame(graph.radarSampler, graph.aiAssistant.radarSampler)
    assertSame(graph.radarSampler, graph.aiAssistant.dataSources.radarSampler)
    assertSame(graph.weatherBriefService, graph.aiAssistant.dataSources.weatherBriefService)
    assertSame(graph.weatherBriefService, app.cachedWeatherBriefs)
    assertSame(graph.routineWindowStore, graph.aiAssistant.dataSources.routineWindowStore)
  }
}
