# Piano — App Android offline per tracciare carte Pokémon (Mawile / Mega Mawile)

## Contesto

Il repo `Card-collection` è vuoto (solo `README.md`). Serve un'app **Android nativa** che:
- tracci un insieme di carte scelte dall'utente (inizialmente **tutti i Mawile e Mega Mawile, EN + JP**), in modo generico per poter aggiungere altri Pokémon;
- consideri ogni **variante** (normal, holo, reverse holo, 1st edition, promo…) e la **rarità**;
- salvi **in locale** immagini, quantità possedute per variante, carte mancanti e **ultimo prezzo** trovato;
- funzioni **completamente offline** e faccia il price check **solo manualmente** (pulsante / pull-to-refresh);
- non dipenda da Cardmarket diretto (che blocca anche utenti umani): prezzi da API aggregatrici con **catena di fallback**.

Decisioni prese con l'utente: Kotlin + Jetpack Compose · niente backend (tutto sul telefono) · ogni variante = uno slot della collezione · check prezzi manuale · lingue EN + JP · prezzi Cardmarket EUR + TCGplayer USD + altre fonti se servono.

## Architettura

```
UI (Compose) ──► ViewModel ──► Repository ──► Room (fonte di verità, offline)
                                   │
                                   ├──► CatalogSync  ──► TCGdex API (EN/JA) ──► ImageStore (filesDir)
                                   └──► PriceCheckWorker (WorkManager, avviato a mano)
                                            └──► PriceProviderChain
                                                   1. TCGdex pricing (Cardmarket EUR + TCGplayer USD)
                                                   2. pokemontcg.io v2 (solo EN, chiave opzionale)
                                                   3. PokemonPriceTracker (EN+JP, chiave free utente)
                                                   4. Prezzo manuale
```

La UI legge **sempre e solo** da Room → l'app è offline per costruzione; la rete serve solo per "Aggiorna catalogo" e "Aggiorna prezzi".

### Stack
Kotlin 2.x · Jetpack Compose (Material 3) · Hilt · Room + KSP · Ktor client (OkHttp engine) + kotlinx.serialization · WorkManager · Coil 3 · DataStore · Tink (cifratura chiavi API) · Navigation Compose. Test: JUnit, MockWebServer, Room in-memory, Turbine. Build: Gradle wrapper + version catalog (`gradle/libs.versions.toml`), tutte le dipendenze locali al progetto.

## Fonti dati

**Catalogo + immagini: TCGdex** (`api.tcgdex.net/v2/{en|ja}/…`, gratis, senza chiave).
- Fornisce nome, set, numero, rarità, data, illustratore, URL immagine (`{image}/high.webp`, `{image}/low.webp`) e `variants {normal, reverse, holo, firstEdition, wPromo}`.
- Ricerca per nome: EN `Mawile` (copre "Mawile", "Mawile-GX", "M Mawile-EX", "Mega Mawile ex"); JP `クチート` (copre `メガクチート`, `MクチートEX`). Le query sono righe di tabella (`tracked_query`), quindi modificabili dall'app.

**Prezzi — catena di fallback per variante** (interfaccia `PriceProvider`, ordine configurabile):
1. **TCGdex pricing**: Cardmarket (EUR: avg, low, trend, avg7/avg30, campi `-holo`) + TCGplayer (USD per variante: normal / holofoil / reverse-holofoil / 1st edition). Dati Cardmarket senza toccare cardmarket.com.
2. **pokemontcg.io v2**: solo EN, stessi due mercati; chiave opzionale. Il futuro del servizio è incerto (passaggio a Scrydex) → provider opzionale e disattivabile.
3. **PokemonPriceTracker API**: piano free (≈100 crediti/giorno, 2 req/min) con carte **giapponesi**; chiave inserita dall'utente.
4. **Manuale**: l'utente inserisce un prezzo (es. letto da Cardmarket nel browser). Ogni carta ha un link "Apri su Cardmarket/TCGplayer".

Regola: **mai scraping diretto di Cardmarket** (blocchi anti-bot + ToS).
Mappatura varianti in `VariantMapper`: NORMAL↔`normal`/`avg,trend`; HOLO↔`holofoil`/`*-holo`; REVERSE↔`reverse-holofoil`/`reverseHoloTrend`; FIRST_EDITION↔`1stEdition*`. Se la fonte non distingue la variante, lo snapshot viene salvato con `confidence = LOW` e la UI lo segnala.

## Modello dati (Room)

| Tabella | Campi principali |
|---|---|
| `tracked_query` | id, label, language (`en`/`ja`), name_pattern, enabled |
| `card` | id (`en:swsh11-091`), language, set_id, set_name, number, name, rarity, release_date, illustrator, image_url, image_low_path, image_high_path, catalog_updated_at |
| `card_variant` | id, card_id FK, type (NORMAL/HOLO/REVERSE/FIRST_EDITION/W_PROMO/OTHER), **wanted** (default true) |
| `collection_entry` | id, variant_id FK, condition (NM/LP/MP/HP/DMG), quantity ≥ 0, notes |
| `price_snapshot` | id, variant_id FK, source, currency, low, mid, trend, market, avg7, avg30, source_updated_at, fetched_at, confidence |
| `price_run` | id, started_at, finished_at, status, ok_count, fail_count, error_summary |

- Quantità per variante = somma di `collection_entry.quantity`.
- **Slot** = variante con `wanted = true`; **mancante** = slot con quantità 0. Completamento = slot posseduti / slot wanted (globale, per lingua, per set).
- `price_snapshot`: si tengono gli ultimi N (es. 10) per variante+fonte → "ultimo prezzo" + freccia di trend; vista `latest_price`.
- Il sync catalogo fa upsert di `card`/`card_variant` **senza mai toccare** quantità e `wanted`.
- Migrazioni Room esplicite + `exportSchema = true` (schemi versionati in `app/schemas/`).

## Flussi

1. **Aggiorna catalogo** (manuale): per ogni `tracked_query` attiva → lista TCGdex → dettaglio carta → upsert transazionale → download immagini low+high in `filesDir/cards/{lang}/{set}/{number}-{low|high}.webp`. Coil carica prima il file locale, poi l'URL come fallback.
2. **Aggiorna prezzi** (manuale): `OneTimeWorkRequest` unico (`ExistingWorkPolicy.KEEP`), expedited, vincolo rete, notifica di avanzamento (continua anche se esci dall'app).
   - Per ogni variante wanted/posseduta: provider in ordine; il primo con dato valido vince (le fonti successive servono solo da fallback, salvo opzione "raccogli tutte").
   - Rate limiter token-bucket per provider; retry esponenziale su 429/5xx; **circuit breaker**: dopo K errori 403/429 il provider viene saltato per il resto del run e messo in cooldown per X ore (salvato in DataStore).
   - Errore → resta lo snapshot precedente, marcato "non aggiornato da …".
   - Il risultato del run viene salvato in `price_run`.
3. **Offline**: tutto da Room; banner "Prezzi aggiornati il …" e badge per prezzi vecchi.

## Schermate

- **Collezione**: griglia con immagine; badge "possedute/slot" per carta; filtri lingua / set / rarità / variante / posseduto / mancante; ordinamento per prezzo, data, numero.
- **Dettaglio carta**: immagine grande (zoom), rarità, set; lista varianti con stepper +/-, condizione, toggle "wanted"; prezzi per fonte con data e confidenza; link esterni; inserimento prezzo manuale.
- **Mancanti & statistiche**: % completamento, lista mancanti con costo stimato, valore collezione in EUR e USD (separati, nessuna conversione implicita).
- **Impostazioni**: query tracciate, chiavi API, ordine provider, valuta preferita, export/import backup.

## Backup (essenziale senza server)

Export/import JSON (versione schema, carte, varianti, quantità, prezzi) tramite Storage Access Framework (la posizione la sceglie l'utente). Le immagini non vengono esportate: si riscaricano. In più Android Auto Backup del DB.

## Sicurezza

- Solo HTTPS (`network_security_config` con cleartext disabilitato); nessun secret nel codice: le chiavi API le inserisce l'utente e sono cifrate con Tink (chiave nell'Android Keystore) dentro DataStore.
- Risposte API validate con kotlinx.serialization (schemi rigidi, limiti di lunghezza, numeri ≥ 0, URL immagine solo da host in allowlist).
- Room usa solo query parametrizzate (DAO `@Query` con argomenti, niente SQL concatenato).
- **Path traversal**: i nomi file delle immagini derivano da id normalizzati con whitelist `[a-z0-9_-]`, si verifica che il path canonico resti sotto `filesDir/cards`, si controllano content-type e dimensione massima.
- Validazione degli input utente (pattern di ricerca: lunghezza e caratteri; quantità ≥ 0; prezzi manuali) e dell'import backup (versione, dimensione massima, schema).
- R8/minify in release; nessun permesso oltre `INTERNET` e `POST_NOTIFICATIONS`.

## Struttura del progetto

```
app/src/main/java/.../cardcollection/
  data/local/      (entity, dao, AppDatabase, migrations)
  data/remote/     (tcgdex/, pokemontcg/, ppt/ — DTO + client)
  data/price/      (PriceProvider, PriceProviderChain, VariantMapper, RateLimiter, CircuitBreaker)
  data/image/      (ImageStore)
  data/repo/       (CardRepository, CollectionRepository, PriceRepository)
  work/            (PriceCheckWorker, CatalogSyncWorker)
  ui/collection, ui/detail, ui/stats, ui/settings, ui/theme
  di/              (moduli Hilt)
docs/ARCHITECTURE.md   (questo piano, versionato nel repo)
```

Convenzione: ogni funzione ha un KDoc in italiano con parametri (nome, tipo, descrizione), valore di ritorno e scopo.

## Milestone

0. Copiare questo piano in `docs/ARCHITECTURE.md` e aggiornare `README.md`.
1. Scaffold: Gradle wrapper, version catalog, Compose, Hilt, tema; GitHub Actions (`./gradlew lint test assembleDebug`).
2. Data layer: entity/DAO/DB, client TCGdex, `CatalogSync`, `ImageStore` e test.
3. UI Collezione + Dettaglio + gestione quantità/wanted.
4. Prezzi: `PriceProvider` TCGdex, chain, rate limiter, circuit breaker, `PriceCheckWorker`, prezzo manuale.
5. Mancanti/statistiche, impostazioni, backup export/import.
6. Provider aggiuntivi (pokemontcg.io, PokemonPriceTracker per JP), toggle opzionale di check automatico, widget.

## Verifica

- **Unit test**: `VariantMapper` (ogni combinazione), parsing DTO con fixture JSON reali per ogni provider, chain con MockWebServer (successo, fallback, 429 → retry, 403 ripetuti → circuit breaker), `ImageStore` (rifiuto di id malevoli tipo `../`), calcolo completamento.
- **Test DAO** Room in-memory + test delle migrazioni (`MigrationTestHelper`).
- `./gradlew lint test assembleDebug` in locale e in CI.
- **Manuale su device/emulatore**: sync catalogo (verificare che compaiano tutti i Mawile/Mega Mawile EN+JP con immagini), modalità aereo → app pienamente navigabile con prezzi e immagini, aggiorna prezzi con rete → nuovi snapshot e data aggiornata, export → reinstall → import.
- Note ambiente: in questo container il dominio `api.tcgdex.net` non è raggiungibile e l'Android SDK non è installato. Per compilare qui servirà installare l'SDK fuori dalla directory del progetto (**da chiedere prima**) oppure affidarsi alla CI GitHub Actions. Le fixture JSON andranno prese dalla documentazione oppure da te.
