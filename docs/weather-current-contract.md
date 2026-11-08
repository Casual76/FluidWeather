# F2 A - provider current e intervalli

Implementazione della semantica; non e una validazione dell'accuratezza meteo reale.
C1/C7 richiedono integrazione, esecuzione test e review parent. Nessuna dichiarazione C2/C6.

## API esatte per F2 C

Package `dev.pampa.fluidweather.core.model`, dichiarazioni in `Forecast.kt`:

```kotlin
enum class AccumulationAnchor { PRECEDING, FOLLOWING, UNKNOWN }
data class PrecipitationAccumulation(
  val durationMillis: Long? = null,
  val anchor: AccumulationAnchor = AccumulationAnchor.UNKNOWN,
)
data class ProviderCurrent(
  val timestampMillis: Long,
  val temperatureC: Double? = null,
  val kind: WeatherKind? = null,
  val precipitationMm: Double? = null,
  val accumulationIntervalMillis: Long? = null,
  val downloadedAtMillis: Long? = null,
  val possiblyInterpolated: Boolean? = null,
) {
  val evidenceType: WeatherEvidenceType // sempre MODEL
  val precipitationInterval: WeatherInterval? // [time - interval, time)
}
data class RainAccumulation(val amountMm: Double, val interval: WeatherInterval)
```

Parametri trailing aggiunti a `ForecastBundle`, dopo `zoneId`:

```kotlin
val current: ProviderCurrent? = null
val hourlyPrecipitation: PrecipitationAccumulation? = null
val minutelyPrecipitation: PrecipitationAccumulation? = null
val minutelyPossiblyInterpolated: Boolean? = null
```

Metodi di `ForecastBundle` (durate e istanti sempre millisecondi UTC):

```kotlin
fun rainNow(nowMillis: Long, maxAgeMillis: Long = 45 * 60_000L): RainAccumulation?
fun rainNowMm(nowMillis: Long, maxAgeMillis: Long = 45 * 60_000L): Double?
fun rainLastHour(nowMillis: Long, maxAgeMillis: Long = 45 * 60_000L): RainAccumulation?
fun rainLastHourMm(nowMillis: Long, maxAgeMillis: Long = 45 * 60_000L): Double?
fun rainSoon(nowMillis: Long, withinMillis: Long): RainAccumulation?
fun rainSoonMm(nowMillis: Long, withinMillis: Long): Double?
```

`RainAccumulation` e un accumulo di modello, non un'osservazione fisica. Il sourceId e
l'istante del download vanno presi dal bundle; non usare il timestamp del round.
Per current temperatura e kind sono istantanei a `timestampMillis`; soltanto la
precipitazione usa `precipitationInterval`. Non attribuire quell'intervallo alla temperatura.
`ProviderCurrent` rifiuta temperatura non finita, precipitazione negativa/non finita e durata
non positiva; i parser scartano i campi invalidi. Nessuna conversione di missing in zero.
Se `current.time` manca o non e valido, current e null. Un blocco senza valori utili e null.
Se manca un intervallo valido, la precipitazione puo sopravvivere ma non ha intervallo noto:
il consumer non deve trasformarla in un tasso o in una prova di pioggia locale attuale.

## Open-Meteo

La stessa richiesta forecast include `current=temperature_2m,weather_code,precipitation`.
Si mantiene `http.readJsonTimed`: current e bundle portano il download originario anche
quando la risposta arriva dalla cache URL. Nessun uso di `System.currentTimeMillis` nel parsing.
`current.interval` e in secondi: conversione a millisecondi, senza assumere 900 secondi.
Accumulo current: `[time-interval,time)`, mai il quarto futuro. Unix time resta UTC,
indipendente da `utc_offset_seconds`. Il fuso IANA valido e conservato nel bundle.
Hourly precipitation: durata 3_600_000 e PRECEDING; minutely: 900_000 e PRECEDING.

Le condizioni current provengono da modelli e le serie a 15 minuti possono essere
interpolate dalle orarie. Il payload non prova la risoluzione nativa del modello selezionato:
`possiblyInterpolated=true` e `minutelyPossiblyInterpolated=true` sono limiti conservativi,
non affermano che sia avvenuta un'interpolazione specifica. Non inventare risoluzione spaziale.

Fonte primaria consultata 2026-11-07: [Open-Meteo docs](https://open-meteo.com/en/docs),
sezioni Current Weather, Current response e 15-Minutely Parameter Definition.

## Aggregazioni e precisione

I punti `MinutePoint` conservano il contratto storico di quarti precedenti con fine al
timestamp. Metadata legacy null resta unknown; per compatibilita gli helper accettano
questo formato storico. Metadata esplicita incompatibile con quarter/PRECEDING impedisce
l'aggregazione. C non deve usare il successo dell'helper per inventare metadata o provenienza
mancante nella cache legacy.

- `rainNow` sceglie l'ultimo punto chiuso, allineato alla griglia UTC di 900_000 ms, e impone
  eta <= maxAgeMillis. Valore nullo/invalido o duplicato di quell'intervallo produce null;
  non ripiega su un altro punto piu vecchio per nascondere un valore sconosciuto.
- `rainLastHour` termina alla fine dell'ultimo quarto effettivamente chiuso disponibile,
  soggetto alla stessa soglia di eta. Accetta esattamente quattro endpoint unici e contigui
  distanti 900_000 ms, tutti validi. Endpoint extra, duplicati, buchi o valori null/NaN/negativi
  invalidano il totale. Un punto alla sinistra dell'intervallo e fuori dalla somma.
- `rainSoon` usa soltanto quarti interamente dentro `[now, now+withinMillis)`. Arrotonda
  l'inizio al quarter successivo e la fine al quarter precedente, richiede tutta la copertura
  interna e restituisce l'intervallo effettivo. Esempio 12:07 + 60 minuti: somma soltanto
  `[12:15,13:00)`. Nessuna prorata per 12:07-12:15 o 13:00-13:07. Un buco interno invalida
  il risultato; nessuna somma parziale fino al primo buco.
- `rainSoonMm` restituisce il numero soltanto se la copertura coincide esattamente con la
  richiesta. Un clock non allineato o un orizzonte incompleto restituisce null. Usare
  `rainSoon` per presentare esplicitamente una finestra interna di durata diversa.

La soglia di 45 minuti e una politica compatibile gia esistente, non prova di attualita
meteorologica. C puo richiedere una soglia minore tramite i metodi espliciti. L'eta del
download va valutata separatamente; la chiusura dell'intervallo non rende fresco un download.
La pipeline `toContext`/`contextAsOf`, gli slot orari addestrati, la fusione e le politiche
barometriche non sono modificati. `RainObservations` beneficia della validazione di
`rainNowMm` e continua a ritornare null per input non utilizzabile.

## Provider coarse e date locali

OWM `rain.3h`/`snow.3h`: conserva l'importo non normalizzato, metadata 10_800_000 ms,
PRECEDING. Non e mm/h, current o risoluzione minutuale. Timestamp `dt` UTC invariato.
La priorita legacy rain poi snow resta invariata: nessuna riscrittura della fusione in F2 A.
Il solo offset `city.timezone` non e un fuso IANA e non viene persistito come tale.
Fonte: [OWM 5 day forecast](https://openweathermap.org/forecast5), JSON response fields.

Meteosource: metadata `PrecipitationAccumulation(null, UNKNOWN)`. La cadenza hourly
non dimostra l'intervallo della precipitazione. La [documentazione primaria](https://www.meteosource.com/documentation)
consultabile non ha consentito una verifica sufficiente di durata/ancoraggio: non inventati.
Le date locali vengono convertite solo con un timezone IANA valido fornito dal provider.
Timezone assente/invalido, data invalida, gap DST o overlap DST: punto scartato, nessun UTC
silenzioso e nessuna scelta arbitraria di offset. Il bundle conserva il fuso valido.
Non sono state effettuate richieste con chiavi o introdotti endpoint.

## Snapshot codec v1

I nuovi dati vivono in `snapshot.context.current` e nei metadata del bundle context.
Chiavi opzionali bundle: `current`, `hourlyPrecipitation`, `minutelyPrecipitation`,
`minutelyPossiblyInterpolated`. Current: `t`, `temp`, `kind`, `pr`, `intervalMillis`,
`downloadedAtMillis`, `possiblyInterpolated`. Metadata: `durationMillis`, `anchor`.
`evidenceType` e derivato MODEL dal tipo, mai derivato dall'eta del timestamp.
Legacy senza chiavi nuove: tutti i nuovi campi null; nessuna ricostruzione del current da
hourly o del download da quello del round. `contextCarriedFromMillis` resta indipendente.
Il codec non ringiovanisce alcun timestamp. Il fallback Open-Meteo preferisce best_match
nonnull e poi la prima variante con bundle effettivamente nonnull.

## Test scritti, non eseguiti

- `core-model/.../ForecastIntervalsTest.kt`: clock non allineato, finestra UTC a mezzanotte,
  closed/future, stale, quattro duplicati, quinto punto, buchi, missing/null/NaN/negativi,
  copertura futura esatta e intervallo interno, esclusione metadata coarse.
- `core-weather/.../ProviderCurrentContractTest.kt`: request/current, secondi e intervallo
  precedente, cache download originario, valori invalidi/missing, codec v1 legacy/roundtrip,
  prima variante nulla e successiva valida, OWM3h, Meteosource timezone/DST ambiguo.
- `MinutelyRainTest`: aspettativa aggiornata per rifiutare un totale futuro incompleto.

Parent: serializzare test/build e integrare con F2 B/C. Nessun Gradle, device, commit o push
eseguito dall'implementer A. Questi test non costituiscono evidenza di accuratezza reale.
