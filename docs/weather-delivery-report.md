# FluidWeather — consegna di valutazione F0–F7

## Risultato e stato della consegna

Implementazione integrata disponibile per valutazione locale. F0–F6 hanno superato
le rispettive due review indipendenti nel perimetro documentato. F7 aggiunge gli
strumenti di confronto, una baseline separata e questa consegna; la sua review
finale è registrata nel [ledger](weather-improvement-status.md), con versione e voti.
Non è una pubblicazione e non dichiara superati i gate ancora privi di riscontri reali.

Il presente documento è l'evidenza candidata alla review F7. Il manifest F7 esterno
identifica i byte esaminati; il ledger conserva l'esito finale senza cambiare il
contenuto della versione congelata.

## Cosa cambia nell'app

- Un'unica valutazione della pioggia per home, dettagli, widget e strumenti IA:
  provenienza, intervalli, download originali, età, conflitti e dati mancanti espliciti.
  Current di modello, accumulo recente e previsione futura restano distinti.
- Radar indipendente dall'IA, supporto geografico distinto dai download mancanti,
  ETA assolute ed estrapolate, limite iniziale di 20 minuti sul frame. Le transizioni
  già trascorse non vengono ripresentate come imminenti o usate per ripristinare asciutto.
- Home con risposta pioggia, prossima transizione e timeline in alto, fonti espandibili;
  approfondimenti, ID e riordino delle tessere conservati. Cielo e movimento usano
  il sistema Fluid esistente e la policy di riduzione del movimento.
- Orari personali inizialmente vuoti, editor e persistenza, prime due finestre in home,
  gestione di fusi, mezzanotte e ora legale; aggregazioni limitate alla copertura effettiva.
- Sintesi immediata EN/IT senza configurazione IA; selezione IA esplicita e isolata,
  limitata a fatti dell'app. Chat con testo generato privato e pubblicazione di fatti
  verificati, controlli sul contesto, cancellazione, scadenza e invalidazione delle cache.
- Widget dalla sola cache e stessi fatti, clock assoluti, dettagli con buchi espliciti;
  righe Daily adattive e precisione temporale coerente. Wipe protetto per generazione,
  nessuna riapparizione dei vecchi risultati nei percorsi verificati, recupero anche
  nella Home rimasta aperta e aggiornamenti con contenuti diversi ma stesso timestamp.

Room passa da 7 a 8 con sola colonna nullable per il fuso, migrazione conservativa
e backfill. Codec snapshot versione 1 compatibile. Engine fermo a **3.0.5**,
modello barometrico addestrato e `AnalogPolicy.OFF` conservati. Nessuna nuova fonte,
chiave incorporata, notifica aggiuntiva, modifica all'engine o pubblicazione introdotta.

## APK e ripiego concreto

Entrambi debug, versione **2.0.2 / code 20**, firmati come build locali di valutazione.
I byte, le dimensioni e gli hash sono in
[`evaluation-apks.json`](../artifacts/weather-improvement/f7/evaluation-apks.json).

| Build | File | Pacchetto |
|---|---|---|
| App migliorata | [FluidWeather-evaluation-2.0.2-debug.apk](../artifacts/weather-improvement/delivery/FluidWeather-evaluation-2.0.2-debug.apk) | `dev.pampa.fluidweather.debug` |
| Logica precedente | [FluidWeather-baseline-F0-2.0.2-debug.apk](../artifacts/weather-improvement/delivery/FluidWeather-baseline-F0-2.0.2-debug.apk) | `dev.pampa.fluidweather.baseline` |

La baseline ricompila il sorgente mobile precedente al lavoro, commit
`2445bd80ba59900bf50a0ce8f9d62f9e399fc53c`, con il medesimo engine
`a055257fc4b559bee54b9ce114f265bcd237adfd`. Cambiano soltanto suffisso debug e
label **FluidWeather Baseline F0**. Patch, due file modificati, documentazione e
metadata sono archiviati in
[`baseline-source`](../artifacts/weather-improvement/f7/baseline-source).
Non è byte per byte l'APK originale F0, che resta conservato nella baseline congelata.

Installare la baseline come app separata; scegliere il relativo launcher per usare
la logica precedente. Nessun trasferimento o condivisione dei database è previsto.
L'APK originale F0 usa invece `.debug` e Room7: non sovrascrivere con esso la nuova
app Room8. Il vecchio codice prevede una migrazione distruttiva sui downgrade.
Non occorre disinstallare o cancellare l'app migliorata per usare il ripiego separato.

Verifica reale su Pampa_Tablet Android15: installazione baseline riuscita mantenendo
le app esistenti; UID **10218** e cartella `/data/user/0/dev.pampa.fluidweather.baseline`
distinti da UID **10211** e cartella `.debug`. Entrambe si avviano offline: baseline
alla prima configurazione, nuova app alla Home già configurata, senza richiesta di
nuova onboarding. Manifest APK: authority `.baseline.ai.tools`, `.baseline.androidx-startup`
e permesso receiver generato separati dai corrispondenti `.debug`; nessun shared UID.
Evidenze: [`coinstalled-packages.log`](../artifacts/weather-improvement/f7/coinstalled-packages.log),
[baseline](../artifacts/weather-improvement/f7/baseline-first-launch.png),
[app riaperta offline](../artifacts/weather-improvement/f7/evaluation-reopened-offline.png).

Il confronto manuale richiede di configurare indipendentemente località, unità e
permessi. Cache, download e storia di apprendimento possono divergere: la semplice
coesistenza non costituisce un confronto controllato di accuratezza o prestazioni.
Widget e schermata Informazioni della baseline conservano parte del vecchio branding;
update/config continuano a riferirsi alla produzione. Non usare l'updater per passare
tra le due varianti. Discovery esterna dei tool, tutti i click widget e resize non
sono certificati da questa prova di installazione e avvio.

## Verifiche e loro limiti

| Prova | Evidenza / risultato |
|---|---|
| Build integrata app e APK test | `f7/integration-checks.log`: riuscita in 31s, 624 task; 7 eseguiti, 5 da cache, 612 up-to-date |
| XML JVM correnti, 13 moduli | **1.253**, zero failure/error/skipped; include output riutilizzati, non 1.253 nuove esecuzioni. Dettaglio `f7/unit-test-summary.json` e copie XML |
| Nuovo confronto pioggia + validatore | 20 test mirati eseguiti, riusciti; successiva suite testbench completa con 266 risultati |
| Parser e confronto prestazioni Python | **35 test eseguiti**, riusciti: 17 parser e 18 nuovi confronti, con input sintetici |
| CLI pioggia | eseguita su copia esplicitamente SYNTHETIC delle fixture; candidato con soli ID revisione diversi, nessuna nuova previsione. Gate `REVIEW_NEEDED / OPEN` |
| Engine-doctor | riuscito, engine3.0.5 pulito e moduli coerenti |
| Baseline separata | build riuscita in 35s; 524 task, 249 eseguiti e 275 da cache; APK/authority, coinstallazione e avvio verificati |
| Identità app Android | SHA256 `6527a61b8123a13c35d4a8e7d3e031841e10dd59c96c1c9468d5dd2bd5a65f46`, identico a F6 correction1; anche l'APK test è identico |
| Prove Android del binario consegnato | F6 correction1: **3 lifecycle + 19 regressioni**, 22 metodi distinti completati. Non rieseguiti in F7; stessa identità binaria verificata. Ulteriore smoke di avvio/coinstallazione F7 documentato sopra |
| Parità sorgenti mobile con F6 correction1 | 537 voci confrontate, zero differenze (`f7/mobile-source-parity.json`); F7 modifica solo testbench, tooling, documenti e variante baseline isolata |

I 22 metodi Android coprono graph/Home reali, wipe, ritorni tardivi, ricreazione,
riapertura offline, recupero nella stessa Activity, cambio A/B, pausa/ripresa,
contenuto con timestamp identico, sintesi/routine/presentazione e motion.
Sono prove con acquisizione sintetica esplicita, non osservazioni meteorologiche.
I 56 casi di layout EN/IT 360/800dp, font1×/2× sono evidenza storica F6 su sorgenti
invariati, non nuove esecuzioni da sommare ai 22. Le sei migrazioni device F1 e i
riscontri delle fasi precedenti restano separati nei rispettivi archivi.

Log, tentativi falliti/abortiti e limiti storici sono conservati senza trasformarli
in successi. I contatori delle suite filtrate non si sommano ai relativi run completi.
La leggibilità è verificata nel perimetro proporzionato concordato; nessun audit
TalkBack/contrasto completo è implicito. Riferimento runtime dettagliato:
[`weather-f6-round1-fixes.md`](weather-f6-round1-fixes.md).

## Gate ancora aperti e come chiuderli

| Gate | Stato | Evidenza mancante / passo concreto |
|---|---|---|
| **C2 accuratezza sul campo** | **NV / aperto** | Previsioni delle due versioni archiviate prima degli eventi e osservazioni locali indipendenti con luogo/orario; campione sufficiente e protocollo congelato. Nessun campione REAL_LOCAL raccolto in questo incarico |
| **C6 p95 rendering e memoria ≤10%** | **NV / aperto** | Baseline/candidato negli stessi scenari e condizioni, stessi input e sequenza, 3 warmup e almeno 3 ripetizioni appaiate. Le acquisizioni F0 avevano input diversi e condizioni non controllate; nessun confronto finale valido disponibile |
| Sensore fisico | NV | Telefono con barometro; emulatore senza sensore non verifica misura/calibrazione fisica |
| Completezza runtime aggiuntiva | NV | Process death, GPS reale, sessione IA/provider/audio completa dal vivo, ogni interleaving e ogni launcher/tier; i test esistenti non danno una prova universale |

[`weather-field-comparison.md`](weather-field-comparison.md) descrive raccolta e
comando `rain-compare`: osservazioni identiche, denominatori e UNKNOWN espliciti,
copertura guadagnata/persa, sottocampione comune ed errori temporali appaiati.
Il comando non autentica le osservazioni e lascia sempre aperta l'accettazione C2.

[`weather-performance-comparison.md`](weather-performance-comparison.md) descrive
metadata, hash dei file effettivi e comando Python: rifiuto di dati incompleti/non
comparabili, outlier conservati, ogni ripetizione e superamento del 10% visibile.
Lo screening numerico non autorizza C6; il tool non può attestare che le condizioni
dichiarate siano state davvero controllate. Fixture sintetiche provano il calcolo.

La consegna soddisfa il percorso previsto di **build valutabile con limiti dichiarati
e gate lasciati aperti**. Non dimostra un miglioramento meteorologico reale né
l'assenza di regressioni prestazionali oltre il 10%. Eventuali nuove fonti restano
fuori dalla build e richiedono il confronto favorevole previsto dal piano.

## Stato operativo

Tutte le modifiche sono locali e non committate. Nessun push o pubblicazione.
Chiavi temporanee non incluse in sorgenti/APK/report; nessuna chiamata IA
dal vivo effettuata per la consegna. Il tablet usato è un AVD: rete ripristinata
(airplane off, Wi-Fi/mobile-data on), poi spento; tutti gli AVD risultano spenti e
il pannello Device vuoto. Dispositivi fisici e altre sessioni non toccati.
