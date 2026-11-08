# F3 B - contratto della presentazione home

Implementazione UI nativa Compose, engine 3.0.5 e dipendenze fissate esistenti. Nessuna esecuzione Gradle/build/device, commit o push da B. Parent responsabile di integrazione e QA.

## Interfacce e file

- `HomeRainOverview(brief: WeatherBrief?, locationName: String, modifier: Modifier, nowMillis: Long? = null)` ? pubblico e isolabile nei test. L'orologio opzionale serve ai test; in produzione si aggiorna ogni 30 secondi solo in RESUMED, ed esce dalla composizione con la testata lazy.
- `HomeRainPresentation` contiene policy pure per headline, finestra ETA e segmentazione della timeline; non valuta il meteo, non interroga provider/radar e non trasforma la previsione in stato presente.
- `HomeScreen` consuma `state.weatherBrief` e `rememberHomeState(..., active = !coveredByPage)` forniti da A. La testata resta l'item `header`, escluso da GridDragController, fuori dagli ID delle tessere. `HomeWidget.ordered`, ordine completo persistito, aperture e requestedWidget restano nel flusso esistente.
- Nuovi `home_rain.xml` EN/IT e `RainSourceFormatter` in core-strings. I dati di dettaglio continuano a provenire da HomeUiState.
- `SkyState` aggiunge solo parametri finali con default, senza Android: precipitation e assessmentKnown. I chiamanti widget/preview precedenti mantengono la resa precedente. Policy pura `forRendering` e `sceneMotionAllowed`; stessa pipeline in SkyPalette/SkyPainters/WeatherScene.

## Gerarchia e accessibilit?

Localita e condizioni/temperatura compatte precedono la frase pioggia (`heading` semantico), la prossima transizione e il limite essenziale. Un solo pannello usa il backdrop esistente; una fascia tonale da MaterialTheme stabilizza il contrasto. Tipografia e forme sono token del tema. Niente temperatura a 108sp, righe in card separate, CTA inerti o nuove catture. La timeline segue subito la risposta; provenienza compatta e tempi originali restano accessibili sotto. Font grandi possono andare a capo senza maxLines/ellissi/altezze fisse. Lo scroll della griglia e libero: rimosso lo snap dell'intera testata che poteva saltare righe al 200%.

Parent QA integration: the first real phone capture showed a repeated long timeline legend and
two source paragraphs occupying too much of the first viewport. The visible legend now lists only
the distinct states; full intervals/amounts remain in its single accessibility description. Each
source has a compact original-time/age line; a Fluid Plain button expands all original interval,
source and download facts. It yields to scrolling and exposes its expanded state for TalkBack.
Main answer and essential precision caveats require no expansion. The inner tonal band is slightly
translucent using the theme surface, with the existing glass backdrop still shared.

La timeline ha una sola descrizione accessibile con intervalli, stati e quantita realmente disponibili. Nessuna semantica duplicata per rettangolo/pixel. I buchi hanno tratteggio diagonale; zero reale resta un segmento noto. Non vengono mostrati quantitativi prorati quando un intervallo e tagliato. Date/ore usano TimeFormats e locale; fuso da WeatherZones.resolve(place.zoneId, null, device), con fallback del dispositivo dichiarato. Parent integration carries known matching snapshot/context zone in the brief place before falling back to device zone. The approximate radar time bucket retains its full source resolution, even when its lower bound has just passed; elapsed time does not narrow prediction precision.

## Fedelt?

MODEL WET/DRY ? sempre stima recente; il radar DRY significa assenza di eco e non prova al suolo. Conflitto, incertezza e indisponibilit? hanno frasi proprie. Nessuna misura locale ? inventata dal sensore. Un ETA richiede evidenza radar, stato utilizzabile e assenza di conflitti/limiti di scadenza; viene soppresso se gi? passato al rendering. Si presenta una fascia assoluta di almeno 10 minuti, con circa, non un countdown. L'et? usa timestamp della fonte, fine intervallo fisico e download originali; mai evaluatedAt. Provenienza corrente e accumulo precedente restano distinguibili. Il limite MODEL avverte della possibile interpolazione oraria e dell'assenza di un arrivo attendibile. Nessuna conversione dBZ/mm.

## Cielo e movimento

La condizione corrente del brief alimenta il cielo; l'assessment presente determina se sono ammessi pioggia/neve/lampi. Unknown e conflitto mantengono l'atmosfera ma sopprimono precipitazioni certe. Il cielo usa gli stessi pennelli: ombre nuvole e foschia sono modulate nella stessa pittura, senza layer GPU decorativi aggiuntivi. Conservati qualit? adattiva e passo massimo 30Hz. Frame e transizioni colore condividono il gate qualit? non STATIC, decorative motion, running, lifecycle RESUMED. running ? falso se coperto o dopo l'uscita della testata dal viewport. Reduced motion elimina anche animazione della pillola compatta, ritorno animato in cima e animateItem della griglia.

## Test scritti, non eseguiti

- `HomeRainPresentationTest`: modello wet/dry qualificato, radar no echo, conflitto/unknown, ETA scaduto e finestra radar, interpolazione, stale indisponibile, buchi vs zero, duplicati, clipping senza prorata, fusi e locale.
- `SkyAssessmentPolicyTest`: backward compatibility, soppressione pioggia con assenza/conflitto/dry, wet corrente e gate motion/qualit?/lifecycle.
- `HomeRainOverviewTest` in app/androidTest: risposta e timeline senza scroll a 360/411dp, heading unknown e conflitto localizzati, assenza azioni click/long-click dell'overview, layout testo a 2x senza overflow e timeline raggiungibile. Viewport sintetico 760dp; nessuna intera app o sorgente finta. App aggiunge soltanto ui-test-junit4 e debug ui-test-manifest, con BOM engine.composeBom. Runner/versioni AndroidX test erano gi? presenti.

Parent deve compilare, eseguire test e verificare su device disposizione reale (status bar e chrome inclusi), leggibilit?, gesto di riordino escluso sulla testata, scenari meteorologici, lingue, 200% e riduzione movimento. L'assenza di azioni semantiche non certifica da sola tutti i gesti della griglia. Non sono dichiarati TalkBack verificato, accuratezza C2 o confronto prestazioni C6 entro il 10%.

The focused Compose suite now includes a source-details expand/collapse test. Initial Android17
execution failed before layout assertions in Espresso's removed reflective InputManager.getInstance
path (wiring passed). The official AndroidX Test release notes document the fix in stable
Espresso3.7.0: https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0 .
App test-only dependency now explicitly selects3.7.0 through versions-app.gradle; engine and
production dependency versions are unchanged. Actual rerun results must be recorded by parent.
