package dev.pampa.fluidweather.feature.settings

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidAlert
import dev.antigravity.fluidengine.ui.fluid.FluidAlertAction
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.fluidweather.core.data.LearningRepository
import dev.pampa.fluidweather.core.data.ObservationRepository
import dev.pampa.fluidweather.core.data.PressureRepository
import dev.pampa.fluidweather.core.model.LearningCsv
import dev.pampa.fluidweather.core.model.PressureCsv
import dev.pampa.fluidweather.core.model.RainEventStore
import dev.pampa.fluidweather.core.model.RainVerificationCsv
import dev.pampa.fluidweather.core.model.VerificationStore
import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.pampa.fluidweather.strings.R
import androidx.compose.ui.res.stringResource

/** Tutto quello che la categoria "Dati e privacy" tocca. */
class DataPrivacyDependencies(
  val pressureRepository: PressureRepository,
  val verificationStore: VerificationStore,
  val observations: ObservationRepository,
  /** I giudizi della classifica pioggia: si esportano come CSV. */
  val rainEventStore: RainEventStore,
  /** L'archivio da cui il telefono impara (verdetti, feature, esiti): si esporta come CSV. */
  val learningRepository: LearningRepository,
  /** Cancella TUTTO l'archivio locale: campioni, verifiche, storico, osservazioni, taratura, istantanee, ultima posizione. */
  val wipeAll: suspend () -> Unit,
)

/**
 * Dati e privacy (fase 15): cosa sta sul telefono (e che NIENTE sta altrove), l'esportazione
 * in CSV dell'archivio barometrico (il formato che il banco di prova rigioca), dei giudizi della
 * pioggia e dell'archivio da cui il telefono impara, e il tasto che cancella tutto, con la
 * conferma che una cancellazione merita.
 */
@Composable
fun DataPrivacyScreen(deps: DataPrivacyDependencies, onBack: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val sampleCount by deps.pressureRepository.count().collectAsState(initial = 0L)
  var epoch by remember { mutableStateOf(0) }
  val verificationCount by produceState(initialValue = -1, epoch) {
    value = runCatching { deps.verificationStore.allVerifications(0L).size }.getOrDefault(0)
  }
  val observationCount by produceState(initialValue = -1, epoch) {
    value = runCatching { deps.observations.since(0L).size }.getOrDefault(0)
  }
  var confirmWipe by remember { mutableStateOf(false) }

  FluidScreen(title = stringResource(R.string.data_title), onBack = onBack) {
    item { FluidSectionHeader(title = stringResource(R.string.data_on_phone)) }
    item {
      FluidListGroup {
        FluidListRow(title = stringResource(R.string.data_readings), subtitle = stringResource(R.string.data_readings_desc), meta = sampleCount.toString())
        FluidListDivider()
        FluidListRow(title = stringResource(R.string.data_verifications), subtitle = stringResource(R.string.data_verifications_desc), meta = if (verificationCount < 0) "…" else verificationCount.toString())
        FluidListDivider()
        FluidListRow(title = stringResource(R.string.data_observations), subtitle = stringResource(R.string.data_observations_desc), meta = if (observationCount < 0) "…" else observationCount.toString())
      }
    }
    item {
      Text(
        stringResource(R.string.data_privacy_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      )
    }

    item {
      androidx.compose.material3.Text(
        stringResource(R.string.data_ai_note),
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = androidx.compose.ui.Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      )
    }
    item { FluidSectionHeader(title = stringResource(R.string.data_export)) }
    item {
      FluidListGroup {
        ExportRow(
          title = stringResource(R.string.data_export_csv),
          description = stringResource(R.string.data_export_csv_desc),
          export = { exportCsv(context, deps.pressureRepository) },
        )
        FluidListDivider()
        ExportRow(
          title = stringResource(R.string.data_export_rain),
          description = stringResource(R.string.data_export_rain_desc),
          export = {
            // A pagine, dritto nel file: sei mesi di giri orari sono oltre centomila righe, e
            // leggerle tutte piu' il loro CSV in una stringa sola finiva la memoria del telefono.
            exportRows(
              context,
              "fluidweather-verifiche-pioggia",
              count = { deps.rainEventStore.verificationCount() },
            ) { out ->
              RainVerificationCsv.write(out) { offset, limit -> deps.rainEventStore.verificationsPage(offset, limit) }
            }
          },
        )
        FluidListDivider()
        ExportRow(
          title = stringResource(R.string.data_export_learning),
          description = stringResource(R.string.data_export_learning_desc),
          export = {
            // Un verdetto l'ora per due anni: poche decine di migliaia di righe, si reggono in memoria.
            val issues = deps.learningRepository.issuesSince(0L)
            exportRows(context, "fluidweather-apprendimento", count = { issues.size }) { out ->
              out.append(LearningCsv.render(issues, deps.learningRepository.outcomesSince(0L), FeatureExtractorV3.names))
              issues.size
            }
          },
        )
      }
    }

    item { FluidSectionHeader(title = stringResource(R.string.data_delete)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.data_delete_all),
          subtitle = stringResource(R.string.data_delete_all_desc),
          badge = {
            FluidButton(text = stringResource(R.string.data_delete), style = FluidButtonStyle.Tinted, onClick = { confirmWipe = true })
          },
        )
      }
    }
  }

  if (confirmWipe) {
    FluidAlert(
      onDismissRequest = { confirmWipe = false },
      title = stringResource(R.string.data_delete_confirm),
      message = stringResource(R.string.data_delete_confirm_desc),
      actions = listOf(
        FluidAlertAction(stringResource(R.string.common_cancel), onClick = { confirmWipe = false }),
        FluidAlertAction(
          stringResource(R.string.data_delete),
          emphasis = FluidAlertAction.Emphasis.Destructive,
          onClick = {
            confirmWipe = false
            scope.launch {
              runCatching { deps.wipeAll() }
              epoch++
            }
          },
        ),
      ),
    )
  }
}

/**
 * Una riga dell'export: il suo tasto, e il suo messaggio ("Salvato in Download: ...") al posto della
 * descrizione. Ognuna tiene il proprio stato: toccarne una non spegne ne' riscrive le altre.
 */
@Composable
private fun ExportRow(title: String, description: String, export: suspend () -> String) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var message by remember { mutableStateOf<String?>(null) }
  var exporting by remember { mutableStateOf(false) }
  FluidListRow(
    title = title,
    subtitle = message ?: description,
    badge = {
      FluidButton(
        text = if (exporting) "…" else stringResource(R.string.data_export),
        style = FluidButtonStyle.Tinted,
        enabled = !exporting,
        onClick = {
          exporting = true
          scope.launch {
            message = runCatching { export() }
              .getOrElse { context.getString(R.string.data_export_failed, it.message ?: "") }
            exporting = false
          }
        },
      )
    },
  )
}

/** Dove e' finito un CSV: nella cartella Download, in una cartella dell'app, o da nessuna parte. */
private sealed interface CsvWritten {
  data class Download(val name: String) : CsvWritten
  data class Folder(val path: String) : CsvWritten
  data class Failed(val message: String) : CsvWritten
}

private fun csvFileName(prefix: String): String {
  val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(Instant.now().atZone(ZoneId.systemDefault()))
  return "$prefix-$stamp.csv"
}

/**
 * Il CSV in Download (MediaStore da Android 10), altrimenti nella cartella esterna dell'app.
 * [write] scrive direttamente nel file, bufferizzato: chi ha tanto da dire lo dice a pezzi, senza
 * costruire prima una stringa grande quanto il file.
 */
private suspend fun writeCsv(context: Context, fileName: String, write: suspend (Appendable) -> Unit): CsvWritten {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    val values = ContentValues().apply {
      put(MediaStore.Downloads.DISPLAY_NAME, fileName)
      put(MediaStore.Downloads.MIME_TYPE, "text/csv")
      put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
      ?: return CsvWritten.Failed(context.getString(R.string.data_create_failed))
    val stream = resolver.openOutputStream(uri)
      ?: return CsvWritten.Failed(context.getString(R.string.data_write_failed))
    stream.bufferedWriter(Charsets.UTF_8).use { write(it) }
    return CsvWritten.Download(fileName)
  }
  val directory = context.getExternalFilesDir(null) ?: context.filesDir
  val file = File(directory, fileName)
  file.bufferedWriter(Charsets.UTF_8).use { write(it) }
  return CsvWritten.Folder(file.absolutePath)
}

/** L'archivio barometrico: una riga per lettura, le sue parole ("letture"). */
private suspend fun exportCsv(context: Context, repository: PressureRepository): String = withContext(Dispatchers.IO) {
  val samples = repository.samplesSince(0L)
  if (samples.isEmpty()) return@withContext context.getString(R.string.data_nothing_to_export)
  when (val written = writeCsv(context, csvFileName("fluidweather-barometro")) { it.append(PressureCsv.render(samples)) }) {
    is CsvWritten.Download -> context.getString(R.string.data_saved_download, written.name, samples.size)
    is CsvWritten.Folder -> context.getString(R.string.data_saved_path, written.path, samples.size)
    is CsvWritten.Failed -> written.message
  }
}

/**
 * Gli altri due export, che parlano di "righe": [count] dice quante ce ne sono prima di creare il
 * file, [write] le scrive e dice quante ne ha scritte. Senza righe il file non si crea: "ancora
 * niente da esportare" dice la verita', un file con la sola intestazione in Download no.
 */
private suspend fun exportRows(
  context: Context,
  prefix: String,
  count: suspend () -> Int,
  write: suspend (Appendable) -> Int,
): String = withContext(Dispatchers.IO) {
  if (count() == 0) return@withContext context.getString(R.string.data_nothing_rows)
  var rows = 0
  when (val written = writeCsv(context, csvFileName(prefix)) { rows = write(it) }) {
    is CsvWritten.Download -> context.getString(R.string.data_saved_download_rows, written.name, rows)
    is CsvWritten.Folder -> context.getString(R.string.data_saved_path_rows, written.path, rows)
    is CsvWritten.Failed -> written.message
  }
}
