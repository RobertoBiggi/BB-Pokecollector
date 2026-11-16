# BB PokeCollector

An offline-first Android app to track your Pokémon card collection: every variant you own, what you are still missing, and what it is worth.

<p align="center">
  <img src="docs/badges/cards.svg" alt="Cards in the database">
  <img src="docs/badges/tcg-images.svg" alt="TCG cards (all languages except Japanese, Korean and Chinese) with image">
  <img src="docs/badges/ocg-images.svg" alt="OCG cards (Japanese and Korean) with image">
  <img src="docs/badges/chinese-images.svg" alt="Chinese cards with image">
</p>

<table align="center">
  <tr>
    <td align="center"><img src="docs/screenshots/06-card-detail.png" width="200" alt="Card detail"><br><sub>Card detail</sub></td>
    <td align="center"><img src="docs/screenshots/07-price-alert.png" width="200" alt="Price alert"><br><sub>Price alert</sub></td>
    <td align="center"><img src="docs/screenshots/04-stats.png" width="200" alt="Statistics"><br><sub>Statistics</sub></td>
    <td align="center"><img src="docs/screenshots/09-price-sources.png" width="200" alt="Price sources and CardTrader token"><br><sub>Price sources and CardTrader token</sub></td>
  </tr>
</table>

## Features

- Collections by Pokémon, by set (base or master set) or hand-picked cards, in many card languages.
- Copies and condition tracked for every variant (normal, holo, reverse, 1st Edition, Poké Ball…).
- Cardmarket prices, TCGplayer prices (USD, English and Japanese cards), optional CardTrader prices, and price alerts by notification.
- Missing cards with cost to complete, collection value and progress statistics.
- Works offline. Backups go to a JSON file. English and Italian UI.

**How to use it:** see the [user guide](docs/USER_GUIDE.md).

## Install

<p>
  <a href="../../releases/latest/download/bb-pokecollector.apk"><img src="docs/badges/download-apk.svg" alt="Download the latest APK"></a>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.robertobiggi.bbpokecollector%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FRobertoBiggi%2FBB-Pokecollector%22%2C%22author%22%3A%22RobertoBiggi%22%2C%22name%22%3A%22BB%20PokeCollector%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3A%20false%7D%22%7D"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" height="54" alt="Get it on Obtainium"></a>
</p>

Requires Android 8.0 or later. The button above always downloads the latest version: open the file on the phone and Android installs it over the previous one, keeping your data. You can also pick the APK from the latest [Release](../../releases/latest), or tap the Obtainium button to get automatic updates (it tracks the latest Release). Every update of `main` publishes a new signed Release.

## Privacy

- **No account and no server**: your collections stay on your phone.
- **Only the addresses it needs**: the app contacts only the image sources, Cardmarket's price-list file, TCGCSV (TCGplayer prices), the card database download, GitHub (to see whether a newer version of the app exists, and to download it when you tap the notification), the anonymous counter below and, if you set a token, the CardTrader API. Every other address is blocked.
- **CardTrader token**: encrypted with an Android Keystore key that never leaves the phone, and excluded from Android backups.
- **Images you choose**: re-encoded without metadata, so location and camera details are not kept.
- **Anonymous statistics**: once a day the app sends one `app_open` event to [Aptabase](https://aptabase.com) with the app version, the Android version and the language. No device identifier, nothing about your collection. It is on by default; turn it off in **Settings → Anonymous statistics** and nothing is sent.

## License and credits

The code is released under the [MIT](LICENSE) license.

Data sources, each with its own license or trademarks:
- card data and images: [TCGdex](https://tcgdex.dev);
- extra card images, for sets TCGdex lacks (mostly Japanese ones and promos): [Pokellector](https://jp.pokellector.com), [Pokoin](https://pokoin.com) and [Pokumon](https://pokumon.com), the last one also for the old unnumbered promos;
- localized prints (Spanish, Italian, German, Portuguese and others) that TCGdex lacks, mostly promos and old sets: the card art of the Pokémon TCG Online/Live games, from [Malie Library](https://malie.io/static/);
- the first Japanese sets (1996-2006) and their promos and decks, as a last resort for images and the source of their correct names and illustrators: [pcg-search.com](https://pcg-search.com/), a fan archive;
- prices: [Cardmarket](https://www.cardmarket.com), [CardTrader](https://www.cardtrader.com) and [TCGplayer](https://www.tcgplayer.com), the last one through [TCGCSV](https://tcgcsv.com), a free daily copy of its data;
- rarities, promo dates and events, used when the database is built: [Bulbapedia](https://bulbapedia.bulbagarden.net);
- Pokémon names: [PokeAPI](https://pokeapi.co);
- anonymous usage counter (optional, see above): [Aptabase](https://aptabase.com).

Pokémon is a trademark of Nintendo, Creatures and GAME FREAK. This app is not affiliated with The Pokémon Company.
