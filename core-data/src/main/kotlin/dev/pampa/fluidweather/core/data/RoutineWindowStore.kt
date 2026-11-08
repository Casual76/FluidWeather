package dev.pampa.fluidweather.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dev.pampa.fluidweather.core.model.RoutineWindow
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.routinePreferences by preferencesDataStore(
  name = "routine_windows",
  corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Dedicated personal settings; archive-only deletion must not clear this store. */
class RoutineWindowStore internal constructor(private val store: DataStore<Preferences>) {
  constructor(context: Context) : this(context.applicationContext.routinePreferences)

  val windows: Flow<List<RoutineWindow>> = store.data.map { RoutineWindowCodec.decode(it[KEY]) ?: emptyList() }
  suspend fun current(): List<RoutineWindow> = windows.first()

  suspend fun upsert(window: RoutineWindow) = update { old ->
    if (old.any { it.id == window.id }) old.map { if (it.id == window.id) window else it }
    else old + window
  }
  suspend fun remove(id: String) = update { old -> old.filterNot { it.id == id } }
  suspend fun clear() { store.edit { it.remove(KEY) } }

  private suspend fun update(transform: (List<RoutineWindow>) -> List<RoutineWindow>) {
    store.edit { preferences ->
      // Do not overwrite unreadable/future settings on an unrelated edit. Explicit clear recovers.
      val old = RoutineWindowCodec.decode(preferences[KEY])
        ?: throw IOException("Unsupported or malformed routine settings")
      preferences[KEY] = RoutineWindowCodec.encode(transform(old))
    }
  }
  private companion object { val KEY = stringPreferencesKey("rules") }
}

/** Bounded, length-prefixed UTF-8, version 1; failure rejects the entire document. */
internal object RoutineWindowCodec {
  private const val MAX_BYTES = 1_048_576
  private const val MAX_RULES = 1024
  fun encode(windows: List<RoutineWindow>): String {
    require(windows.size <= MAX_RULES && windows.map { it.id }.distinct().size == windows.size)
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
      out.writeInt(1)
      out.writeInt(windows.size)
      windows.forEach { w ->
        for (s in listOf(w.id, w.name)) {
          val b = s.toByteArray(Charsets.UTF_8)
          require(b.size <= MAX_BYTES && b.toString(Charsets.UTF_8) == s)
          out.writeInt(b.size); out.write(b)
        }
        out.writeInt(w.daysOfWeek.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) })
        out.writeLong(w.start.toNanoOfDay()); out.writeLong(w.end.toNanoOfDay())
      }
    }
    require(bytes.size() <= MAX_BYTES)
    return Base64.getEncoder().encodeToString(bytes.toByteArray())
  }
  fun decode(encoded: String?): List<RoutineWindow>? {
    if (encoded == null) return emptyList()
    if (encoded.length > MAX_BYTES * 2) return null
    return try {
      val bytes = Base64.getDecoder().decode(encoded)
      require(bytes.size <= MAX_BYTES)
      DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == 1)
        val count = input.readInt(); require(count in 0..MAX_RULES)
        fun string(): String {
          val size = input.readInt(); require(size in 0..MAX_BYTES && size <= input.available())
          val b = ByteArray(size); input.readFully(b)
          return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()
        }
        val result = List(count) {
          val id = string(); val name = string(); val mask = input.readInt()
          require(mask in 1..127)
          RoutineWindow(id, name, DayOfWeek.values().filter { mask and (1 shl (it.value - 1)) != 0 }.toSet(),
            LocalTime.ofNanoOfDay(input.readLong()), LocalTime.ofNanoOfDay(input.readLong()))
        }
        require(input.available() == 0 && result.map { it.id }.distinct().size == result.size)
        result
      }
    } catch (_: IllegalArgumentException) { null }
      catch (_: IOException) { null }
      catch (_: java.time.DateTimeException) { null }
  }
}
