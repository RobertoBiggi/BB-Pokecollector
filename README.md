# Card Collection

App Android (Kotlin + Jetpack Compose) per tracciare la propria collezione di carte Pokémon e controllarne i prezzi. Funziona anche offline e l'interfaccia è in **italiano e inglese**.
Ogni collezione traccia le carte che scegli, con ogni variante (normal, holo, reverse holo, 1st edition…): per esempio tutti i Mawile e Mega Mawile.

## Cosa fa

- **Più collezioni:** all'avvio vedi l'elenco delle collezioni, ognuna con copertina, completamento e valore; toccandone una si apre con le schede Carte, Mancanti, Statistiche e Gestione. Ogni collezione ha ricerche, carte aggiunte a mano e quantità proprie; catalogo, immagini e prezzi sono condivisi.
- **Import/export JSON:** esporti una collezione in un unico file, senza immagini oppure con immagini piccole o grandi incorporate, e la importi su un altro telefono anche offline. L'import crea sempre una nuova collezione. Sono accettati anche i backup della prima versione.
- **Catalogo da TCGdex:** per ogni ricerca della collezione (es. `Mawile` in inglese, `クチート` in giapponese) scarica tutte le carte Pokémon che contengono quel nome. Le carte digitali di TCG Pocket e gli Allenatori vengono esclusi. Le immagini sono salvate sul telefono.
- **Ricerca in ogni lingua:** nella griglia trovi una carta anche scrivendo il nome del Pokémon in un'altra lingua ("Omastar" trova オムスター), senza badare ad accenti, maiuscole e punteggiatura; l'hiragana vale come il katakana.
- **Lingue delle carte:** inglese, italiano, francese, tedesco, spagnolo, portoghese e giapponese, con catalogo, immagini e prezzi completi. Cinese tradizionale, thailandese e indonesiano hanno dati limitati su TCGdex, e l'app lo segnala.
- **Lingua della collezione:** EN o JP per una collezione in una sola lingua (le ricerche non la chiedono più), oppure Mista per scegliere la lingua a ogni ricerca e mescolare carte di lingue diverse.
- **Lingua dell'app:** italiano o inglese, da Impostazioni, oppure quella di sistema.
- **Slot per variante:** ogni variante è uno slot della collezione. Per ognuna segni quante copie hai e in che condizione; con "la voglio" decidi se conta tra le mancanti. Nella griglia il badge `1/2` indica le varianti che hai su quelle che vuoi o hai, e `×3` le copie totali.
- **Azioni sulla carta:** dal dettaglio aggiorni il prezzo di quella sola carta, riscarichi l'immagine (da TCGdex o, se lì manca, da TCGplayer) oppure ne scegli una dalla galleria. L'immagine scelta viene ridimensionata e ricodificata in WebP, senza metadati.
- **Codice stampato:** ogni carta mostra il codice in basso a sinistra, come sulla carta vera (es. `POR 032/088`, `M1L 046/063`). Puoi cercare e ordinare anche per codice.
- **Aggiungi una carta per codice:** in Gestione → "Aggiungi una carta singola" puoi cercare su TCGdex per nome oppure per codice stampato: `M3 117/080`, `117/080` (anche con parole in più come la rarità, es. `117/080 MUR`), `MEG 046/132` o l'id TCGdex `M3-117`. Il set si può scrivere sia con la sigla di Cardmarket sia con l'id di TCGdex: per EX Deoxys vanno bene `DX 107` ed `ex8 107` (vale anche nella ricerca della collezione e nella scelta del set).
- **Prezzi su richiesta:** l'aggiornamento parte solo quando lo chiedi. Prende Cardmarket in EUR (disponibile anche per le carte JP) e TCGplayer in USD tramite TCGdex, senza mai contattare cardmarket.com. L'ultimo prezzo resta salvato in locale; puoi anche inserirne uno a mano.
- **Prezzi CardTrader (facoltativi):** in Impostazioni incolli il tuo token API di CardTrader (resta solo sul telefono). Da quel momento ogni aggiornamento aggiunge a ogni variante il prezzo più basso in EUR, mostrato a parte e senza sostituire Cardmarket. CardTrader non ha una pagina per rarità: normal e reverse della stessa carta sono lo stesso prodotto, quindi gli annunci vengono filtrati per variante (`pokemon_reverse`, prima edizione), per lingua e per condizione: conta la condizione che hai impostato sulla variante, o una migliore (NM → Near Mint, LP → Slightly Played, MP → Moderately Played, HP → Played, DMG → qualsiasi); le carte gradate sono escluse e si prende il più basso.
- **Se la fonte blocca:** in caso di risposta 429 l'app riprova con attese crescenti. Dopo 3 blocchi di fila mette la fonte in pausa per 6 ore.
- **Collezioni per set:** scegli un set (inglese o giapponese, anche vintage) e collezionalo come *set base* (una carta per slot) o *master set* (tutte le varianti: normale, holo, reverse, Poké Ball e Master Ball, senza distinguere la 1st Edition).
- **Mancanti e statistiche:** costo stimato delle carte che mancano, percentuale di completamento, valore della collezione (EUR e USD separati), avanzamento per lingua e per serie.

## Installare e aggiornare l'app

L'app non è sul Play Store. A ogni push su `main` GitHub Actions pubblica una **Release** con l'APK firmato con la chiave di release (versione `0.6.N`, dove N cresce a ogni commit).

- **Con aggiornamenti automatici:** installa [Obtainium](https://github.com/ImranR98/Obtainium) e aggiungi l'indirizzo di questo repository: ti avvisa delle nuove release e le installa. Finché il repository è privato serve un token GitHub in sola lettura, da inserire nelle impostazioni della sorgente in Obtainium.
- **A mano:** scarica `card-collection-<versione>.apk` dall'ultima Release e aprilo sul telefono, consentendo l'installazione da origini sconosciute.

Al primo avvio crea una collezione con una ricerca (es. "Mawile"), premi **Scarica catalogo** e poi **Prezzi**.

Le build di prova di ogni push (artifact `card-collection-apk` in Actions, file `card-collection-<versione>.apk`) hanno lo stesso id e lo stesso nome dell'app vera e si aggiornano a vicenda, ma sono firmate con la chiave di debug: per passare da una build di prova alla release (o viceversa) serve disinstallare l'app, quindi esporta prima le collezioni.

**Passaggio dalle versioni fino alla 0.5.0** (firmate con la chiave di debug): Android non accetta l'aggiornamento a una firma diversa. Esporta ogni collezione in JSON con le immagini, disinstalla l'app, installa la release e reimporta. Serve solo una volta.

Requisiti: Android 8.0 o successivo.

## Struttura

| Modulo | Contenuto |
|---|---|
| `core/` | Kotlin/JVM puro, senza testi per l'utente: client TCGdex, normalizzazione del catalogo, mappatura prezzi per variante, catena di fonti con rate limiter e circuit breaker, validatori, formato JSON delle collezioni, statistiche. Testato con fixture reali. |
| `app/` | Android: Room, WorkManager, archivio immagini, UI Compose, import/export tramite Storage Access Framework. I testi sono in `res/values` (inglese) e `res/values-it` (italiano). |
| `docs/` | Piano di architettura (`ARCHITECTURE.md`). |

## Sviluppo

```bash
./gradlew -PcoreOnly :core:test           # test della logica (serve solo la JDK)
./gradlew :app:testDebugUnitTest          # test dell'app, migrazioni comprese (serve l'Android SDK)
./gradlew :app:assembleDebug              # APK di prova
```

**Release:** la CI firma la build release con i secret `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` e `RELEASE_KEY_PASSWORD` e controlla l'impronta del certificato prima di pubblicare. Senza secret la build release resta non firmata e la Release non viene creata. La chiave non va mai messa nel repository: se va persa, l'app installata non si può più aggiornare.

Il workflow manuale `.github/workflows/tcgdex-probe.yml` stampa una risposta reale di TCGdex (es. `ja/cards/M3-117`), utile per verificare l'API da un ambiente che non la raggiunge.

## Licenza

Il codice è distribuito con licenza [MIT](LICENSE). I dati di terze parti inclusi o scaricati (TCGdex, PokeAPI, immagini delle carte) restano soggetti alle rispettive licenze e marchi, indicati qui sotto.

## Crediti

Catalogo, immagini e prezzi (Cardmarket e TCGplayer) arrivano da [TCGdex](https://tcgdex.dev). I nomi dei Pokémon in più lingue vengono da [PokeAPI](https://pokeapi.co) (licenza BSD-3-Clause). Pokémon e i nomi dei Pokémon sono marchi di Nintendo, Creatures e GAME FREAK; questa app non è affiliata né approvata da The Pokémon Company.
