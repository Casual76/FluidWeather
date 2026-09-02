package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.Contribution
import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.FusedForecast
import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusedValue
import dev.pampa.fluidweather.core.model.HourlyPoint
import dev.pampa.fluidweather.core.model.WeatherKind
import java.io.File
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** L'esito di un provider in un giro, ridotto a quello che serve ricordare. */
data class ProviderFetchSummary(
  val providerId: String,
  val hours: Int,
  val error: String?,
) {
  val ok: Boolean get() = error == null
}

/**
 * L'istantanea di un giro meteo per un posto: e' cio' che il ciclo in background salva e che la
 * home legge SUBITO all'apertura, invece di rifare il giro dei provider davanti all'utente
 * (feedback sul telefono, 2026-09-02). Porta con se' anche l'opinione piu' completa
 * ([context], Open-Meteo con le 6 ore passate) perche' e' il contesto del verdetto locale.
 */
data class WeatherSnapshot(
  /** "gps" per la posizione del telefono, "place-<id>" per una localita' salvata. */
  val placeKey: String,
  val latitude: Double,
  val longitude: Double,
  val fetchedAtMillis: Long,
  val fetches: List<ProviderFetchSummary>,
  val fused: FusedForecast,
  val context: ForecastBundle?,
  /** L'ultima volta che questo giro ha seminato previsioni da verificare (vedi il refresher). */
  val predictionsRegisteredAtMillis: Long? = null,
) {
  val providersResponding: Int get() = fetches.count { it.ok }

  fun ageMillis(nowMillis: Long): Long = nowMillis - fetchedAtMillis

  /** Distanza in km dal punto dell'istantanea: un'istantanea di un altro posto non vale. */
  fun distanceKmTo(latitude: Double, longitude: Double): Double =
    haversineKm(this.latitude, this.longitude, latitude, longitude)

  companion object {
    const val GPS_KEY = "gps"

    fun keyFor(placeId: Long): String = "place-$placeId"

    fun from(placeKey: String, latitude: Double, longitude: Double, round: WeatherRound, fetchedAtMillis: Long) =
      WeatherSnapshot(
        placeKey = placeKey,
        latitude = latitude,
        longitude = longitude,
        fetchedAtMillis = fetchedAtMillis,
        fetches = round.fetches.map {
          ProviderFetchSummary(it.descriptor.id, it.bundle?.hourly?.size ?: 0, it.error)
        },
        fused = round.fused,
        context = round.fetches.firstOrNull { it.descriptor.id == ProviderRegistry.OPEN_METEO }?.bundle,
      )
  }
}

internal fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
  val r = 6371.0
  val dLat = Math.toRadians(lat2 - lat1)
  val dLon = Math.toRadians(lon2 - lon1)
  val a = sin(dLat / 2) * sin(dLat / 2) +
    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
  return 2 * r * asin(sqrt(a))
}

/**
 * L'istantanea in JSON, a mano con gli elementi di kotlinx: niente plugin di serializzazione
 * nel progetto (i client leggono JsonElement puro) e nessun modello di dominio annotato. Il
 * formato e' versionato: una versione sconosciuta si scarta e si rifa' il giro, senza migrazioni.
 */
object WeatherSnapshotCodec {

  private const val VERSION = 1

  fun encode(snapshot: WeatherSnapshot): String = buildJsonObject {
    put("version", VERSION)
    put("placeKey", snapshot.placeKey)
    put("latitude", snapshot.latitude)
    put("longitude", snapshot.longitude)
    put("fetchedAtMillis", snapshot.fetchedAtMillis)
    snapshot.predictionsRegisteredAtMillis?.let { put("predictionsRegisteredAtMillis", it) }
    put(
      "fetches",
      buildJsonArray {
        snapshot.fetches.forEach { fetch ->
          add(
            buildJsonObject {
              put("id", fetch.providerId)
              put("hours", fetch.hours)
              fetch.error?.let { put("error", it) }
            },
          )
        }
      },
    )
    put(
      "weights",
      buildJsonObject { snapshot.fused.providerWeights.forEach { (id, weight) -> put(id, weight) } },
    )
    put("hours", buildJsonArray { snapshot.fused.hours.forEach { add(encodeHour(it)) } })
    snapshot.context?.let { put("context", encodeBundle(it)) }
  }.toString()

  fun decode(text: String): WeatherSnapshot? {
    val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
    if (root["version"].double()?.toInt() != VERSION) return null
    val placeKey = root["placeKey"].string() ?: return null
    val latitude = root["latitude"].double() ?: return null
    val longitude = root["longitude"].double() ?: return null
    val fetchedAt = root["fetchedAtMillis"].double()?.toLong() ?: return null
    val fetches = root["fetches"].asArray().mapNotNull { fetch ->
      ProviderFetchSummary(
        providerId = fetch["id"].string() ?: return@mapNotNull null,
        hours = fetch["hours"].double()?.toInt() ?: 0,
        error = fetch["error"].string(),
      )
    }
    val weights = (root["weights"] as? JsonObject)?.mapNotNull { (id, value) ->
      value.double()?.let { id to it }
    }?.toMap() ?: emptyMap()
    val hours = root["hours"].asArray().mapNotNull { decodeHour(it) }
    return WeatherSnapshot(
      placeKey = placeKey,
      latitude = latitude,
      longitude = longitude,
      fetchedAtMillis = fetchedAt,
      fetches = fetches,
      fused = FusedForecast(hours = hours, providerWeights = weights),
      context = root["context"]?.let { decodeBundle(it) },
      predictionsRegisteredAtMillis = root["predictionsRegisteredAtMillis"].double()?.toLong(),
    )
  }

  // ----------------------------------------------------------------------------- ore fuse

  private fun encodeHour(hour: FusedHour): JsonObject = buildJsonObject {
    put("t", hour.timestampMillis)
    hour.kind?.let { put("kind", it.name) }
    hour.windDirectionDeg?.let { put("wd", it) }
    put(
      "v",
      buildJsonObject {
        hour.values.forEach { (variable, fused) ->
          put(
            variable,
            buildJsonObject {
              put("value", fused.value)
              put(
                "c",
                buildJsonArray {
                  fused.contributions.forEach { c ->
                    add(
                      buildJsonArray {
                        add(JsonPrimitive(c.providerId))
                        add(JsonPrimitive(c.weight))
                        add(JsonPrimitive(c.value))
                      },
                    )
                  }
                },
              )
            },
          )
        }
      },
    )
  }

  private fun decodeHour(element: JsonElement): FusedHour? {
    val timestamp = element["t"].double()?.toLong() ?: return null
    val values = (element["v"] as? JsonObject)?.mapNotNull { (variable, fused) ->
      val value = fused["value"].double() ?: return@mapNotNull null
      val contributions = fused["c"].asArray().mapNotNull { c ->
        Contribution(
          providerId = c.at(0).string() ?: return@mapNotNull null,
          weight = c.at(1).double() ?: return@mapNotNull null,
          value = c.at(2).double() ?: return@mapNotNull null,
        )
      }
      variable to FusedValue(value, contributions)
    }?.toMap() ?: emptyMap()
    return FusedHour(
      timestampMillis = timestamp,
      values = values,
      kind = element["kind"].string()?.let { name -> WeatherKind.entries.firstOrNull { it.name == name } },
      windDirectionDeg = element["wd"].double(),
    )
  }

  // ----------------------------------------------------------------------- il contesto

  private fun encodeBundle(bundle: ForecastBundle): JsonObject = buildJsonObject {
    put("providerId", bundle.providerId)
    put("fetchedAtMillis", bundle.fetchedAtMillis)
    put("latitude", bundle.latitude)
    put("longitude", bundle.longitude)
    put(
      "hourly",
      buildJsonArray {
        bundle.hourly.forEach { p ->
          add(
            buildJsonObject {
              put("t", p.timestampMillis)
              putNullable("temp", p.temperatureC)
              putNullable("rh", p.relativeHumidityPercent)
              putNullable("dew", p.dewPointC)
              putNullable("msl", p.pressureMslHpa)
              putNullable("pr", p.precipitationMm)
              putNullable("pop", p.precipitationProbabilityPercent)
              putNullable("cc", p.cloudCoverPercent)
              putNullable("ws", p.windSpeedKmh)
              putNullable("wd", p.windDirectionDeg)
              putNullable("wg", p.windGustKmh)
              putNullable("cape", p.capeJkg)
              putNullable("uv", p.uvIndex)
              putNullable("vis", p.visibilityMeters)
              p.kind?.let { put("kind", it.name) }
            },
          )
        }
      },
    )
  }

  private fun decodeBundle(element: JsonElement): ForecastBundle? {
    val providerId = element["providerId"].string() ?: return null
    val points = element["hourly"].asArray().mapNotNull { p ->
      HourlyPoint(
        timestampMillis = p["t"].double()?.toLong() ?: return@mapNotNull null,
        temperatureC = p["temp"].double(),
        relativeHumidityPercent = p["rh"].double(),
        dewPointC = p["dew"].double(),
        pressureMslHpa = p["msl"].double(),
        precipitationMm = p["pr"].double(),
        precipitationProbabilityPercent = p["pop"].double(),
        cloudCoverPercent = p["cc"].double(),
        windSpeedKmh = p["ws"].double(),
        windDirectionDeg = p["wd"].double(),
        windGustKmh = p["wg"].double(),
        capeJkg = p["cape"].double(),
        uvIndex = p["uv"].double(),
        visibilityMeters = p["vis"].double(),
        kind = p["kind"].string()?.let { name -> WeatherKind.entries.firstOrNull { it.name == name } },
      )
    }
    return ForecastBundle(
      providerId = providerId,
      fetchedAtMillis = element["fetchedAtMillis"].double()?.toLong() ?: 0L,
      latitude = element["latitude"].double() ?: 0.0,
      longitude = element["longitude"].double() ?: 0.0,
      hourly = points,
    )
  }

  private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: Double?) {
    if (value != null) put(key, value) else put(key, JsonNull)
  }
}

/**
 * Un file per posto nella cartella dei dati dell'app, piu' una copia in memoria: chi legge
 * spesso (la home) non paga il disco, chi osserva ([updates]) sa quando un posto e' cambiato.
 */
class WeatherSnapshotStore(private val directory: File) {

  private val memory = mutableMapOf<String, WeatherSnapshot>()
  private val lock = Any()
  private val updatesState = MutableStateFlow<Map<String, Long>>(emptyMap())

  /** placeKey -> fetchedAtMillis dell'ultima istantanea scritta, per chi vuole reagire. */
  val updates: StateFlow<Map<String, Long>> = updatesState

  suspend fun read(placeKey: String): WeatherSnapshot? {
    synchronized(lock) { memory[placeKey] }?.let { return it }
    return withContext(Dispatchers.IO) {
      val file = fileFor(placeKey)
      if (!file.exists()) return@withContext null
      val decoded = runCatching { WeatherSnapshotCodec.decode(file.readText()) }.getOrNull()
      if (decoded != null) synchronized(lock) { memory[placeKey] = decoded }
      decoded
    }
  }

  suspend fun write(snapshot: WeatherSnapshot) {
    synchronized(lock) { memory[snapshot.placeKey] = snapshot }
    withContext(Dispatchers.IO) {
      runCatching {
        directory.mkdirs()
        val target = fileFor(snapshot.placeKey)
        val temp = File(directory, target.name + ".tmp")
        temp.writeText(WeatherSnapshotCodec.encode(snapshot))
        if (!temp.renameTo(target)) {
          target.writeText(WeatherSnapshotCodec.encode(snapshot))
          temp.delete()
        }
      }
    }
    updatesState.value = updatesState.value + (snapshot.placeKey to snapshot.fetchedAtMillis)
  }

  /** Dati e privacy: memoria e file, e chi osserva vede il vuoto. */
  suspend fun clear() {
    synchronized(lock) { memory.clear() }
    withContext(Dispatchers.IO) { runCatching { directory.deleteRecursively() } }
    updatesState.value = emptyMap()
  }

  private fun fileFor(placeKey: String): File = File(directory, "$placeKey.json")
}

/**
 * Chi fa il giro e lo mette via. E' l'unico punto che chiama il coordinatore, cosi' la regola
 * sulla semina delle verifiche vive in un posto solo: una passata ogni quarto d'ora seminerebbe
 * quattro volte le stesse previsioni, e le tabelle crescerebbero senza dire niente di nuovo.
 */
class WeatherSnapshotRefresher(
  private val coordinator: RoundSource,
  private val store: WeatherSnapshotStore,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  /** L'istantanea se e' abbastanza recente e abbastanza vicina; altrimenti null. */
  suspend fun fresh(
    placeKey: String,
    latitude: Double,
    longitude: Double,
    maxAgeMillis: Long,
    maxDistanceKm: Double = MAX_DISTANCE_KM,
  ): WeatherSnapshot? {
    val snapshot = store.read(placeKey) ?: return null
    if (snapshot.ageMillis(clock()) > maxAgeMillis) return null
    if (snapshot.distanceKmTo(latitude, longitude) > maxDistanceKm) return null
    return snapshot
  }

  /** Il giro completo, poi l'istantanea su disco. Le eccezioni dei provider restano dentro il giro. */
  suspend fun refresh(placeKey: String, latitude: Double, longitude: Double): WeatherSnapshot {
    val now = clock()
    val previous = store.read(placeKey)
    val lastRegistration = previous?.predictionsRegisteredAtMillis
    val register = lastRegistration == null || now - lastRegistration >= REGISTRATION_INTERVAL_MILLIS
    val round = coordinator.refresh(latitude, longitude, registerPredictions = register)
    val snapshot = WeatherSnapshot.from(placeKey, latitude, longitude, round, now).copy(
      predictionsRegisteredAtMillis = if (register) now else lastRegistration,
    )
    store.write(snapshot)
    return snapshot
  }

  companion object {
    /** Le previsioni si seminano al piu' una volta l'ora: gli orizzonti sono a ore intere. */
    const val REGISTRATION_INTERVAL_MILLIS: Long = 55 * 60_000L

    /** Oltre tre km l'istantanea e' di un altro posto: la scala di un modello e' ~2 km. */
    const val MAX_DISTANCE_KM: Double = 3.0
  }
}
