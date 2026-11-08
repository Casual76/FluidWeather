# F3 A - Home data e ordine: handoff al parent

Implementazione nel checkout condiviso; test scritti e NON eseguiti. Nessun Gradle/device,
commit/push, upgrade, provider, chiave, notifica o pubblicazione. Conservate le modifiche F1 di
backfill zoneId in HomeData; nessun intervento nei file di B.

## File e API

- `feature-home/.../HomeData.kt`: trailing `HomeUiState.weatherBrief: WeatherBrief? = null`;
  `rememberHomeState(deps, place, active: Boolean = true)` compatibile con B.
- `HomeDependencies.weatherBriefService` obbligatorio: singleton del grafo. Aggiunta dipendenza
  esplicita `dataGeneration: () -> Long`, collegata a `graph.wipeGuard::generation`.
- `app/.../navigation/FluidWeatherNavHost.kt`: soltanto i due argomenti HomeDependencies.
- `feature-home/.../HomeBriefController.kt`: `HomeBriefInput`, `homePoint`, `homeSnapshotMatches`,
  `HomeBriefController.consume`, interni e con clock/dispatcher/servizio/generazione iniettabili.
- `core-ui/.../HomeWidgets.kt`: ordered elimina unknown/duplicati mantenendo prima occorrenza e
  accodando default mancanti. visibleIds resta una proiezione: si persiste l'ordine completo.
- `feature-home/build.gradle`: solo coroutines-test con engine.coroutinesTest gia fissata.

## Flusso e lifecycle

Holder distinto per Place: nome/coordinate della selezione subito, nessun dato di altra citta.
Coordinate finite e nei range, GPS mancante resta null, (0,0) reale ammesso. Il dato legacy viene
accettato con placeKey corrispondente e distanza <=2km; il modello F2 valida anche il contesto.
Caricamento, readiness e consumo brief sono attivi soltanto con active e Lifecycle.RESUMED.
Il caricamento alla ripresa usa cache/cadenza esistente; nowcast resta GPS + barometro e viene
eseguito su Default. CancellationException e sempre propagata dalle operazioni di home.
Parent integration: when the requested place has no valid zone, the controller preserves the
zone from a matching local snapshot/context in the place carried by both immediate and shared
briefs. A snapshot for another key or point cannot donate its zone. Presentation therefore uses
known place metadata before declaring a device-zone fallback. A focused controller test covers
both the matching and foreign-snapshot paths.

Ogni input nuovo avvia immediatamente LocalPrecipitationEvaluator con radarSample=null su Default,
poi WeatherBriefService.brief condiviso (deadline/cache/cutoff restano F2). Nessuna fonte autonoma.
Il brief non dipende dal barometro o da fused.hours: current/minutely con ore fuse vuote funzionano.
Dettagli legacy e relativo fallback restano disponibili. Consumo ripetuto entro 60 secondi dall'inizio
del ciclo, anticipato all'ETA futura piu vicina, e immediato alla ripresa. collectLatest cancella
lavori vecchi; ensureActive e generazione impediscono emissioni tardive. Dopo il servizio si rilegge
lo store: contenuti cambiati anche a stesso fetchedAt scartano il risultato prima di pubblicarlo.

Wipe: ascolto dello store anche da home coperta/pausa; la generazione esistente viene inoltre
osservata ogni secondo per coprire cache disco non ancora annunciate da updates. I risultati brief
e le scritture legacy asincrone controllano generazione prima di pubblicare. Si azzerano sintesi,
snapshot e dettagli, si interrompe il consumo, e si attende un refresh esplicito. Un watermark del
momento di invalidazione rifiuta snapshot di round iniziati prima del wipe anche se arrivano dopo.
Il watermark sopravvive alla selezione di un'altra citta nella stessa composizione.

## Test scritti, non eseguiti

`HomeBriefControllerTest`: model immediato/current cached senza fused; deadline reale del servizio
con radar lento; cambio citta in volo; cambio contenuto a fetchedAt identico via input e durante
radar; rilettura periodica di una scrittura confluita; GPS NaN/infinito/key/distanza; ETA attraversata;
cancellazione equivalente a pausa/inactive e ripresa immediata; wipe con risposta non cancellabile
e cache rimossa. I test di pausa esercitano la cancellazione del consumer, non un owner Android.
`HomeWidgetOrderTest`: unknown/duplicati, permutazione valida, nuovi default, nowcast nascosto che
ritorna alla posizione originale. Parent esegue suite feature-home/core-ui e controlli integrazione.

## Limiti da verificare in integrazione

WeatherSnapshotStore.updates e uno StateFlow di mappe placeKey -> fetchedAt: una scrittura a timestamp
identico puo non emettere. Il controller la intercetta prima di applicare una risposta pendente e
alla rilettura <=60s; senza modificare lo store fuori ownership non si puo promettere una notifica
istantanea per quel caso. Le altre snapshot emesse aggiornano subito il consumer.

WeatherSnapshotRefresher esistente scrive lo store senza DataWipeGuard. Questa patch impedisce il
riuso nella home di round pre-wipe (watermark/generazione), ma non modifica la persistenza upstream:
il parent deve valutare separatamente quel limite, soprattutto se la composizione viene distrutta
tra wipe e ritorno. La visibilita della cancellazione senza eventi store ha risoluzione <=1s;
le risposte in corso non possono superare il controllo di generazione per ripubblicare il brief.
Nessuna dichiarazione di accuratezza sul campo, performance o approvazione gate.
