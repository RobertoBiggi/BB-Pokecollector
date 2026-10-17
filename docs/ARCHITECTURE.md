# Architettura — Card Collection

## Obiettivo

App Android nativa per tracciare un insieme di carte Pokémon scelto dall'utente (inizialmente tutti i Mawile e Mega Mawile, EN e JP). Per ogni carta tiene:
- varianti, rarità e codice stampato;
- immagini salvate in locale;
- quantità possedute per variante;
- carte mancanti;
- ultimo prezzo trovato.

Funziona **completamente offline**. Il controllo prezzi parte **solo a mano**.

Decisioni prese con l'utente:
- Kotlin + Jetpack Compose;
- nessun backend, tutto sul telefono;
- ogni variante è uno slot della collezione;
- check prezzi manuale;
- lingue EN e JP;
- codice stampato visibile ovunque;
- vista a griglia.

## Schema

```
UI Compose ──► MainViewModel ──► Repository ──► Room (unica fonte della UI → offline)
                                    │
                                    ├─ CatalogRepository ──► TcgdexClient (EN/JA) ──► ImageStore (filesDir/cards)
                                    └─ PriceRepository  ──► PriceChain
                                                              └─ TcgdexPriceProvider (Cardmarket EUR + TCGplayer USD)
WorkManager: CatalogSyncWorker, PriceCheckWorker (unici, espressi, avviati a mano)
```

| Modulo | Ruolo |
|---|---|
| `core` (Kotlin/JVM) | DTO TCGdex, `TcgdexClient`, `CatalogMapper`, `VariantMapper`, `PriceChain` + `RateLimiter` + `CircuitBreaker`, `Validators`, `BackupCodec`, `CollectionStats`. Si compila e si testa senza Android SDK. |
| `app` (Android) | Room, DataStore, `ImageStore`, repository, worker, UI. Dipendenze iniettate a mano in `AppContainer`. |

## Fonte dati: TCGdex

Lo schema è stato verificato sulle risposte reali (workflow `tcgdex-probe.yml`).

- **Ricerca:** `/v2/{en|ja}/cards?name=…` cerca per sottostringa del nome. `Mawile` trova anche `Mawile GX`, `M Mawile EX` e `Mega Mawile ex`; `クチート` trova anche `メガクチートex`.
- **Esclusioni:** le carte di **TCG Pocket** (serie `tcgp`, solo digitali) e le carte non Pokémon (es. "Mawile Spirit Link"). Sono escluse anche le varianti jumbo.
- **Dettaglio:** `/cards/{id}` contiene `variants_detailed`, con i prezzi **per variante**: `pricing.cardmarket` (EUR) e `pricing.tcgplayer` (USD, `null` per le carte JP).
- **Codice stampato:**
  - EN: `set.abbreviation.official` (es. `POR`), assente nelle ere più vecchie;
  - JP: l'id del set coincide con la sigla stampata (es. `SM10a`, `M1L`);
  - numero: `localId/cardCount.official`, con il totale allungato alle stesse cifre (`032/088`). Le promo restano invariate (`XY103`).
- **Immagini:** `{image}/low.webp` per la griglia e `{image}/high.webp` per il dettaglio. Molte carte JP non hanno immagine: l'app mostra un segnaposto con nome e codice.

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

## Modello dati (Room, versione 1)

| Tabella | Contenuto |
|---|---|
| `tracked_query` | ricerche per nome e lingua; le iniziali sono Mawile (EN) e クチート (JP) |
| `card` | catalogo: set, sigla, numero stampato, rarità, serie, data, illustratore, URL e percorsi delle immagini |
| `card_variant` | una riga per variante (slot) |
| `collection_entry` | **dati utente**: quantità, condizione, "la voglio", note. Nessun vincolo verso il catalogo, così il backup si importa anche prima del sync |
| `price_snapshot` | storico prezzi: ultimi 10 per variante, con fonte, data e affidabilità |
| `price_run` | registro degli aggiornamenti prezzi |

Il sync del catalogo aggiorna solo `card` e `card_variant`: non tocca mai i dati dell'utente.

## Flussi

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
- **Import del backup:** dimensione massima 2 MB, schema verificato; le voci non valide vengono scartate e contate.

## Build e verifica

- `./gradlew :core:test`: 30 test su fixture reali (mappatura, prezzi, catena con 429/403/circuit breaker, validatori, backup, statistiche).
- `./gradlew :app:testDebugUnitTest`: test di assemblaggio e filtri della libreria.
- La CI (`.github/workflows/android.yml`) esegue test, lint e `assembleDebug`, e pubblica l'APK come artifact.

## Possibili evoluzioni

- Aggiunta di fonti prezzi verificabili (es. una con chiave dell'utente per le carte JP).
- Check prezzi periodico, come opzione.
- Widget.
- Più condizioni per la stessa variante.
- Migrazioni Room esplicite al primo cambio di schema (gli schemi sono esportati in `app/schemas/`).

## Prototipo

`docs/demo/prototype.html` è il prototipo cliccabile usato per allinearsi su UX e idee, con dati di esempio.
