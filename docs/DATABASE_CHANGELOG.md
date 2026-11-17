# Card database changelog

What changed in the card database (`cards.sqlite`, the `catalog` release) and how it is built, newest first. The generator lives in the separate `Card-collector-db-gen` repository; every entry comes from its history. Add an entry here whenever a new database is published.

## 2026-11-17

- **Pikachu World Collection 2000 and 2010**: the 19 cards now have their Cardmarket product, so they show Cardmarket prices. The 2010 set is one card per country (USA, DE, ES, FR, IT, PL, PT, KR, two JPN), each with its own CardTrader blueprint.
- Published as a patch of the 2026-11-16 database (only `cardmarketId`, `cmProduct` and `cmHow` of those cards changed).
- **Special Deck Set MEGA** (`ja:mgds`, official code MG, released 2026-11-13, 38 cards: the Mega Feraligatr ex, Mega Dragonite ex and Mega Gengar ex decks): new set from Cardmarket expansion 6963, with the card numbers (001/038–038/038) and each card's Cardmarket product. It is not on CardTrader yet, so it has no blueprints or images.

## 2026-11-16

- **New sets and cards**: German Radiant Collection, Spanish HGSS and Unleashed, SM30a Tapu Koko. SM04 Pikachu is removed in German and French (never printed in those languages).
- **Malie** (malie.io) as an image source for localized prints.
- **CardTrader blueprints** for Pikachu World Collection 2000 and 2010.
- **pcg-search.com** as an image source for the first Japanese sets, with corrections to their names and illustrators.

## 2026-11-15

- **Pokumon**: numbered matches rebuilt (Mega promos 135–165), English pages for Miscellaneous Promos, name order ignored in "A and B" titles. Zorua, Zoroark and Raikou promos moved to `en:miscp`.

## 2026-11-14

- **Single-variant rarities**: Double Rare, Ultra Rare, Secret Rare, Shiny Rare, and Shiny Rare V/VMAX cards have only a NORMAL variant (no holo or reverse holo).
- **Pokoin**: manual images for Nidoran Lv.13 and the six Unown of Shining Darkness.
- **Cardmarket product titles** keep the Unown letter and the Nidoran gender (Nidoran ♂ Lv.13, Unown [K] Lv.15).

## 2026-11-13

- **TCGplayer**: product ids (`card.tcgplayer`) from TCGCSV.

## 2026-11-09

- **Release dates** for Japanese sets that exist only on Cardmarket: Perfect Battle Deck 60 Zygarde EX (xyg), Golduck BREAK + Palkia EX Combo Deck (xyf), M Audino EX Mega Battle Deck (xyh), 24 more decks and starters, promos with their start month, and the Classic sets.
- **Printed numbers** of Space-Time Creation (`ja:dp1`, 122 cards) from TCG Collector.
- The empty extra set `meproducts` was removed.

## 2026-11-08

- **Shining Darkness** (`ja:dp3`): printed numbers 001/119–119/119 from TCG Collector.

## 2026-11-07

- **Japanese set fixes**: names, dates and official card counts of BW8a, SV4a, CP5, XY3, SM7a and SM8b, confirmed against Bulbapedia and Serebii. The corrected name also applies to Cardmarket-only sets (CP5).
- **Chinese Cardmarket expansions** (CS1a…) that had landed in TCGdex's Japanese placeholder sets now go in `zh-cn`, with their English name. The manual card CS2a moved to `zh-cn`.
- **Pikachu's New Friends** (SM0): 4 promos from Cardmarket, images from Pokellector.
- **Pokoin** also gives images to Simplified Chinese cards.
- **Shining Darkness** (`ja:dp3`): new set with 119 cards (Cardmarket 4313, CardTrader 2009) and its release date.

## 2026-11-06

- **Simplified Chinese (`zh-cn`)**: TCGdex cards plus promos from Pokumon; Cardmarket prices where the name is unique. Chinese promos with Pokumon finishes (Poké Ball, Master Ball, Reverse, Holo) become variants of the same card.
- **Pokumon** as an image source: Japanese unnumbered and numbered promos, matched by language, set and number; PNG/WebP images; set aliases (`xy-p`, `bw-p`, `pcg-p`, `dp-p`, `l-p`, `p`, `ppp`, `mcdonalds`, `t`); several pages for the same number (Staff, Winner…); missing Japanese promos added (`manual-cards-ja.tsv`).
- **STAMPED variants**: Pokumon pages with a stamp (Staff, Winner, Finalist…) become STAMPED variants with their own image (`card_variant.pokumon`).
- **Promo details** for Japanese unnumbered promos: dates and occasions (`card_promo`).
- **Set English names**: `card_set.nameEn` for Japanese sets (from CardTrader, with corrections).
- **Variant corrections**: Espeon ★ and Umbreon ★ of POP Series 5 have no holo (`variant-overrides.tsv`).
- **Manual Cardmarket products** added to Cardmarket-only sets without touching the whole set (`extra-products.tsv`).
- **Pokédex**: Heliolisk of Phantom Gate (CardTrader blueprint with a non-existent Cardmarket id) and the Mega cards with the M attached to the name (MGengar EX) now have a Pokédex number.
- **Bill's Archive** removed completely (source, `card.billsarchive` column, data, tests). The badges count Pokumon.

## 2026-11-05

- **Pokoin** as an image source: Battle Academy stamps, CardTrader blueprints (also for cards that already have an image from another source: 1,122 more cards), manual images, placeholder images discarded, accented names (Pokémon), cards without a number (duplicate pages, same name by Cardmarket order).
- **Dedupe of sets** and corrections to products and variants; faster image phase.
- **CardTrader blueprints**: Energies with a stamp, SM Trainer Kit Alolan Sandslash & Ninetales Great Balls, stamped Bulbasaur (350890) and Pikachu (359086).
- **Release dates**: sets take the date of their European twin (same as TCGdex) and Bulbapedia dates for decks, gyms, kits and promos: 187 sets fewer without a date.
- **Celebrations Classic Collection**: the six cards with the original set's numbering merge with TCGdex's (CC014–CC021), with no duplicates in the two sets.
- **Mega Evolution Products** without the Cardmarket catalog cards that the site does not list.
- **Badges** for the README (cards in the database, % with an image per group: TCG, OCG, Chinese).
- Corrections for Beginning Set and SE Asia Gym Promos; manual release dates; manual Trainers without a Pokédex number.

## 2026-11-04

- **Pokoin** first used as an image source; set dedupe, product and variant corrections, faster image phase.

## 2026-11-03

- **Image sources** (Pokellector); English names and Pokédex numbers corrected; CardTrader blueprints checked (including typos).
- **Manual corrections** of products, blueprints, Pokédex numbers and names; Pokédex corrections apply before CardTrader; hand-picked products skip the marker check; Trainer images with differently translated names.

## 2026-10-30

- **Oversized (JUMBO)** cards from CardTrader and Cardmarket, also for European languages; generic Cardmarket prints confirmed as oversize become JUMBO; manual oversize matches (GX/EX/V of SM, XY, SWSH, SV, WCD); JUMBO without a Cardmarket product get the CardTrader blueprint on the variant; blueprint of Terapagos & Friends.
- Wrong Cardmarket hints from TCGdex (card with the wrong name) are discarded.
- Cardmarket-only cards without a Pokémon in the name (Trainers, Energies) enter the database like TCGdex ones.

## 2026-10-29

- **Ascended Heroes and MEGA Dream ex**: reverse Ball/Rocket and Energy variants from Cardmarket's Additionals expansions.

## 2026-10-28

- **First database** built by the generator (moved from the Card-collection repository).
- **English names** of the cards (`card.nameEn`), also from CardTrader (Trainers, old spellings, Dark/Brock's).
- **All the catalog's cards** are included (Trainers and Energies too), and the Pokémon in names without spaces is recognised.
- Trainers and Energies without a product link to Cardmarket by position, when the order of the products is confirmed.
