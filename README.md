# Card-collection

App Android (Kotlin + Jetpack Compose) che traccia la collezione di carte Pokémon e controlla i prezzi, e funziona anche offline.
Primo obiettivo: tutti i **Mawile** e **Mega Mawile**, EN e JP, con ogni variante (normal, holo, reverse holo, 1st edition…).

- Catalogo e immagini da TCGdex, salvati sul telefono
- Quantità possedute per variante e carte mancanti
- Price check manuale con fallback su più fonti (Cardmarket EUR, TCGplayer USD, …); l'ultimo risultato resta salvato in locale

Architettura e roadmap: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
