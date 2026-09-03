package dev.pampa.fluidweather.feature.appwidget

import android.content.ComponentName
import android.content.Context
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.pampa.fluidweather.core.data.NowcastHistoryStore
import dev.pampa.fluidweather.core.data.SavedLocationsRepository
import dev.pampa.fluidweather.core.model.UnitPreferences
import dev.pampa.fluidweather.core.weather.WeatherSnapshotStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Quel poco del grafo che serve a disegnare un widget.
 *
 * Stessa forma di `SensorRuntime` e `CycleRuntime`: il sistema istanzia il ricevitore da solo,
 * senza passare da un'Activity, e l'unico modo per arrivare al grafo e' chiedere all'Application.
 * Un'interfaccia stretta invece di `AppGraph` intero perche' cosi' il modulo non dipende da `:app`
 * — e perche' l'elenco qui sotto e' anche la dichiarazione di cosa il widget ha diritto di sapere.
 *
 * Non c'e' niente per andare in rete, e non e' una dimenticanza: il widget disegna quello che il
 * ciclo ha gia' scritto su disco.
 */
interface AppWidgetRuntime {
  val snapshotStore: WeatherSnapshotStore
  val savedLocations: SavedLocationsRepository
  val nowcastHistory: NowcastHistoryStore

  /**
   * Le unita' come stato gia' pronto: il disegno di un widget non puo' sospendere, e un
   * `runBlocking` su una lettura DataStore sarebbe la stessa trappola gia' vista nei testi
   * delle notifiche.
   */
  val unitPreferences: StateFlow<UnitPreferences>

  /** Serve al collettore che ridisegna quando cambia l'aspetto, non al disegno. */
  val engineSettings: Flow<EngineSettings>

  /** Dove porta il tocco. Lo dice `:app`, cosi' questo modulo non lo conosce. */
  val mainActivity: ComponentName
}

fun Context.appWidgetRuntime(): AppWidgetRuntime = applicationContext as AppWidgetRuntime
