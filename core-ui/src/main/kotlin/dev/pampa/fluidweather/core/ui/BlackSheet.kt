package dev.pampa.fluidweather.core.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidGrabber
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.ui.haptics.rememberFluidHaptics
import dev.pampa.fluidweather.strings.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/** Il nero delle pagine di dettaglio: una stanza buia sopra il cielo, non una surface scura. */
val BlackSheetColor: Color = Color(0xFF0B0B0E)

/**
 * Il foglio NERO del piano: sale dal basso, prende TUTTA la pagina, si chiude con la X o
 * trascinando giu'. Ospita le pagine dei widget e — per decisione del 2026-09-02 — anche
 * Benchmark e Segnalazione. E' un `ModalBottomSheet` e non un pannello di vetro in
 * composizione perche' e' opaco per scelta: non ha niente da rifrangere.
 *
 * A tutta altezza sempre, anche con poco contenuto: la pagina di un dato e' una pagina, non un
 * riquadro che cambia taglia a seconda di quanto ha da dire.
 */
/**
 * Lo stesso foglio nero, ma **pigro**.
 *
 * Esiste per le pagine lunghe. `BlackSheet` mette il contenuto in una `Column` con
 * `verticalScroll`: tutto viene composto, misurato e posizionato in un colpo solo all'apertura, e
 * a ogni fotogramma di scorrimento il contenitore ri-posiziona **tutti** i figli. Per la pagina
 * Orario sono fino a 246 ore per una decina di nodi l'una — circa 2.400 — e si vedeva: misurata su
 * un Galaxy S25, l'apertura passava l'88% dei fotogrammi oltre il budget (89 ms l'uno, il peggiore
 * 400) e lo scorrimento dentro la pagina il 100% (85 ms).
 *
 * Le altre nove pagine non hanno questo problema (dieci giorni, una manciata di inquinanti, una
 * tabella corta) e restano su `BlackSheet`: convertirle vorrebbe dire riscrivere ogni `PageSection`
 * e ogni `Spacer` in un `item {}` per non guadagnare niente. Il guscio — foglio, maniglia, titolo,
 * tasto di chiusura, aptica, superficie dei suggerimenti — e' lo stesso di [BlackSheet], condiviso.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlackSheetLazy(
  title: String,
  onDismiss: () -> Unit,
  content: LazyListScope.() -> Unit,
) {
  BlackSheetChrome(title = title, onDismiss = onDismiss) { dismiss, header ->
    LazyColumn(
      modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight()
        .padding(horizontal = 20.dp)
        .navigationBarsPadding(),
    ) {
      item(key = "black-sheet-title") { header(dismiss) }
      content()
      item(key = "black-sheet-tail") { Spacer(Modifier.height(28.dp)) }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlackSheet(
  title: String,
  onDismiss: () -> Unit,
  content: @Composable ColumnScope.() -> Unit,
) {
  BlackSheetChrome(title = title, onDismiss = onDismiss) { dismiss, header ->
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight()
        .padding(horizontal = 20.dp)
        .navigationBarsPadding()
        .verticalScroll(rememberScrollState()),
    ) {
      header(dismiss)
      content()
      Spacer(Modifier.height(28.dp))
    }
  }
}

/** Il titolo di sezione dentro un foglio nero. */
@Composable
fun BlackSheetSectionTitle(text: String) {
  Text(
    text = text.uppercase(),
    style = MaterialTheme.typography.labelMedium,
    color = Color.White.copy(alpha = 0.55f),
    modifier = Modifier
      .padding(top = 14.dp, bottom = 6.dp)
      // Un titolo di sezione: TalkBack lo annuncia come intestazione e ci salta sopra.
      .semantics { heading() },
  )
}

/** Una nota di testo dentro un foglio nero. */
@Composable
fun BlackSheetNote(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = Color.White.copy(alpha = 0.7f),
  )
}

/**
 * Il guscio del foglio nero: la finestra, la maniglia, il titolo col tasto di chiusura, l'aptica
 * di apertura e chiusura, e la superficie dei suggerimenti. Le due varianti — a colonna e pigra —
 * differiscono solo per il contenitore che scorre, e questo e' tutto il resto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlackSheetChrome(
  title: String,
  onDismiss: () -> Unit,
  body: @Composable BoxScope.(dismiss: () -> Unit, header: @Composable (() -> Unit) -> Unit) -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  // Il foglio prende tutta la pagina: la sua apertura e la sua chiusura si sentono come quelle
  // di un pannello dell'engine, altrimenti l'unica cosa muta dell'app sarebbe la piu' grande.
  val haptics = rememberFluidHaptics()
  LaunchedEffect(Unit) { haptics.play(FluidHapticEvent.Open) }
  val dismiss = {
    haptics.play(FluidHapticEvent.Close)
    onDismiss()
  }
  val header: @Composable (() -> Unit) -> Unit = { close ->
    Column {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.SemiBold,
          color = Color.White,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = close) {
          Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_close), tint = Color.White)
        }
      }
      Spacer(Modifier.height(8.dp))
    }
  }
  ModalBottomSheet(
    onDismissRequest = dismiss,
    sheetState = sheetState,
    modifier = Modifier.fillMaxHeight(),
    containerColor = BlackSheetColor,
    contentColor = Color.White,
    shape = ContinuousCornerShape(topStart = FluidRadius.Sheet, topEnd = FluidRadius.Sheet),
    dragHandle = { FluidGrabber(Modifier.statusBarsPadding()) },
    contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
  ) {
    // Il foglio vive in una finestra sua: i suggerimenti delle sue pagine hanno bisogno di un
    // padrone di casa qui dentro, o finirebbero dietro al foglio con le coordinate sbagliate.
    TutorialSurface {
      body(dismiss, header)
    }
  }
}
