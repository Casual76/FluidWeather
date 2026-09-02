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

  FluidScreen(title = "Dati e privacy", onBack = onBack) {
    item { FluidSectionHeader(title = "Cosa c'e' sul telefono") }
    item {
      FluidListGroup {
        FluidListRow(title = "Letture del barometro", subtitle = "L'archivio grezzo, raffiche comprese", meta = sampleCount.toString())
        FluidListDivider()
        FluidListRow(title = "Verifiche dei provider", subtitle = "I giudizi che fanno la classifica", meta = if (verificationCount < 0) "…" else verificationCount.toString())
        FluidListDivider()
        FluidListRow(title = "Osservazioni tue", subtitle = "Le segnalazioni fatte a mano", meta = if (observationCount < 0) "…" else observationCount.toString())
      }
    }
    item {
      Text(
        "Tutto vive qui, in un database SQLite e in qualche file di preferenze. Nessun account, nessun " +
          "backend, nessuna analitica. Ai servizi meteo arrivano solo coordinate arrotondate a cento " +
          "metri e un User-Agent che dice chi siamo; le chiavi personali viaggiano solo verso il loro servizio.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      )
    }

    item { FluidSectionHeader(title = "Esporta") }
    item {
      FluidListGroup {
        FluidListRow(
          title = "Archivio barometrico in CSV",
          subtitle = exportMessage ?: "Una riga per lettura, UTC, nella cartella Download: e' cio' che il banco di prova rigioca.",
          badge = {
            FluidButton(
              text = if (exporting) "…" else "Esporta",
              style = FluidButtonStyle.Tinted,
              enabled = !exporting,
              onClick = {
                exporting = true
                scope.launch {
                  exportMessage = runCatching { exportCsv(context, deps.pressureRepository) }
                    .getOrElse { "Esportazione fallita: ${it.message}" }
                  exporting = false
                }
              },
            )
          },
        )
      }
    }

    item { FluidSectionHeader(title = "Cancella") }
    item {
      FluidListGroup {
        FluidListRow(
          title = "Cancella tutti i dati",
          subtitle = "Letture, verifiche, storico, osservazioni, taratura, istantanee. Le impostazioni restano.",
          badge = {
            FluidButton(text = "Cancella", style = FluidButtonStyle.Tinted, onClick = { confirmWipe = true })
          },
        )
      }
    }
  }

  if (confirmWipe) {
    FluidAlert(
      onDismissRequest = { confirmWipe = false },
      title = "Cancellare tutto?",
      message = "Mesi di storia barometrica non si ricostruiscono: il motore riparte da zero, taratura compresa.",
      actions = listOf(
        FluidAlertAction("Annulla", onClick = { confirmWipe = false }),
        FluidAlertAction(
          "Cancella",
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
  if (samples.isEmpty()) return@withContext "Nessuna lettura da esportare."
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
      ?: return@withContext "Impossibile creare il file in Download."
    resolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
      ?: return@withContext "Impossibile scrivere il file in Download."
    "Salvato in Download: $name (${samples.size} letture)."
  } else {
    val directory = context.getExternalFilesDir(null) ?: context.filesDir
    val file = File(directory, name)
    file.writeText(csv)
    "Salvato in ${file.absolutePath} (${samples.size} letture)."
  }
}
