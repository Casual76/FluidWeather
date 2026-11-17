# Affidabilità quotidiana di FluidWeather

Incarico del 17 novembre 2026. Base iniziale: `46f2fa877003305d122faaf09aa0d9656b7bc7da`.

## Obiettivo e metodo

Rendere affidabili le funzioni già offerte, con precedenza alle notifiche e ai percorsi quotidiani. Le nuove funzionalità richiedono una motivazione di prodotto; le correzioni dei comportamenti esistenti fanno parte dell'incarico. Il lavoro procede per ricognizione parallela, riproduzione dei difetti, interventi con responsabilità separate, integrazione e review indipendente. Le priorità vengono aggiornate in base alle prove, senza ridurre la profondità delle singole correzioni.

L'utente ha confermato che il problema delle notifiche è variabile e va indagato nel suo insieme. Ha richiesto **solo verifiche sul computer per ora**: nessun emulatore o telefono viene utilizzato. La consegna distingue test JVM, compilazione e analisi statica da consegna reale delle notifiche, comportamento in Doze e prove sul campo.

Tre agenti con contesto fresco analizzano notifiche, dati meteo e percorsi d'uso. L'orchestratore integra e gestisce l'unico runner Gradle del progetto. Il filesystem è condiviso; i perimetri di scrittura vengono assegnati esplicitamente. Modello ereditato: GPT-6.1 Sol, reasoning ultra. Il limite locale è tre figli contemporanei; gli incarichi successivi riutilizzano la capacità disponibile. Il Fluid Engine rimane alla versione fissata dal progetto.

## Criteri di accettazione

- Gli avvisi falliti non diventano eventi già consegnati; permessi, canali, ripartenze e tentativi successivi rispettano le scelte dell'utente.
- I riepiloghi usano lavoro durevole e la data/ora locale corretta; dati mancanti non diventano tempo asciutto.
- Cache, provider e interfacce recuperano da errori temporanei senza mostrare dati di un altro luogo o dichiarare freschi dati vecchi.
- Radar, home, widget e impostazioni applicano il comportamento promesso; errori visibili hanno un percorso di recupero.
- Le regressioni importanti hanno prove pertinenti, le suite interessate passano e l'APK di valutazione compila.
- La review indipendente distingue difetti bloccanti, limiti delle prove e verifiche che richiedono un dispositivo reale.

## Stato

Branch di lavoro: `reliability/daily-use-20261117`. Rapporti dettagliati, log ed evidenze locali in `artifacts/reliability-2026-11-17/`.

La suite iniziale è passata in 1m26s: rapporti XML per 1.277 casi, zero fallimenti/errori/saltati. Il batch comprende task eseguiti, recuperati da cache e già aggiornati: il totale dei rapporti non equivale a 1.277 nuove esecuzioni. Due invocazioni preliminari non hanno eseguito test (quoting PowerShell e scoperta del toolchain Java); i log sono conservati separatamente.

Prove rosse eseguite prima degli interventi interessati: 1 difetto DST; 16 test notifiche con 13 fallimenti e 3 controlli passanti; 20 test dati con 18 fallimenti e 2 controlli passanti; 3 test del costruttore orario reale con 2 fallimenti e 1 controllo passante. Sono fixture di regressione, non misure meteo sul campo. Le ulteriori prove aggiunte durante l'implementazione verranno riportate separatamente.

## Interventi candidati, in attesa di verifica integrata

| Area | Risultato richiesto |
|---|---|
| Consegna notifiche | Il registro avanza per ciascun invio accettato, con controllo di permessi/canale/gruppo e memoria distinta per luogo. Un host banner assente consente il ripiego sul sistema. |
| Riepilogo | Handoff breve a WorkManager, lavoro unico per occorrenza, retry entro sei ore e sullo stesso giorno, recupero al riavvio del processo, riconciliazione ora/fuso/riavvio/aggiornamento e correzione DST. |
| Pioggia | Intervalli espliciti e compatibili nella fusione; nessuna suddivisione inventata di accumuli di tre ore. Notifiche e presentazioni usano l'inizio effettivo; dati mancanti/legacy ambigui non diventano asciutto o zero. |
| Cache e provenienza | Un giro vuoto non sostituisce l'ultima cache utile. L'acquisizione reale dei contributori sopravvive al salvataggio; l'ora del giro rimane distinta dall'età dei dati. |
| Taratura e nowcast | Riferimenti confrontabili nel tempo/spazio, persistenza con esito reale, scadenza della vecchia raffica, nessun nuovo verdetto operativo da un segnale fermo o futuro. Letture senza fix recente conservate senza falsa posizione/quota. |
| Apprendimento locale | Giudizi nuovi versionati e riferiti al luogo; righe pregresse conservate. I pesi locali non si trasferiscono automaticamente a un'altra città; la quota mostrata deriva dai contributi reali della fusione. |
| Radar e uso quotidiano | Aggiornamento/riprova e ripresa lifecycle; frame vecchi riconoscibili; refresh Home esplicito, widget invalidato dalle località, fusi coerenti, preferenze aptiche e risparmio energia applicati. |
| Azioni e assistente | Esito visibile di prova notifiche e cancellazione incompleta; cancellazioni indipendenti tentate e fallimenti propagati; serializzazione del cambio canale aggiornamenti; tolleranza al normale jitter GPS mantenendo le barriere di grounding. |

Gli agenti non eseguono build né pilotano dispositivi. Le suite verdi della candidata, la migrazione Room sul computer, la compilazione APK e le review indipendenti restano da completare prima della consegna.
