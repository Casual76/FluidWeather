package dev.pampa.fluidweather.feature.settings

import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.*
import dev.antigravity.fluidengine.ui.theme.*
import dev.pampa.fluidweather.core.data.RoutineWindowStore
import dev.pampa.fluidweather.core.model.RoutineWindow
import dev.pampa.fluidweather.strings.*
import dev.pampa.fluidweather.strings.R
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutineWindowsScreen(store: RoutineWindowStore, onBack: () -> Unit) {
  val context = LocalContext.current
  val locale = LocalConfiguration.current.locales[0]
  val scope = rememberCoroutineScope()
  var windows by remember(store) { mutableStateOf<List<RoutineWindow>?>(null) }
  var readError by remember(store) { mutableStateOf(false) }
  var editingId by rememberSaveable { mutableStateOf<String?>(null) }
  var existing by rememberSaveable { mutableStateOf(false) }
  var name by rememberSaveable { mutableStateOf("") }
  var days by rememberSaveable { mutableIntStateOf(0) }
  var start by rememberSaveable { mutableIntStateOf(-1) }
  var end by rememberSaveable { mutableIntStateOf(-1) }
  var invalid by rememberSaveable { mutableStateOf(false) }
  var writeError by rememberSaveable { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }
  BackHandler(enabled = editingId != null) {
    if (!busy) editingId = null
  }
  LaunchedEffect(store) {
    try { store.windows.collect { windows = it; readError = false } }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { readError = true }
  }
  fun edit(window: RoutineWindow?) {
    editingId = window?.id ?: UUID.randomUUID().toString()
    existing = window != null
    name = window?.name.orEmpty()
    days = window?.daysOfWeek?.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) } ?: 0
    start = window?.start?.let { it.hour * 60 + it.minute } ?: -1
    end = window?.end?.let { it.hour * 60 + it.minute } ?: -1
    invalid = false; writeError = false
  }
  fun change(remove: Boolean) {
    val id = editingId ?: return
    val rule = validatedRoutine(id, name, days, start, end)
    if (!remove && rule == null) { invalid = true; return }
    busy = true; writeError = false
    scope.launch {
      try {
        if (remove) store.remove(id) else store.upsert(requireNotNull(rule))
        editingId = null
      } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { writeError = true }
      finally { busy = false }
    }
  }
  FluidScreen(title = stringResource(R.string.routines_title), onBack = {
    if (!busy) { if (editingId != null) editingId = null else onBack() }
  }) {
    item { Text(stringResource(R.string.routines_description), style = MaterialTheme.typography.bodyMedium) }
    if (readError) item { Text(stringResource(R.string.routines_read_error), color = MaterialTheme.colorScheme.error) }
    if (editingId != null) {
      item {
        FluidTextField(name, { name = it }, label = stringResource(R.string.routines_name), enabled = !busy,
          modifier = Modifier.fillMaxWidth(), isError = invalid && name.isBlank())
      }
      item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.routines_days), style = MaterialTheme.typography.titleSmall)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DayOfWeek.entries.forEach { day ->
              val bit = 1 shl (day.value - 1)
              FluidChip(day.getDisplayName(TextStyle.FULL, locale), days and bit != 0, { days = days xor bit }, enabled = !busy)
            }
          }
        }
      }
      item {
        FluidListGroup {
          listOf(true, false).forEachIndexed { index, isStart ->
            if (index > 0) FluidListDivider()
            val value = if (isStart) start else end
            FluidListRow(
              title = stringResource(if (isStart) R.string.routines_start else R.string.routines_end),
              subtitle = if (value < 0) stringResource(R.string.routines_choose_time) else TimeFormats.clock(value / 60, value % 60, locale),
              onClick = if (busy) null else ({
                val initial = value.coerceAtLeast(0)
                TimePickerDialog(context, { _, hour, minute -> if (isStart) start = hour * 60 + minute else end = hour * 60 + minute },
                  initial / 60, initial % 60, TimeFormats.is24Hour(locale)).show()
              }),
            )
          }
        }
      }
      if (start >= 0 && end >= 0 && end < start) item { Text(stringResource(R.string.routines_overnight)) }
      if (invalid) item { Text(stringResource(R.string.routines_invalid), color = MaterialTheme.colorScheme.error) }
      if (writeError) item { Text(stringResource(R.string.routines_error), color = MaterialTheme.colorScheme.error) }
      item { FluidButton(stringResource(R.string.routines_save), onClick = { change(false) }, enabled = !busy, loading = busy) }
      item { FluidButton(stringResource(R.string.routines_cancel), style = FluidButtonStyle.Plain, enabled = !busy, onClick = { editingId = null }) }
      if (existing) item { FluidButton(stringResource(R.string.routines_delete), style = FluidButtonStyle.Destructive, enabled = !busy, onClick = { change(true) }) }
    } else if (windows != null && !readError) {
      if (windows!!.isEmpty()) item { Text(stringResource(R.string.routines_empty)) }
      windows!!.forEach { window ->
        item(key = window.id) {
          FluidListGroup {
            FluidListRow(title = window.name, subtitle = routineRuleLabel(window, locale) +
              if (window.crossesMidnight) "\n" + stringResource(R.string.routines_overnight) else "", onClick = { edit(window) })
          }
        }
      }
      item { FluidButton(stringResource(R.string.routines_add), onClick = { edit(null) }) }
    }
  }
}
