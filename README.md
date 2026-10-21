# Card Collection

App Android (Kotlin + Jetpack Compose) per tracciare la propria collezione di carte Pokémon e controllarne i prezzi. Funziona anche offline e l'interfaccia è in **italiano e inglese**.
Il primo obiettivo sono tutti i **Mawile** e **Mega Mawile**, con ogni variante (normal, holo, reverse holo, 1st edition…).

## Cosa fa

- **Più collezioni:** all'avvio vedi l'elenco delle collezioni, ognuna con copertina, completamento e valore; toccandone una si apre con le schede Carte, Mancanti, Statistiche e Gestione. Ogni collezione ha ricerche, carte aggiunte a mano e quantità proprie; catalogo, immagini e prezzi sono condivisi.
- **Import/export JSON:** esporti una collezione in un unico file, senza immagini oppure con immagini piccole o grandi incorporate, e la importi su un altro telefono anche offline. L'import crea sempre una nuova collezione. Sono accettati anche i backup della prima versione.
- **Catalogo da TCGdex:** scarica tutte le carte Pokémon che contengono il nome cercato (di default `Mawile` per l'inglese e `クチート` per il giapponese). Le carte digitali di TCG Pocket e gli Allenatori vengono esclusi. Le immagini sono salvate sul telefono.
- **Lingue delle carte:** inglese, italiano, francese, tedesco, spagnolo, portoghese e giapponese, con catalogo, immagini e prezzi completi. Cinese tradizionale, thailandese e indonesiano hanno dati limitati su TCGdex, e l'app lo segnala.
- **Lingua dell'app:** italiano o inglese, da Impostazioni, oppure quella di sistema.
- **Slot per variante:** ogni variante è uno slot della collezione. Per ognuna segni quante copie hai e in che condizione; con "la voglio" decidi se conta tra le mancanti. Nella griglia il badge `1/2` indica le varianti che hai su quelle che vuoi o hai, e `×3` le copie totali.
- **Azioni sulla carta:** dal dettaglio aggiorni il prezzo di quella sola carta, riscarichi l'immagine da TCGdex oppure ne scegli una dalla galleria (utile per le carte giapponesi senza immagine). L'immagine scelta viene ridimensionata e ricodificata in WebP, senza metadati.
- **Codice stampato:** ogni carta mostra il codice in basso a sinistra, come sulla carta vera (es. `POR 032/088`, `M1L 046/063`). Puoi cercare e ordinare anche per codice.
- **Aggiungi una carta per codice:** in Gestione → "Aggiungi una carta singola" puoi cercare su TCGdex per nome oppure per codice stampato: `M3 117/080`, `117/080` (anche con parole in più come la rarità, es. `117/080 MUR`), `MEG 046/132` o l'id TCGdex `M3-117`.
- **Prezzi su richiesta:** l'aggiornamento parte solo quando lo chiedi. Prende Cardmarket in EUR (disponibile anche per le carte JP) e TCGplayer in USD tramite TCGdex, senza mai contattare cardmarket.com. L'ultimo prezzo resta salvato in locale; puoi anche inserirne uno a mano.
- **Prezzi CardTrader (facoltativi):** in Impostazioni incolli il tuo token API di CardTrader (resta solo sul telefono). Da quel momento ogni aggiornamento aggiunge a ogni variante il prezzo più basso in EUR, mostrato a parte e senza sostituire Cardmarket. CardTrader non ha una pagina per rarità: normal e reverse della stessa carta sono lo stesso prodotto, quindi gli annunci vengono filtrati per variante (`pokemon_reverse`, prima edizione), per lingua e per condizione (in Impostazioni scegli la peggiore accettata, di default Near Mint, e dal dettaglio di ogni carta puoi cambiarla solo per quella; escluse le carte gradate) e si prende il più basso.
- **Se la fonte blocca:** in caso di risposta 429 l'app riprova con attese crescenti. Dopo 3 blocchi di fila mette la fonte in pausa per 6 ore.
- **Mancanti e statistiche:** costo stimato delle carte che mancano, percentuale di completamento, valore della collezione (EUR e USD separati), avanzamento per lingua e per serie.

## Installare l'APK

L'app non è sul Play Store: l'APK di debug viene compilato da GitHub Actions a ogni push.

1. Apri la scheda **Actions** del repository e scegli l'ultima esecuzione verde di **Android CI**.
2. In fondo alla pagina, nella sezione **Artifacts**, scarica `card-collection-debug-apk` (è uno zip).
3. Estrai `app-debug.apk` e copialo sul telefono.
4. Aprilo e consenti l'installazione da origini sconosciute quando Android lo chiede.
   Gli APK sono firmati sempre con la stessa chiave di debug (`app/signing/debug.keystore`), quindi ogni nuova versione si installa sopra la precedente senza perdere i dati. Fino alla 0.3.0 la chiave cambiava a ogni build: per passare da quelle versioni alla 0.4.0 esporta le collezioni in JSON, disinstalla l'app, installa la nuova e reimporta.
5. Al primo avvio apri la collezione **Mawile** (già pronta con le ricerche Mawile EN e クチート JP), premi **Scarica catalogo** e poi **Prezzi**.

Requisiti: Android 8.0 o successivo.

## Struttura

| Modulo | Contenuto |
|---|---|
| `core/` | Kotlin/JVM puro, senza testi per l'utente: client TCGdex, normalizzazione del catalogo, mappatura prezzi per variante, catena di fonti con rate limiter e circuit breaker, validatori, formato JSON delle collezioni, statistiche. Testato con fixture reali. |
| `app/` | Android: Room, WorkManager, archivio immagini, UI Compose, import/export tramite Storage Access Framework. I testi sono in `res/values` (inglese) e `res/values-it` (italiano). |
| `docs/` | Piano di architettura (`ARCHITECTURE.md`) e prototipo cliccabile (`demo/prototype.html`). |

## Sviluppo

```bash
./gradlew :core:test                      # test della logica (serve solo la JDK)
./gradlew :app:assembleDebug              # APK (serve l'Android SDK)
./gradlew -PcoreOnly :core:test           # come sopra, senza configurare il modulo Android
```

Il workflow `.github/workflows/tcgdex-probe.yml` stampa nei log risposte reali di TCGdex. Serve per verificare lo schema dell'API quando lo si sviluppa da un ambiente che non raggiunge TCGdex.
