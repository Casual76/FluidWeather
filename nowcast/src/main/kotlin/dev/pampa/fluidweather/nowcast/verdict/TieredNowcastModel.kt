package dev.pampa.fluidweather.nowcast.verdict

import dev.pampa.fluidweather.nowcast.climatology.PooledPriors
import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/** La famiglia di modelli del v3: la logistica leggibile o gli alberi (decisione D4 sul banco). */
enum class ModelFamily { LOGISTICA, GBM }

/**
 * "Il tuo barometro" v3: un modello per livello di contesto ([ContextTier]), ognuno con le sue tre
 * finestre, nessuna previsione dei provider dentro.
 *
 * Il livello lo decide chi chiama ([ContextTier.of]); le feature sono le quarantadue di
 * [FeatureExtractorV3], con le tabelle di quel livello. Qui si sceglie la tabella (o gli alberi), si
 * da' il verdetto con i suoi fattori e si mette la parola con le soglie del livello
 * ([AlertThresholdsV3]). I pavimenti dell'osservazione ([floors]) sono dati accanto al modello: li
 * applica il motore (P2), come oggi, perche' vengono dopo la ricalibrazione personale.
 *
 * **La famiglia.** Le tabelle logistiche ci sono sempre: sono il modello quando D4 ha scelto la
 * logistica e il paracadute quando ha scelto gli alberi. Gli alberi vivono in risorse `.fwgb` con la
 * loro impronta SHA-256; se anche una sola manca, non torna con l'impronta, non si legge o parla un
 * altro contratto di feature, **tutti** i livelli passano alla logistica e [loadProblem] dice perche'.
 * Tutto o niente: un telefono che mescola alberi in un livello e logistica in un altro non e' nessuno
 * dei due modelli che il gate ha certificato.
 *
 * Su Android il caricamento legge risorse Java: va fatto fuori dal thread principale e una volta sola
 * (nota per P2), e la famiglia attiva va mostrata nella diagnostica — R8 potrebbe togliere le risorse
 * e il telefono cadrebbe in silenzio sulla logistica.
 */
class TieredNowcastModel private constructor(
  val version: String,
  /** La famiglia che l'artefatto chiede. */
  val requestedFamily: ModelFamily,
  private val logistic: Map<ContextTier, NowcastModel>,
  private val trees: Map<ContextTier, TreeNowcastModel>?,
  /** Il riferimento di tutti i posti che viaggia con il modello (NONE_NOCLIMA, e ripiego delle tabelle locali). */
  val pooled: PooledPriors?,
  val floors: Map<ContextTier, ObservationFloors>,
  val alerts: Map<ContextTier, AlertThresholds>,
  /** Perche' gli alberi chiesti non ci sono; null se tutto e' andato come chiesto. */
  val loadProblem: String?,
) {

  init {
    require(ContextTier.entries.all { it in logistic }) { "manca la tabella logistica di qualche livello" }
  }

  /** La famiglia che parla davvero. */
  val activeFamily: ModelFamily get() = if (trees != null) ModelFamily.GBM else ModelFamily.LOGISTICA

  /** Il modello del livello [tier]. */
  fun forTier(tier: ContextTier): RainModel = trees?.get(tier) ?: logistic.getValue(tier)

  /** La tabella logistica del livello (anche quando parlano gli alberi: e' il paracadute). */
  fun logisticFor(tier: ContextTier): NowcastModel = logistic.getValue(tier)

  /** I pavimenti dell'osservazione del livello. */
  fun floorsFor(tier: ContextTier): ObservationFloors = floors[tier] ?: ObservationFloors(tier)

  /** Le soglie delle parole del livello [tier] (ALLERTA, SORVEGLIANZA): il motore le riapplica dopo la taratura. */
  fun thresholdsFor(tier: ContextTier): AlertThresholds = alerts[tier] ?: AlertThresholds.DEFAULT

  /** Il verdetto del livello [tier] sulle quarantadue feature, con la parola delle soglie del livello. */
  fun verdict(tier: ContextTier, features: DoubleArray): NowcastVerdict {
    require(features.size == FeatureExtractorV3.COUNT) { "il v3 vuole ${FeatureExtractorV3.COUNT} feature, arrivate ${features.size}" }
    val raw = forTier(tier).verdict(features)
    val thresholds = alerts[tier] ?: AlertThresholds.DEFAULT
    return raw.copy(level = thresholds.levelOf(raw.windows))
  }

  /** Lo stesso modello con la famiglia forzata: per il banco, che giudica anche il paracadute. */
  fun withFamily(family: ModelFamily): TieredNowcastModel = when {
    family == ModelFamily.LOGISTICA -> TieredNowcastModel(version, family, logistic, null, pooled, floors, alerts, null)
    trees != null -> TieredNowcastModel(version, family, logistic, trees, pooled, floors, alerts, null)
    else -> throw IllegalStateException("gli alberi non ci sono: ${loadProblem ?: "l'artefatto e' solo logistico"}")
  }

  /** Lo stesso modello con altri pavimenti: il banco prova le politiche. */
  fun withFloors(policies: Map<ContextTier, ObservationFloors>): TieredNowcastModel =
    TieredNowcastModel(version, requestedFamily, logistic, trees, pooled, policies, alerts, loadProblem)

  companion object {

    /** Dove vivono gli alberi fra le risorse. */
    const val RESOURCE_DIR: String = "dev/pampa/fluidweather/nowcast/verdict/v3/"

    /** Il nome breve del livello nei nomi dei file. */
    fun slug(tier: ContextTier): String = tier.name.lowercase(Locale.ROOT).replace('_', '-')

    /** Il nome del file degli alberi di un (livello, finestra), per esempio `stale-1-3h.fwgb`. */
    fun resourceName(tier: ContextTier, windowIndex: Int): String = "${slug(tier)}-${RainWindows.ALL[windowIndex].label}.fwgb"

    /** Tutti i nomi, nell'ordine livello x finestra. */
    val RESOURCE_NAMES: List<String> = ContextTier.entries.flatMap { tier -> RainWindows.ALL.indices.map { resourceName(tier, it) } }

    fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { String.format(Locale.ROOT, "%02x", it) }

    /**
     * Mette insieme un modello dai pezzi. [treeBytes] (nome del file -> byte) serve solo se [family] e'
     * [ModelFamily.GBM]; [expectedSha] (nome -> SHA-256), se c'e', si controlla file per file. Qualunque
     * problema con gli alberi lascia la logistica e lo scrive in [loadProblem].
     */
    fun assemble(
      version: String,
      family: ModelFamily,
      logistic: Map<ContextTier, NowcastModel>,
      treeBytes: ((String) -> ByteArray?)?,
      expectedSha: Map<String, String>?,
      pooled: PooledPriors?,
      floors: Map<ContextTier, ObservationFloors> = ObservationFloors.none(),
      alerts: Map<ContextTier, AlertThresholds> = AlertThresholdsV3.BY_TIER,
    ): TieredNowcastModel {
      if (family == ModelFamily.LOGISTICA) {
        return TieredNowcastModel(version, family, logistic, null, pooled, floors, alerts, null)
      }
      val loaded = try {
        requireNotNull(treeBytes) { "nessuna sorgente per gli alberi" }
        ContextTier.entries.associateWith { tier ->
          val ensembles = RainWindows.ALL.indices.map { w ->
            val name = resourceName(tier, w)
            val bytes = treeBytes(name) ?: throw IllegalArgumentException("manca $name")
            if (expectedSha != null) {
              val expected = expectedSha[name] ?: throw IllegalArgumentException("nessuna impronta attesa per $name")
              val actual = sha256(bytes)
              require(actual == expected) { "impronta di $name: $actual, attesa $expected" }
            }
            val ensemble = TreeEnsemble.parse(bytes, FeatureExtractorV3.COUNT)
            require(ensemble.tier == tier.ordinal && ensemble.window == w) {
              "$name dice livello ${ensemble.tier} finestra ${ensemble.window}, atteso ${tier.ordinal}/$w"
            }
            ensemble
          }
          TreeNowcastModel(ensembles, RainWindows.ALL.map { it.label }, logistic.getValue(tier), FeatureExtractorV3.names)
        }
      } catch (e: Exception) {
        return TieredNowcastModel(version, family, logistic, null, pooled, floors, alerts, "alberi non caricati, parla la logistica: ${e.message}")
      }
      return TieredNowcastModel(version, family, logistic, loaded, pooled, floors, alerts, null)
    }

    /**
     * Il modello spedito: le tabelle di [TrainedNowcastV3], gli alberi (se la famiglia li chiede) dalle
     * risorse con [loader], i pavimenti di [FloorPoliciesV3] e le soglie di [AlertThresholdsV3].
     */
    fun trained(
      loader: (String) -> InputStream? = { name -> TieredNowcastModel::class.java.classLoader?.getResourceAsStream(name) },
    ): TieredNowcastModel = assemble(
      version = TrainedNowcastV3.VERSION,
      family = ModelFamily.valueOf(TrainedNowcastV3.FAMILY),
      logistic = TrainedNowcastV3.logisticTables(),
      treeBytes = { name -> loader(RESOURCE_DIR + name)?.use { it.readBytes() } },
      expectedSha = TrainedNowcastV3.GBM_SHA256,
      pooled = PooledPriors.decode(TrainedNowcastV3.POOLED),
      floors = FloorPoliciesV3.BY_TIER,
      alerts = AlertThresholdsV3.BY_TIER,
    )
  }
}
