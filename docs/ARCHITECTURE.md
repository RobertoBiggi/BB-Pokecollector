# Piano — App Android offline per tracciare carte Pokémon (Mawile / Mega Mawile)

## ▶ Step attuale: feedback demo v1 → codice carta stampato

L'utente ha visto la demo ([artifact](https://claude.ai/artifact/8VdKkXWB3pcTJbBSKh53tt), `docs/demo/prototype.html`) e ha confermato due cose: **ogni variante è uno slot** e la **griglia** è perfetta. Chiede di aggiungere il **codice carta stampato in basso a sinistra** (es. `M2a 001/123`).

**Modifiche alla demo** (`docs/demo/prototype.html`):
- Ogni carta in `CARDS` riceve `code` (sigla del set stampata, es. `M2a`, `SV4a`, `MEW`, `PAL`) e `num` nel formato stampato `001/123`. Le carte delle ere senza sigla stampata (prima di Sole e Luna) mostrano solo il numero.
- Funzione `printedCode(card)` → `"M2a 001/123"`; la uso in:
  - **illustrazione**: in basso a sinistra, come sulla carta vera (al posto dell'attuale set/numero separati);
  - **tile della griglia**: riga meta `M2a 001/123 · Doppia Rara` in font mono, con l'era spostata nel dettaglio;
  - **dettaglio**: voce "Codice" in cima alla lista dei metadati;
  - **Mancanti**: nel sottotitolo di ogni riga.
- Ricerca anche per codice (es. digitando "m2a" o "001/").
- Nuovo ordinamento "Ordina: codice" (sigla, poi numero).
- Bump di `STORE_KEY` non necessario: i dati catalogo stanno nel codice, non nello stato salvato.

**Modifiche al piano Android** (sezioni sotto):
- `card`: aggiungo `set_code` (sigla stampata, nullable) e `printed_number` (`001/123`). Li ricavo da TCGdex: `set.abbreviation.official` (EN) oppure l'id del set JP (es. `M2a`), più `localId` e `set.cardCount.official`.
- UI: codice sempre visibile su tile, dettaglio e mancanti; ricerca e ordinamento per codice.
- Va confermato sulle risposte reali di TCGdex che la sigla JP corrisponda all'id del set: se non è così, serve una tabella di mappatura `set_id → codice` in `VariantMapper`/`SetCodeMapper`.

**Verifica**: stesso script Playwright della v1 (390px, light/dark, zero errori console, nessun overflow) + screenshot della griglia e del dettaglio per controllare il codice in basso a sinistra; ripubblico sullo stesso artifact, poi commit + push su `ccr-da55776b-conrjq`.

**Dopo il via libera dell'utente sulla demo** → Milestone 1 (scaffold Kotlin/Compose + CI GitHub Actions).

---

## ▶ Step attuale: demo con prototipo cliccabile

Prima di scrivere codice Kotlin, l'utente vuole una demo per controllare che siamo allineati su UX e idee. Scelta fatta: **prototipo web cliccabile** che simula l'app sul telefono. Va pubblicato come Artifact privato (link da aprire sul telefono) e versionato in `docs/demo/prototype.html`.

**Contenuto** (un solo file HTML, mobile-first, tema chiaro/scuro, niente dipendenze esterne):
- **Collezione**: griglia di carte con illustrazioni segnaposto disegnate in CSS. Non posso garantire le vere immagini TCGdex da qui: le mostro con stile per tipo Acciaio/Folletto ed effetto shimmer su holo/reverse, per far vedere la variante. Badge "possedute/slot", filtri (lingua EN/JP, posseduto/mancante, rarità), ordinamento per prezzo/numero, ricerca.
- **Dettaglio carta**: immagine grande, rarità, set, lista varianti (Normal/Holo/Reverse/1st Ed.) con stepper +/- quantità, condizione, toggle "la voglio", prezzi per fonte (Cardmarket EUR, TCGplayer USD) con data e badge di confidenza, inserimento di un prezzo manuale e link "Apri su Cardmarket".
- **Mancanti**: lista degli slot mancanti con costo stimato totale.
- **Statistiche**: % di completamento (globale, EN, JP), valore della collezione in EUR e USD, numero di copie.
- **Impostazioni**: query tracciate (`Mawile` EN, `クチート` JP), ordine dei provider prezzi, campo chiave API (finto), export/import backup.
- **Aggiorna prezzi (simulato)**: barra di avanzamento e log della catena di fallback. Esempio: TCGdex ✓, un 429 → passa a PokemonPriceTracker, una carta JP senza dati → "prezzo manuale consigliato". Al termine si aggiorna la data dell'"ultimo prezzo".
- **Interruttore "Modalità aereo"**: mostra il banner offline, disabilita gli aggiornamenti e lascia tutto navigabile, per far vedere il comportamento offline.
- Lo stato (quantità, wanted, prezzi simulati) resta salvato in `localStorage` con try/catch, così la demo "ricorda" le modifiche.

**Dati**: una dozzina di carte Mawile / Mega Mawile EN + JP (es. Mawile, Mawile-GX, M Mawile-EX, Mega Mawile ex, クチート, メガクチートex). Set, numeri e prezzi sono **di esempio**, e la demo lo dice chiaramente con una nota "dati fittizi: nella vera app arrivano da TCGdex".

**Passi**: `Artifact quickstart` (intent other) → carico lo skill `artifact-design` → scrivo `docs/demo/prototype.html` → pubblico l'Artifact → commit + push su `ccr-da55776b-conrjq`.

**Verifica**: apro il file con Playwright/Chromium (preinstallato) a 390px di larghezza, faccio screenshot di ogni schermata, controllo che non ci siano errori in console e che il flusso aggiorna prezzi / modalità aereo / quantità funzioni. Poi raccolgo il feedback dell'utente e aggiorno il piano Android di conseguenza.

---

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
| `card` | id (`en:swsh11-091`), language, set_id, set_name, **set_code** (sigla stampata, es. `M2a`), number, **printed_number** (`001/123`), name, rarity, release_date, illustrator, image_url, image_low_path, image_high_path, catalog_updated_at |
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
