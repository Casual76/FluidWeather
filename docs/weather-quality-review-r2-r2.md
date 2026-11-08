**Verdetto: CORRECT.** La correzione dei campioni di pressione supera il controllo statico e dispone di regressioni archiviate pertinenti. Rimane un errore riproducibile nel contenuto UV di Dettagli; la verifica completa sul telefono corrente resta inoltre aperta.

**Identità della revisione**

- Ruolo: critico indipendente UX/contenuti, con audit della semantica pressione.
- Modello/effort: GPT-6.1 Sol, high.
- Fase: correction2/3, FluidWeather 2.1.2/code23.
- Versione esatta: `Q-R2-0aad796e4a07`.
- Base HEAD verificata prima e dopo: `88655da79e208819da16c251b8b2051f969f4cda`.
- JSON SHA256 verificato: `393ba23a526744264aa2ddbcca42e5847acfed553b8611efb902475042487ad0`.
- ZIP SHA256 verificato: `0fcb8078231662187ab01f7aefed98bd3c1ec883b4f1a5ba1494ba9e4c2f2438`.
- Tutti i **766 file** del manifest corrispondono agli hash, sia all’inizio sia alla fine.

Ho letto le skill Fluid Engine e Material 3 Expressive. Nessuna memoria, review precedente, recovery, ledger o conclusione altrui consultata. Nessuna modifica, build, test, dispositivo, provider o rete utilizzati.

**Valutazione Q1–Q5**

8 indica soddisfatto con prove. NV indica verifica assente, non un difetto dimostrato; NA indica fuori dal perimetro applicabile. I voti non vengono mediati.

| Criterio | Voto | Stato e motivazione | Evidenze principali | Severità residua |
|---|---:|---|---|---|
| Q1 — Fondale autonomo dei fogli | 8/10 | Soddisfatto nel perimetro esaminato. Controlli scuri, glifi leggibili e nessuna rifrazione della home dentro i pannelli osservati. | `BlackSheet.kt`, test delle due superfici reali, screenshot pressione/luna/precipitazioni | Nessun difetto bloccante rilevato |
| Q2 — Precipitazioni leggibile e consultabile | 8/10 | Soddisfatto. Sei ore iniziali, percentuali leggibili, selezione giorno, tabella e fonti su richiesta. Zero, assenza e quantità piccole sono distinti. | `PrecipitationPage.kt`, `PrecipitationText.kt`, `r1-physical-precipitation.png`, regressioni archiviate | Nessun difetto bloccante rilevato |
| Q3 — Dato utile prima della diagnostica | 8/10 | Soddisfatto. Dettagli e Pressione aprono il proprio dato; le spiegazioni estese richiedono espansione. Luna e Sole mantengono una gerarchia comprensibile. | Screenshot R2 Dettagli/Pressione, immagini pertinenti R1/round0, test Nowcast | Nessun difetto bloccante di gerarchia rilevato |
| Q4 — Interazioni sulle pagine reali e formati | 7/10 | Parzialmente verificato. Buona evidenza tablet e controlli specifici a 360dp; **full-app telefono corrente NV**. La copertura sostitutiva non chiude integralmente la formulazione della rubrica. | Log finale 79/79, prove native 360dp, screenshot e alberi tablet | Gate di verifica aperto |
| Q5 — Qualificazione, lacune e regressioni | 7/10 | Pressione e lacune temporali corrette; rimane il massimo UV giornaliero calcolato su una finestra parziale senza qualificarla. Widget supportato solo entro i limiti sotto dichiarati. | Helper pressione, geometria temporale, screenshot R2, finding F1 | **P2, da correggere** |

**F1 — “Massimo di oggi” esclude il picco già trascorso**

**Severità: P2. Stato: confermato dal codice; riproduzione descritta, non eseguita. Criterio: Q5.**

Posizioni:

- [DetailsPage.kt:49](/C:/VibeCoded%20Projects/FluidWeather/feature-home/src/main/kotlin/dev/pampa/fluidweather/feature/home/pages/DetailsPage.kt:49): `next24` esclude i campioni precedenti a `now − 30 minuti`.
- [DetailsPage.kt:127](/C:/VibeCoded%20Projects/FluidWeather/feature-home/src/main/kotlin/dev/pampa/fluidweather/feature/home/pages/DetailsPage.kt:127): il massimo UV di oggi viene cercato dentro `next24`.
- `core-strings/src/main/res/values-it/strings.xml:274`: etichetta «Massimo di oggi»; stessa promessa nella risorsa inglese.

**Input concreto:** apertura alle 16:00, nello stesso fuso della pagina; `fusedHours` contiene UV 6 alle 12:00, UV 2 alle 16:00 e UV 0 alle 18:00.

**Effetto:** il valore 6 viene eliminato prima del calcolo. La pagina presenta «Massimo di oggi 2», alle 16:00, benché il dataset contenga un massimo giornaliero di 6 alle 12:00. La previsione è qualificata, ma l’aggregazione dichiarata non corrisponde al suo intervallo.

Il problema non richiede alcuna valutazione dell’accuratezza meteorologica: è una discrepanza fra selezione dei dati e testo. `r2-physical-details-pressure-final.png` conferma che questa etichetta è effettivamente esposta nell’interfaccia corrente; la schermata da sola non dimostra il caso numerico sopra.

**Correzione richiesta:** qualificare il valore come massimo dei campioni ancora disponibili oggi, oppure calcolare il massimo sui campioni dell’intera giornata e dichiarare l’eventuale copertura parziale. La verifica dovrebbe includere un picco già trascorso maggiore dei valori futuri.

**Esito della falsificazione sulla pressione**

Non ho trovato una persistenza del difetto +17h → +3h.

- `PressureForecast.kt:11–19` richiede un’ancora unica e finita e un endpoint unico e finito **esattamente** a +3h. Campioni vicini, successivi, duplicati o non finiti non sostituiscono l’endpoint.
- Con `(t0, 1020)` e `(t0+17h, 1016)`, il risultato è assente. In `PressurePage.kt:223–243` questo produce due trattini e impedisce l’emissione delle interpretazioni di cambiamento/tempesta.
- Le interpretazioni restano presenti quando esiste un confronto valido: non attribuisco quindi al codice una rimozione generale di quei messaggi.
- `pressureForecastSeries` usa l’intervallo `[inizio ora, inizio ora+48h)`, senza allungarlo per raggiungere 48 valori disponibili.
- L’ancora è esplicitamente «Previsione · valida …» e il confronto «3 ore dopo». `r2-physical-pressure.png` mostra entrambe le qualificazioni.
- `curveSamples` conserva coppie timestamp/valore, elimina timestamp ambigui e valori non finiti. Dettagli passa gli stessi campioni temporali alle curve.
- `timedLineGeometry` posiziona i punti sul tempo trascorso; `Charts.TimeLine` interrompe segmenti oltre soglia e disegna i campioni isolati. Per Dettagli/provider la soglia è 90 minuti.
- L’asse centrale rappresenta il vero punto medio temporale, anche se non coincide con un campione. Gli assi R2 osservati sono coerenti con questa scelta.

Il nuovo test Android di Dettagli verifica la nota sulle lacune; **non costituisce da solo una verifica visiva dei pixel della curva sintetica**. La conclusione sulla geometria combina sorgente, test unitari archiviatati e schermate reali.

**Giudizio visivo e contenutistico**

Ho aperto con `view_image` tutte e tre le schermate R2 richieste, le prove native Sole/Orario, immagini full-app pertinenti di home, precipitazioni, luna, aria, giornaliero e orario, due immagini telefoniche precedenti e i sei difetti originali.

Il miglioramento rispetto alle immagini originali è sostanziale:

- Precipitazioni sostituisce il muro di descrizioni con una sintesi breve e un grafico consultabile. Le etichette sotto ogni barra rendono leggibili anche probabilità molto piccole; la legenda distingue punto zero e assenza.
- Dettagli apre direttamente vento e misure. La qualificazione temporale occupa una riga proporzionata; metodo e fonti non dominano la lettura iniziale.
- Pressione presenta bene valore, confronto e curva. Le date sull’orizzonte di due giorni riducono l’ambiguità del cambio giorno.
- Luna mantiene selettori distinguibili e separa date e orari delle fasi. Sole usa righe larghe per le date annuali.
- La home conserva temperatura grande, cielo e tessere di vetro; la sintesi pioggia separa previsione futura e presente non verificato.

Orario a 360dp è denso e offre poco margine fra le colonne, ma la prova osservata consente di distinguere assenza, zero e traccia. Non deduco da questa fixture la leggibilità di ogni combinazione numerica, né un difetto generalizzato non dimostrato. L’area bianca esterna di `r1-native-sun-en.png` appartiene al test host.

**Prove e limiti**

**Controlli propri eseguiti:** lettura dei sorgenti e delle asserzioni; ispezione delle immagini e degli alberi disponibili; confronto degli hash; aggregazione degli XML; verifica delle modifiche nei componenti widget/engine. Nessun test eseguito da me.

**Esecuzioni archiviate:**

- Log Android finale: **79/79**, 112,127 secondi, terminale `-1`.
- Primo run da 79: un fallimento del selettore cercava un solo trattino nel parent semantico appiattito che ne conteneva due. Il log e la nuova asserzione geometrica sostengono la diagnosi di errore del selettore; non lo classifico automaticamente come difetto prodotto.
- XML red pressione: **2 test, 1 failure**, con il confronto errato a +17h esplicitamente documentato.
- Helper finale: **7 test, zero failure**.
- XML canonici: **43 UI + 23 strings + 58 home + 45 widget = 169**, senza failure/error/skip. Sono risultati anche riutilizzati, non 169 nuove esecuzioni.
- Hash del debug installato archiviato e del candidato uguali: `7bc2a4ad…dd36476`. Test APK e release corrispondono ai metadata.
- Firma, certificato precedente e proprietà release sono attestazioni archiviate; non ho rieseguito la verifica crittografica.

**NV:** app completa sul telefono corrente, nuovo ciclo launcher/widget, acquisizione con barometro fisico, live IA/audio. Le immagini `final-phone-*` sono round0; le prove 360dp sono sul tablet. I file produttivi widget risultano invariati e i 45 risultati unitari offrono supporto limitato, sufficiente a non dichiarare una regressione dimostrata, ma non una verifica launcher corrente.

**NA per questa approvazione:** accuratezza sul campo e benchmark controllati. Non introduco un audit generale TalkBack/contrasto.

La correzione della pressione è sufficientemente sostenuta nel perimetro dichiarato. Per avanzare senza riserve occorre correggere F1 e risolvere esplicitamente il gate Q4; gli altri NV non diventano automaticamente nuovi requisiti di questa correzione.
