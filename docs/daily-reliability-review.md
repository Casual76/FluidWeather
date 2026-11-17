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
