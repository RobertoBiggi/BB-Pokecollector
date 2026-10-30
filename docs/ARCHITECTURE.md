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
                                    ├─ CatalogRepository ──► CatalogStore ──► cards.sqlite (scaricato, sola lettura)
                                    │                       └─► ImageStore (filesDir/cards, immagini da assets.tcgdex.net)
                                    └─ PriceRepository  ──► listino prezzi Cardmarket (+ CardTrader col token)
WorkManager: CatalogSyncWorker, PriceCheckWorker (unici, espressi, avviati a mano)
```

| Modulo | Ruolo |
|---|---|
| `core` (Kotlin/JVM) | modelli, `VariantMapper`, `PriceChain` + `RateLimiter` + `CircuitBreaker`, listino Cardmarket e client CardTrader, `Validators`, `CollectionFiles` (JSON), `CollectionStats`. Si compila e si testa senza Android SDK e non contiene testi per l'utente. |
| `app` (Android) | Room, DataStore, `ImageStore`, repository, worker, UI. Dipendenze iniettate a mano in `AppContainer`. |

**Testi e lingue:** repository, worker e core restituiscono solo dati ed esiti tipizzati (enum e sealed class, es. `InputError`, `ImportError`, `AbortReason`). È la UI a trasformarli in testo con `stringResource`. Le risorse sono in `res/values` (inglese, lingua di base) e `res/values-it` (italiano). La lingua si sceglie in Impostazioni (Sistema / Italiano / English) con `AppCompatDelegate.setApplicationLocales`, che la ricorda anche su Android 12 e precedenti. Date e valute seguono la lingua corrente.

## Fonte dati: database delle carte

Le carte non si scaricano più da un'API: l'app legge un file SQLite (`cards.sqlite`) generato dal repository privato [Card-collector-db-gen](https://github.com/TheGaBr0/Card-collector-db-gen) (vedi più sotto), che contiene già carte, set, varianti, i Pokémon di ogni carta e gli abbinamenti a Cardmarket e CardTrader.

- **Installazione:** da Impostazioni → Database delle carte si scarica da un indirizzo HTTPS (predefinito: l'ultima release del repository; modificabile). `CatalogStore` scarica in un file temporaneo, controlla `meta.schemaVersion` (deve coincidere con `CatalogDb.SCHEMA_VERSION` e `SCHEMA_VERSION` del generatore) e lo sostituisce solo se valido. Il file sta in `filesDir/catalog/`. Senza database l'app mostra un avviso e le ricerche non trovano nulla.
- **Lettura:** `CatalogDb` apre il file in sola lettura con `android.database.sqlite`. Ricerca per nome = Pokémon (`PokemonNames.dexIdsMatching`, in ogni lingua) → `card_pokemon`; se il testo non è un Pokémon, per sottostringa del nome. Ricerca per codice (`CardCodeSearch.parse`): numero, totale e sigla/id del set. Collezione per set: tutte le carte del set.
- **Sync:** `CatalogRepository.sync` legge dal database le carte delle ricerche attive e le salva in Room (`card`, `card_variant`); non serve rete. Poi scarica le immagini mancanti.
- **Immagini:** solo le carte di origine TCGdex (`card.origin = 0`) hanno un indirizzo, ricavato dall'id: `https://assets.tcgdex.net/{lingua}/{serie}/{set}/{numero}`. Per le altre, con il token CardTrader, si usa l'immagine del blueprint indicato dal database (`cardTraderBlueprintId` + `cardTraderExpansionId`). Si può sempre sceglierne una a mano.
- **Prezzi:** il prodotto Cardmarket (`cmProduct`, o quello della variante per Poké Ball e stampe a parte) dà il prezzo in euro dal listino pubblico; il blueprint CardTrader dà il prezzo più basso col token. I prezzi in dollari di TCGplayer non ci sono più (venivano dall'API TCGdex).
- **Host raggiungibili:** `assets.tcgdex.net` (immagini), `downloads.s3.cardmarket.com`, `api.cardtrader.com`. Il download del database usa un client a parte, perché l'indirizzo lo sceglie l'utente (solo HTTPS).

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

- **pokemontcg.io:** a ottobre 2026 risponde quasi sempre con errori 500/502 e non ha i set usciti dopo l'acquisizione da parte di Scrydex (il successore, a pagamento). Non è implementato.
- **PokemonPriceTracker:** non c'è modo di verificarne lo schema senza chiave. Non è implementato.

La catena `PriceChain` accetta altre fonti tramite l'interfaccia `PriceProvider`: aggiungerne una richiede solo l'implementazione e l'inserimento in `PriceRepository`. Quando non c'è un prezzo nuovo resta l'ultimo salvato, oppure il prezzo manuale.

## Modello dati (Room, versione 16)

| Tabella | Contenuto |
|---|---|
| `collection` | collezioni create dall'utente (alla prima installazione non ce n'è nessuna), con la lingua: `en`, `ja` oppure `mixed` |
| `tracked_query` | ricerche **di una collezione**, per lingua: per nome (`kind` = `name`) oppure set intero (`set_base` / `set_master`, `pattern` = id del set) |
| `card` | catalogo condiviso: set, sigla, numero stampato, rarità, serie, data, illustratore, numero del Pokédex (`dexId`), URL e percorsi delle immagini (`queryId` è un campo storico della v1) |
| `card_variant` | una riga per variante (slot) |
| `collection_card` | appartenenza carta ↔ collezione: `queryId` (ricerca che l'ha aggiunta, null se a mano o importata) e `hidden` (tolta dall'utente, il sync non la riaggiunge) |
| `collection_entry` | **dati utente per collezione**: quantità, condizione, "la voglio", note. Nessun vincolo verso il catalogo |
| `price_snapshot` | storico prezzi condiviso: ultimi 10 per variante, con fonte, data e affidabilità |
| `price_run` | registro degli aggiornamenti prezzi, con la collezione (ultimi 10 per collezione) |
| `price_alert` | avvisi di prezzo per variante: "sale o scende" con la sua soglia, prezzo obiettivo in euro, stato dell'ultimo controllo (prezzi di riferimento, obiettivo già notificato) |

Uno slot è una variante desiderata (`wanted`) oppure posseduta: una variante con almeno una copia conta sempre come completata, anche senza "la voglio". Le mancanti sono le varianti desiderate con 0 copie.

**Lingua della collezione** (`CollectionLanguage`, core): EN o JP fissano la lingua di tutte le ricerche e delle aggiunte singole, e la schermata Gestisci non la chiede più. "Mista" fa scegliere la lingua a ogni ricerca (EN, JP o le altre lingue di TCGdex), per mescolare carte di lingue diverse. Si cambia in qualsiasi momento da Gestisci; le ricerche già salvate restano invariate.

Una carta è visibile in una collezione se non è nascosta e se è stata aggiunta a mano oppure da una ricerca attiva. Il sync del catalogo aggiorna solo `card`, `card_variant` e i collegamenti: non tocca mai i dati dell'utente.

**Schema:** il database è alla versione 16; le versioni precedenti alla 11 non sono più supportate. Le migrazioni sono 11 → 12 (`card_variant.cardmarketId`, il prodotto Cardmarket delle stampe a parte), 12 → 13 (`card_variant.print`, il timbro o il prodotto che distingue più varianti dello stesso tipo), 13 → 14 (`card.cardmarketProduct` e `card.cardTraderExpansionId`, letti dal database delle carte al primo sync) , 14 → 15 (tabella `price_alert`, vuota) e 15 → 16 (`price_alert.changePercent`, vuota per gli avvisi esistenti, che usano la soglia delle impostazioni). Gli id delle carte (`lingua:idTcgdex`) sono gli stessi del database, quindi quantità e note delle collezioni esistenti restano agganciate. Lo schema esportato da Room è in `app/schemas/`; una modifica allo schema richiede di alzare la versione e di scrivere la migrazione.

**Stampe a parte e 1st Edition:** oltre a normal, holo e reverse, una carta ha una variante per ogni stampa che Cardmarket vende come prodotto a parte e che TCGdex abbina a un prodotto diverso da quello della carta: Shadowless, Guance rosse, Pokétour '99 e Jumbo (`VariantType.isPrint`). Ogni timbro di TCGdex (tornei, prerelease, staff…) con un prodotto proprio è una variante `STAMPED` (il timbro sta in `print`, id `en:ex5-85|STAMPED|reed-weichler`); i timbri non contano finché l'utente non li segna come voluti. I prodotti Cardmarket della stessa carta (stessa metacard, stesso set) che TCGdex non elenca diventano varianti `CARDMARKET_PRINT`, solo se la carta è la sola con quel nome nel set. Il prezzo viene dal loro prodotto (listino Cardmarket, poi TCGdex); CardTrader non le distingue e le salta. Su Cardmarket la 1st Edition è un'etichetta degli annunci, non un prodotto: resta una sola variante `FIRST_EDITION` per carta, ricavata dal timbro `1st-edition` di TCGdex. L'interruttore "Conta le 1st Edition" delle impostazioni, se spento, rende "non volute" le 1st Edition su cui l'utente non ha fatto scelte: non contano tra le mancanti né nei totali, le copie possedute sì.

**Lettura reattiva:** `CollectionRepository` combina le tabelle osservate in un'unica fotografia (`DbState`), calcolata fuori dal thread dell'interfaccia, accorpata durante le modifiche ravvicinate e condivisa da tutte le schermate (`shareIn` nello scope dell'app). Ricerca, filtri e ordinamento della griglia sono calcolati nel ViewModel, anch'essi fuori dal thread UI.

**Ricerca per nome in ogni lingua:** `PokemonNames` (core) legge una tabella dei 1025 Pokémon in 7 lingue (da PokeAPI, BSD-3-Clause) inclusa nel modulo con percorso assoluto, sicuro con R8. I nomi sono normalizzati (NFKC, senza accenti né punteggiatura, hiragana → katakana); bastano 3 caratteri latini o 2 CJK. Viene precaricata all'avvio.

### Carte con simboli speciali e sigle dei set

L'abbinamento a Cardmarket e CardTrader, comprese le carte con simboli (Gold Star ☆/★, Delta Species δ, Prism Star ◇, che Cardmarket scrive a parole: "Rayquaza Gold Star") e i set che hanno sigle diverse sulla carta, su Cardmarket e su TCGdex (EX Deoxys: `DX` e `ex8`; Astral Radiance: `ASR`, `astr` e `swsh10`), lo fa il generatore del database delle carte (vedi sotto): l'app legge il prodotto Cardmarket e il blueprint CardTrader già abbinati.

**Ricerca per codice:** `CatalogDb.cardsByCode` accetta come set l'id TCGdex o la sigla stampata salvata nel database (`card_set.setCode`). La sigla Cardmarket, dove è diversa da quella stampata (es. `astr` per `ASR`), per ora non vale: la vecchia ricerca per sigla Cardmarket/stampata (`SetCodeAliases`) è stata tolta nel passaggio al database e va rifatta leggendo le sigle dal database. La tabella delle sigle stampate dei set inglesi raccolta per quella ricerca (`tcgdex-printed-codes.tsv`, probe TCGdex del 27/10/2026) è nella storia git, se serve al generatore.

## Database di tutte le carte (repository Card-collector-db-gen)

Il generatore (`gen_card_db.py`, Python con sola libreria standard) non sta più in questo repository: vive in [Card-collector-db-gen](https://github.com/TheGaBr0/Card-collector-db-gen) (privato). Per ogni Pokémon produce `cards.sqlite` (e il dump `d1-*.sql` per Cloudflare D1), senza immagini né prezzi, ripetendo le regole dell'app (varianti, Poké Ball/Master Ball, stampe a parte, abbinamento Cardmarket e CardTrader).

- **Pubblicazione:** il file si carica come asset della release con tag fisso `catalog` di questo repository (`gh release upload catalog cards.sqlite --clobber`); `--latest=false` alla creazione la tiene separata dalle release dell'APK. L'app lo scarica da `SettingsStore.DEFAULT_CATALOG_URL` (modificabile in Impostazioni).
- **Formato:** l'app legge `meta.schemaVersion` e rifiuta i file con una versione diversa da `CatalogDb.SCHEMA_VERSION`, che deve restare uguale a `SCHEMA_VERSION` del generatore. Tabelle e colonne (`card`, `card_set`, `card_variant`, `card_pokemon`, `pokemon`, `meta`) sono descritte nel generatore.
- **Dati condivisi:** `pokemon-names.tsv`, `cardmarket-expansions.tsv` e `cardmarket-ball-variants.tsv` esistono in due copie (qui in `core`, là in `data/`): se cambiano, vanno aggiornate in entrambi.

## Collezioni per set (set base e master set)

Oltre alle ricerche per nome, una collezione può contenere un **set intero**: si crea con "Nuova collezione → Per set" oppure da Gestione → "Aggiungi un set intero".

- **Elenco dei set** (`CatalogRepository.setChoices`): i set della lingua presenti nel database delle carte (`CatalogDb.sets`), con la sigla stampata.
- **Sync:** prende tutte le carte del set dal database delle carte (`CatalogDb.cardsOfSet`, Allenatori ed Energie comprese), con le stesse varianti delle ricerche per nome. Se il database non ha carte per quel set, il set viene contato come vuoto e segnalato nell'esito dell'aggiornamento.
- **Livelli** (`SetCollectionPolicy`, core):
  - *Set base*: uno slot per carta, la variante principale (normal, altrimenti holo, poi reverse…);
  - *Master set*: tutte le finiture (normale, holo, reverse, Poké Ball, Master Ball), senza le edizioni: 1st Edition e stampe a parte (Shadowless, Guance rosse, Pokétour '99, Jumbo, timbri, altre stampe Cardmarket) non contano, a meno che siano l'unica versione della carta.

  Le varianti escluse diventano voci `collection_entry` con `wanted = false`, inserite solo se la voce non esiste: le scelte e le quantità dell'utente non vengono mai toccate. Prezzi, mancanti e statistiche usano già "desiderata o posseduta", quindi non servono altre modifiche.
- **Fonti dei dati:** le checklist vengono da TCGdex (licenza MIT, modello varianti con finiture Poké Ball/Master Ball, timbri e sottotipi). I siti di master set valutati non si possono usare come fonte da includere: Pokellector e il database ufficiale giapponese lo vietano nei termini, Bulbapedia è CC BY-NC-SA (ShareAlike e non commerciale), Serebii e PriceCharting non hanno licenze di riuso. Per i set giapponesi senza carte su TCGdex (ADV, PCG10, LEGEND, molti XY e Spada e Scudo) la strada prevista è completare le checklist su TCGdex con pull request.
- **Copertura verificata con un probe (ottobre 2026):** inglese completo da Base Set a Black & White con immagini; giapponese vintage (PMCG, neo, VS, web, e-Card, PCG1-9) con l'elenco completo ma senza immagini; ADV, PCG10, LEGEND, molti XY e Spada e Scudo senza carte.

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
   5. download delle immagini mancanti. Prima si dimenticano i percorsi il cui file non c'è più (`ImagePaths.missing`, `ImageStore.isSaved`): il backup Android ripristina il database ma non le immagini, e dopo una reinstallazione la griglia mostrava il segnaposto per immagini che il sync credeva già scaricate. I percorsi nuovi si scrivono solo dove mancano (`fillImagePaths`).

   Tra due richieste passano almeno 250 ms; sul 429 l'app aspetta e riprova una volta.

   **Lavori in parallelo:** sync, aggiornamento prezzi e collegamento a CardTrader (che parte da solo all'apertura) possono girare insieme. Chi completa un dato di una carta aggiorna solo quella colonna (`fillDexId`, `fillIllustrator`, `fillDetails`, `clearCardTraderImage` con le condizioni lette dal database), mai la riga intera da una copia letta prima: riscriverla cancellava i percorsi delle immagini salvati nel frattempo dal sync, lasciando il file sul disco.
2. **Azioni sulla carta (dettaglio):**
   - *Aggiorna prezzo*: `PriceRepository.checkCard` interroga la stessa catena (rate limiter e circuit breaker compresi) per tutte le varianti della carta, senza registrare un run.
   - *Scarica immagine*: riscarica il dettaglio da TCGdex (l'immagine può essere arrivata dopo) e salva low/high; sostituisce l'eventuale immagine manuale.
   - *Scegli immagine*: selettore di sistema (Photo Picker), lettura fino a 20 MB, decodifica con sottocampionamento, lato lungo massimo 1200 px, ricodifica WebP (scarta metadati e contenuti non immagine). Il file ha nome `<id>-manual-<timestamp>.webp` dentro `filesDir/cards` e viene usato sia come low sia come high, quindi il sync non lo sovrascrive; finisce anche negli export con immagini.
3. **Aggiungi carta singola:** cerca nel database delle carte per nome (anche in un'altra lingua) oppure, se il testo contiene un numero, per codice stampato (`CardCodeSearch.parse`, poi `CatalogDb.cardsByCode`).
   - Parte dalle carte con quel numero e, se c'è, restringe per totale stampato (`117/080`).
   - Una parola che è l'id o la sigla stampata di un set (`M3`, `MEG`) tiene solo le carte di quel set; le altre parole restringono per nome.
   - Un filtro che svuoterebbe i risultati viene saltato, così le parole in più (es. la rarità `MUR`) non bloccano la ricerca.
4. **Aggiorna prezzi:** riguarda le varianti desiderate o possedute delle carte tracciate. Ogni carta viene scaricata una sola volta per run.
   - Rate limit di 300 ms tra le richieste.
   - Retry esponenziale su 429 e 5xx.
   - Circuit breaker: dopo 3 blocchi la fonte va in pausa per 6 ore; la pausa è salvata in DataStore.
   - Dopo 5 errori di rete consecutivi l'aggiornamento si interrompe.
   - I prezzi già salvati restano validi.
5. **Avvisi di prezzo** (spenti di default, Impostazioni → Avvisi di prezzo):
   - Dal dettaglio della carta, pannello "Avviso prezzo" sotto i grafici di ogni variante: "avvisami se sale o scende" con la soglia di quella variante (1-50%, proposta quella delle impostazioni) e/o "avvisami quando arriva a X €". Salvando, i prezzi di riferimento partono da quelli attuali.
   - `PriceAlertWorker` gira ogni giorno alle 4:30 locali (`PriceAlertRules.DAILY_RUN_TIME`): il listino Cardmarket viene rigenerato verso le 3 (`createdAt` reale "…T02:48+0200"). È un lavoro unico (`price-alerts`) che alla fine accoda il controllo del giorno dopo con l'orario ricalcolato, così resta alle 4:30 anche con l'ora legale; all'apertura dell'app viene riprogrammato se manca. Aspetta la rete; se la rete o il listino non rispondono riprova fino a 3 volte, poi passa al giorno dopo.
   - Aggiorna solo le varianti con un avviso (`PriceRepository.runAlertCheck`, stessa catena dell'aggiornamento manuale: listino Cardmarket e, con il token, CardTrader) e confronta i prezzi appena scaricati con lo stato salvato (`PriceAlertRules.evaluate`, core, testato in `PriceAlertsTest`):
     - **sale o scende:** per ogni fonte (Cardmarket €, CardTrader €, TCGplayer $) quando il prezzo si sposta di almeno la soglia dell'avviso (scelta nel pannello; gli avvisi senza una soglia propria usano quella delle impostazioni, 5% di default) rispetto al prezzo dell'ultimo avviso, così le piccole variazioni di ogni giorno si sommano;
     - **prezzo obiettivo:** quando il prezzo più basso in euro (Cardmarket o CardTrader) scende entro il 5% sopra l'obiettivo o sotto (`TARGET_MARGIN`); una volta sola, e si riarma quando il prezzo risale.
   - Le notifiche vanno sul canale "Avvisi prezzo" (separato da quello silenzioso degli aggiornamenti) e aprono il dettaglio della carta. I prezzi inseriti a mano non fanno partire avvisi. Gli avvisi non sono inclusi nei file JSON delle collezioni.
6. **Offline:** la UI legge solo da Room e le immagini dai file locali.

## Sicurezza

- **Rete:** solo HTTPS (`network_security_config` senza traffico in chiaro). Un interceptor di rete blocca ogni host fuori da `assets.tcgdex.net`, `downloads.s3.cardmarket.com` e `api.cardtrader.com`, anche dopo un redirect (il download del database delle carte usa un client a parte, solo HTTPS). Le immagini si scaricano con controllo di tipo e dimensione (massimo 3 MB).
- **Segreti:** nessuno nel repository. Le fonti usate non richiedono chiavi.
- **Validazione:** gli id remoti passano da una whitelist (niente `..` né `/`) prima di finire in URL o nomi file. I percorsi delle immagini sono verificati in forma canonica sotto `filesDir/cards`.
- **Risposte API:** limite di 2 MB; JSON tollerante ai campi sconosciuti ma con i tipi controllati.
- **Database:** solo query Room parametrizzate.
- **Input utente:** ricerche limitate a 2-40 caratteri ammessi, prezzi manuali a 0-99999,99, quantità a 0-999.
- **Import dei file JSON:** limite 40 MB in streaming, schema verificato, immagini controllate per formato e dimensione; le voci non valide vengono scartate e contate.

## Build e verifica

- `./gradlew -PcoreOnly :core:test`: test su fixture reali (mappatura, lingue, prezzi, catena con 429/403/circuit breaker, validatori, file JSON, ricerca per codice e per nome, statistiche).
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

