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

- **pokemontcg.io:** a ottobre 2026 risponde quasi sempre con errori 500/502 e non ha i set usciti dopo l'acquisizione da parte di Scrydex (il successore, a pagamento). Non è implementato.
- **PokemonPriceTracker:** non c'è modo di verificarne lo schema senza chiave. Non è implementato.

La catena `PriceChain` accetta altre fonti tramite l'interfaccia `PriceProvider`: aggiungerne una richiede solo l'implementazione e l'inserimento in `PriceRepository`. Quando non c'è un prezzo nuovo resta l'ultimo salvato, oppure il prezzo manuale.

## Modello dati (Room, versione 11)

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

Uno slot è una variante desiderata (`wanted`) oppure posseduta: una variante con almeno una copia conta sempre come completata, anche senza "la voglio". Le mancanti sono le varianti desiderate con 0 copie.

**Lingua della collezione** (`CollectionLanguage`, core): EN o JP fissano la lingua di tutte le ricerche e delle aggiunte singole, e la schermata Gestisci non la chiede più. "Mista" fa scegliere la lingua a ogni ricerca (EN, JP o le altre lingue di TCGdex), per mescolare carte di lingue diverse. Si cambia in qualsiasi momento da Gestisci; le ricerche già salvate restano invariate.

Una carta è visibile in una collezione se non è nascosta e se è stata aggiunta a mano oppure da una ricerca attiva. Il sync del catalogo aggiorna solo `card`, `card_variant` e i collegamenti: non tocca mai i dati dell'utente.

**Schema:** il database è alla versione 13; le versioni precedenti alla 11 non sono più supportate. Le migrazioni sono 11 → 12 (`card_variant.cardmarketId`, il prodotto Cardmarket delle stampe a parte) e 12 → 13 (`card_variant.print`, il timbro o il prodotto che distingue più varianti dello stesso tipo). Lo schema esportato da Room è in `app/schemas/`; una modifica allo schema richiede di alzare la versione e di scrivere la migrazione.

**Stampe a parte e 1st Edition:** oltre a normal, holo e reverse, una carta ha una variante per ogni stampa che Cardmarket vende come prodotto a parte e che TCGdex abbina a un prodotto diverso da quello della carta: Shadowless, Guance rosse, Pokétour '99 e Jumbo (`VariantType.isPrint`). Ogni timbro di TCGdex (tornei, prerelease, staff…) con un prodotto proprio è una variante `STAMPED` (il timbro sta in `print`, id `en:ex5-85|STAMPED|reed-weichler`); i timbri non contano finché l'utente non li segna come voluti. I prodotti Cardmarket della stessa carta (stessa metacard, stesso set) che TCGdex non elenca diventano varianti `CARDMARKET_PRINT`, solo se la carta è la sola con quel nome nel set. Il prezzo viene dal loro prodotto (listino Cardmarket, poi TCGdex); CardTrader non le distingue e le salta. Su Cardmarket la 1st Edition è un'etichetta degli annunci, non un prodotto: resta una sola variante `FIRST_EDITION` per carta, ricavata dal timbro `1st-edition` di TCGdex. L'interruttore "Conta le 1st Edition" delle impostazioni, se spento, rende "non volute" le 1st Edition su cui l'utente non ha fatto scelte: non contano tra le mancanti né nei totali, le copie possedute sì.

**Lettura reattiva:** `CollectionRepository` combina le tabelle osservate in un'unica fotografia (`DbState`), calcolata fuori dal thread dell'interfaccia, accorpata durante le modifiche ravvicinate e condivisa da tutte le schermate (`shareIn` nello scope dell'app). Ricerca, filtri e ordinamento della griglia sono calcolati nel ViewModel, anch'essi fuori dal thread UI.

**Ricerca per nome in ogni lingua:** `PokemonNames` (core) legge una tabella dei 1025 Pokémon in 7 lingue (da PokeAPI, BSD-3-Clause) inclusa nel modulo con percorso assoluto, sicuro con R8. I nomi sono normalizzati (NFKC, senza accenti né punteggiatura, hiragana → katakana); bastano 3 caratteri latini o 2 CJK. Viene precaricata all'avvio.

### Carte con simboli speciali (☆, ★, δ, ◇)

TCGdex scrive le Gold Star con ☆ (U+2606) nei set EX e con ★ (U+2605) in POP 5, le Delta Species con δ (in giapponese "デルタ種") e le Prism Star con ◇. Cardmarket usa le parole: "Rayquaza Gold Star", "Omastar δ Delta Species", "Rainbow Energy Delta", "Lunala ◇". `CardmarketCatalog.nameKey` trasforma simboli e parole in marcatori in fondo alla chiave (`gyarados~delta~goldstar`), così la carta speciale e quella normale non coincidono mai, e i nomi del Pokémon usati per l'abbinamento prendono i marcatori della carta. Il nome completo esatto ("Rayquaza ex") ha la precedenza sui nomi del Pokémon.

### Sigle dei set

Lo stesso set ha sigle diverse sulla carta, su Cardmarket/CardTrader e su TCGdex (EX Deoxys: `DX` sulla carta e su Cardmarket, `ex8` su TCGdex; Astral Radiance: `ASR` sulla carta, `astr` su CardTrader, `swsh10` su TCGdex). `SetCodeAliases` (core) accetta tutte e tre:
- **Sigla Cardmarket/CardTrader:** dall'elenco incluso nell'app (`cardmarket-expansions.tsv`), abbinata al set TCGdex con le stesse regole dei prezzi (`CardTraderMatcher.expansionFor`) e tenuta in cache.
- **Sigla stampata dei set europei:** da `tcgdex-printed-codes.tsv` (id TCGdex → `abbreviation.official`), perché l'elenco dei set di TCGdex non la riporta. Tabella generata il 27/10/2026 dal dettaglio di ogni set inglese (probe `tcgdex-probe.yml`: `jq '[.id, .abbreviation.official]'` su `/v2/en/sets/{id}`); i set nuovi senza riga si cercano comunque per id, sigla Cardmarket o, con pochi set candidati, per sigla ufficiale scaricata al momento (`CardCodeSearch.narrowByAbbreviation`). Le Trainer Gallery ("ASR:TG") rispondono alla sigla del set principale. Nei cataloghi asiatici la sigla stampata è già l'id.
- Una sigla può indicare più set: `TR` è Team Rocket Returns sulla carta e Team Rocket su Cardmarket, e si cerca in entrambi. Le parole dei nomi delle carte (`ex`, `GX`, `V`…) non valgono mai come sigla.

Su 205 set inglesi (TCG Pocket escluso) una sessantina hanno la sigla stampata diversa da quella Cardmarket/CardTrader (es. `ASR`/`astr`, `LOR`/`lorg`, `CIN`/`cinv`, `HS`/`hgs`, `SF`/`sft`, `COL`/`clo`, `EP`/`epo`, `P1`…`P9`/`pop1`…`pop9`, i Trainer Kit `TK…`); quattro non ne hanno nessuna (Poké Card Creator Pack, Jumbo, Sample, W Promotional) e si cercano solo per id. Le usano la ricerca per codice (`CardCodeSearch`, `CardmarketCardSearch.findByCode`, che accetta anche la sigla dei set solo Cardmarket), la scelta del set per le collezioni per set (le sigle compaiono accanto all'id) e il filtro della griglia (`dx 107` trova la carta 107/107 di ex8).

I set EX inglesi (`ex1`…`ex16`) si abbinano a Cardmarket prima per "EX " + nome: senza, "Holon Phantoms" prendeva il set giapponese omonimo e "Deoxys" restava ambiguo tra diversi mazzi. Lo stesso confronto non ha trovato altri set abbinati male per i prezzi: "Arceus" (pl4) si abbina grazie alla sigla stampata `AR`, che la richiesta dei prezzi passa già; Trainer Gallery, Shiny Vault, Celebrations Classic Collection, Trainer Kit EX Latias/Latios e McDonald's 2021-2024 finiscono nel set Cardmarket indicato dalle eccezioni di `CardTraderMatcher`, come previsto.

TCGdex a volte collega la carta speciale al prodotto Cardmarket della versione normale (es. ex8-107 "Rayquaza ☆" → prodotto 276425, il Rayquaza normale). Se i marcatori del prodotto indicato non coincidono con quelli della carta, `PriceRepository` scarta quell'id per l'abbinamento CardTrader e il prezzo Cardmarket che ne deriva, e tiene solo quello TCGplayer. Test di regressione: `SpecialCardNamesTest`.

## Collezioni per set (set base e master set)

Oltre alle ricerche per nome, una collezione può contenere un **set intero**: si crea con "Nuova collezione → Per set" oppure da Gestione → "Aggiungi un set intero".

- **Elenco dei set** (`CatalogRepository.setChoices`): i set di TCGdex della lingua (EN o JP, senza quelli di TCG Pocket) più, per il giapponese, i set che TCGdex non elenca presi da `cardmarket-extra-sets.tsv` (DP, Platino, BW, promo, mazzi).
- **Sync** (`syncSet`): scarica il dettaglio del set da TCGdex e ogni sua carta (Allenatori ed Energie comprese) con lo stesso percorso delle ricerche per nome (varianti, Poké Ball/Master Ball, Cardmarket). Se TCGdex non ha carte per quel set usa l'elenco dei set mancanti (`CardmarketCardSearch.extraSetAll`). Se nessuna fonte ha le carte, il set viene contato come vuoto e segnalato nell'esito dell'aggiornamento.
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
3. **Aggiungi carta singola:** cerca su TCGdex per nome oppure, se il testo contiene un numero, per codice stampato (`CardCodeSearch`).
   - Se una parola coincide con l'id di un set (nei cataloghi asiatici è la sigla stampata, es. `M3`) o con la sua sigla Cardmarket (`SetCodeAliases`, es. `dx` per EX Deoxys, che su TCGdex è `ex8`) cerca solo in quel set; le altre parole restringono per nome (`dx Rayquaza 102`). Le parole dei nomi delle carte (`ex`, `GX`, `V`…) non valgono come sigla: "Rayquaza ex 102" non cerca in Expedition (`EX` su Cardmarket).
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

- **Rete:** solo HTTPS (`network_security_config` senza traffico in chiaro). Un interceptor di rete blocca ogni host fuori da `api.tcgdex.net`, `assets.tcgdex.net`, `downloads.s3.cardmarket.com` e `api.cardtrader.com`, anche dopo un redirect. Le immagini si scaricano con controllo di tipo e dimensione (massimo 3 MB).
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

