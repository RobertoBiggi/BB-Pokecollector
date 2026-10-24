package com.robertobiggi.cardcollection.core.cardtrader

import com.robertobiggi.cardcollection.core.model.PokemonNames
import com.robertobiggi.cardcollection.core.tcgdex.SetOrdinal
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Abbina espansioni e carte di TCGdex a quelle di CardTrader. */
object CardTraderMatcher {

    /**
     * Normalizza un nome di set per il confronto (minuscolo, solo lettere e cifre, senza "pokemon").
     * @param name String - nome del set.
     * @return String - nome normalizzato.
     */
    fun normalizeName(name: String): String =
        name.lowercase().replace("pokémon", "").replace("pokemon", "").filter { it.isLetterOrDigit() }

    /**
     * Normalizza un numero di carta: toglie il totale ("075/110" → "75") e gli zeri iniziali.
     * @param raw String - numero stampato o localId.
     * @return String - numero normalizzato, maiuscolo.
     */
    fun normalizeNumber(raw: String): String {
        val head = raw.substringBefore('/').trim().uppercase()
        val stripped = head.trimStart('0')
        return if (stripped.isEmpty() && head.isNotEmpty()) "0" else stripped
    }

    /**
     * Trova l'espansione di CardTrader corrispondente al set.
     * @param expansions List<CtExpansionDto> - espansioni del gioco.
     * @param setName String - nome del set su TCGdex.
     * @param setId String - id del set su TCGdex.
     * @param setCode String? - sigla stampata del set.
     * @param preferCode Boolean - true per le carte giapponesi: i nomi TCGdex sono in giapponese o coincidono con quelli dei set inglesi (es. "30th CELEBRATION"), quindi si prova prima la sigla.
     * @return CtExpansionDto? - espansione trovata, oppure null.
     */
    fun expansionFor(
        expansions: List<CtExpansionDto>,
        setName: String,
        setId: String,
        setCode: String?,
        preferCode: Boolean = false,
    ): CtExpansionDto? {
        val wanted = normalizeName(setName)
        val byName = { expansions.firstOrNull { normalizeName(it.name) == wanted } }
        val byCode = {
            listOfNotNull(setId, setCode).map { it.lowercase() }.flatMap { listOfNotNull(JP_CODE_ALIASES[it], it) }
                .firstNotNullOfOrNull { code -> expansions.firstOrNull { it.code?.lowercase() == code } }
        }
        // Trainer Gallery, Galarian Gallery e Shiny Vault sono set a parte su TCGdex, ma su CardTrader le carte (TG01, GG01, SV1…) stanno nel set principale.
        // Va prima del codice: la sigla del set a parte può coincidere con quella di un altro set (Hidden Fates Shiny Vault "sma" è lo Starter Set Sole e Luna).
        val base = GALLERY_SUFFIXES.firstNotNullOfOrNull { wanted.removeSuffix(it).takeIf { b -> b != wanted && b.isNotEmpty() } }
        val ofBase = { base?.let { b -> expansions.firstOrNull { normalizeName(it.name) == b } } }
        val exact = if (preferCode) byCode() ?: byName().takeIf { wanted.isNotEmpty() } ?: ofBase()
        else byName().takeIf { wanted.isNotEmpty() } ?: ofBase() ?: byCode()
        if (exact != null) return exact
        // Nomi con prefissi diversi ("SV02: Paldea Evolved"): vale solo se uno contiene l'altro per intero.
        if (wanted.length >= MIN_CONTAINED_LENGTH) {
            val contained = expansions.filter {
                val n = normalizeName(it.name)
                n.length >= MIN_CONTAINED_LENGTH && (n.endsWith(wanted) || wanted.endsWith(n))
            }
            if (contained.size == 1) return contained.first()
        }
        return null
    }

    private fun looseNumber(raw: String): String = normalizeNumber(raw).replace(Regex("""(?<=\D)0+(?=\d)"""), "")

    /**
     * Restringe i blueprint a quelli con l'id prodotto Cardmarket indicato da TCGdex. Serve quando il numero non trova la carta
     * (Celebrations Classic Collection: CardTrader usa il numero del set originale) o ne trova più d'una.
     * @param blueprints List<CtBlueprintDto> - blueprint dell'espansione, o già scelti per numero.
     * @param hint Int? - id prodotto Cardmarket.
     * @return List<CtBlueprintDto> - blueprint con quell'id; vuota se nessuno lo ha o manca l'indizio.
     */
    fun byCardmarketHint(blueprints: List<CtBlueprintDto>, hint: Int?): List<CtBlueprintDto> =
        if (hint == null) emptyList() else blueprints.filter { hint in it.cardMarketIds.orEmpty() }

    /**
     * Sceglie tra il blueprint trovato dal numero e quello indicato dall'id Cardmarket di TCGdex. Il numero può portare a un'altra
     * carta (set con la stessa sigla, o numerazione diversa: 30th Celebration Classic Collection "001" è Exeggcute 001/128):
     * se l'id Cardmarket indica un blueprint di un'altra carta (nome diverso) e quelli trovati dal numero non lo contengono, vale l'id.
     * @param byNumber List<CtBlueprintDto> - blueprint con il numero della carta.
     * @param byHint List<CtBlueprintDto> - blueprint con l'id Cardmarket indicato da TCGdex.
     * @return List<CtBlueprintDto> - blueprint scelti.
     */
    fun preferHint(byNumber: List<CtBlueprintDto>, byHint: List<CtBlueprintDto>): List<CtBlueprintDto> {
        if (byHint.isEmpty()) return byNumber
        if (byNumber.isEmpty()) return byHint
        val hintIds = byHint.map { it.id }.toSet()
        val hintNames = byHint.map { pokemonKey(it.name.replace(LEVEL_SUFFIX, "")) }.toSet()
        val sameCard = byNumber.any { it.id in hintIds || pokemonKey(it.name.replace(LEVEL_SUFFIX, "")) in hintNames }
        return if (sameCard) byNumber else byHint
    }

    /**
     * Sceglie il blueprint che rappresenta la carta quando ce ne sono più d'uno con lo stesso numero: se sono tutti la stessa
     * carta in finiture diverse (normale, holo…), vale quello senza finitura o timbro nella versione; se sono carte diverse, nessuno.
     * Serve a immagine e dettagli, che sono uguali per ogni finitura; i prezzi scelgono la finitura per conto loro.
     * @param blueprints List<CtBlueprintDto> - blueprint con lo stesso numero.
     * @return CtBlueprintDto? - blueprint scelto, oppure null se non c'è una carta sola.
     */
    fun representative(blueprints: List<CtBlueprintDto>): CtBlueprintDto? {
        if (blueprints.size <= 1) return blueprints.singleOrNull()
        if (blueprints.map { pokemonKey(it.name.replace(LEVEL_SUFFIX, "")) }.distinct().size != 1) return null
        return blueprints.firstOrNull { bp -> bp.version?.let { VERSION_FINISH.containsMatchIn(it) } != true } ?: blueprints.first()
    }

    /**
     * Seleziona i blueprint con il numero di carta indicato.
     * @param blueprints List<CtBlueprintDto> - carte dell'espansione.
     * @param localId String - numero della carta su TCGdex.
     * @param dexId Int? - numero del Pokédex: le espansioni giapponesi del 1996-1999 non hanno il numero di collezione e CardTrader le identifica da "No.025" nella versione.
     * @return List<CtBlueprintDto> - blueprint con lo stesso numero (o, per quelle espansioni, con lo stesso numero del Pokédex).
     */
    fun blueprintsFor(blueprints: List<CtBlueprintDto>, localId: String, dexId: Int? = null): List<CtBlueprintDto> {
        val wanted = normalizeNumber(localId)
        if (wanted.isEmpty()) return emptyList()
        val byNumber = blueprints.filter { bp -> collectorNumber(bp)?.let { normalizeNumber(it) == wanted } == true }
            // Gli zeri dopo le lettere non sempre coincidono (TCGdex "SV001", CardTrader "SV1").
            .ifEmpty { blueprints.filter { bp -> collectorNumber(bp)?.let { looseNumber(it) == looseNumber(localId) } == true } }
        if (byNumber.isNotEmpty() || dexId == null) return byNumber
        val names = PokemonNames.namesOf(dexId).map { pokemonKey(it) }.toSet()
        // Molti blueprint di queste espansioni non hanno nemmeno "No.": allora vale il nome della carta.
        return blueprints.filter { isPokemonOf(it, dexId, names) }
    }

    /**
     * Blueprint senza id Cardmarket (promo, mazzi) che porta il nome della carta. Serve alle carte che arrivano da Cardmarket
     * e non hanno un numero con cui cercarle: vale solo se il nome indica un solo blueprint, altrimenti non si indovina.
     * @param blueprints List<CtBlueprintDto> - carte dell'espansione.
     * @param name String - nome della carta (anche con le mosse tra parentesi quadre, come nel catalogo Cardmarket).
     * @return CtBlueprintDto? - il blueprint, oppure null se il nome non c'è o è ambiguo.
     */
    fun blueprintByName(blueprints: List<CtBlueprintDto>, name: String): CtBlueprintDto? {
        val wanted = pokemonKey(name.substringBefore('[').replace(LEVEL_SUFFIX, ""))
        if (wanted.isEmpty()) return null
        return blueprints.filter { it.cardMarketIds.isNullOrEmpty() && pokemonKey(it.name.replace(PRINTED_SUFFIX, "").replace(LEVEL_SUFFIX, "")) == wanted }
            .singleOrNull()
    }

    /**
     * Trova il blueprint di una carta senza Pokédex (Allenatore, Energia) in un'espansione senza numeri di collezione.
     * Si cercano le due carte Pokémon di riferimento (prima e dopo) nell'elenco di CardTrader e si prende il blueprint
     * che occupa la stessa posizione tra le carte che non sono Pokémon. Se il numero di carte tra i due riferimenti
     * non coincide con quello di TCGdex, o un riferimento non è univoco, non si indovina.
     * @param blueprints List<CtBlueprintDto> - blueprint dell'espansione, nell'ordine di CardTrader.
     * @param ordinal SetOrdinal - posizione della carta su TCGdex.
     * @return CtBlueprintDto? - il blueprint, oppure null se la posizione non è affidabile.
     */
    fun blueprintByOrdinal(blueprints: List<CtBlueprintDto>, ordinal: SetOrdinal): CtBlueprintDto? {
        val cards = blueprints.filter { bp -> bp.fixedProperties?.get("pokemon_rarity").let { it != null && it !is JsonNull } }
        fun indexOf(dex: Int): Int? {
            val names = PokemonNames.namesOf(dex).map { pokemonKey(it) }.toSet()
            return cards.indices.filter { isPokemonOf(cards[it], dex, names) }.singleOrNull()
        }
        val left = ordinal.leftDex?.let { indexOf(it) ?: return null } ?: -1
        val right = ordinal.rightDex?.let { indexOf(it) ?: return null } ?: cards.size
        if (left >= right) return null
        val between = cards.subList(left + 1, right).filterNot { PokemonNames.mentionsPokemon(it.name) }
        return if (between.size == ordinal.count) between.getOrNull(ordinal.rank) else null
    }

    private fun isPokemonOf(bp: CtBlueprintDto, dex: Int, names: Set<String>): Boolean {
        val listed = bp.version?.let { DEX_VERSION.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        return if (listed != null) listed == dex else pokemonKey(bp.name.replace(LEVEL_SUFFIX, "")) in names
    }

    private fun pokemonKey(name: String): String =
        name.lowercase().replace('♀', 'f').replace('♂', 'm').filter { it.isLetterOrDigit() }

    /**
     * Numero stampato con il totale del set ("014/135"), come lo scrive la carta.
     * @param bp CtBlueprintDto - blueprint.
     * @return String? - numero con totale, oppure null se CardTrader non lo indica (es. promo senza numero).
     */
    fun printedNumber(bp: CtBlueprintDto): String? =
        (bp.fixedProperties?.get("collector_number") as? JsonPrimitive)?.content?.trim()?.takeIf { WITH_TOTAL.matches(it) }
            ?: bp.version?.let { VERSION_NUMBER.find(it)?.value }

    /**
     * Numero stampato del blueprint. Il numero scritto nella versione ("Ultra Rare | 035/054") vale più del campo
     * `collector_number`, che su CardTrader a volte è sbagliato (es. sm10b: 032 invece di 035, come un'altra carta).
     */
    private fun collectorNumber(bp: CtBlueprintDto): String? =
        bp.version?.let { PRINTED_NUMBER.find(it)?.groupValues?.get(1) }
            ?: (bp.fixedProperties?.get("collector_number") as? JsonPrimitive)?.content

    private const val MIN_CONTAINED_LENGTH = 6

    private val GALLERY_SUFFIXES = listOf("trainergallery", "galariangallery", "shinyvault")

    /**
     * Set giapponesi di TCGdex (id in minuscolo) che su CardTrader hanno un codice diverso.
     * Verificato su ogni set confrontando numero e specie Pokémon delle carte; i set senza carte su TCGdex non servono.
     */
    private val JP_CODE_ALIASES = mapOf(
        // Prima generazione (1996-1999): su CardTrader non hanno il numero di collezione, vedi blueprintsFor.
        "pmcg1" to "1ep", "pmcg2" to "jun", "pmcg3" to "myf", "pmcg4" to "rog", "pmcg5" to "gls", "pmcg6" to "gcd",
        "neo1" to "gstnw", "neo2" to "ctr", "neo3" to "awl", "neo4" to "del",
        "vs1" to "pvs", "web1" to "pwe",
        "e1" to "ec1", "e2" to "ec2", "e3" to "ec3", "e4" to "ec4", "e5" to "ec5",
        "pcg1" to "fol", "pcg2" to "cbs", "pcg3" to "rgsb", "pcg4" to "gsso", "pcg5" to "mif",
        // McDonald's: CardTrader dà ai set recenti un altro nome (25th Anniversary, Match Battle, Dragon Discovery) e una sigla diversa da TCGdex.
        "2021swsh" to "mc21", "2022swsh" to "mc22", "2023sv" to "mc23", "2024sv" to "m24",
        // Altri set che CardTrader chiama in modo diverso da TCGdex (confrontati numero e nome delle carte: tutte o quasi coincidono).
        "cel25cc" to "c25", "30th-c" to "30c", "svp" to "svpromo", "swsh10.5" to "pkmgo", "fut2020" to "futsal", "exu" to "suf",
        "tk-xy-su" to "suicune", "tk-xy-p" to "xytkp", "tk-sm-l" to "xytkl", "tk-sm-r" to "xytka",
        "pcg6" to "hrt", "pcg7" to "hof", "pcg8" to "mic", "pcg9" to "odffe",
        "s5i" to "s5l", "sm1p" to "sm1plus", "sm2p" to "sm2plus", "sm3p" to "sm3", "sm4p" to "sm4", "sm5p" to "sm5plus",
        "sv-p" to "promosv", "m-p" to "mp-promo",
        // Set che TCGdex elenca senza carte: servono per cercarli nel catalogo Cardmarket (e per i prezzi CardTrader).
        "adv1" to "exp", "adv2" to "mod", "adv3" to "roh", "adv4" to "mvsa", "adv5" to "uns", "pcg10" to "wcp",
        "l1a" to "l1", "l1b" to "l1ss", "xy1a" to "xy1", "xy1b" to "xy1y", "xy5a" to "xy5", "xy5b" to "xy5t",
        "xy8a" to "xy8", "xy8b" to "xy8r", "xy11a" to "xy11f", "xy11b" to "xy11", "cp6" to "ep20", "s3a" to "ls3",
    )

    private val VERSION_FINISH = Regex("""holo|reverse|stamp""", RegexOption.IGNORE_CASE)

    private val LEVEL_SUFFIX = Regex("""\s+Lv\.\s*\d+.*""")

    /** Numero e dettagli che CardTrader aggiunge al nome delle promo ("Moltres ex 014/135 Holo Promo"). */
    private val PRINTED_SUFFIX = Regex("""\s+\d+/\d+.*""")

    private val WITH_TOTAL = Regex("""\w*\d\w*/\w*\d\w*""")

    private val VERSION_NUMBER = Regex("""\w*\d\w*/\w*\d\w*\s*$""")

    private val PRINTED_NUMBER = Regex("""(\w*\d\w*)/\w*\d\w*\s*$""")

    private val DEX_VERSION = Regex("""No\.(\d+)""")
}
