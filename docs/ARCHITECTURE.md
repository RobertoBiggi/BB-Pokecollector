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

### Seconda fonte: TCGCSV

[TCGCSV](https://tcgcsv.com) è una copia pubblica dei dati TCGplayer, aggiornata ogni giorno verso le 20:00 UTC, gratuita e senza chiave. Le sue linee guida chiedono uno User-Agent riconoscibile e richieste distanziate (250 ms in `TcgcsvClient`); non serve attribuzione. Copre le carte inglesi (categoria 3) e giapponesi (categoria 85).

- **Abbinamento** (`TcgcsvCatalog`): set per sigla (`abbreviation` di TCGplayer = sigla TCGdex per i set inglesi recenti, es. MEG, e id del set per i giapponesi, es. M3, SM10a). Per i set inglesi con sigla diversa (es. Lost Origin: LOR su TCGdex, SWSH11 su TCGplayer) si confronta il nome del set senza il prefisso. Poi il numero stampato (`extendedData.Number`, es. "117/080"), senza zeri iniziali. Per le carte inglesi deve coincidere anche il nome; tra più prodotti con lo stesso numero si sceglie quello senza parentesi (es. non "Poke Ball Pattern"). Se resta ambiguo, nessun abbinamento.
- **Prezzi** (`TcgcsvPriceProvider`): `subTypeName` Normal / Holofoil / Reverse Holofoil / 1st Edition…; prezzo di mercato, altrimenti medio. Se il prodotto ha una sola versione vale per la normal o la holo con affidabilità media.
- **Catena:** `PriceChain` si ferma quando ha sia EUR sia USD; altrimenti la fonte successiva completa il valore mancante (fonte salvata come "TCGdex + TCGplayer (TCGCSV)"). Il completamento non conta come fallback.
- **Immagini:** per le carte inglesi e giapponesi senza immagine su TCGdex, il sync del catalogo e il pulsante "Scarica immagine" usano l'immagine del prodotto TCGplayer (`tcgplayer-cdn.tcgplayer.com`, 400 px e 1000 px), ricodificata in WebP.
- Set, prodotti e prezzi di ogni set si scaricano una volta per aggiornamento.

### Altre fonti valutate

- **pokemontcg.io:** a ottobre 2026 risponde quasi sempre con errori 500/502 e non ha i set usciti dopo l'acquisizione da parte di Scrydex (il successore, a pagamento). Non è implementato.
- **PokemonPriceTracker:** non c'è modo di verificarne lo schema senza chiave. Non è implementato.

La catena `PriceChain` accetta altre fonti tramite l'interfaccia `PriceProvider`: aggiungerne una richiede solo l'implementazione e l'inserimento in `PriceRepository`. Quando non c'è un prezzo nuovo resta l'ultimo salvato, oppure il prezzo manuale.

## Modello dati (Room, versione 4)

| Tabella | Contenuto |
|---|---|
| `collection` | collezioni create dall'utente (alla prima installazione non ce n'è nessuna), con la lingua: `en`, `ja` oppure `mixed` |
| `tracked_query` | ricerche per nome e lingua **di una collezione** |
| `card` | catalogo condiviso: set, sigla, numero stampato, rarità, serie, data, illustratore, numero del Pokédex (`dexId`), URL e percorsi delle immagini (`queryId` è un campo storico della v1) |
| `card_variant` | una riga per variante (slot) |
| `collection_card` | appartenenza carta ↔ collezione: `queryId` (ricerca che l'ha aggiunta, null se a mano o importata) e `hidden` (tolta dall'utente, il sync non la riaggiunge) |
| `collection_entry` | **dati utente per collezione**: quantità, condizione, "la voglio", note. Nessun vincolo verso il catalogo |
| `price_snapshot` | storico prezzi condiviso: ultimi 10 per variante, con fonte, data e affidabilità |
| `price_run` | registro degli aggiornamenti prezzi, con la collezione (ultimi 10 per collezione) |

Uno slot è una variante desiderata (`wanted`) oppure posseduta: una variante con almeno una copia conta sempre come completata, anche senza "la voglio". Le mancanti sono le varianti desiderate con 0 copie.

**Lingua della collezione** (`CollectionLanguage`, core): EN o JP fissano la lingua di tutte le ricerche e delle aggiunte singole, e la schermata Gestisci non la chiede più. "Mista" fa scegliere la lingua a ogni ricerca (EN, JP o le altre lingue di TCGdex), per mescolare carte di lingue diverse. Si cambia in qualsiasi momento da Gestisci; le ricerche già salvate restano invariate.

Una carta è visibile in una collezione se non è nascosta e se è stata aggiunta a mano oppure da una ricerca attiva. Il sync del catalogo aggiorna solo `card`, `card_variant` e i collegamenti: non tocca mai i dati dell'utente.

**Migrazioni** (`Migrations`):
- **1 → 2**: crea la collezione "Mawile" con id 1, sposta lì ricerche e quantità e collega le carte tramite il vecchio `queryId`.
- **2 → 3**: aggiunge `card.dexId`, compilato al prossimo aggiornamento del catalogo o dall'import di un file che lo contiene.
- **3 → 4**: aggiunge `collection.language`. Le collezioni con ricerche e carte tutte in inglese (o tutte in giapponese) prendono quella lingua; le altre diventano miste.

`MigrationTest` esegue ogni passaggio e la catena 1 → 4 su SQLite (JVM) e confronta il risultato con gli schemi che Room esporta in `app/schemas/` durante la stessa build.

**Lettura reattiva:** `CollectionRepository` combina le tabelle osservate in un'unica fotografia (`DbState`), calcolata fuori dal thread dell'interfaccia, accorpata durante le modifiche ravvicinate e condivisa da tutte le schermate (`shareIn` nello scope dell'app). Ricerca, filtri e ordinamento della griglia sono calcolati nel ViewModel, anch'essi fuori dal thread UI.

**Ricerca per nome in ogni lingua:** `PokemonNames` (core) legge una tabella dei 1025 Pokémon in 7 lingue (da PokeAPI, BSD-3-Clause) inclusa nel modulo con percorso assoluto, sicuro con R8. I nomi sono normalizzati (NFKC, senza accenti né punteggiatura, hiragana → katakana); bastano 3 caratteri latini o 2 CJK. Viene precaricata all'avvio.

## File JSON di una collezione (schema 2)

```json
{
  "schema": 2, "app": "card-collection", "type": "collection", "exportedAt": "…",
  "info":    { "name": "Mawile", "language": "mixed", "queries": [{ "language": "en", "pattern": "Mawile", "enabled": true }] },
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
- **Import:** crea sempre una nuova collezione, con nome reso unico se serve. La lingua viene da `info.language`; nei file senza (versioni precedenti) si deduce da ricerche e carte. Le carte già presenti nel catalogo non vengono sovrascritte; le immagini incluse si usano solo per le carte che non ne hanno.
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
2. **Azioni sulla carta (dettaglio):**
   - *Aggiorna prezzo*: `PriceRepository.checkCard` interroga la stessa catena (rate limiter e circuit breaker compresi) per tutte le varianti della carta, senza registrare un run.
   - *Scarica immagine*: riscarica il dettaglio da TCGdex (l'immagine può essere arrivata dopo) e salva low/high; sostituisce l'eventuale immagine manuale.
   - *Scegli immagine*: selettore di sistema (Photo Picker), lettura fino a 20 MB, decodifica con sottocampionamento, lato lungo massimo 1200 px, ricodifica WebP (scarta metadati e contenuti non immagine). Il file ha nome `<id>-manual-<timestamp>.webp` dentro `filesDir/cards` e viene usato sia come low sia come high, quindi il sync non lo sovrascrive; finisce anche negli export con immagini.
3. **Aggiungi carta singola:** cerca su TCGdex per nome oppure, se il testo contiene un numero, per codice stampato (`CardCodeSearch`).
   - Se una parola coincide con l'id di un set (nei cataloghi asiatici è la sigla stampata, es. `M3`) cerca solo in quel set.
   - Altrimenti scarica le carte con quel numero (`/cards?localId=`, confronto esatto lato app) e restringe per totale ufficiale del set, nome e, per i cataloghi europei con pochi set candidati, sigla ufficiale (es. `MEG`).
   - Un filtro che svuoterebbe i risultati viene saltato, così le parole in più (es. la rarità `MUR`) non bloccano la ricerca.
4. **Aggiorna prezzi:** riguarda le varianti desiderate o possedute delle carte tracciate. Ogni carta viene scaricata una sola volta per run.
   - Rate limit di 300 ms tra le richieste.
   - Retry esponenziale su 429 e 5xx.
   - Circuit breaker: dopo 3 blocchi la fonte va in pausa per 6 ore; la pausa è salvata in DataStore.
   - Dopo 5 errori di rete consecutivi l'aggiornamento si interrompe.
   - I prezzi già salvati restano validi.
5. **Offline:** la UI legge solo da Room e le immagini dai file locali.

## Sicurezza

- **Rete:** solo HTTPS (`network_security_config` senza traffico in chiaro). Un interceptor di rete blocca ogni host fuori da `api.tcgdex.net`, `assets.tcgdex.net`, `tcgcsv.com` e `tcgplayer-cdn.tcgplayer.com`, anche dopo un redirect. Le immagini si scaricano con controllo di tipo e dimensione (massimo 3 MB); quelle di TCGplayer, costruite dall'id numerico del prodotto, vengono anche ricodificate in WebP.
- **Segreti:** nessuno nel repository. Le fonti usate non richiedono chiavi.
- **Validazione:** gli id remoti passano da una whitelist (niente `..` né `/`) prima di finire in URL o nomi file. I percorsi delle immagini sono verificati in forma canonica sotto `filesDir/cards`.
- **Risposte API:** limite di 2 MB (8 MB per i prodotti di un set TCGCSV); JSON tollerante ai campi sconosciuti ma con i tipi controllati.
- **Database:** solo query Room parametrizzate.
- **Input utente:** ricerche limitate a 2-40 caratteri ammessi, prezzi manuali a 0-99999,99, quantità a 0-999.
- **Import dei file JSON:** limite 40 MB in streaming, schema verificato, immagini controllate per formato e dimensione; le voci non valide vengono scartate e contate.

## Build e verifica

- `./gradlew -PcoreOnly :core:test`: test su fixture reali (mappatura, lingue, prezzi, catena con 429/403/circuit breaker e completamento da TCGCSV, abbinamento dei prodotti TCGplayer, validatori, file JSON, ricerca per codice e per nome, statistiche).
- `./gradlew :app:testDebugUnitTest`: assemblaggio e visibilità della libreria, filtri, migrazioni confrontate con gli schemi Room.
- Il lint di Android blocca la build se una stringa manca in una delle due lingue.
- **CI** (`.github/workflows/android.yml`), job `build` su ogni push e pull request:
  - test, lint, `assembleDebug` e `assembleRelease` (quest'ultimo non firmato, per verificare R8 e che i nomi dei Pokémon siano nell'APK);
  - pubblica l'APK di prova come artifact solo per i push del repo, mai per le pull request di terzi.
- **Firma:**
  - le build di prova usano il keystore di debug del repo (password pubbliche) con id `….debug`, quindi non possono sostituire l'app vera;
  - la release usa una chiave che esiste solo nei secret di GitHub.
- **Versione:** `versionCode` è il numero di commit di `main` (`git rev-list --count HEAD`), `versionName` è `appVersion` (in `gradle.properties`) seguito da quel numero.
- **Job `release`** (solo push su `main`):
  - costruisce la release firmata;
  - verifica l'impronta SHA-256 del certificato e che l'APK non sia debuggable;
  - pubblica la GitHub Release `v<versione>` con l'APK, che Obtainium usa per gli aggiornamenti.
- Su `main` i run non vengono interrotti a metà, e le release sono in coda tra loro.

## Possibili evoluzioni

- Aggiunta di fonti prezzi verificabili (es. una con chiave dell'utente per le carte JP).
- Check prezzi periodico, come opzione.
- Widget.
- Più condizioni per la stessa variante.
- Altre lingue dell'interfaccia: basta aggiungere `res/values-xx/strings.xml` e la voce in `locales_config.xml` e in Impostazioni.

