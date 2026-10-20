package com.robertobiggi.cardcollection.core.tcgcsv

import kotlinx.serialization.Serializable

/**
 * Risposta di TCGCSV: copia giornaliera dei dati dell'API TCGplayer, sempre racchiusa in [results].
 */
@Serializable
data class TcgcsvResponse<T>(val success: Boolean = true, val results: List<T> = emptyList())

/** Gruppo TCGplayer, cioè un set (es. "ME01: Mega Evolution", sigla "MEG"). */
@Serializable
data class TcgcsvGroupDto(val groupId: Long, val name: String = "", val abbreviation: String? = null)

/** Campo aggiuntivo di un prodotto, es. name = "Number", value = "117/080". */
@Serializable
data class TcgcsvExtendedDto(val name: String = "", val value: String? = null)

/** Prodotto TCGplayer: una carta (o un prodotto sigillato, che non ha il campo "Number"). */
@Serializable
data class TcgcsvProductDto(
    val productId: Long,
    val name: String = "",
    val imageUrl: String? = null,
    val extendedData: List<TcgcsvExtendedDto> = emptyList(),
) {
    /**
     * Numero stampato sulla carta, se il prodotto ne ha uno.
     * @return String? - es. "117/080" oppure "TG17/TG30"; null per i prodotti sigillati.
     */
    fun number(): String? = extendedData.firstOrNull { it.name == "Number" }?.value?.trim()?.takeIf { it.isNotEmpty() }
}

/** Prezzi in dollari di un prodotto per una variante ([subTypeName], es. "Normal", "Reverse Holofoil"). */
@Serializable
data class TcgcsvPriceDto(
    val productId: Long,
    val lowPrice: Double? = null,
    val midPrice: Double? = null,
    val marketPrice: Double? = null,
    val subTypeName: String = "",
)
