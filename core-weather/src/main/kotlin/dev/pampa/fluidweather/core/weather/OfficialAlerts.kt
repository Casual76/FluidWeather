package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.OfficialAlert
import dev.pampa.fluidweather.core.model.OfficialAlertSource
import java.io.StringReader
import java.text.Normalizer
import java.time.OffsetDateTime
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.JsonElement
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Le allerte ufficiali, dal servizio che ha giurisdizione sul punto: NWS negli Stati Uniti
 * (per coordinate esatte), Meteoalarm in Europa (per paese, poi per area). Il testo arriva
 * cosi' com'e': il canale lo dice, e il piano lo pretende — "testo non reinterpretato, fonte in
 * chiaro".
 *
 * Meteoalarm nel feed ATOM "legacy" non porta poligoni ma nomi di area (regioni in Italia,
 * dipartimenti in Francia, Landkreise in Germania): il punto si abbina per NOME, coi
 * candidati che il geocoder del telefono da' per quel punto. E' una corrispondenza onesta ma
 * non perfetta; le allerte a scala nazionale ("Italy") passano sempre.
 */
class OfficialAlertsClient(private val http: ProviderHttp) {

  suspend fun forPoint(
    latitude: Double,
    longitude: Double,
    countryCode: String?,
    areaCandidates: List<String>,
  ): List<OfficialAlert> = when {
    countryCode == null -> emptyList()
    countryCode.equals("US", ignoreCase = true) -> nws(latitude, longitude)
    else -> meteoalarm(countryCode, areaCandidates)
  }

  suspend fun nws(latitude: Double, longitude: Double): List<OfficialAlert> {
    val (lat, lon) = WeatherPoint.round(latitude, longitude)
    val url = "https://api.weather.gov/alerts/active?point=$lat,$lon"
    val root = http.readJson(url, ALERTS_TTL_MILLIS, mapOf("Accept" to "application/geo+json"))
    return NwsAlertsParser.parse(root)
  }

  suspend fun meteoalarm(countryCode: String, areaCandidates: List<String>): List<OfficialAlert> {
    val slug = MeteoalarmFeeds.slugFor(countryCode) ?: return emptyList()
    val xml = http.readText("https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-$slug", ALERTS_TTL_MILLIS)
    return MeteoalarmFeedParser.parse(xml).filter { alert ->
      MeteoalarmFeedParser.matchesArea(alert.areaDescription, areaCandidates)
    }
  }

  private companion object {
    /** Dieci minuti: un'allerta nuova deve arrivare in fretta, e i feed reggono il ritmo. */
    const val ALERTS_TTL_MILLIS = 10 * 60_000L
  }
}

/** Il parser delle allerte NWS (`/alerts/active?point=`): GeoJSON, `features[].properties`. */
object NwsAlertsParser {

  fun parse(root: JsonElement): List<OfficialAlert> =
    root["features"].asArray().mapNotNull { feature ->
      val p = feature["properties"] ?: return@mapNotNull null
      // Solo gli avvisi reali: i test e gli esercizi del servizio non sono meteo.
      if (p["status"].string()?.equals("Actual", ignoreCase = true) == false) return@mapNotNull null
      OfficialAlert(
        id = p["id"].string() ?: feature["id"].string() ?: return@mapNotNull null,
        source = OfficialAlertSource.NWS,
        event = p["event"].string() ?: return@mapNotNull null,
        severity = p["severity"].string(),
        headline = p["headline"].string(),
        description = p["description"].string(),
        instruction = p["instruction"].string(),
        areaDescription = p["areaDesc"].string(),
        sender = p["senderName"].string(),
        onsetMillis = parseIsoMillis(p["onset"].string() ?: p["effective"].string()),
        expiresMillis = parseIsoMillis(p["ends"].string() ?: p["expires"].string()),
        link = p["@id"].string() ?: feature["id"].string(),
      )
    }
}

/**
 * Il parser del feed ATOM di Meteoalarm: un `entry` per area, coi campi CAP in namespace
 * `urn:oasis:names:tc:emergency:cap:1.2`. DOM e non pull-parser perche' il DOM di
 * `javax.xml` esiste identico su Android e sulla JVM, e un feed nazionale e' decine di KB.
 */
object MeteoalarmFeedParser {

  private const val ATOM = "http://www.w3.org/2005/Atom"
  private const val CAP = "urn:oasis:names:tc:emergency:cap:1.2"

  fun parse(xml: String): List<OfficialAlert> {
    val factory = DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = true
      // Niente entita' esterne: un feed e' dati, non un programma.
      runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
      runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
      runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }
    val document = runCatching {
      factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }.getOrNull() ?: return emptyList()

    val entries = document.getElementsByTagNameNS(ATOM, "entry")
    return (0 until entries.length).mapNotNull { index ->
      val entry = entries.item(index) as? Element ?: return@mapNotNull null
      if (entry.cap("status")?.equals("Actual", ignoreCase = true) == false) return@mapNotNull null
      val identifier = entry.cap("identifier") ?: entry.atom("id") ?: return@mapNotNull null
      val area = entry.cap("areaDesc")
      // L'id del feed e' per (avviso, area): lo stesso avviso su due regioni sono due voci.
      OfficialAlert(
        id = if (area != null) "$identifier@$area" else identifier,
        source = OfficialAlertSource.METEOALARM,
        event = entry.cap("event") ?: entry.atom("title") ?: return@mapNotNull null,
        severity = entry.cap("severity"),
        headline = entry.atom("title"),
        description = null,
        instruction = null,
        areaDescription = area,
        sender = "meteoalarm.org",
        onsetMillis = parseIsoMillis(entry.cap("onset") ?: entry.cap("effective")),
        expiresMillis = parseIsoMillis(entry.cap("expires")),
        link = entry.htmlLink(),
      )
    }
  }

  /**
   * Se un'area del feed corrisponde a uno dei nomi che il geocoder da' per il punto (regione,
   * provincia, comune). Confronto per parole normalizzate (minuscole, senza accenti, senza le
   * particelle): "Emilia e Romagna" del feed e "Emilia-Romagna" del geocoder sono lo stesso
   * posto, "Trentino Alto Adige" e "Trentino-Alto Adige/Südtirol" pure.
   */
  fun matchesArea(areaDescription: String?, candidates: List<String>): Boolean {
    val area = tokens(areaDescription ?: return false)
    if (area.isEmpty()) return false
    return candidates.any { candidate ->
      val words = tokens(candidate)
      words.isNotEmpty() && (area.all { it in words } || words.all { it in area })
    }
  }

  private val particles = setOf("e", "di", "del", "della", "dei", "degli", "and", "of", "the", "de", "du", "des", "la", "le", "les")

  internal fun tokens(text: String): Set<String> {
    val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
      .replace(Regex("\\p{M}+"), "")
      .lowercase()
    return normalized.split(Regex("[^a-z0-9]+"))
      .filter { it.isNotBlank() && it !in particles }
      .toSet()
  }

  private fun Element.cap(name: String): String? =
    getElementsByTagNameNS(CAP, name).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

  private fun Element.atom(name: String): String? {
    val nodes = getElementsByTagNameNS(ATOM, name)
    for (i in 0 until nodes.length) {
      val node = nodes.item(i) as? Element ?: continue
      // Solo i figli diretti: un entry ha dentro anche l'author, con il suo <name>.
      if (node.parentNode === this) return node.textContent?.trim()?.takeIf { it.isNotEmpty() }
    }
    return null
  }

  private fun Element.htmlLink(): String? {
    val links = getElementsByTagNameNS(ATOM, "link")
    var fallback: String? = null
    for (i in 0 until links.length) {
      val link = links.item(i) as? Element ?: continue
      if (link.parentNode !== this) continue
      val href = link.getAttribute("href").takeIf { it.isNotBlank() } ?: continue
      val type = link.getAttribute("type")
      val rel = link.getAttribute("rel")
      if (href.contains("geocode=") && type.isBlank()) return href
      if (fallback == null && rel != "related") fallback = href
    }
    return fallback
  }
}

/**
 * I feed nazionali di Meteoalarm, per codice ISO del paese. Gli slug sono quelli del sito
 * (verificati il 2026-09-02 per IT, FR, DE, ES, GB, AT, CH, NL); i paesi assenti non hanno un
 * servizio membro o non hanno ancora uno slug verificato: meglio nessuna allerta che un 404.
 */
object MeteoalarmFeeds {

  private val slugs = mapOf(
    "IT" to "italy",
    "FR" to "france",
    "DE" to "germany",
    "ES" to "spain",
    "GB" to "united-kingdom",
    "AT" to "austria",
    "CH" to "switzerland",
    "NL" to "netherlands",
    "BE" to "belgium",
    "PT" to "portugal",
    "GR" to "greece",
    "HR" to "croatia",
    "SI" to "slovenia",
    "IE" to "ireland",
    "DK" to "denmark",
    "FI" to "finland",
    "IS" to "iceland",
    "NO" to "norway",
    "SE" to "sweden",
    "PL" to "poland",
    "CZ" to "czechia",
    "SK" to "slovakia",
    "HU" to "hungary",
    "RO" to "romania",
    "BG" to "bulgaria",
    "RS" to "serbia",
    "LU" to "luxembourg",
    "MT" to "malta",
    "CY" to "cyprus",
    "EE" to "estonia",
    "LV" to "latvia",
    "LT" to "lithuania",
    "ME" to "montenegro",
    "MK" to "north-macedonia",
    "BA" to "bosnia-herzegovina",
    "MD" to "moldova",
    "IL" to "israel",
    "UA" to "ukraine",
  )

  fun slugFor(countryCode: String): String? = slugs[countryCode.uppercase()]
}

/** ISO-8601 con offset ("2026-09-02T00:58:00-05:00", "…Z") -> epoch millis; altrimenti null. */
internal fun parseIsoMillis(text: String?): Long? =
  text?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

/**
 * Le coordinate di un punto meteo, arrotondate al millesimo di grado (~100 m). Un fix GPS che
 * balla di qualche metro non deve valere un URL nuovo: la cache per-URL vive di questo.
 */
object WeatherPoint {
  fun round(latitude: Double, longitude: Double): Pair<Double, Double> =
    Math.round(latitude * 1000.0) / 1000.0 to Math.round(longitude * 1000.0) / 1000.0
}
