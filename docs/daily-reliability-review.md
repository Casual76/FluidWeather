# Review indipendente dell'affidabilità quotidiana

Il perimetro autorizzato comprende verifiche sul computer. Consegna reale in Doze/OEM, vibrazione fisica, launcher e accuratezza meteorologica sul campo restano non verificati e non sono promossi dai test JVM/Robolectric.

## Primo giro

Snapshot: `2ebb93c39358cc4778582ebd965fc5fdfe28aa5d`, rispetto alla base `46f2fa877003305d122faaf09aa0d9656b7bc7da`. Due reviewer GPT-6.1 Sol con contesto fresco, distinti dagli implementatori, hanno esaminato lo stesso commit. Nessun reviewer ha eseguito Gradle o usato dispositivi.

| Criterio | Tecnico | Uso quotidiano | Media | Minimo | Esito |
|---|---:|---:|---:|---:|---|
| C1 Notifiche e recupero | 6 | 5 | 5,5 | 5 | Non superato |
| C2 Tempo, età e cache meteo | 6 | 5 | 5,5 | 5 | Non superato |
| C3 Percorsi quotidiani e impostazioni | 6 | 6 | 6 | 6 | Non superato |
| C4 Persistenza, privacy e migrazioni | 4 | 4 | 4 | 4 | Non superato |
| C5 Regressioni e integrazione sul computer | 3 | 4 | 3,5 | 3 | Non superato |

I voti del primo giro sono parziali: l'integrazione non compilava. Non rappresentano una certificazione runtime dei criteri. Entrambe le review hanno individuato il blocco di compilazione Kotlin e la possibilità che il ciclo meteo riscrivesse dopo il wipe il registro precedente, comprese le coordinate. La correzione viene verificata nel giro successivo.

Il primo batch integrato è terminato in 3m08s con tre task falliti. L'errore di compilazione è reale; il test DAO fallisce aprendo il database della fixture prima della migrazione; la soglia di volume del replay non è compatibile con il nuovo requisito di segnale corrente dopo Doze. I due test di migrazione 6→9 e 8→9 sono passati in quel batch, senza che ciò chiuda il test DAO fallito.

Rapporti completi e log sono conservati localmente in `artifacts/reliability-2026-11-17/`: `review-technical-r0.md`, `review-daily-r0.md`, `integration1.log` e la copia degli XML. Le prove proposte dai reviewer sono distinte da quelle effettivamente eseguite dal runner.

## Verifica del delta

Il secondo giro riguarda `e282f2b40efdab27befd1da4191fa1c8258ed9ce`. Due reviewer nuovi hanno verificato il delta: compilazione e commit del registro dopo il wipe sono chiusi. Hanno individuato un ulteriore percorso GPS nel nowcast che poteva riscrivere memoria barometrica e storico dopo il wipe; il guard del registro non proteggeva questi salvataggi precedenti.

| Criterio | Tecnico | Uso quotidiano | Media | Minimo | Esito |
|---|---:|---:|---:|---:|---|
| C1 Notifiche e recupero | 8 | 7 | 7,5 | 7 | Da completare |
| C2 Tempo, età e cache meteo | 8 | 8 | 8 | 8 | Verificato nel perimetro PC |
| C3 Percorsi quotidiani e impostazioni | 8 | 8 | 8 | 8 | Verificato nel perimetro PC |
| C4 Persistenza, privacy e migrazioni | 5 | 5 | 5 | 5 | Non superato |
| C5 Regressioni e integrazione sul computer | 6 | 6 | 6 | 6 | Non superato |

L'integrazione 2 compila APK app e APK dei test strumentati; migrazioni Room e replay passano. L'integrazione 3 verifica 328/328 test meteo e 69/71 test notifiche: due fixture richiedono adeguamento (identità delle eccezioni recuperate dalle coroutine e clock della risposta fake). Il lint completo rileva cinque errori preesistenti nelle utility debug e nei test di locale. Questi risultati non sono presentati come un'integrazione verde.

La correzione successiva protegge anche i salvataggi del nowcast e aggiunge un controllo positivo e due prove GPS sospese durante la cancellazione, con Room/DataStore reali. I test corretti e i cinque errori lint devono passare nel batch successivo; nessun voto viene trasferito a una nuova versione senza review del delta.

## Terzo giro: integrazione verde e ultimo caso concorrente

Snapshot: `6117fadd89463f4236689543bce48dc50efd8c21`. Due reviewer nuovi hanno verificato il delta. Il percorso GPS iniziato prima della cancellazione è protetto; la suite usa Room/DataStore reali, un controllo positivo e valutazioni sospese. L'integrazione 4 passa in 5m18s: 1.176 task, 114 eseguiti e 1.062 già aggiornati. I 225 XML disponibili raccolgono **1.492 casi, zero fallimenti/errori/saltati**; non sono 1.492 nuove esecuzioni nel singolo batch. Lint: zero errori, otto warning e un hint. APK debug e APK dei test strumentati compilati; nessuna installazione o esecuzione su dispositivo.

| Criterio | Tecnico | Uso quotidiano | Media | Minimo | Esito |
|---|---:|---:|---:|---:|---|
| C1 Notifiche e recupero | 8 | 8 | 8 | 8 | Verificato nel perimetro PC |
| C2 Tempo, età e cache meteo | 8 | 8 | 8 | 8 | Verificato nel perimetro PC |
| C3 Percorsi quotidiani e impostazioni | 8 | 8 | 8 | 8 | Verificato nel perimetro PC |
| C4 Persistenza, privacy e migrazioni | 6 | 6 | 6 | 6 | Non superato |
| C5 Regressioni e integrazione sul computer | 8 | 8 | 8 | 8 | Verificato nel perimetro PC |

Entrambe le review individuano un lavoro iniziato **durante** la cancellazione: può leggere input non ancora eliminati e conservare il token intermedio, riscrivendoli dopo. Il reviewer tecnico riproduce il primitivo compilato con JShell; il percorso Nowcast è tracciato staticamente. La correzione invalida la generazione anche all'uscita dal clear, in `finally`, e aggiunge tre regressioni: scrittore accodato durante un clear riuscito, clear fallito e valutazione GPS reale partita durante il clear. La nuova candidata richiede ricollaudo e review del delta.

Rapporti di questo giro: `review-technical-r2.md` e `review-daily-r2.md`, nella cartella locale delle evidenze. Le review successive usano GPT-6.2 Sol su richiesta dell'utente.

## Quarto giro

Snapshot `a1d5a3da4cb2c3a3281c69726e010fc99b9a6d9e`. Le due review GPT-6.2 Sol confermano la correzione del lavoro avviato durante la cancellazione. La prova indipendente sul guardiano compilato conferma che i commit con un token intermedio sono respinti anche se la cancellazione fallisce o viene interrotta. L'integrazione 5 passa in 4m42s: 116 task eseguiti e 1.060 già aggiornati. Gli XML riportano 1.495 casi, 778 nelle suite rieseguite e 717 riutilizzati, senza fallimenti, errori o skip. Lint senza errori, otto warning e un hint.

| Criterio | Tecnico | Uso quotidiano | Media | Minimo | Esito |
|---|---:|---:|---:|---:|---|
| C1 Notifiche e recupero | 8 | 8 | 8 | 8 | Verificato sul PC |
| C2 Tempo, età e cache meteo | 8 | 8 | 8 | 8 | Verificato sul PC |
| C3 Percorsi quotidiani e impostazioni | 8 | 8 | 8 | 8 | Verificato sul PC |
| C4 Persistenza, privacy e migrazioni | 8 | 6 | 7 | 6 | Da completare |
| C5 Regressioni e integrazione sul computer | 8 | 8 | 8 | 8 | Verificato sul PC |

La review d'uso quotidiano ha ricostruito tre percorsi preesistenti che conservavano input acquisiti prima del wipe: passata del barometro in attesa del GPS, stima di taratura in attesa del riferimento, turno dell'assistente sospeso sul provider. I rapporti `review-technical-r3.md` e `review-daily-r3.md` distinguono questi percorsi dalle prove procedurali del guardiano. La candidata successiva fa ereditare la generazione all'intera operazione, comprese le scritture meteo derivate, e verifica i nuovi ingressi con regressioni dedicate.

## Esito delle ultime review

Sul commit `669bb7a` (R4) i tre ingressi R3 sono protetti. Le due review hanno chiesto di coprire anche il salvataggio delle osservazioni e la pubblicazione del log dell'assistente. I test d'integrazione 6 hanno inoltre rilevato un import assente e due asserzioni sull'identità di un'eccezione attraverso `withContext`. I test corretti verificano ancora la propagazione della cancellazione e l'assenza di scritture.

Sul commit `38f80cc` (R5) l'osservazione è protetta nel repository e il log resta privato fino alla pubblicazione del turno. Il reviewer ha individuato un ultimo passaggio federato che poteva rinnovare la generazione tra costruzione del contesto e avvio del tool. L'integrazione 7 ha compilato gli APK e concluso lint. Un test si è fermato nella fixture perché la barriera era agganciata alle chiamate a tool anziché al ramo di solo testo.

Il delta `68d910e` (R6) cattura la generazione prima di leggere il contesto federato e la ripristina fino all'esecuzione del tool. La review indipendente ha dato GO sul sorgente per questo delta. L'integrazione 8 conferma la regressione diagnostica con la barriera nel ramo di solo testo, il rifiuto del commit di osservazioni scadute e i percorsi riparati in R3. La decisione di pubblicare in beta si basa sulle review del delta e su 228 XML, 1.507 casi verdi, lint Debug e Release senza errori e APK firmato con il certificato della stabile. Il runner ha rieseguito 82 casi e riutilizzato 1.425 risultati. Il collaudo su dispositivo resta fuori dal perimetro.
