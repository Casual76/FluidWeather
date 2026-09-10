# FluidWeather

Il meteo che parte dal tuo telefono. Il barometro del dispositivo diventa un motore di previsione
a breve termine (nowcast 0-6 ore), tarato su anni di dati reali e verificato su un banco di prova
che gira sul computer; attorno, una costellazione di servizi meteo fusi con pesi che imparano
dove sei, il radar, la qualita' dell'aria, sole e luna, le notifiche, il benchmark dei provider e
le segnalazioni di chi guarda fuori dalla finestra.

Costruita sul [Fluid Engine](https://github.com/Casual76/fluid-engine) (design system Fluid,
aggiornamento in-app, config remota), distribuita fuori dagli store tramite il Pampa Store.

## Struttura

| modulo | cosa contiene |
|---|---|
| `nowcast`, `testbench` | la pipeline barometrica pura (JVM): pulizia, de-tidalizzazione, feature, modello, apprendimento; il banco che la rigioca sugli archivi |
| `core-model`, `core-data`, `core-sensor`, `core-weather`, `core-cycle` | dominio, archivio Room/DataStore, campionamento del barometro, livello provider e fusione, ciclo in background e notifiche |
| `core-strings`, `core-ui` | tutte le parole (italiano e inglese) e i mattoni di interfaccia |
| `feature-*`, `app` | home, radar, benchmark, segnalazione, impostazioni; il grafo dell'app |

## Compilare

Serve `local.properties` con `sdk.dir`. Facoltativi, sempre fuori da git:

- `maps.apiKey` — la chiave Google Maps del radar (senza, il radar spiega perche' non si apre);
- `pampa.storeFile`, `pampa.storePassword`, `pampa.keyAlias`, `pampa.keyPassword` — la firma di
  release (senza, la release resta non firmata).

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

Vedi [LICENSE](LICENSE). I dati meteo hanno le attribuzioni elencate in Impostazioni > Informazioni.
