# Recupero della qualità dopo la 2.1.1

## Incarico e configurazione

Le sei schermate dell'utente evidenziano difetti ancora presenti dopo la prima
correzione: fondale della home visibile attraverso controlli dei fogli, pagine
dominate da diagnostica, grafici privi di una lettura immediata e centinaia di
descrizioni dei campioni stampate come normale interfaccia.

La precedente consegna non soddisfaceva la qualità richiesta. I conteggi e le
review storiche restano prove circoscritte, non un'approvazione di questi flussi.

Configurazione confermata dall'incarico e dall'aumento del limite dell'utente:
orchestratore, massimo tre implementatori simultanei GPT-6.1 Sol high, tre critici
indipendenti Sol high per giro. Massimo tre giri di correzione dopo round0.
Implementatori con aree separate; build e dispositivo posseduti dall'orchestratore.
Engine 3.0.5 invariato. Nessuna nuova fonte, chiave o notifica richiesta.

## Unità di implementazione

- A: proprietà dei fondali e superfici dei fogli, controlli e pagine di dettaglio.
- B: esperienza precipitazioni con grafici e ore consultabili, copertura e fonti.
- C: sintesi pioggia, timeline e distinzione fra presente e previsione disponibile.

Imagegen è stato considerato come richiesto. Le icone di questa correzione sono
controlli vettoriali nativi: si riusa il sistema esistente. Non serve generare
bitmap o introdurre un secondo stile di icone per correggere questi difetti.

## Criteri osservabili

| ID | Criterio |
|---|---|
| Q1 | I fogli possiedono il proprio fondale. Sole, nuvole e contenuto della home non compaiono dentro selettori e pannelli dei dettagli. Testo e icone restano nitidi su temi chiaro/scuro; il vetro della home rimane funzionante. |
| Q2 | Precipitazioni ha una sintesi e un grafico leggibili, un orizzonte iniziale limitato e ore/giorni consultabili. Nessuna lista automatica di centinaia di descrizioni tecniche. Zero, dati mancanti e copertura parziale restano distinti. |
| Q3 | Ogni pagina apre il proprio dato utile; diagnostica, metodo e clock estesi richiedono un'espansione esplicita. Nessun pannello pioggia gigante ripetuto in vento, luna o pressione. Fonti e limiti rimangono accessibili. |
| Q4 | Selettori, espansioni, scorrimento, chiusura e ritorno funzionano sulle pagine reali. Verificate home e pagine coinvolte su telefono e tablet, con screenshot e alberi della build identificata. |
| Q5 | Meteo corrente e previsione restano qualificati. Nessun dato mancante diventa asciutto, nessuna fonte viene ringiovanita e nessuna ETA inventata. La grafica recuperata e il widget utile non regrediscono. |

8 significa criterio soddisfatto con prove; NV e NA sono separati dai voti.
Nessuna media compensa un difetto riproducibile. I reviewer esaminano la stessa
versione integrata, senza leggere le conclusioni degli altri. Oltre alla review
dei sorgenti, l'accettazione richiede le schermate effettive dei flussi completi.
Accessibilità di base proporzionata; nessun nuovo audit generale TalkBack/contrasto
richiesto dall'utente. Accuratezza sul campo e benchmark controllato restano separati.

## Stato

Round0, versione `Q-R0-7d54f7ad111d`: **CORRECT**, nessun avanzamento o pubblicazione.
Tre critici Sol high indipendenti; report completi in
`weather-quality-review-r0-r1.md`, `weather-quality-review-r0-r2.md`,
`weather-quality-review-r0-r3.md`. Le prove eseguite dai reviewer sono sole letture;
build, 71 casi Android e XML JVM sono evidenze dell'orchestratore.

| Criterio | R1 | R2 | R3 | Media | Minimo | Problemi aperti |
|---|---:|---:|---:|---:|---:|---|
| Q1 | 8 | 8 | 8 | 8 | 8 | Nessun difetto delle superfici rilevato |
| Q2 | 7 | 7 | 8 | 7,33 | 7 | Barre 0–1% troppo simili; enfasi sui campioni |
| Q3 | 8 | 6 | 7 | 7 | 6 | Due date lunghe comprimono la riga Sole sul telefono |
| Q4 | 7 | 7 | 7 | 7 | 7 | Apertura Orario telefono non dimostrata |
| Q5 | 7 | 7 | 6 | 6,67 | 6 | Orario nasconde null/zero/tracce; fusi non dichiarati; curva pressione comprime lacune |

Correzione1/3 in corso: valori delle singole barre visibili e zero distinto,
date Sole su righe intere, celle Orario esplicite, fuso della pagina dichiarato
quando diverso da quello della località, asse pressione basato sui timestamp con
interruzioni oltre la soglia dichiarata. Nessuna modifica al modello barometrico.

Il riavvio supplementare dell'AVD telefono è fallito dopo refresh dell'inventario:
prima `Could not communicate with device support`, poi processo emulatore uscito
con codice1 prima di andare online. Nessuna esecuzione riuscita è dedotta da questi
tentativi. Il tablet fisico resta disponibile; le prove successive devono dichiarare
se esercitano il dispositivo completo o un viewport di test.

## Correzione1 — esito

Versione `Q-R1-fae0169b347e`, tre nuovi critici Sol high, report
`weather-quality-review-r1-r1.md`, `weather-quality-review-r1-r2.md`,
`weather-quality-review-r1-r3.md`. Esito **CORRECT**, nessuna pubblicazione.

| Criterio | R1 | R2 | R3 | Media | Minimo | Problemi aperti |
|---|---:|---:|---:|---:|---:|---|
| Q1 | 8 | 9 | 8 | 8,33 | 8 | Nessun nuovo difetto |
| Q2 | 8 | 8 | 8 | 8 | 8 | Nessun nuovo difetto |
| Q3 | 8 | 8 | 8 | 8 | 8 | Nessun nuovo difetto |
| Q4 | 8 | 8 | 8 | 8 | 8 | Copertura incrementale accettata; telefono completo attuale e launcher widget NV separati |
| Q5 | 7 | 7 | 7 | 7 | 7 | Riepilogo pressione scambia +17h per +3h; grafico pressione in Dettagli conserva asse per indice |

Correzione2/3: confronto pressione con endpoint a tre ore supportato, altrimenti
nessun valore/rate/interpretazione a tre ore; orizzonte dei due giorni realmente
limitato nel tempo; timestamp accoppiati anche ai grafici dei Dettagli.
L'AVD Pampa_Tablet è uscito con codice1 prima dell'avvio nel tentativo aggiuntivo
per il widget. Nessun nuovo test launcher viene attribuito a quell'operazione.

## Correzione2 — esito

Versione `Q-R2-0aad796e4a07`, tre nuovi Sol high indipendenti; report completi
`weather-quality-review-r2-r1/r2/r3.md`. Esito **CORRECT + OPEN-GATE**.

| Criterio | R1 | R2 | R3 | Media | Minimo | Problemi aperti |
|---|---:|---:|---:|---:|---:|---|
| Q1 | 8 | 8 | 9 | 8,33 | 8 | Nessun difetto bloccante |
| Q2 | 8 | 8 | 8 | 8 | 8 | Nessun difetto bloccante |
| Q3 | 8 | 8 | 8 | 8 | 8 | Nessun difetto bloccante |
| Q4 | 7 | 7 | 7 | 7 | 7 | Full-app telefono corrente NV |
| Q5 | 8 | 7 | 7 | 7,33 | 7 | Picco UV etichettato giornaliero su finestra parziale; valori non finiti; take prima dei duplicati |

Il confronto pressione +3h e le curve temporali passano il perimetro corretto.
Correzione3/3: picco disponibile nelle sole ore future di oggi, timestamp unici e
valori UV finiti/non negativi; finestra temporale completa prima della validazione.
L'utente autorizza anche il telefono fisico SM-S931B, solo pacchetto Debug.
Il nuovo giro completa i flussi reali senza modificare l'app principale.

Steering dell'utente: ridurre le spiegazioni ovvie nell'interfaccia. Rimossi il
titolo ripetuto «Previsione del modello» e la legenda «Barre: probabilità...»;
percentuali e contesto parlano da soli. Sorgenti, clock e metodo rimangono espandibili;
lacune e conflitti significativi restano visibili. Nessuna modifica ai calcoli.
Filone cielo realistico avviato con due implementatori Sol high in checkout isolati,
escluso dalla candidata correttiva e dalle relative identità di review.

## Correzione3 — approvata nel perimetro di presentazione

Versione `Q-R3-db2d00bb8464`, tre nuovi critici Sol high indipendenti. Report
`weather-quality-review-r3-r1.md`, `weather-quality-review-r3-r2.md`,
`weather-quality-review-r3-r3.md`. Tutti raccomandano **ADVANCE**, senza difetti
bloccanti. Verifiche proprie in sola lettura;80 metodi Android del parent superati,
173 XML con output riutilizzati. Campo, prestazioni controllate, liveIA/audio e
13 ore fisiche restano NV; il nuovo widget non è incluso in questa approvazione.

| Criterio | R1 | R2 | R3 | Media | Minimo | Problemi aperti |
|---|---:|---:|---:|---:|---:|---|
| Q1 | 8 | 8 | 8 | 8 | 8 | Nessun difetto bloccante |
| Q2 | 8 | 8 | 8 | 8 | 8 | Nessun difetto bloccante |
| Q3 | 8 | 8 | 8 | 8 | 8 | Nessun difetto bloccante |
| Q4 | 8 | 8 | 8 | 8 | 8 | Telefono corrente ora coperto; launcher distinto NV |
| Q5 | 8 | 8 | 8 | 8 | 8 | Nessun difetto bloccante; limiti runtime separati |

Nessuna pubblicazione effettuata. L'utente ha aggiunto cielo realistico, widget
ispirato al riferimento iOS e ha poi autorizzato idratazione locale indipendente
da GPS/rete e recupero dai tentativi sensore falliti. Questi filoni sono separati,
con implementazioni in checkout isolati, test e review propri prima della consegna.
Modello e funzionamento delle segnalazioni non cambiano senza un nuovo incarico.
