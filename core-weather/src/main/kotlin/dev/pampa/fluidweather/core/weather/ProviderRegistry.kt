package dev.pampa.fluidweather.core.weather

/**
 * Dove un provider vale davvero. Le scatole sono deliberatamente strette: interrogare AROME a
 * Tokyo non e' "piu' dati", e' rumore con un costo.
 */
sealed interface Coverage {
  fun contains(latitude: Double, longitude: Double): Boolean

  data object Global : Coverage {
    override fun contains(latitude: Double, longitude: Double) = true
  }

  data class Box(
    val minLatitude: Double,
    val maxLatitude: Double,
    val minLongitude: Double,
    val maxLongitude: Double,
  ) : Coverage {
    override fun contains(latitude: Double, longitude: Double) =
      latitude in minLatitude..maxLatitude && longitude in minLongitude..maxLongitude
  }
}

/**
 * La riga del registro: tutto quello che serve a decidere se e come interrogare un provider,
 * dichiarato in un posto solo. La licenza e l'attribuzione stanno qui perche' sono parte del
 * contratto, non note a pie' di pagina.
 */
data class ProviderDescriptor(
  val id: String,
  val label: String,
  val coverage: Coverage,
  val requiresKey: Boolean,
  val horizonHours: Int,
  /** Cosa porta di suo: il motivo per cui sta nella costellazione. */
  val why: String,
  val attribution: String,
)

/**
 * La costellazione dichiarativa del piano: mondiali e regionali insieme, keyless di default,
 * premium solo con chiave dell'utente. I modelli nazionali passano da Open-Meteo (un solo stile
 * di client, molte fisiche diverse); MET Norway e NWS parlano in proprio.
 *
 * SMHI e' rimasto fuori: il suo endpoint open-data risponde 404 (verificato 2026-09-01) e la
 * Scandinavia e' comunque casa di MET Norway.
 */
object ProviderRegistry {

  const val OPEN_METEO = "open-meteo"
  const val OPEN_METEO_ICON = "open-meteo-icon"
  const val OPEN_METEO_ECMWF = "open-meteo-ecmwf"
  const val OPEN_METEO_GFS = "open-meteo-gfs"
  const val OPEN_METEO_AROME = "open-meteo-arome"
  const val OPEN_METEO_JMA = "open-meteo-jma"
  const val MET_NORWAY = "met-norway"
  const val NWS = "nws"
  const val OPENWEATHERMAP = "openweathermap"
  const val METEOSOURCE = "meteosource"

  val all: List<ProviderDescriptor> = listOf(
    ProviderDescriptor(
      id = OPEN_METEO,
      label = "Open-Meteo",
      coverage = Coverage.Global,
      requiresKey = false,
      // Dieci giorni: e' il best_match che regge il widget Giornaliero del piano.
      horizonHours = 10 * 24,
      why = "il miglior modello disponibile per il punto, scelto da Open-Meteo",
      attribution = "Weather data by Open-Meteo.com (CC BY 4.0)",
    ),
    ProviderDescriptor(
      id = OPEN_METEO_ICON,
      label = "DWD ICON",
      coverage = Coverage.Global,
      requiresKey = false,
      horizonHours = 7 * 24,
      why = "la fisica del servizio meteo tedesco, forte sull'Europa",
      attribution = "Weather data by Open-Meteo.com / DWD (CC BY 4.0)",
    ),
    ProviderDescriptor(
      id = OPEN_METEO_ECMWF,
      label = "ECMWF IFS",
      coverage = Coverage.Global,
      requiresKey = false,
      horizonHours = 7 * 24,
      why = "il riferimento mondiale della media scadenza",
      attribution = "Weather data by Open-Meteo.com / ECMWF (CC BY 4.0)",
    ),
    ProviderDescriptor(
      id = OPEN_METEO_GFS,
      label = "NOAA GFS",
      coverage = Coverage.Global,
      requiresKey = false,
      horizonHours = 7 * 24,
      why = "il globale americano: un'opinione indipendente ovunque",
      attribution = "Weather data by Open-Meteo.com / NOAA (CC BY 4.0)",
    ),
    ProviderDescriptor(
      id = OPEN_METEO_AROME,
      label = "Météo-France AROME",
      // Il dominio AROME: Francia e vicini — copre anche il nord-ovest italiano.
      coverage = Coverage.Box(37.5, 55.4, -12.0, 16.0),
      requiresKey = false,
      horizonHours = 3 * 24,
      why = "il regionale ad alta risoluzione sull'Europa occidentale",
      attribution = "Weather data by Open-Meteo.com / Météo-France (CC BY 4.0)",
    ),
    ProviderDescriptor(
      id = OPEN_METEO_JMA,
      label = "JMA",
      coverage = Coverage.Box(20.0, 50.0, 120.0, 150.0),
      requiresKey = false,
      horizonHours = 7 * 24,
      why = "il giapponese sull'Asia orientale, tifoni compresi",
      attribution = "Weather data by Open-Meteo.com / JMA (CC BY 4.0)",
    ),
    ProviderDescriptor(
      id = MET_NORWAY,
      label = "MET Norway",
      coverage = Coverage.Global,
      requiresKey = false,
      horizonHours = 9 * 24,
      why = "il servizio norvegese, eccellente su Nord Europa e Atlantico",
      attribution = "Weather data from MET Norway (NLOD/CC BY 4.0)",
    ),
    ProviderDescriptor(
      id = NWS,
      label = "NWS",
      coverage = Coverage.Box(24.0, 50.0, -125.0, -66.0),
      requiresKey = false,
      horizonHours = 7 * 24,
      why = "il servizio ufficiale statunitense, sul suo territorio",
      attribution = "Weather data from the US National Weather Service",
    ),
    ProviderDescriptor(
      id = OPENWEATHERMAP,
      label = "OpenWeatherMap",
      coverage = Coverage.Global,
      requiresKey = true,
      horizonHours = 5 * 24,
      why = "un'opinione globale in piu', con la chiave dell'utente",
      attribution = "Weather data by OpenWeatherMap",
    ),
    ProviderDescriptor(
      id = METEOSOURCE,
      label = "Meteosource",
      coverage = Coverage.Global,
      requiresKey = true,
      horizonHours = 24,
      why = "nowcast orario indipendente, con la chiave dell'utente",
      attribution = "Weather data by Meteosource",
    ),
  )

  /** I provider che vale la pena interrogare per QUESTO punto, con QUESTE chiavi. */
  fun available(
    latitude: Double,
    longitude: Double,
    keys: Map<String, String>,
  ): List<ProviderDescriptor> = all.filter { descriptor ->
    descriptor.coverage.contains(latitude, longitude) &&
      (!descriptor.requiresKey || !keys[descriptor.id].isNullOrBlank())
  }
}
