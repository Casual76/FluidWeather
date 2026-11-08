# Consultazione rapida e widget: correzione dopo stable2.1.2

Richiesta utente del2026-11-08: il widget deve avvicinarsi molto di più alla reference iOS fornita; riepilogo rapido e momenti personali devono mostrare icone e dati leggibili in pochi secondi. Le nuove schermate evidenziano ancora un titolo nero sul cielo scuro, fonti duplicate e testo tecnico eccessivo. La resa generale dell'app e le nuvole morbide superiori sono la base da conservare.

Baseline: main `169c719`, stable2.1.2/code23; APK SHA256 `2af83f122c5aafec3d365d7a03df6df7c93b4bb6e3a18a6399aeb70860432ef4`. Le immagini utente restano fuori dal repository. Le catture host precedenti, conservate negli artifact privati, sono evidenza storica di confronto, non nuove esecuzioni.

## Perimetro e organizzazione

- A: widget aderente alla reference, gerarchia temperatura/condizioni, ore allineate e barre min/max giornaliere; renderer condiviso con l'host di prova.
- B: riepilogo e momenti con dati/icone, date brevi, dettaglio espandibile, titolo leggibile sul cielo e vetro reale.
- C: pannello pioggia compatto, percentuale con icona, gap riconoscibili senza spiegazioni lunghe.

Tre implementatori Sol-high in checkout isolati, aree di scrittura separate. Root integra, possiede tutte le build e il tablet. Telefono affidato all'altra sessione: nessun accesso. Emulatori spenti. Nessuna modifica a engine3.0.5, modelli, formule, cache/acquisizione, migrazioni o raccolta sensore. Fonti, clock, conflitti e dati mancanti restano corretti e consultabili; la riduzione del testo è presentazione, non perdita delle evidenze.

Questa è una nuova fase richiesta dopo la consegna precedente, non una quarta correzione del vecchio gate. Review iniziale di tre critici Sol-high indipendenti, al massimo tre giri di correzione. Ogni voto obbligatorio deve raggiungere8 con evidenze; media e minimo sono riportati e non compensano fallimenti. NV resta distinto da NA.

## Criteri

| ID | Esito verificabile |
|---|---|
| G1.widget | Confronto visivo diretto con la reference: temperatura dominante, condizioni compatte, ore ordinate, barre min/max nei formati adatti; misure reali RemoteViews senza clipping e budget bitmap limitato. |
| G2.rapidità | Riepilogo e momenti popolati con dati veri di fixture mostrano icone/numeri e testi brevi; niente muro di avvertenze o zone/offset ripetuti. Informazioni complete raggiungibili dal dettaglio. |
| G3.coerenza | Missing/zero, parziale, conflitti, freschezza, intervalli, fusi/DST, unità e selezione IA conservati; nessuna precisione o quantità inventata per semplificare la UI. |
| G4.regressioni | Vetro/foreground leggibili, flussi e collegamenti conservati; cielo accettato, dati, modelli e affidabilità invariati. |
| G5.consegna | Build/test pertinenti e prove visive su tablet con EN/IT, larghezze telefono/tablet e testo ingrandito; identità degli artifact e tre report indipendenti. |

## Stato

Riscontro preliminare sul tablet, in sola lettura: il widget principale esistente è198,588×198,588dp, mentre la baseline dichiara110×110 e formati larghi250dp. La nuova QA deve includere una taglia intermedia e la selezione responsive per questo spazio reale, non soltanto composizioni a dimensione forzata. Nessuna modifica al widget principale o ai suoi dati è stata effettuata. Il comando Device per aprire il launcher ha fallito anche il singolo retry diagnostico (`unable to resolve Intent`, categoria MAIN/LAUNCHER); quel percorso manuale è interrotto, senza workaround adb/SSH. Il launcher già visibile ha consentito una cattura iniziale; i metadata del solo widget meteo sono conservati negli artifact privati. Le prove proseguono tramite i runner del progetto.

Implementazione in corso. Punteggi e accettazione ancora NV. I casi Compose devono includere brief e routine popolati e parziali, come la finestra scuola7:20–14:20: i precedenti casi con brief nullo non coprivano il problema mostrato dall'utente. I controlli visivi del cielo con condizioni diverse sono prove della resa, non della precisione meteorologica o del modello, che non viene riaddestrato in questa fase.
