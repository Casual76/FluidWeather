# Regressioni segnalate dopo la 2.1.0

L'utente ha segnalato con schermate reali la scomparsa della testata meteo, testo
nero sulle tessere, un pannello quasi opaco e un widget occupato da diagnostica
senza temperatura. Queste regressioni non sono state intercettate dai gate precedenti.
La pubblicazione 2.1.0 non rappresenta quindi una verifica positiva di questi aspetti.

## Cause riscontrate e correzioni

- La nuova HomeHeader aveva sostituito interamente la testata precedente. Ripristinati
  località, temperatura grande, condizioni, min/max, età, composizione sul cielo e
  parallasse. La sintesi pioggia rimane un blocco compatto sottostante.
- HomeRainOverview aggiungeva una banda surfaceContainer all'88% sopra GlassTile.
  Rimossa la banda opaca; fonti, clock e limiti estesi sono dietro l'azione esplicita.
- Il tema Material scuro interno non reimpostava il LocalContentColor ereditato
  dall'app chiara. Il vetro sul canvas meteo fornisce ora lo stesso foreground
  chiaro a Text di default e ai consumer onSurface espliciti. I fogli senza canvas
  mantengono la propria palette. Engine invariato.
- Il widget richiedeva il nuovo current del brief per la temperatura, eliminando
  dati orari utilizzabili nelle cache precedenti. Ripristinata una previsione oraria
  vicina, univoca e qualificata con orario: non diventa una misura o un verdetto di
  pioggia. Il layout torna alle taglie compatte precedenti; diagnostica integrale,
  coordinate e clock estesi non occupano il launcher. Cache e rete restano separate.
- Righe giornaliere compatte a font normale, layout esteso con caratteri grandi;
  probabilità di pioggia sintetica nella tessera, dettaglio dei campioni nella pagina.
- La risposta best-match Open-Meteo contenente current era tenuta dalla cache meteo
  di 30 minuti: il dato corrente poteva scadere prima del successivo download. TTL
  best-match ridotto a 5 minuti, senza cambiare i timestamp originali; altri modelli
  conservano la cache precedente. Nessun aumento della frequenza dei job introdotto.

## Verifiche eseguite

375 risultati JVM pertinenti riusciti (widget45, home44, weather286), con regressioni
su cache precedenti qualificate e rinnovo del current senza ringiovanirne il timestamp.
I vecchi test che imponevano temperatura nulla nelle cache con previsione disponibile
sono stati corretti: quel contratto codificava il difetto, anziché evitarlo.

La prima prova Android del nuovo test di contrasto su Medium_Phone Android17 ha
fallito nel ridisegno richiesto da captureToImage, prima dell'asserzione sui pixel;
non è un pass. Log conservato in artifacts/hotfix-2.1.1/glass-contrast-device.log.
Su Android15 il test dei pixel passa: testo predefinito e onSurface esplicito sono
chiari sul vetro meteo anche con tema esterno chiaro. Ripetuto sull'ultimo APK UI:
1 test riuscito in 5,919 s, terminale -1, log glass-contrast-final.log.

Verificata visivamente l'app completa con dati reali Open-Meteo: Sesto Fiorentino
su telefono e Roma su tablet. Testata con temperatura grande e cielo ripristinati,
righe Daily compatte, testo bianco e pannello pioggia senza banda interna opaca.
Acquisizioni phone-home-sesto.png e tablet-home-final.png conservate negli artefatti.
Sul launcher Android15 il widget esistente mostra 54 gradi (preferenza QA Fahrenheit),
Roma, sintesi qualificata, quattro ore e ultimo orario dati, senza diagnostica estesa.
widget-final.png è ritagliato al widget reale, 608x466 px; il click sulla temperatura
apre effettivamente la Home. Nessun resize o controllo su altri launcher dedotto.
L'ultima pulizia successiva a queste catture mantiene separata l'etichetta del
verdetto barometrico dalla sintesi pioggia, evitando un secondo "piove" non qualificato.

La prova visiva è su debug; firma, R8 e metadati della release sono verificati a parte.
Nessuna prova di accuratezza meteorologica sul campo o di prestazioni controllate
viene dedotta da questi controlli. AVD spenti dopo le prove, dispositivo fisico intatto.

Release finale riuscita: 1m19s, 1.138 task (22 eseguiti, 1.116 up-to-date), inclusi
R8, resource shrinking e lint vital. Engine-doctor conferma engine 3.0.5 pulito.
APK di produzione dev.pampa.fluidweather, 2.1.1/code22, 24.967.980 byte,
SHA256 fccf640813a36a828857d5dfef3565bc34df60a7620b86e42325828216f1a184.
ZIP integro, ARM64/ARMv7, flag debug assente; apksigner verifica la firma v2 e il
certificato coincide con la stable 2.1.0 per l'aggiornamento sul posto.
