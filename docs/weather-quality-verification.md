# Verifiche della correzione qualità 2.1.2

Stato: verifica integrata in corso; nessuna pubblicazione o approvazione finale.
Requisiti e rubrica Q1–Q5: `weather-quality-recovery.md`.

## Prove già eseguite

- Build debug e AndroidTest riuscita, engine 3.0.5 invariato. La prima compilazione
  ha trovato uno smart cast Kotlin fra moduli, corretto; una compilazione successiva
  ha trovato un import di test non valido, corretto. Log falliti conservati.
- 110 risultati JVM iniziali (40 core-ui, 23 strings, 47 home), senza errori.
  Il controllo widget da 45 casi viene incluso nella build del candidato rifinito.
- Prima suite Android15: 68 casi, 8 fallimenti effettivi sullo stesso intervallo
  sotto il minuto, in EN/IT e due larghezze/font. Non conteggiata come riuscita.
- Correzione della semantica temporale conservando orario assoluto e risoluzione
  originale. Seconda suite Android15: 68/68, 168,094 secondi, terminale -1.
  I due casi regular/lazy verificano i veri ModalBottomSheet: identità dei fondali,
  palette, glifi chiari, selezione e stabilità dei pixel col cielo rosso poi verde.
- L'utente ha autorizzato il tablet fisico. Installato il solo pacchetto di prova
  `.debug`, affiancato alla stable presente; nessuna cancellazione o migrazione
  dei dati della produzione. Primo avvio completato nella copia QA, città Sesto
  Fiorentino aggiunta manualmente, senza fornire chiavi o configurare IA.
- Sul Samsung SM-X710 Android16 sono state aperte e scorse le pagine Precipitazioni,
  Pressione, Luna, Dettagli, Sole, Qualità aria, Giornaliero e Orario. Testati giorno
  delle precipitazioni, tabella ore, apertura fonti, cambio giorno Luna, espansione
  del giorno nel giornaliero, chiusura X e ritorno. Screenshot `physical-*` sono la
  prima passata, precedente agli ultimi ritocchi dichiarati sotto.
- Questo tablet non ha barometro. La pressione vista è qualificata come previsione;
  il verdetto pieno Nowcast viene verificato separatamente con fixture UI dichiarate.
  Nessuna prova di accuratezza del sensore o sul campo è dedotta dalle immagini.
- Emulatore spento dopo la suite; nessun secondo AVD avviato insieme al primo.

## Ritocchi derivati dalla prima passata fisica

- Sintesi Home più breve, senza ripetere modello/risoluzione e probabilità zero;
  metadati completi nelle fonti e nella semantica. Precipitazioni usa solo la riga
  condivisa sul presente, evitando un secondo grafico sopra quello della pagina.
- Tabella precipitazioni con intestazione comune e valori compatti; conteggi di
  copertura abbreviati. Nessun grafico decorativo per probabilità tutte assenti.
- La tessera Precipitazioni tratta le prossime ore: non ripete un errore sul presente
  quando la previsione futura è disponibile. Mancante non diventa zero o asciutto.
- Clock di cache etichettati come cache, senza attribuirli al download o all'emissione
  di tutti i modelli contribuenti. Orologi originali della pioggia nelle fonti condivise.
- UV/visibilità: etichette brevi; qualificazione della previsione all'inizio del foglio.
- Sole: il calcolo che cerca dodici ore fra alba e tramonto non viene più chiamato
  "Equinozi". Nessuna modifica alle effemeridi o nuovo istante astronomico inventato.
- Aggiunti un controllo contro il doppio grafico e due casi Nowcast EN/IT con valori
  sintetici6/16/22 e diagnostica inizialmente chiusa. Questi ultimi non sono una prova
  dell'acquisizione barometrica.

## Candidato rifinito

Identità in `artifacts/quality-2.1.2/candidate-metadata.json`: versione 2.1.2/code23,
debug SHA256 `f46f6784e32317ee1ab056e4e70e96f955ea66021717529e01e1336cb4b18d3a`.
Il test APK ha SHA256 `6833cb33e0278c4f047c4ad7a7d180cf34b16d0b2b79fb81d5c3a325b9cfd98a`.
L'ultima build di conferma è riuscita in 17 secondi, con 584 task UP-TO-DATE:
conferma del candidato già compilato, non una ricompilazione completa.

La suite sul tablet fisico Samsung SM-X710 Android16 ha eseguito **71/71 casi**,
98,227 secondi, terminale -1 (`physical-quality-tests.log`). Include 2 surface,
5 precipitazioni, 6 overview, 56 casi parametrizzati F6 e 2 Nowcast sintetici.
I 155 risultati XML JVM sono 40 core-ui, 23 strings, 47 home e 45 widget,
senza failure/error/skip; comprendono risultati riutilizzati, non 155 nuove esecuzioni.
Engine-doctor superato, engine3.0.5 pulito.

Le immagini `final-physical-*` provengono dal candidato sopra: Home, Precipitazioni
con giorno selezionato/tabella/fonti, Luna con Domani selezionato, Dettagli iniziali
e scorsi, Pressione e relativo metodo, Sole, Qualità aria, Giornaliero e Orario.
Le azioni passano attraverso l'app reale, con dati provider; non costituiscono
osservazioni meteo indipendenti. Nessun sensore barometrico su questo tablet.

Il telefono Medium_Phone Android17 usa la stessa build, impostazioni EN e unità
imperiali già presenti. Immagini `final-phone-*`: Home, Nowcast e fattori espansi,
Precipitazioni e tabella del secondo intervallo del giorno successivo, Pressione
con 24 ore selezionate, Luna con Tomorrow selezionato, Dettagli e metodo, Sole,
Qualità aria e Giornaliero. I valori barometrici provengono dall'emulatore:
nessuna accuratezza del sensore reale è dedotta. Il tentativo `phone-hourly-attempt-home.png`
mostra Home, quindi non viene contato come prova dell'apertura di Orario sul telefono.
Due selezioni di tessere parzialmente coperte dalla barra sono state ripetute dopo
scorrimento; nessuna cattura Home viene etichettata come pagina aperta riuscita.

## Gate ancora da chiudere

Tre review indipendenti della stessa versione; build release e verifica della firma
prima della consegna. I vecchi screenshot e i risultati riutilizzati non vengono
presentati come nuove esecuzioni del candidato. Accuratezza meteorologica sul campo
e benchmark prestazioni controllato restano separati.

## Correzione1 dopo la review non superata

I tre report round0 e la tabella generale sono conservati separatamente. Il vecchio
manifest/ZIP e i metadata sono in `artifacts/quality-2.1.2/r0/`; le immagini `final-*`
rimangono prove del round0, non del sorgente corretto successivamente.

- Sole: le due date attorno a dodici ore di luce sono righe a tutta larghezza.
- Precipitazioni: percentuali e ore sotto ogni barra, punto per zero e croce per
  assenza; scala 0–100% conservata. Conteggi completi ridondanti rimossi dalla
  lettura iniziale, mantenuti nella semantica; conteggi parziali restano visibili.
- Orario: null è `—`, zero resta numerico, un importo positivo inferiore alla
  precisione viene espresso con `<` e soglia nell'unità scelta. Nessuna variazione
  ai campioni o alle soglie dei modelli. Stessa distinzione nella tabella precipitazioni.
- Le pagine esistenti mantengono il fuso del dispositivo, ora dichiarato quando
  diverso da quello del luogo o quando il luogo non ha un fuso. La timeline Home
  dichiara il proprio fuso se diverso da quello del dispositivo. La riga campione
  di Dettagli usa lo stesso fuso dei grafici di quella pagina.
- Pressione: posizioni proporzionali ai timestamp, asse etichettato al tempo medio
  effettivo e interruzioni oltre30 minuti per il sensore /90 per le ore provider.
  La soglia di visualizzazione è dichiarata; non è una soglia del modello. Anche
  i grafici diagnostici Nowcast usano i timestamp; nessuna serie viene riaddestrata.
- Tre regressioni JVM sulla geometria temporale e due sul testo probabilità.
  Nuovi controlli Android sul Sole EN/IT al200%, Orario in finestra Material reale
  da360dp con unità metriche/imperiali, scorrimento e chiusura, valori sotto le barre.
  Il viewport da360dp sul tablet non viene chiamato emulatore telefono.

Build `correction1-final-build.log` riuscita in25 secondi,565 task,6 eseguiti.
Un tentativo precedente dei nuovi test non compilava per il nome di un preset
unità inesistente; corretto usando `UnitPreferences.forCountry("US")`.
La prima suite correttiva ha eseguito76 casi con2 fallimenti del driver di scroll:
`performScrollTo` cercava una riga non composta dalla lista Lazy. Le asserzioni su
null/zero/tracce precedenti allo scroll erano riuscite. Passato a `performScrollToNode`
sul contenitore pigro, senza togliere riga lontana, ritorno e chiusura.

## Evidenze finali della correzione1

`correction1-reviewed-candidate-tests.log`: **76/76**,159,666 secondi, terminale-1.
Questa esecuzione usa gli APK attuali identificati in `candidate-metadata.json`.
Due pause bounded di25 secondi, solo se richieste dall'argomento di test, permettono
di acquisire lo schermo attraverso il pannello Device; i valori rimangono fixture.
I risultati76 comprendono i72 casi precedenti e i4 nuovi: non si sommano ai run
sovrapposti. I160 risultati JVM (43/23/49/45) comprendono output riutilizzati.

- `r1-native-hourly-metric.png`: vera finestra Material da360dp sul tablet, con
  fuso dichiarato, null, zero e quantità `<0,1`; il test scorre fino all'ora70,
  ritorna alla prima e chiude. Anche le unità imperiali sono asserite dal secondo caso.
- `r1-native-sun-en.png`: viewport360dp,font2, risorse/date EN, con crepuscoli e
  tutte le date annuali su righe leggibili. La porzione bianca esterna è il test host,
  non la pagina dell'app; questo non è un'acquisizione dell'app completa sul telefono.
- `r1-physical-sun.png`: pagina reale dell'app completa sul tablet, scorsa sino
  alle date annuali e alla disclosure, con gli inset del foglio di produzione.
- `r1-physical-home/precipitation/pressure/hourly`: app completa prima dell'ultimo
  ritocco esclusivamente a SunPage. I componenti rappresentati in quelle immagini
  sono invariati dall'ultima build. Le immagini `final-phone-*` restano del round0;
  nessuna è rietichettata come esecuzione del nuovo APK sul telefono.

Le prime esportazioni Compose in `correction1-proof/` sono bianche e non sono
evidenze visuali valide. Sono escluse dal dossier finale. Una cattura arrivata dopo
la chiusura del test è stata scartata; non è presentata come pagina meteo.

L'ultima verifica nativa ha confermato che anche le date del giorno più lungo/corto
richiedevano righe intere. Corrette insieme ai crepuscoli e all'uso del locale
effettivo del componente. Le due prove Sole verificano ora tutte le etichette annuali.

Release build riuscita in2m9s,920 task:89 eseguiti,4 cache,827 UP-TO-DATE. APK release
24.996.001 byte, SHA256 `aa294459ff0ab2cc4b764b545a4c244a5e4d3efc88226ae31ef682c9f607ea99`.
Versione2.1.2/code23, non debuggable, firma v2 valida e certificato identico alla2.1.1;
integrità ZIP e sole ABI ARM confermate. Nessuna pubblicazione effettuata.

Rimane non rieseguito il flusso completo Orario sull'AVD telefono dopo il fallimento
del suo avvio. La prova sostitutiva copre il medesimo foglio di produzione in una
finestra Android reale da360dp, oltre all'app completa sul tablet. La sufficienza
di questa copertura è da valutare in review, senza trasformarla in hardware diverso.

## Correzione2 — pressione e campioni temporali

Il vecchio riepilogo provider prendeva il primo campione disponibile dopo tre ore:
con un buco sino a +17h lo presentava come confronto a tre ore e divideva per3.
Riprodotto in `correction2-pressure-red.log` e `r2-pressure-red.xml`:2 test,1 fallito.
Il confronto richiede ora un endpoint unico e finito esattamente a +3h dall'ancora;
se manca, valore, tendenza e interpretazione a tre ore sono assenti. L'ancora è
qualificata come previsione valida al suo orario; l'etichetta è «3 ore dopo».
La serie copre48 ore effettive, senza prolungarsi quando mancano campioni.

I grafici di Dettagli selezionano valori e timestamp insieme, scartano duplicati e
non finiti e interrompono le linee oltre90 minuti. Anche il limite delle24 ore
è temporale. Sette test del calcolo e due sulla selezione accoppiata aggiunti.
`correction2-green-build.log`: build riuscita45s,565 task,22 eseguiti. I169 XML
canonici (43 UI,23 strings,58 home,45 widget) non sono169 nuove esecuzioni.

Tre nuovi casi Android attraversano i corpi produttivi Pressione/Dettagli con
campioni sintetici. Il primo run79 casi aveva un fallimento nel selettore: le
Column di solo layout sono appiattite nella semantica e il parent conteneva entrambi
i valori «—». Il controllo ora richiede il valore immediatamente sotto la propria
etichetta, senza togliere le asserzioni su valore/tendenza mancanti o assenza di
messaggi di temporale. `correction2-physical-tests-final.log`: **79/79**,112,127s,
terminale-1. Sono79 casi distinti, non si sommano ai run precedenti sovrapposti.

`r2-physical-pressure.png` mostra la pagina completa con orario della previsione,
riepilogo e asse su due giorni. `r2-physical-details.png` e
`r2-physical-details-pressure-final.png` mostrano Dettagli e la curva pressione
dopo scroll, sul Samsung SM-X710 Android16. Il tablet non ha barometro: il ramo
misurato su sensore fisico non è verificato. Le immagini reali usano i dati provider;
le lacune sintetiche sono esercitate nei test, non attribuite al meteo osservato.

Release build `correction2-release-build.log`: riuscita2m2s,920 task,43 eseguiti,
3 cache,874 UP-TO-DATE. APK release24.995.994 byte, SHA256
`18b59088af6f8984a24c959b16e23b8e355fd4ddf2f99423e9fda18b0ab21509`.
Versione2.1.2/code23, non debuggable, firma v2 e certificato uguale alla2.1.1;
ZIP e ABI ARM verificati. Engine-doctor finale positivo; nessuna pubblicazione.
`candidate-metadata.json` identifica i tre binari. La copia dell'APK debug
installato è stata confrontata per byte/hash con la candidata.

Le prove native360dp Sole/Orario e le immagini full-app degli altri dettagli della
correzione1 restano pertinenti per quei sorgenti invariati; non sono nuove esecuzioni
della correzione2. Full-app telefono corrente e nuovo ciclo launcher/widget restano
NV dopo i fallimenti degli AVD. Accuratezza sul campo e confronto prestazioni
controllato restano fuori da questa approvazione; nessuna deduzione da79 test UI.

## Correzione3 — UV, testo essenziale e telefono completo

Il picco UV ora usa coppie timestamp/valore uniche, finite e non negative, soltanto
da adesso alla mezzanotte locale. La riga è «Picco disponibile oggi», con «Da ora»
e orario: non è il massimo di una giornata intera. La curva UV usa gli stessi
campioni supportati. Nessun cap sul numero di ore precede la rilevazione dei duplicati
nel periodo di24 ore. Quattro nuovi test coprono il massimo passato, infinito/NaN/
negativi/duplicati, mezzanotte nel fuso e il duplicato oltre la posizione24.
Un caso Android attraversa la pagina produttiva con lo stesso controesempio.

Su richiesta dell'utente sono state tolte spiegazioni ridondanti dalla superficie
principale: titolo «Previsione del modello», legenda delle barre ovvia, ripetizioni
del qualificatore modello e testo barometrico già spiegato nel metodo espanso.
Le percentuali, i nomi delle sezioni e il contesto indicano cosa si sta leggendo.
Le lacune, i conflitti, le fonti e i clock originali rimangono disponibili. Il titolo
«Pioggia prevista/non prevista» conserva il significato previsionale senza una lezione.
Il fuso dei dettagli continua a essere dichiarato quando può cambiare la lettura;
Nowcast è locale al barometro e non mostra il fallback del fuso sopra la raccolta.

L'utente ha autorizzato il telefono fisico SM-S931B Android17. Installati soltanto
il pacchetto Debug e il runner; nessun upgrade, wipe o configurazione dell'app
principale. `r3-execution-provenance.json` verifica per hash l'APK Debug installato.
`correction3-phone-tests-final.log`: **80/80**,62,690s, terminale-1. Il primo run80
aveva un fallimento perché il test richiedeva più righe anche al titolo appena
accorciato. Il caso finale usa il titolo lungo del conflitto, mantenendo le asserzioni
su wrapping reale, assenza di overflow e timeline raggiungibile; non le elimina.
I173 risultati JVM (43/23/62/45) comprendono output riutilizzati.

Acquisizioni dell'app completa sul telefono della build corrente:

- `r3-phone-home.png`: temperatura grande, cielo/vetro e titolo breve.
- `r3-phone-hourly.png` e `r3-phone-hourly-scrolled.png`: apertura dalla tessera,
  valori zero/tracce e scorrimento ai giorni successivi; albero scrolled attuale.
- `r3-phone-precipitation.png` e `r3-phone-precipitation-selected.png`: sei ore,
  selezione9nov, avanzamento alle06–11, espansione tabella e ritorno.
- `r3-phone-pressure.png`, `r3-phone-daily.png`, `r3-phone-air.png`: pagine reali
  aperte dalle tessere, poi chiuse mediante Back.
- `r3-phone-sun.png` / `r3-phone-sun-dates.png`: scroll sino alle date annuali;
  `r3-phone-moon-selected.png`: selezionato Domani e chiuso con la X del foglio.
- `r3-phone-details.png`, `r3-phone-details-uv.png`, `r3-phone-details-pressure.png`:
  apertura, nuovo riepilogo UV e grafico pressione raggiunti tramite scroll.
- `r3-phone-nowcast.png`: avvio con soli15 minuti nell'archivio Debug nuovo, stato
  di raccolta e disclosure. Non prova13 ore di continuità né un verdetto del sensore.

Le immagini sono state aperte e ispezionate; i JSON pertinenti sono conservati.
L'albero Orario precedente al binario finale è fuori dal gruppo `r3-*` e non viene
attribuito al nuovo screenshot. Le immagini R2 tablet e le prove360dp/font2 dei
componenti invariati rimangono evidenza incrementale; non sono nuove esecuzioni R3.
I pannelli dei dispositivi sono stati chiusi senza spegnere i dispositivi fisici.

Build finale Debug/test riuscita27s,555 task,6 eseguiti; build JVM/Debug precedente
1m2s,579 task,31 eseguiti. Release finale2m5s,920 task,18 eseguiti. APK release
24.995.887 byte, SHA256 `5b94d518fad28c743ad434a8a10a23aec5d3b4010e6e2c1be357a7f59aab2a8e`.
Firma v2 valida, certificato precedente invariato, ZIP e ABI ARM verificati; engine
doctor positivo. Nessuna pubblicazione: i nuovi filoni cielo/widget richiesti
dall'utente restano in checkout separati e non fanno parte di questa identità.
Nuovo ciclo launcher/widget, liveIA/audio e sensore continuo rimangono NV.
