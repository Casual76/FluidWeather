package dev.pampa.fluidweather.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidTheme
import dev.pampa.fluidweather.core.data.AppearanceSettingsStore
import dev.pampa.fluidweather.core.data.OnboardingStore
import dev.pampa.fluidweather.core.data.SamplingSettings
import dev.pampa.fluidweather.core.data.SamplingSettingsStore
import dev.pampa.fluidweather.core.model.AppearanceSettings
import dev.pampa.fluidweather.core.model.DayPhase
import dev.pampa.fluidweather.core.model.GlassLevel
import dev.pampa.fluidweather.core.model.SamplingMode
import dev.pampa.fluidweather.core.sensor.ActivityRecognizer
import dev.pampa.fluidweather.core.sensor.CalibrationController
import dev.pampa.fluidweather.core.sensor.SamplingScheduler
import dev.pampa.fluidweather.core.ui.SkyState
import dev.pampa.fluidweather.core.ui.WeatherAccent
import dev.pampa.fluidweather.core.ui.WeatherScene
import dev.pampa.fluidweather.core.ui.deviceGlassTier
import dev.pampa.fluidweather.core.ui.toSceneQuality
import java.time.LocalTime
import kotlinx.coroutines.launch

/** Tutto quello che il primo avvio tocca; lo costruisce :app dal suo grafo. */
class OnboardingDependencies(
  val appearanceStore: AppearanceSettingsStore,
  val samplingSettings: SamplingSettingsStore,
  val scheduler: SamplingScheduler,
  val activityRecognizer: ActivityRecognizer,
  val onboardingStore: OnboardingStore,
  val calibrationController: CalibrationController,
)

private const val PAGES = 7

/**
 * Il primo avvio (fase 15): sette pagine sopra il cielo del momento — l'app si presenta con la
 * sua faccia (decisione 2026-09-02). Tre passi narrativi coi permessi (posizione, notifiche,
 * attivita'), la scelta del vetro e dell'accuratezza, e l'ultimo passo che fa partire la
 * taratura di dieci minuti IN BACKGROUND: nessuno resta inchiodato a una schermata.
 */
@Composable
fun OnboardingScreen(deps: OnboardingDependencies, onFinished: () -> Unit) {
  val context = LocalContext.current
  val phase = remember { phaseFromClock() }
  FluidTheme(
    settings = remember { EngineSettings(themeMode = ThemeMode.DARK, dynamicColorEnabled = false) },
    brand = WeatherAccent.presetFor(null, phase),
  ) {
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0E))) {
      WeatherScene(
        state = SkyState(phase, null, null),
        quality = remember { deviceGlassTier(context).toSceneQuality() },
        modifier = Modifier.fillMaxSize(),
      )
      OnboardingPages(deps, onFinished)
    }
  }
}

@Composable
private fun OnboardingPages(deps: OnboardingDependencies, onFinished: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val pagerState = rememberPagerState(pageCount = { PAGES })
  val appearance by deps.appearanceStore.settings.collectAsState(initial = AppearanceSettings())
  val sampling by deps.samplingSettings.settings.collectAsState(initial = SamplingSettings())
  var permissionEpoch by remember { mutableIntStateOf(0) }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    permissionEpoch++
    deps.activityRecognizer.start()
  }
  fun granted(permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
  val locationGranted = remember(permissionEpoch) { granted(Manifest.permission.ACCESS_FINE_LOCATION) }
  val notificationsGranted = remember(permissionEpoch) {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS)
  }
  val activityGranted = remember(permissionEpoch) { granted(Manifest.permission.ACTIVITY_RECOGNITION) }

  fun next() {
    scope.launch { pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(PAGES - 1)) }
  }

  Column(
    Modifier
      .fillMaxSize()
      .statusBarsPadding()
      .navigationBarsPadding(),
  ) {
    HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
      Column(
        Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 28.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
      ) {
        when (page) {
          0 -> Page(
            eyebrow = "FluidWeather",
            title = "Il meteo che parte dal tuo telefono",
            body = "Dentro c'e' un barometro. Qui diventa un motore di previsione a breve, tarato su " +
              "anni di dati veri, e si aggiunge a una costellazione di servizi meteo fusi con pesi " +
              "che imparano dove sei. Tre permessi, due scelte, e si parte.",
          ) {
            FluidButton(text = "Avanti", onClick = { next() }, fillWidth = true)
          }
          1 -> Page(
            eyebrow = "1 di 3 · Posizione",
            title = "Dove sei, per davvero",
            body = "La posizione serve al cielo (che colora lo schermo), ai provider (che coprono il tuo " +
              "punto), alla riduzione al livello del mare (la quota GPS vale hPa interi) e ai pin del " +
              "radar. Resta sul telefono: ai servizi arrivano coordinate arrotondate a cento metri.",
          ) {
            PermissionActions(
              granted = locationGranted,
              grantLabel = "Consenti la posizione",
              onGrant = { launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
              onNext = { next() },
            )
          }
          2 -> Page(
            eyebrow = "2 di 3 · Notifiche",
            title = "Quando il barometro ha qualcosa da dire",
            body = "L'allerta locale, la pioggia in arrivo, le allerte ufficiali e il riepilogo del " +
              "mattino sono quattro canali separati, ognuno col suo interruttore. Con l'app aperta " +
              "arrivano come banner, mai in tendina.",
          ) {
            PermissionActions(
              granted = notificationsGranted,
              grantLabel = "Consenti le notifiche",
              onGrant = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                  launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
              },
              onNext = { next() },
            )
          }
          3 -> Page(
            eyebrow = "3 di 3 · Attivita'",
            title = "Un ascensore non e' un fronte",
            body = "Il riconoscimento dell'attivita' dice al motore quando sei in auto, in bici o in " +
              "movimento: quelle letture si scartano prima che diventino un falso cambio di tempo. " +
              "Senza, il motore si affida alla sola quota GPS.",
          ) {
            PermissionActions(
              granted = activityGranted,
              grantLabel = "Consenti il riconoscimento",
              onGrant = { launcher.launch(Manifest.permission.ACTIVITY_RECOGNITION) },
              onNext = { next() },
            )
          }
          4 -> Page(
            eyebrow = "Aspetto",
            title = "Quanto vetro",
            body = "Il vetro rifrange il cielo e costa GPU. Scegli tu il livello, oppure lascia decidere " +
              "al telefono; in risparmio energia puo' scendere di un gradino da solo.",
          ) {
            GlassChoice(appearance, deps.appearanceStore, scope)
            Spacer(Modifier.height(16.dp))
            FluidButton(text = "Avanti", onClick = { next() }, fillWidth = true)
          }
          5 -> Page(
            eyebrow = "Accuratezza",
            title = "Quanto spesso leggere il barometro",
            body = "Piu' letture, piu' segnale, piu' batteria. Bilanciata e' il compromesso suggerito; " +
              "si cambia quando vuoi dalle impostazioni.",
          ) {
            SamplingMode.entries.forEach { mode ->
              ChoiceRow(
                title = mode.label(),
                subtitle = mode.description(),
                selected = sampling.mode == mode,
                onClick = {
                  scope.launch {
                    deps.samplingSettings.setMode(mode)
                    deps.scheduler.apply(mode)
                  }
                },
              )
            }
            Spacer(Modifier.height(16.dp))
            FluidButton(text = "Avanti", onClick = { next() }, fillWidth = true)
          }
          else -> Page(
            eyebrow = "Taratura iniziale",
            title = "Dieci minuti, in sottofondo",
            body = "Adesso parte una raffica di dieci minuti a una lettura al secondo: la sua mediana, " +
              "confrontata con la pressione al mare dei servizi, stima il bias del tuo sensore. Non " +
              "serve restare qui. Poi al motore servono circa tredici ore di storia prima del primo " +
              "verdetto: la barra in home ti dice a che punto e'.",
          ) {
            FluidButton(
              text = "Inizia",
              onClick = {
                scope.launch {
                  deps.onboardingStore.setDone(true)
                  deps.scheduler.applyCurrentMode()
                  deps.calibrationController.start()
                  onFinished()
                }
              },
              fillWidth = true,
            )
          }
        }
      }
    }
    Row(
      horizontalArrangement = Arrangement.Center,
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 18.dp),
    ) {
      repeat(PAGES) { index ->
        Box(
          Modifier
            .padding(horizontal = 4.dp)
            .size(if (index == pagerState.currentPage) 10.dp else 7.dp)
            .background(
              Color.White.copy(alpha = if (index == pagerState.currentPage) 0.95f else 0.35f),
              FluidCapsuleShape,
            ),
        )
      }
    }
  }
}

@Composable
private fun Page(eyebrow: String, title: String, body: String, actions: @Composable () -> Unit) {
  val shadow = Shadow(color = Color.Black.copy(alpha = 0.35f), blurRadius = 12f)
  Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelMedium.copy(shadow = shadow), color = Color.White.copy(alpha = 0.75f))
  Spacer(Modifier.height(8.dp))
  Text(title, style = MaterialTheme.typography.headlineLarge.copy(shadow = shadow), color = Color.White, fontWeight = FontWeight.SemiBold)
  Spacer(Modifier.height(14.dp))
  Text(body, style = MaterialTheme.typography.bodyLarge.copy(shadow = shadow), color = Color.White.copy(alpha = 0.9f))
  Spacer(Modifier.height(28.dp))
  actions()
}

@Composable
private fun PermissionActions(granted: Boolean, grantLabel: String, onGrant: () -> Unit, onNext: () -> Unit) {
  if (granted) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
      Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White)
      Spacer(Modifier.width(8.dp))
      Text("Concesso", style = MaterialTheme.typography.bodyMedium, color = Color.White)
    }
    FluidButton(text = "Avanti", onClick = onNext, fillWidth = true)
  } else {
    FluidButton(text = grantLabel, onClick = onGrant, fillWidth = true)
    Spacer(Modifier.height(8.dp))
    FluidButton(text = "Piu' tardi", style = FluidButtonStyle.Plain, onClick = onNext, fillWidth = true)
  }
}

@Composable
private fun GlassChoice(
  appearance: AppearanceSettings,
  store: AppearanceSettingsStore,
  scope: kotlinx.coroutines.CoroutineScope,
) {
  GlassLevel.entries.forEach { level ->
    ChoiceRow(
      title = level.label(),
      subtitle = level.description(),
      selected = !appearance.autoGlass && appearance.manualLevel == level,
      enabled = !appearance.autoGlass,
      onClick = { scope.launch { store.setManualLevel(level) } },
    )
  }
  Spacer(Modifier.height(8.dp))
  ToggleRow("Decidi tu automaticamente", appearance.autoGlass) { scope.launch { store.setAutoGlass(it) } }
  ToggleRow("Riduci in risparmio energia", appearance.reduceOnPowerSave) { scope.launch { store.setReduceOnPowerSave(it) } }
}

@Composable
private fun ChoiceRow(title: String, subtitle: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 3.dp)
      .background(
        Color.White.copy(alpha = if (selected) 0.22f else 0.10f),
        ContinuousCornerShape(FluidRadius.Card),
      )
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 10.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = if (enabled) 1f else 0.5f))
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = if (enabled) 0.75f else 0.4f))
    }
    if (selected) Icon(Icons.Rounded.Check, contentDescription = "Scelto", tint = Color.White)
  }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp),
  ) {
    Text(title, style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.weight(1f))
    FluidSwitch(checked = checked, onCheckedChange = onChange)
  }
}

/** Senza posizione il cielo non mente ne' spegne: fase grossolana dall'orologio locale. */
private fun phaseFromClock(): DayPhase = when (LocalTime.now().hour) {
  in 6..7 -> DayPhase.DAWN
  in 8..17 -> DayPhase.DAY
  in 18..19 -> DayPhase.DUSK
  else -> DayPhase.NIGHT
}
