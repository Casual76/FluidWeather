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
import dev.pampa.fluidweather.core.data.ObservationRepository
import dev.pampa.fluidweather.core.data.PressureRepository
import dev.pampa.fluidweather.core.model.PressureCsv
import dev.pampa.fluidweather.core.model.VerificationStore
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
  /** Cancella TUTTO l'archivio locale: campioni, verifiche, storico, osservazioni, taratura, istantanee. */
  val wipeAll: suspend () -> Unit,
)

/**
 * Dati e privacy (fase 15): cosa sta sul telefono (e che NIENTE sta altrove), l'esportazione
 * dell'archivio barometrico in CSV (il formato che il banco di prova rigioca), e il tasto che
 * cancella tutto, con la conferma che una cancellazione merita.
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
  var exportMessage by remember { mutableStateOf<String?>(null) }
  var exporting by remember { mutableStateOf(false) }
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
        FluidListRow(
          title = stringResource(R.string.data_export_csv),
          subtitle = exportMessage ?: stringResource(R.string.data_export_csv_desc),
          badge = {
            FluidButton(
              text = if (exporting) "…" else stringResource(R.string.data_export),
              style = FluidButtonStyle.Tinted,
              enabled = !exporting,
              onClick = {
                exporting = true
                scope.launch {
                  exportMessage = runCatching { exportCsv(context, deps.pressureRepository) }
                    .getOrElse { context.getString(R.string.data_export_failed, it.message ?: "") }
                  exporting = false
                }
              },
            )
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

/** Il CSV in Download (MediaStore da Android 10), altrimenti nella cartella esterna dell'app. */
private suspend fun exportCsv(context: Context, repository: PressureRepository): String = withContext(Dispatchers.IO) {
  val samples = repository.samplesSince(0L)
  if (samples.isEmpty()) return@withContext context.getString(R.string.data_nothing_to_export)
  val csv = PressureCsv.render(samples)
  val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(Instant.now().atZone(ZoneId.systemDefault()))
  val name = "fluidweather-barometro-$stamp.csv"
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    val values = ContentValues().apply {
      put(MediaStore.Downloads.DISPLAY_NAME, name)
      put(MediaStore.Downloads.MIME_TYPE, "text/csv")
      put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
      ?: return@withContext context.getString(R.string.data_create_failed)
    resolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
      ?: return@withContext context.getString(R.string.data_write_failed)
    context.getString(R.string.data_saved_download, name, samples.size)
  } else {
    val directory = context.getExternalFilesDir(null) ?: context.filesDir
    val file = File(directory, name)
    file.writeText(csv)
    context.getString(R.string.data_saved_path, file.absolutePath, samples.size)
  }
}
