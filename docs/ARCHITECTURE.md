# Architettura — Card Collection

## Obiettivo

App Android nativa per tracciare collezioni di carte Pokémon scelte dall'utente (la prima: tutti i Mawile e Mega Mawile). Per ogni carta tiene:
- varianti, rarità e codice stampato;
- immagini salvate in locale;
- quantità possedute per variante;
- carte mancanti;
- ultimo prezzo trovato.

Funziona **completamente offline**. Il controllo prezzi parte **solo a mano**. L'interfaccia è in **italiano e inglese**.

Decisioni prese con l'utente:
- Kotlin + Jetpack Compose;
- nessun backend, tutto sul telefono;
- ogni variante è uno slot della collezione;
- check prezzi manuale;
- più collezioni, con quantità separate;
- carte in più lingue (EN, IT, FR, DE, ES, PT, JA complete; ZH-TW, TH, ID limitate);
- app in italiano e inglese, a scelta in Impostazioni;
- codice stampato visibile ovunque;
- vista a griglia.

## Schema

```
UI Compose ──► MainViewModel ──► Repository ──► Room (unica fonte della UI → offline)
                                    │
                                    ├─ CatalogRepository ──► TcgdexClient (10 lingue) ──► ImageStore (filesDir/cards)
                                    └─ PriceRepository  ──► PriceChain
                                                              └─ TcgdexPriceProvider (Cardmarket EUR + TCGplayer USD)
WorkManager: CatalogSyncWorker, PriceCheckWorker (unici, espressi, avviati a mano)
```

| Modulo | Ruolo |
|---|---|
| `core` (Kotlin/JVM) | DTO TCGdex, `TcgdexClient`, `CatalogMapper`, `VariantMapper`, `PriceChain` + `RateLimiter` + `CircuitBreaker`, `Validators`, `CollectionFiles` (JSON), `CollectionStats`. Si compila e si testa senza Android SDK e non contiene testi per l'utente. |
| `app` (Android) | Room, DataStore, `ImageStore`, repository, worker, UI. Dipendenze iniettate a mano in `AppContainer`. |

**Testi e lingue:** repository, worker e core restituiscono solo dati ed esiti tipizzati (enum e sealed class, es. `InputError`, `ImportError`, `AbortReason`). È la UI a trasformarli in testo con `stringResource`. Le risorse sono in `res/values` (inglese, lingua di base) e `res/values-it` (italiano). La lingua si sceglie in Impostazioni (Sistema / Italiano / English) con `AppCompatDelegate.setApplicationLocales`, che la ricorda anche su Android 12 e precedenti. Date e valute seguono la lingua corrente.

## Fonte dati: TCGdex

Lo schema è stato verificato sulle risposte reali (workflow `tcgdex-probe.yml`).

- **Ricerca:** `/v2/{lingua}/cards?name=…` cerca per sottostringa del nome. `Mawile` trova anche `Mawile GX`, `M Mawile EX` e `Mega Mawile ex`; `クチート` trova anche `メガクチートex`.
- **Esclusioni:** le carte di **TCG Pocket** (serie `tcgp`, solo digitali) e le carte non Pokémon (es. "Mawile Spirit Link"). Sono escluse anche le varianti jumbo.
- **Dettaglio:** `/cards/{id}` contiene `variants_detailed`, con i prezzi **per variante**: `pricing.cardmarket` (EUR) e `pricing.tcgplayer` (USD, `null` per le carte JP).
- **Codice stampato:**
  - lingue europee: `set.abbreviation.localized` se presente (es. `PXY` in francese), altrimenti `official` (es. `POR`); assente nelle ere più vecchie;
  - cataloghi asiatici (JA, ZH-TW, TH, ID): l'id del set coincide con la sigla stampata (es. `SM10a`, `M1L`);
  - numero: `localId/cardCount.official`, con il totale allungato alle stesse cifre (`032/088`). Le promo restano invariate (`XY103`).
- **Immagini:** `{image}/low.webp` per la griglia e `{image}/high.webp` per il dettaglio. Molte carte asiatiche non hanno immagine: l'app mostra un segnaposto con nome e codice.
- **Varianti tradotte:** nei cataloghi europei `variants_detailed` può avere nomi tradotti (es. "Olografica"). In quel caso le varianti si ricavano dai flag `variants`, che hanno chiavi standard.

### Lingue verificate (probe)

| Lingua | Stato |
|---|---|
| EN, IT, FR, DE, ES, PT | catalogo, immagini e prezzi Cardmarket; TCGplayer solo per EN |
| JA | catalogo e prezzi Cardmarket; molte carte senza immagine |
| ZH-TW, TH, ID | poche carte, quasi senza immagini né prezzi (segnalate nella UI) |
| KO, ZH-CN, NL, PL, RU | escluse: ricerche vuote o quasi |

### Mappatura prezzi per variante

| Variante | Cardmarket (EUR) | TCGplayer (USD) |
|---|---|---|
| Normal | `trend` | `normal.marketPrice` |
| Holo | `trend` (affidabilità media se esiste anche la normal) | `holofoil` |
| Reverse | `trend-holo` | `reverse-holofoil` |
| 1st Edition | `trend` (affidabilità bassa) | `1stEditionHolofoil` / `1stEditionNormal` |

TCGdex usa `0` per "nessun dato": i valori nulli o pari a zero vengono scartati.

### Altre fonti valutate

- **pokemontcg.io:** non risponde più dal runner CI. Non è implementato.
- **PokemonPriceTracker:** non c'è modo di verificarne lo schema senza chiave. Non è implementato.

La catena `PriceChain` accetta altre fonti tramite l'interfaccia `PriceProvider`: aggiungerne una richiede solo l'implementazione e l'inserimento in `PriceRepository`. Quando non c'è un prezzo nuovo resta l'ultimo salvato, oppure il prezzo manuale.

## Modello dati (Room, versione 2)

| Tabella | Contenuto |
|---|---|
| `collection` | collezioni create dall'utente; alla prima installazione c'è "Mawile" |
| `tracked_query` | ricerche per nome e lingua **di una collezione** |
| `card` | catalogo condiviso: set, sigla, numero stampato, rarità, serie, data, illustratore, URL e percorsi delle immagini (`queryId` è un campo storico della v1) |
| `card_variant` | una riga per variante (slot) |
| `collection_card` | appartenenza carta ↔ collezione: `queryId` (ricerca che l'ha aggiunta, null se a mano o importata) e `hidden` (tolta dall'utente, il sync non la riaggiunge) |
| `collection_entry` | **dati utente per collezione**: quantità, condizione, "la voglio", note. Nessun vincolo verso il catalogo |
| `price_snapshot` | storico prezzi condiviso: ultimi 10 per variante, con fonte, data e affidabilità |
| `price_run` | registro degli aggiornamenti prezzi, con la collezione |

Una carta è visibile in una collezione se non è nascosta e se è stata aggiunta a mano oppure da una ricerca attiva. Il sync del catalogo aggiorna solo `card`, `card_variant` e i collegamenti: non tocca mai i dati dell'utente.

**Migrazione 1 → 2** (`Migrations.SQL_1_2`): crea la collezione "Mawile" con id 1, sposta lì ricerche e quantità e collega le carte tramite il vecchio `queryId`. `MigrationTest` la esegue su SQLite (JVM) e confronta il risultato con lo schema che Room esporta in `app/schemas/` durante la stessa build.

## File JSON di una collezione (schema 2)

```json
{
  "schema": 2, "app": "card-collection", "type": "collection", "exportedAt": "…",
  "info":    { "name": "Mawile", "queries": [{ "language": "en", "pattern": "Mawile", "enabled": true }] },
  "cards":   [{ "id": "en:me03-032", "language": "en", "tcgdexId": "me03-032", "name": "Mawile",
                "setId": "me03", "setName": "Perfect Order", "setCode": "POR", "localId": "032",
                "printedNumber": "032/088", "variants": ["NORMAL", "REVERSE"], "hidden": false,
                "imageLow": "<base64, facoltativa>", "imageHigh": "<base64, facoltativa>" }],
  "entries": [{ "variantId": "en:me03-032|NORMAL", "quantity": 2, "condition": "NM", "wanted": true }],
  "prices":  [{ "variantId": "en:me03-032|NORMAL", "source": "TCGdex", "eur": 0.03, "usd": 0.11,
                "fetchedAt": 1760000000000, "confidence": "HIGH" }]
}
```

- **Export:** senza immagini, con immagini piccole oppure con piccole e grandi. Nel file vanno l'ultimo prezzo di mercato e tutti i prezzi manuali di ogni variante.
- **Import:** crea sempre una nuova collezione, con nome reso unico se serve. Le carte già presenti nel catalogo non vengono sovrascritte; le immagini incluse si usano solo per le carte che non ne hanno.
- **Validazione:**
  - lettura in streaming con limite di 40 MB e al massimo 5.000 carte;
  - id e varianti controllati, con lunghezze troncate;
  - immagini accettate solo se WebP, PNG o JPEG entro 3 MB;
  - voci e prezzi tenuti solo se riferiti a carte del file.
- **Compatibilità:** il backup schema 1 della prima versione viene importato come "Collezione importata"; le carte arrivano al primo "Aggiorna catalogo".

## Flussi

0. **Navigazione:** l'elenco delle collezioni apre una collezione con le schede Carte, Mancanti, Statistiche e Gestione. Catalogo e prezzi si aggiornano per la collezione aperta, con lavori unici per collezione.
1. **Aggiorna catalogo:**
   1. ricerca per ogni query attiva;
   2. dettaglio di ogni carta e del suo set (con cache);
   3. filtro delle carte da escludere;
   4. salvataggio di carta e varianti in una transazione;
   5. download delle immagini mancanti.

   Tra due richieste passano almeno 250 ms; sul 429 l'app aspetta e riprova una volta.
2. **Aggiorna prezzi:** riguarda le varianti desiderate o possedute delle carte tracciate. Ogni carta viene scaricata una sola volta per run.
   - Rate limit di 300 ms tra le richieste.
   - Retry esponenziale su 429 e 5xx.
   - Circuit breaker: dopo 3 blocchi la fonte va in pausa per 6 ore; la pausa è salvata in DataStore.
   - Dopo 5 errori di rete consecutivi l'aggiornamento si interrompe.
   - I prezzi già salvati restano validi.
3. **Offline:** la UI legge solo da Room e le immagini dai file locali.

## Sicurezza

- **Rete:** solo HTTPS (`network_security_config` senza traffico in chiaro). Le immagini si scaricano solo da `assets.tcgdex.net`, con controllo di tipo e dimensione (massimo 3 MB).
- **Segreti:** nessuno nel repository. Le fonti usate non richiedono chiavi.
- **Validazione:** gli id remoti passano da una whitelist (niente `..` né `/`) prima di finire in URL o nomi file. I percorsi delle immagini sono verificati in forma canonica sotto `filesDir/cards`.
- **Risposte API:** limite di 2 MB; JSON tollerante ai campi sconosciuti ma con i tipi controllati.
- **Database:** solo query Room parametrizzate.
- **Input utente:** ricerche limitate a 2-40 caratteri ammessi, prezzi manuali a 0-99999,99, quantità a 0-999.
- **Import dei file JSON:** limite 40 MB in streaming, schema verificato, immagini controllate per formato e dimensione; le voci non valide vengono scartate e contate.

## Build e verifica

- `./gradlew :core:test`: test su fixture reali (mappatura, lingue, prezzi, catena con 429/403/circuit breaker, validatori, file JSON, statistiche).
- `./gradlew :app:testDebugUnitTest`: assemblaggio e visibilità della libreria, filtri, migrazione 1 → 2 confrontata con lo schema Room.
- Il lint di Android blocca la build se una stringa manca in una delle due lingue.
- La CI (`.github/workflows/android.yml`) esegue test, lint e `assembleDebug`, e pubblica l'APK come artifact.

## Possibili evoluzioni

- Aggiunta di fonti prezzi verificabili (es. una con chiave dell'utente per le carte JP).
- Check prezzi periodico, come opzione.
- Widget.
- Più condizioni per la stessa variante.
- Altre lingue dell'interfaccia: basta aggiungere `res/values-xx/strings.xml` e la voce in `locales_config.xml` e in Impostazioni.

## Prototipo

`docs/demo/prototype.html` è il prototipo cliccabile usato all'inizio per allinearsi su UX e idee, con dati di esempio. Non rispecchia l'app attuale.
