package com.robertobiggi.cardcollection.ui.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.robertobiggi.cardcollection.R
import com.robertobiggi.cardcollection.core.backup.ImportError
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.data.local.PriceRunEntity
import com.robertobiggi.cardcollection.data.repo.AbortReason
import com.robertobiggi.cardcollection.data.repo.CardUi
import com.robertobiggi.cardcollection.data.repo.FileError
import com.robertobiggi.cardcollection.data.repo.ImageResult
import com.robertobiggi.cardcollection.data.repo.InputError
import com.robertobiggi.cardcollection.data.repo.PriceStep
import com.robertobiggi.cardcollection.data.repo.SyncPhase
import com.robertobiggi.cardcollection.data.repo.VariantUi
import com.robertobiggi.cardcollection.work.JobState
import com.robertobiggi.cardcollection.work.WorkKeys

/**
 * Testo da mostrare all'utente, risolto nella lingua corrente solo al momento della visualizzazione.
 * @property id risorsa stringa.
 * @property args argomenti del formato.
 */
class UiText(@StringRes val id: Int, vararg val args: Any) {
    /**
     * Risolve il testo con le risorse del contesto (che seguono la lingua scelta).
     * @param context Context - contesto di un'activity.
     * @return String - testo tradotto.
     */
    fun resolve(context: Context): String = context.getString(id, *args)
}

/**
 * Nome della lingua del catalogo.
 * @receiver Language - lingua.
 * @return Int - risorsa stringa del nome.
 */
@StringRes
fun Language.labelRes(): Int = when (this) {
    Language.EN -> R.string.lang_en
    Language.IT -> R.string.lang_it
    Language.FR -> R.string.lang_fr
    Language.DE -> R.string.lang_de
    Language.ES -> R.string.lang_es
    Language.PT -> R.string.lang_pt
    Language.JA -> R.string.lang_ja
    Language.ZH_TW -> R.string.lang_zh_tw
    Language.TH -> R.string.lang_th
    Language.ID -> R.string.lang_id
}

/**
 * Sigla breve della lingua per chip e righe (JP per il giapponese, come sulle carte).
 * @receiver Language - lingua.
 * @return String - es. "EN", "IT", "JP", "ZH-TW".
 */
fun Language.shortLabel(): String = if (this == Language.JA) "JP" else code.uppercase()

/**
 * Nome della variante.
 * @receiver VariantType - variante.
 * @return Int - risorsa stringa.
 */
@StringRes
fun VariantType.labelRes(): Int = when (this) {
    VariantType.NORMAL -> R.string.variant_normal
    VariantType.HOLO -> R.string.variant_holo
    VariantType.REVERSE -> R.string.variant_reverse
    VariantType.POKE_BALL -> R.string.variant_poke_ball
    VariantType.MASTER_BALL -> R.string.variant_master_ball
    VariantType.FIRST_EDITION -> R.string.variant_first_edition
    VariantType.W_PROMO -> R.string.variant_w_promo
}

/**
 * Nome della variante di una carta: nelle carte solo holo è la rarità (es. "Double Rare").
 * @param card CardUi - carta.
 * @param variant VariantUi - variante.
 * @return String - nome da mostrare.
 */
@Composable
fun variantLabel(card: CardUi, variant: VariantUi): String = card.rarityLabelFor(variant) ?: stringResource(variant.type.labelRes())

/**
 * Messaggio per l'esito di un'azione sull'immagine di una carta.
 * @receiver ImageResult - esito.
 * @return Int - risorsa stringa.
 */
@StringRes
fun ImageResult.messageRes(): Int = when (this) {
    ImageResult.DOWNLOADED -> R.string.msg_image_downloaded
    ImageResult.SAVED -> R.string.msg_image_saved
    ImageResult.NOT_AVAILABLE -> R.string.msg_image_not_available
    ImageResult.NETWORK_ERROR -> R.string.msg_network_error
    ImageResult.INVALID_IMAGE -> R.string.msg_image_invalid
}

/**
 * Messaggio per un dato non valido.
 * @receiver InputError - errore.
 * @return Int - risorsa stringa.
 */
@StringRes
fun InputError.messageRes(): Int = when (this) {
    InputError.NAME_EMPTY -> R.string.err_name_empty
    InputError.QUERY_INVALID -> R.string.err_query_invalid
    InputError.QUERY_DUPLICATE -> R.string.err_query_duplicate
}

/**
 * Messaggio per un file da importare non valido.
 * @receiver ImportError - errore.
 * @return Int - risorsa stringa.
 */
@StringRes
fun ImportError.messageRes(): Int = when (this) {
    ImportError.TOO_LARGE -> R.string.err_import_too_large
    ImportError.INVALID_JSON -> R.string.err_import_invalid
    ImportError.NOT_THIS_APP -> R.string.err_import_other_app
    ImportError.UNSUPPORTED_VERSION -> R.string.err_import_version
    ImportError.TOO_MANY_ITEMS -> R.string.err_import_too_many
    ImportError.READ_FAILED -> R.string.err_file_open
}

/**
 * Messaggio per un errore di accesso al file.
 * @receiver FileError - errore.
 * @return Int - risorsa stringa.
 */
@StringRes
fun FileError.messageRes(): Int = when (this) {
    FileError.NOT_FOUND -> R.string.err_file_not_found
    FileError.OPEN_FAILED -> R.string.err_file_open
    FileError.PERMISSION_DENIED -> R.string.err_file_permission
}

/**
 * Testo dell'elemento in lavorazione di un lavoro in background.
 * @param state JobState - stato del lavoro.
 * @param prices Boolean - true per l'aggiornamento prezzi, false per il catalogo.
 * @return String - descrizione breve, es. "cerco \"Mawile\"" o "Mawile GX · Holo".
 */
@Composable
fun jobProgressText(state: JobState, prices: Boolean): String {
    if (state.waitingForNetwork) return stringResource(R.string.job_waiting)
    if (prices) {
        when (PriceStep.entries.firstOrNull { it.name == state.phase }) {
            PriceStep.DOWNLOAD_GUIDE -> return stringResource(R.string.job_download_guide)
            PriceStep.READ_GUIDE -> return stringResource(R.string.job_read_guide)
            PriceStep.DOWNLOAD_CATALOG -> return stringResource(R.string.job_download_catalog)
            PriceStep.READ_CATALOG -> return stringResource(R.string.job_read_catalog)
            PriceStep.LOAD_CARDTRADER -> return stringResource(R.string.job_load_cardtrader)
            null -> Unit
        }
    }
    val label = state.label ?: return stringResource(R.string.job_starting)
    if (prices) {
        val variant = VariantType.fromName(state.phase)
        val item = if (variant != null) stringResource(R.string.job_price_item, label, stringResource(variant.labelRes())) else label
        return stringResource(R.string.job_item, item, state.done + 1, state.total)
    }
    return when (SyncPhase.entries.firstOrNull { it.name == state.phase }) {
        SyncPhase.SEARCH -> stringResource(R.string.job_search, label)
        SyncPhase.IMAGES -> stringResource(R.string.job_images, state.done + 1, state.total, label)
        SyncPhase.IMAGES_CARDTRADER -> stringResource(R.string.job_images_cardtrader, state.done + 1, state.total, label)
        SyncPhase.CARDMARKET -> stringResource(R.string.job_search_cardmarket, label)
        SyncPhase.CARDMARKET_DOWNLOAD -> stringResource(R.string.job_download_catalog)
        SyncPhase.CARDMARKET_READ -> stringResource(R.string.job_read_catalog)
        else -> stringResource(R.string.job_item, label, state.done + 1, state.total)
    }
}

/**
 * Riepilogo dell'ultimo sync del catalogo.
 * @param state JobState? - stato del lavoro.
 * @return String? - riepilogo, oppure null se il lavoro non è concluso.
 */
@Composable
fun catalogResultText(state: JobState?): String? {
    if (state == null || state.running || state.output.isEmpty()) return null
    val cards = state.output[WorkKeys.CARDS] as? Int ?: 0
    val images = state.output[WorkKeys.IMAGES] as? Int ?: 0
    val errors = state.output[WorkKeys.ERRORS] as? Int ?: 0
    return if (errors > 0) stringResource(R.string.catalog_done_errors, cards, images, errors) else stringResource(R.string.catalog_done, cards, images)
}

/**
 * Riepilogo di un aggiornamento prezzi registrato.
 * @param run PriceRunEntity - run.
 * @return String - conteggi oppure motivo dell'interruzione.
 */
@Composable
fun runSummaryText(run: PriceRunEntity): String {
    if (run.finishedAt == null) return stringResource(R.string.run_in_progress)
    return when (AbortReason.entries.firstOrNull { it.name == run.summary }) {
        AbortReason.SOURCE_PAUSED -> stringResource(R.string.abort_source_paused)
        AbortReason.OFFLINE -> stringResource(R.string.abort_offline)
        null -> stringResource(R.string.prices_done, run.okCount, run.fallbackCount, run.failCount)
    }
}
