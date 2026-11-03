# FluidWeather

Il meteo che parte dal tuo telefono. Il barometro del dispositivo diventa un motore di previsione
a breve termine (nowcast 0-6 ore), tarato su anni di dati reali e verificato su un banco di prova
che gira sul computer; attorno, una costellazione di servizi meteo fusi con pesi che imparano
dove sei, il radar, la qualita' dell'aria, sole e luna, le notifiche, il benchmark dei provider e
le segnalazioni di chi guarda fuori dalla finestra.

Costruita sul [Fluid Engine](https://github.com/Casual76/fluid-engine) (design system Fluid,
aggiornamento in-app, config remota, assistente), distribuita fuori dagli store tramite il Pampa Store.

## Struttura

| modulo | cosa contiene |
|---|---|
| `nowcast`, `testbench` | la pipeline barometrica pura (JVM): pulizia, de-tidalizzazione, feature, modello, apprendimento; il banco che la rigioca sugli archivi |
| `core-model`, `core-data`, `core-sensor`, `core-weather`, `core-cycle` | dominio, archivio Room/DataStore, campionamento del barometro, livello provider e fusione, ciclo in background e notifiche |
| `core-strings`, `core-ui` | tutte le parole (italiano e inglese) e i mattoni di interfaccia |
| `feature-*`, `app` | home, radar, benchmark, segnalazione, impostazioni; il grafo dell'app |

## Compilare

Serve `local.properties` con `sdk.dir`. Facoltative, sempre fuori da git:

- `pampa.storeFile`, `pampa.storePassword`, `pampa.keyAlias`, `pampa.keyPassword` — la firma di
  release (senza, la release resta non firmata).

Il radar non chiede chiavi: la mappa e' MapLibre con i tile di OpenFreeMap (prima era Google Maps,
la cui chiave `maps.apiKey` non si legge piu'). Neanche la posizione passa piu' da Google: e' il
`LocationManager` di Android, senza Play Services.

```bash
./gradlew.bat --no-daemon :app:assembleDebug testDebugUnitTest :core-model:test :nowcast:test :testbench:test
```

## Il banco

Il banco rigioca la pipeline su quattro anni di archivi (dieci localita', Open-Meteo) fingendo di
essere un telefono: cadenza a quindici minuti, raffiche col rumore del sensore, quota GPS che
balla, buchi in Doze, letture secche. Non e' un dettaglio di realismo — e' cio' su cui il modello
viene addestrato *e* giudicato, e prima non lo era.

```bash
./gradlew.bat --no-daemon :testbench:run --args=fetch          # gli archivi, una volta
./gradlew.bat --no-daemon :testbench:run --args=stages         # una metrica per stadio
./gradlew.bat --no-daemon :testbench:run --args=train          # rigenera TrainedNowcastV1.kt
./gradlew.bat --no-daemon :testbench:run --args=replay-oos     # la resa dei conti, fuori campione
./gradlew.bat --no-daemon :testbench:run --args=replay-ideale  # quanto costa essere un telefono
```

I rapporti finiscono in `testbench/reports/`.

## Rilascio

`manifest.json` alla radice e' la fonte unica: lo legge il Pampa Store per mostrare l'app, e la
legge l'app stessa per aggiornarsi (sezione `app`) e per i flag remoti (sezione `engine`). Le
release sono GitHub Release di questo repo, pubblicate col publisher del Pampa Store.

## Licenza

FluidWeather e' software libero: si distribuisce sotto la **GNU General Public License, versione 3**
(testo completo in [`LICENSE`](LICENSE)). Chi riceve l'APK ha diritto ai sorgenti, che sono questo
repository, pubblico: <https://github.com/Casual76/FluidWeather>. Si possono leggere, modificare e
ridistribuire alle condizioni della GPL-3.0, con la stessa licenza.

**Perche' GPL-3.0.** Dalla 3.0 il Fluid Engine contiene codice di
[LibrePods](https://github.com/librepods-org/librepods) (GPL-3.0): il comportamento dei pulsanti e
delle pagine non e' piu' riscritto, e' portato. Un'app che distribuisce l'engine e' un'opera
derivata, e va distribuita con la stessa licenza: e' la regola di tutte le app Pampa che lo usano.
Prima di questa versione FluidWeather era Apache-2.0. Per poter essere GPL-3.0 senza pezzi
proprietari dentro, il radar e' passato da Google Maps a MapLibre, e la posizione dal servizio di
Google Play al `LocationManager` di Android.

Le opere di altri che l'APK porta con se' sono elencate nella pagina **Impostazioni > Informazioni**
dell'app, con gli avvisi che le loro licenze chiedono: il vetro di Kyant (Apache-2.0), i caratteri
Inter (SIL OFL 1.1), LibrePods (GPL-3.0), MapLibre Native Android e i suoi gesti (BSD-2-Clause),
OkHttp e Okio, Gson, geojson e turf (Apache-2.0); i dati della mappa sono © OpenMapTiles e ©
OpenStreetMap contributors (ODbL), serviti da OpenFreeMap (MIT). I testi delle licenze dell'engine
sono in [`engine/LICENSES/`](engine/LICENSES).

Il servizio di previsioni RainViewer (le immagini del radar) e gli altri servizi meteo restano
servizi di rete con le loro condizioni d'uso: non sono parte del programma.

