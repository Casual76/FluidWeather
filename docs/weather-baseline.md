# FluidWeather F0: baseline e protocollo pioggia

Snapshot di partenza: `2445bd80ba59900bf50a0ce8f9d62f9e399fc53c`, 2026-11-07.
Identificatore fixture: `rain-validation-v1`. Questa aggiunta non cambia il comportamento
dell'app. Il parent possiede `weather-improvement-status.md`, integrazione, esecuzioni e capture.
Nessuna build, test, app o dispositivo avviato dall'implementer F0.

Hash SHA-256 del pacchetto F0 (prima dell'integrazione del parent):

| File (nelle directory validation/rain-validation autorizzate) | SHA-256 |
|---|---|
| RainValidation.kt | `1DC9D5CAAF9CE1F002A3470A8F4BC4748D6BDA2BCB5646CFA53985C9DB1A11F3` |
| RainValidationTest.kt | `C004E783E0FBEE8214D1ACA845B29CA0FE544845AA02BDD2CC2D04217B3B5B28` |
| records.tsv | `C3ED1047BC129B36DD3A0150C3B2CFA4444FE0E2EBAFCBC7833CCB69F76506AC` |
| scenario-inputs.tsv | `C752ABCB96AE3A1C32E2E323C04F7913ECD0522D231BF92DBF238E171557A95C` |
| targets.tsv | `B60806C4D0DC7D6D9169EF2D61C128D6119A72C65E1B580F26B9E7AA12946FAC` |

## Contratto letto e limiti

- `README.md`, `testbench/build.gradle`: banco JVM, Java 17, JUnit 4 già disponibile;
  main esistente `dev.pampa.fluidweather.testbench.MainKt`. Nessuna dipendenza aggiunta.
- `Forecast.kt`: `MinutePoint` contiene accumuli mm del quarto d'ora precedente al
  timestamp, non misure istantanee. `rainNowMm` sceglie l'ultimo punto concluso entro
  45 minuti; `rainLastHourMm` richiede almeno quattro valori non null; `rainSoonMm`
  somma punti futuri e oggi tratta i null come zero. Questi sono contratti legacy,
  non regole del nuovo valutatore né garanzie di completezza delle serie.
- `RainObservations.kt`: intensità = accumulo del quarto * 4; nella fusione vince
  la fonte che segnala pioggia. Il nome "observation" non rende indipendente un modello.
- `RadarSampler.kt`: finestra 128 px, zoom 7, ultimi 7 frame passati, moto sugli ultimi
  4; il codice conserva un percorso per fino a 3 frame futuri, che il servizio pubblico
  attuale non offre. Età >20 minuti riduce confidenza di 0,15, non blocca la lettura;
  copertura bassa e assenza eco possono ancora arrivare come lettura con note.
- `EtaEstimator.kt`: soglia 10 dBZ, advezione del frame corrente a passi di 5 minuti,
  orizzonte massimo 90 minuti limitato dalla finestra. Se ora è asciutto cerca il primo
  arrivo; se piove cerca la prima fine. Non descrive più episodi completi.
- `HomeData.kt`: serie `observedPrecipitation` derivata dalle ore di un provider;
  non è ground truth locale. La home conserva l'ultimo dato senza tetto di età;
  barometro applicabile solo al luogo GPS con sensore disponibile. Snapshot senza ore
  preserva i valori precedenti aggiornando la data: conservare anche questo caso in capture.

Fonti primarie controllate il 2026-11-07:
[RainViewer transition FAQ](https://www.rainviewer.com/api/transition-faq.html) e
[Weather Maps API](https://www.rainviewer.com/api/weather-maps-api.html): dal 2026-01-01
niente frame futuri pubblici, massimo zoom 7, passato di circa 2 ore a cadenza 10 minuti.
La pagina introduttiva contiene ancora riferimenti a nowcast: usare FAQ di transizione,
contratto effettivo e payload catturato, senza fabbricare frame mancanti.
[Open-Meteo docs](https://open-meteo.com/en/docs): condizioni correnti da modello;
serie 15 minuti possono essere interpolate, in base a regione/variabile. Un modello
non misura la pioggia al telefono. Cadenza o passo numerico non significano accuratezza
temporale: nessuna promessa di arrivo/fine al minuto esatto.

## Campioni C1.scenarios

Tutti i file di `testbench/src/test/resources/rain-validation/` sono **SYNTHETIC**.
Coordinate e orari sono inventati. `scenario-inputs.tsv` conserva campioni etichettati
con clock fisso `2026-11-07T10:00:00Z`, quarto concluso, mm, frame, dBZ e copertura.
`-` significa dato assente. Le descrizioni legacy sono dedotte dai sorgenti letti,
non risultati di esecuzioni dell'app. Le intenzioni future sono specifiche, non assert
su feature implementate. Per arrivo/fine il campione contiene ETA illustrativi: non
contiene tile/griglie di moto e non è un replay di RadarSampler/EtaEstimator.

| Caso | Campione / legacy | Intento futuro (non implementato F0) |
|---|---|---|
| Pioggia presente | 0,25 mm/15 min, radar 20 dBZ, copertura 1; merge pioggia | Presente con luogo, fonte, intervallo ed età |
| Arrivo | Quarto asciutto, punto radar asciutto; ETA illustrativo +10 min da moto | Finestra di arrivo e confidenza, separata dal presente |
| Fine | Pioggia attuale; ETA illustrativo +15 min da moto | Finestra di fine, nessuna falsa precisione |
| Intermittente | Tre slot contigui: riferimento RAIN/DRY/RAIN; legacy prima transizione | Esplicitare episodi e pause, evitare singola fine definitiva |
| Conflitto | Modello asciutto, radar 20 dBZ: legacy merge pioggia | Conservare disaccordo e provenienza |
| Vecchio | Quarto/frame 09:00 al clock 10:00: quarto rifiutato, radar solo penalizzato | Distinguere evidenza vecchia da pioggia corrente |
| Senza copertura | Nessun quarto, radar 0 dBZ con copertura 0 | UNKNOWN, non prova di asciutto |

`targets.tsv` preregistra 10 slot. `records.tsv` conserva 20 record con previsioni
emesse alle 10:00, prima dei target e delle osservazioni. I tre slot intermittenti
usano la stessa stazione fittizia, senza sovrapposizioni. Gli altri casi sono stazioni
fittizie distinte pur avendo coordinate uguali: non sono eventi reali indipendenti.
Il controllo `dry-control` aggiunge un negativo corretto.

I record `fixture-candidate` sono previsioni inventate per verificare il valutatore,
**non output del legacy**: nel conflitto sono volutamente asciutti per contare un errore.
Vecchio/senza copertura usano UNKNOWN come decisione esplicita del campione, non come
asserzione sul legacy. Risultato atteso calcolato a mano: 10 slot, 8 classificabili,
4 hit, 1 asciutto corretto, 2 falsi asciutti, 1 falso allarme, 2 previsioni UNKNOWN,
0 osservazioni UNKNOWN. Onset: una coppia, errore -2 min; fine: una coppia, +2 min;
MAE 2 min per entrambi, 9 slot non appaiabili per ciascun tipo. Questi numeri non
certificano accuratezza reale e non chiudono C2.

## Utility JVM e formato

Package `dev.pampa.fluidweather.testbench.validation`, file `RainValidation.kt`:

- `RainRecordingTsv.readTargets(text)`, `readRecords(text)`, `writeRecords(records)`;
  schema e intestazione esatti nelle costanti `TARGET_HEADER`, `RECORD_HEADER`.
  UTF-8, separatore TAB, timestamp ISO-8601 con offset (fixture in UTC), booleani
  `true`/`false`, enum maiuscoli; `-` solo per transizioni assenti. Nessuna inferenza.
- `RainValidation.evaluate(targets, records)` restituisce conteggi, denominatori,
  errori onset/fine firmati in minuti (previsto meno osservato: positivo = tardi),
  MAE nullable se senza coppie e numero di slot non appaiati. Ordine input irrilevante.
- Ruoli FORECAST/OBSERVATION; classi SYNTHETIC, MODEL_SIMULATION, REAL_LOCAL.
  Una valutazione accetta una sola classe e revisione. MODEL_SIMULATION è confronto
  con riferimento simulato/modello separato, mai riscontro reale. REAL_LOCAL richiede
  osservatore/pluviometro sul posto e indipendente. Le etichette sono dichiarazioni
  del raccoglitore: il software non può autenticarle. Non rietichettare le fixture.
- Identità stazione e coordinate devono coincidere esattamente col piano; una stazione
  non si muove. Nessun abbinamento automatico per prossimità. Slot `[start,end)` con
  start < end; non sovrapposti per stazione. RAIN = presenza di almeno un episodio
  nello slot, DRY = nessuna pioggia, UNKNOWN = impossibilità di decidere. Soglia fisica
  e capacità del riferimento vanno preregistrate e mantenute uguali tra versioni.
- Previsione emessa strettamente prima di start; osservazione completata a end o dopo,
  riferita all'intero medesimo slot. `independent=true` solo sul riferimento; fonte
  diversa dal candidato. `revision` è il commit completo valutato anche sulla riga
  osservazione (attribuzione al run, non revisione dell'osservatore). Per modifiche non
  committate usare lo SHA-1 del manifest congelato dei sorgenti; non sono richiesti commit.
- Rifiuto dell'intero run per duplicati ID/ruolo-target, chiavi inattese, record o coppie
  mancanti, luoghi/intervalli incongruenti, revisioni/classi miste, osservazioni premature,
  issue tardivo, transizioni fuori intervallo o invertite. Conservare separatamente il
  log rifiutato e il motivo; non eliminare righe per migliorare una metrica.
- UNKNOWN resta un record valido: non è un record assente e non diventa DRY. I due
  contatori UNKNOWN possono sovrapporsi; `scoredSlots` è il denominatore classificabile.
  Confrontare anche copertura e slot totali: aumentare UNKNOWN non dimostra miglioramento.
- Onset/fine opzionali: solo stesso episodio unico e non ambiguo, interamente delimitabile
  per quella transizione nello slot. Omettere per intermittente, bordo o campioni radi;
  nessuna ricerca del vicino né accoppiamento fra slot. Se manca uno dei due tempi,
  lo slot è non appaiato. Riportare sempre il denominatore; non sono minuti di precisione
  garantita. Il protocollo deve annotare risoluzione/incertezza dei tempi del riferimento.

Esempio d'uso per un futuro adattatore del parent (nessun nuovo comando main integrato):

```kotlin
val targets = RainRecordingTsv.readTargets(targetFile.readText(Charsets.UTF_8))
val records = RainRecordingTsv.readRecords(recordFile.readText(Charsets.UTF_8))
val report = RainValidation.evaluate(targets, records)
```

Il parent ha integrato e verificato il comando `rain-validation <targets.tsv> <records.tsv>`
in `Main.kt`: stampa classe, revisione, conteggi, denominatori ed errori e termina
con errore per input non valido. Esecuzione delle fixture, dalla radice del repository:

```powershell
.\gradlew.bat --no-daemon --no-watch-fs :testbench:run '--args=rain-validation src/test/resources/rain-validation/targets.tsv src/test/resources/rain-validation/records.tsv'
```

## Capture baseline controllata C6 (parent)

Prima di cambiare il comportamento, congelare una directory di evidenze esterna alle
fixture con ID `F0-2445bd80-<UTC>-<device>`. Usare lo stesso APK/revisione per screenshot,
input e misure. Un commit da solo non identifica un checkout modificato: conservare
`git rev-parse HEAD`, `git status --short`, patch, hash dei file untracked rilevanti,
engine/submodule SHA, Gradle/JDK, variante/versionCode e SHA-256 APK.

1. Salvare payload provider già disponibili, URL/parametri senza segreti, fetch/issue,
   intervalli di validità, frame time, timezone, posizione richiesta e reale con età
   e accuratezza fix; null, cache, errori, copertura e fonti. Conservare tile necessarie
   al replay se consentito e relativi hash: URL RainViewer da solo può scadere.
   Salvare configurazione remota, impostazioni, località scelta, clock, cache/sensori.
2. Parent cattura su telefono e tablet la stessa sequenza: apertura home, dettagli
   pioggia/nowcast, radar, cambio luogo GPS/salvato, refresh e ritorno home. Coprire
   dati freschi/vecchi, offline, assenti, conflitto e tablet senza barometro. Associare
   screenshot e albero accessibilità a ciascun input e timestamp; registrare viewport,
   densità, font scale, tema, locale, timezone, orientamento e stato iniziale.
3. Prima delle misure fissare dispositivo/OS, refresh rate, batteria/alimentazione,
   termica, rete, animazioni, app in background e scenario. Separare cold/warm start;
   stesso reset cache per entrambe le versioni, warm-up 3 passaggi poi almeno 3
   ripetizioni identiche per scenario/versione. Per dati controllati usare replay
   congelato/adattatore del parent, non confrontare due refresh live con meteo diverso.
4. Archiviare frame timing grezzo e memoria PSS a punti fissi dopo idle definito
   (es. 5 s); non confrontare debug con release. Per rendering dichiarare strumento,
   unità, numero frame e p95 con nearest rank `ceil(0.95*n)` sui frame raccolti;
   per memoria riportare distribuzione delle ripetizioni e p95 con la stessa regola.
   Confronto `% = 100*(nuovo/base-1)`, separatamente per scenario e metrica;
   base zero/assenza campioni => NV. Soglia C6: regressione p95 non oltre 10%.
   Conservare outlier, jank, tracce e dispersione; nessuna selezione del giro migliore.
5. Fare audit accessibilità sugli stessi stati: etichette, ordine focus, lettura
   TalkBack, contrasto, font grande, target tattili e movimento ridotto. Collegare ogni
   esito a screenshot/trace e snapshot. Questa utility non raccoglie UI o performance.

Stato C6: **NV**, capture a cura del parent; questo documento è un protocollo, non una misura.

## Raccolta locale prospettica C2

Preregistrare stazioni, coordinate, date, durata slot, soglia pioggia, lead time,
frequenza di osservazione, tolleranza/incertezza transizioni e gestione interruzioni.
Includere giornate asciutte, pioggia e pause, non solo eventi scelti dopo il fatto.
Congelare i target prima della previsione: un manifest rimasto senza coppia deve fallire.
Emettere e salvare entrambe le versioni prima di ogni target, sugli stessi input già
disponibili a quell'istante. Archiviare file e hash con timestamp affidabile prima
dell'evento; vietato ricostruire issue time da fetch successivi o archivi orari.

Un osservatore locale o strumento misura indipendentemente, preferibilmente senza
vedere il candidato; registrare inizio/fine periodo monitorato, luogo, risoluzione,
log originale e orari completamento. Non usare radar, fusione, minutely o storico
provider come verità indipendente. Se il telefono si sposta, nuovo piano/stazione;
non correggere coordinate a posteriori. Interruzioni => UNKNOWN con motivo nel log,
non cancellazione. Manifest/prove originali immutabili, correzioni versionate e motivate.

Valutare baseline e candidato separatamente, attribuendo le stesse osservazioni al
rispettivo run/revisione. Unire i report solo su identiche chiavi, luoghi, intervalli,
riferimenti e denominatori; mantenere lead time e classi separati. Confrontare falsi
asciutti e allarmi sia come conteggi sia con denominatore esplicito, copertura UNKNOWN,
errori onset/fine con numerosità e incertezza. Se cambia l'insieme classificabile,
presentare anche il sottoinsieme comune e i casi persi; non dichiarare miglioramento
dal solo calo di casi valutabili. Pochi eventi non certificano generalizzazione.

C2 resta **NV** fino a osservazioni reali indipendenti sufficienti al confronto:
nessun peggioramento dei falsi asciutti/allarmi e almeno una metrica pertinente migliorata.
Simulazione di modello e fixture sintetiche verificano strumenti/semantica, non C2.

## Verifica riproducibile C7.reproducibility

Test preparati con JUnit già presente: conteggi manuali e segni errori, round trip TSV,
ordine input, rifiuti temporalità/duplicati/mancanti/luoghi/intervalli/provenienza,
UNKNOWN, timing non appaiabile e schema. Nessuna asserzione dipende da rete, clock,
Android o feature future. **Test non eseguiti** dall'implementer, per proprietà Gradle
del parent. Esecuzione parent dalla radice PowerShell:

```powershell
Set-Location 'C:\VibeCoded Projects\FluidWeather'
.\gradlew.bat --no-daemon --no-watch-fs :testbench:test --tests 'dev.pampa.fluidweather.testbench.validation.RainValidationTest'
# Suite JVM completa, quando il parent libera il runner:
.\gradlew.bat --no-daemon --no-watch-fs :testbench:test
# Identità e controllo diff (includere untracked nel manifest):
git rev-parse HEAD
git status --short
git diff --check
Get-ChildItem 'testbench/src/main/kotlin/dev/pampa/fluidweather/testbench/validation','testbench/src/test/kotlin/dev/pampa/fluidweather/testbench/validation','testbench/src/test/resources/rain-validation' -Recurse -File | Get-FileHash -Algorithm SHA256
Get-FileHash 'docs/weather-baseline.md' -Algorithm SHA256
```

Report test attesi: `testbench/build/reports/tests/test/index.html` e XML in
`testbench/build/test-results/test/`. Archiviare comando, exit code, stdout e hash
snapshot prima di attribuire C7 verificato. F0 non autorizza promozioni delle fasi successive.
