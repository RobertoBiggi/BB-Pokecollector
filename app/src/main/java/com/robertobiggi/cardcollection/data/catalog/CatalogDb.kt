package com.robertobiggi.cardcollection.data.catalog

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.robertobiggi.cardcollection.core.model.CatalogCard
import com.robertobiggi.cardcollection.core.model.CatalogVariant
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.PokemonNames
import com.robertobiggi.cardcollection.core.model.VariantType
import java.io.Closeable
import java.io.File

/** Il file del catalogo non è un database delle carte valido (formato o versione diversi, file danneggiato). */
class InvalidCatalogException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Set del catalogo, per l'elenco da cui creare una collezione per set.
 * @property id id del set (es. "base1", "SM10a").
 * @property name nome del set.
 * @property cards carte del set nel catalogo.
 * @property declared carte dichiarate dal set (numero ufficiale o totale), se note.
 */
data class CatalogSet(val id: String, val name: String, val cards: Int, val declared: Int?, val releaseDate: String?, val code: String? = null)

/** Carta trovata da una ricerca, con quel che serve a mostrarla nell'elenco. */
data class CardBrief(val id: String, val localId: String, val name: String, val setName: String, val imageUrl: String?)

/**
 * Lettura del database delle carte (`cards.sqlite`, generato dal repository Card-collector-db-gen), in sola lettura.
 * Il file contiene le carte di tutti i Pokémon, già abbinate a Cardmarket e CardTrader: l'app non interroga più nessun catalogo remoto.
 * Un'istanza va chiusa con [close]; le query sono sincrone, quindi vanno chiamate fuori dal thread dell'interfaccia.
 */
class CatalogDb private constructor(private val db: SQLiteDatabase, val generatedAt: String, val languages: Set<String>) : Closeable {

    private val variantTypes: Map<Int, VariantType> = readEnum("variantType").mapNotNull { (code, name) -> VariantType.fromName(name)?.let { code to it } }.toMap()

    /** True se il database ha la colonna `card.nameEn` (i file generati prima non ce l'hanno: restano validi e mostrano i nomi originali). */
    private val hasNameEn: Boolean = db.rawQuery("PRAGMA table_info(card)", null).use { c ->
        var found = false
        while (c.moveToNext()) if (c.getString(1) == "nameEn") found = true
        found
    }

    private fun readEnum(kind: String): Map<Int, String> =
        db.rawQuery("SELECT code, name FROM enum_code WHERE kind = ?", arrayOf(kind)).use { c ->
            buildMap { while (c.moveToNext()) put(c.getInt(0), c.getString(1)) }
        }

    /**
     * Set di una lingua che hanno almeno una carta, nell'ordine di uscita (i più recenti per primi).
     * @param language Language - lingua del catalogo.
     * @return List<CatalogSet> - set disponibili.
     */
    fun sets(language: Language): List<CatalogSet> =
        db.rawQuery(
            """SELECT s.setId, s.name, COUNT(c.n), COALESCE(s.cardCountTotal, s.cardCountOfficial), s.releaseDate, s.setCode
               FROM card_set s JOIN card c ON c.s = s.s WHERE s.language = ?
               GROUP BY s.s ORDER BY s.releaseDate IS NULL, s.releaseDate DESC, s.name""",
            arrayOf(language.code),
        ).use { c -> buildList { while (c.moveToNext()) add(CatalogSet(c.getString(0), c.getString(1), c.getInt(2), if (c.isNull(3)) null else c.getInt(3), c.getString(4), c.getString(5)?.takeIf { it.isNotBlank() })) } }

    /**
     * Tutte le carte di un set.
     * @param language Language - lingua del catalogo.
     * @param setId String - id del set.
     * @return List<CatalogCard> - carte del set, in ordine di numero.
     */
    fun cardsOfSet(language: Language, setId: String): List<CatalogCard> =
        query("WHERE s.language = ? AND s.setId = ?", arrayOf(language.code, setId))

    /**
     * Le carte di uno o più Pokémon.
     * @param language Language - lingua del catalogo.
     * @param dexIds Collection<Int> - numeri del Pokédex nazionale.
     * @return List<CatalogCard> - carte che li contengono (anche "Pikachu & Zekrom GX").
     */
    fun cardsOfPokemon(language: Language, dexIds: Collection<Int>): List<CatalogCard> {
        if (dexIds.isEmpty()) return emptyList()
        val marks = dexIds.joinToString(",") { "?" }
        return query(
            "WHERE s.language = ? AND c.n IN (SELECT n FROM card_pokemon WHERE dexId IN ($marks))",
            arrayOf(language.code) + dexIds.map { it.toString() },
        )
    }

    /**
     * Le carte che corrispondono a un nome: se il testo è il nome di uno o più Pokémon (in una qualsiasi lingua)
     * le carte di quei Pokémon, altrimenti le carte il cui nome contiene il testo.
     * @param language Language - lingua del catalogo.
     * @param text String - testo cercato.
     * @return List<CatalogCard> - carte trovate.
     */
    fun cardsByName(language: Language, text: String): List<CatalogCard> {
        val dex = PokemonNames.dexIdsMatching(text)
        if (dex.isNotEmpty()) return cardsOfPokemon(language, dex)
        return query("WHERE s.language = ? AND c.name LIKE ? ESCAPE '\\'", arrayOf(language.code, "%" + like(text) + "%"))
    }

    /**
     * Le carte con un numero stampato (es. "117/080", "SM190", "TG05"), eventualmente ristrette da sigla o parole.
     * @param language Language - lingua del catalogo.
     * @param number Int - numero cercato (senza zeri iniziali).
     * @param total Int? - totale del set stampato, se indicato.
     * @param tokens List<String> - sigla o id del set e parti del nome.
     * @return List<CatalogCard> - carte trovate, restringendo con totale e parole solo finché resta qualcosa.
     */
    fun cardsByCode(language: Language, number: Int, total: Int?, tokens: List<String>): List<CatalogCard> {
        var hits = query(
            "WHERE s.language = ? AND c.localId NOT GLOB '*[^0-9]*' AND LENGTH(c.localId) <= 6 AND CAST(c.localId AS INTEGER) = ?",
            arrayOf(language.code, number.toString()),
        )
        if (total != null) hits = hits.narrow { (it.printedNumber.substringAfter('/', "").toIntOrNull() ?: total) == total }
        for (token in tokens) {
            val inSet = hits.filter { it.setId.equals(token, true) || it.setCode.equals(token, true) }
            hits = if (inSet.isNotEmpty()) inSet else hits.narrow { it.name.contains(token, ignoreCase = true) }
        }
        return hits
    }

    /**
     * Le carte che hanno un prodotto Cardmarket, per ritrovare una carta il cui id è cambiato.
     * @param language Language - lingua del catalogo.
     * @param product Int - id prodotto Cardmarket.
     * @return List<CatalogCard> - carte con quel prodotto.
     */
    fun cardsByProduct(language: Language, product: Int): List<CatalogCard> =
        query("WHERE s.language = ? AND (c.cmProduct = ? OR c.cardmarketId = ?)", arrayOf(language.code, product.toString(), product.toString()))

    /**
     * Le carte con un nome esatto (originale o inglese).
     * @param language Language - lingua del catalogo.
     * @param name String - nome della carta.
     * @return List<CatalogCard> - carte con quel nome.
     */
    fun cardsNamed(language: Language, name: String): List<CatalogCard> =
        query("WHERE s.language = ? AND (c.name = ? OR ${if (hasNameEn) "c.nameEn" else "c.name"} = ?)", arrayOf(language.code, name, name))

    /**
     * Una carta con le sue varianti.
     * @param cardId String - id locale ("lingua:idTcgdex").
     * @return CatalogCard? - la carta, oppure null se il catalogo non la contiene.
     */
    fun card(cardId: String): CatalogCard? = query("WHERE c.id = ?", arrayOf(cardId)).firstOrNull()

    private fun query(where: String, args: Array<String>): List<CatalogCard> {
        val rows = db.rawQuery(
            """SELECT c.n, c.id, s.language, c.name, s.setId, s.name, s.setCode, s.serieId, s.serieName, c.localId, c.printedNumber, c.rarity,
                      s.releaseDate, c.illustrator, c.dexId, c.cardmarketId, c.cardmarketExpansionId, c.cardmarketName, c.cardmarketProductHint,
                      c.cmProduct, c.cardTraderBlueprintId, s.cardTraderExpansionId, c.origin, ${if (hasNameEn) "c.nameEn" else "NULL"}
               FROM card c JOIN card_set s ON s.s = c.s $where ORDER BY s.setId, c.localId""",
            args,
        ).use { c -> buildList { while (c.moveToNext()) add(Row(c)) } }
        val variants = HashMap<Long, MutableList<CatalogVariant>>()
        for (chunk in rows.map { it.n }.chunked(SQL_CHUNK)) {
            db.rawQuery(
                "SELECT n, type, print, cardmarketId FROM card_variant WHERE n IN (${chunk.joinToString(",")}) ORDER BY n, type, print",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val type = variantTypes[c.getInt(1)] ?: continue
                    variants.getOrPut(c.getLong(0)) { ArrayList() } += CatalogVariant(type, if (c.isNull(3)) null else c.getInt(3), c.getString(2))
                }
            }
        }
        return rows.mapNotNull { r -> r.toCard(variants[r.n].orEmpty()) }
    }

    private inner class Row(c: Cursor) {
        val n = c.getLong(0)
        val id = c.getString(1)
        val language = c.getString(2)
        /** Nome inglese se il database lo conosce, altrimenti quello della lingua della carta. */
        val name: String = c.getString(23) ?: c.getString(3)
        val setId = c.getString(4)
        val setName = c.getString(5)
        val setCode: String? = c.getString(6)
        val serieId: String? = c.getString(7)
        val serieName: String? = c.getString(8)
        val localId = c.getString(9)
        val printedNumber = c.getString(10)
        val rarity: String? = c.getString(11)
        val releaseDate: String? = c.getString(12)
        val illustrator: String? = c.getString(13)
        val dexId = if (c.isNull(14)) null else c.getInt(14)
        val cardmarketId = if (c.isNull(15)) null else c.getInt(15)
        val cardmarketExpansionId = if (c.isNull(16)) null else c.getInt(16)
        val cardmarketName: String? = c.getString(17)
        val productHint = if (c.isNull(18)) null else c.getInt(18)
        val product = if (c.isNull(19)) null else c.getInt(19)
        val blueprint = if (c.isNull(20)) null else c.getInt(20)
        val ctExpansion = if (c.isNull(21)) null else c.getInt(21)
        val fromTcgdex = c.getInt(22) == ORIGIN_TCGDEX

        fun toCard(variants: List<CatalogVariant>): CatalogCard? {
            val lang = Language.fromCode(language) ?: return null
            return CatalogCard(
                id = id, language = lang, tcgdexId = id.substringAfter(':'), name = name, setId = setId, setName = setName, setCode = setCode,
                serieId = serieId, serieName = serieName, localId = localId, printedNumber = printedNumber, rarity = rarity, releaseDate = releaseDate,
                illustrator = illustrator,
                // Le immagini sono su TCGdex, con un indirizzo ricavato dall'id; le carte delle altre fonti non ce l'hanno.
                imageBaseUrl = if (fromTcgdex && serieId != null) "$IMAGE_HOST/$language/$serieId/$setId/$localId" else null,
                variants = variants.ifEmpty { listOf(CatalogVariant(VariantType.NORMAL)) }, dexId = dexId, cardmarketId = cardmarketId,
                cardmarketExpansionId = cardmarketExpansionId, cardmarketName = cardmarketName, cardmarketProductHint = productHint,
                cardmarketProduct = product, cardTraderBlueprintId = blueprint, cardTraderExpansionId = ctExpansion,
            )
        }
    }

    override fun close() = db.close()

    companion object {
        /** Versione del formato che l'app sa leggere (`meta.schemaVersion`, vedi `SCHEMA_VERSION` in `gen_card_db.py` di Card-collector-db-gen). */
        const val SCHEMA_VERSION = 2
        private const val ORIGIN_TCGDEX = 0
        private const val SQL_CHUNK = 500
        private const val IMAGE_HOST = "https://assets.tcgdex.net"

        /**
         * Apre il file in sola lettura e ne controlla il formato.
         * @param file File - database delle carte.
         * @return CatalogDb - database aperto.
         * @throws InvalidCatalogException se il file non è un database valido o ha una versione diversa.
         */
        fun open(file: File): CatalogDb {
            val db = try {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            } catch (e: Exception) {
                throw InvalidCatalogException("File non apribile", e)
            }
            try {
                val meta = db.rawQuery("SELECT key, value FROM meta", null).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }
                if (meta["schemaVersion"]?.toIntOrNull() != SCHEMA_VERSION) throw InvalidCatalogException("Versione del database non supportata: ${meta["schemaVersion"]}")
                return CatalogDb(db, meta["generatedAt"].orEmpty(), meta["languages"].orEmpty().split(',').filter { it.isNotEmpty() }.toSet())
            } catch (e: InvalidCatalogException) {
                db.close()
                throw e
            } catch (e: Exception) {
                db.close()
                throw InvalidCatalogException("Database non valido", e)
            }
        }

        private fun like(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        private fun <T> List<T>.narrow(predicate: (T) -> Boolean): List<T> = filter(predicate).ifEmpty { this }
    }
}
