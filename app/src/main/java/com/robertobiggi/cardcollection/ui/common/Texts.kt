package com.robertobiggi.cardcollection.ui.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.robertobiggi.cardcollection.R
import com.robertobiggi.cardcollection.core.backup.ImportError
import com.robertobiggi.cardcollection.core.image.CatalogImageSources
import com.robertobiggi.cardcollection.core.image.ImageFileNames
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.core.model.VariantType
import com.robertobiggi.cardcollection.data.image.ImageStore
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
 * Text to show to the user, resolved in the current language only at display time.
 * @property id string resource.
 * @property args format arguments.
 */
class UiText(@StringRes val id: Int, vararg val args: Any) {
    /**
     * Resolves the text with the context's resources (which follow the chosen language).
     * @param context Context - context of an activity.
     * @return String - translated text.
     */
    fun resolve(context: Context): String = context.getString(id, *args)
}

/**
 * Name of the catalog language.
 * @receiver Language - language.
 * @return Int - string resource of the name.
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
 * Short code of the language for chips and rows (JP for Japanese, as on the cards).
 * @receiver Language - language.
 * @return String - e.g. "EN", "IT", "JP", "ZH-TW".
 */
fun Language.shortLabel(): String = if (this == Language.JA) "JP" else code.uppercase()

/**
 * Name of the variant.
 * @receiver VariantType - variant.
 * @return Int - string resource.
 */
@StringRes
fun VariantType.labelRes(): Int = when (this) {
    VariantType.NORMAL -> R.string.variant_normal
    VariantType.HOLO -> R.string.variant_holo
    VariantType.REVERSE -> R.string.variant_reverse
    VariantType.POKE_BALL -> R.string.variant_poke_ball
    VariantType.MASTER_BALL -> R.string.variant_master_ball
    VariantType.ENERGY_REVERSE -> R.string.variant_energy_reverse
    VariantType.FIRST_EDITION -> R.string.variant_first_edition
    VariantType.W_PROMO -> R.string.variant_w_promo
    VariantType.SHADOWLESS -> R.string.variant_shadowless
    VariantType.RED_CHEEK -> R.string.variant_red_cheek
    VariantType.POKETOUR_99 -> R.string.variant_poketour_99
    VariantType.JUMBO -> R.string.variant_jumbo
    VariantType.STAMPED -> R.string.variant_stamped
    VariantType.CARDMARKET_PRINT -> R.string.variant_cardmarket_print
}

/**
 * Name of a TCGdex stamp, with words capitalized ("chris-fulop" → "Chris Fulop", "staff+pre-release" → "Staff + Pre Release").
 * @param print String - stamp as saved.
 * @return String - name to show.
 */
fun stampName(print: String): String =
    print.split('+').joinToString(" + ") { part -> part.split('-').joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) } }

/**
 * Name of a card's variant: on holo-only cards it is the rarity (e.g. "Double Rare").
 * @param card CardUi - card.
 * @param variant VariantUi - variant.
 * @return String - name to show.
 */
@Composable
fun variantLabel(card: CardUi, variant: VariantUi): String = when (variant.type) {
    VariantType.STAMPED -> stampName(variant.print)
    VariantType.CARDMARKET_PRINT -> stringResource(R.string.variant_cardmarket_print, variant.print)
    else -> card.rarityLabelFor(variant) ?: stringResource(variant.type.labelRes())
}

/**
 * Message for the outcome of an action on a card's image.
 * @receiver ImageResult - outcome.
 * @return Int - string resource.
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
 * Message for invalid data.
 * @receiver InputError - error.
 * @return Int - string resource.
 */
@StringRes
fun InputError.messageRes(): Int = when (this) {
    InputError.NAME_EMPTY -> R.string.err_name_empty
    InputError.QUERY_INVALID -> R.string.err_query_invalid
    InputError.QUERY_DUPLICATE -> R.string.err_query_duplicate
}

/**
 * Message for an invalid file to import.
 * @receiver ImportError - error.
 * @return Int - string resource.
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
 * Message for a file access error.
 * @receiver FileError - error.
 * @return Int - string resource.
 */
@StringRes
fun FileError.messageRes(): Int = when (this) {
    FileError.NOT_FOUND -> R.string.err_file_not_found
    FileError.OPEN_FAILED -> R.string.err_file_open
    FileError.PERMISSION_DENIED -> R.string.err_file_permission
}

/**
 * Line with repeated attempts, images not downloaded and last error of the image download.
 * @param state JobState - state of the job.
 * @return String? - text, or null if there is nothing to say.
 */
@Composable
fun jobIssuesText(state: JobState): String? =
    if (state.retries == 0 && state.imagesFailed == 0) null else stringResource(R.string.job_images_issues, state.retries, state.imagesFailed, state.note ?: "-")

/**
 * Text of the item being processed by a background job.
 * @param state JobState - state of the job.
 * @param prices Boolean - true for the price update, false for the catalog.
 * @return String - short description, e.g. "searching \"Mawile\"" or "Mawile GX · Holo".
 */
@Composable
fun jobProgressText(state: JobState, prices: Boolean): String {
    if (state.waitingForNetwork) return stringResource(R.string.job_waiting)
    if (prices) {
        when (PriceStep.entries.firstOrNull { it.name == state.phase }) {
            PriceStep.DOWNLOAD_GUIDE -> return stringResource(R.string.job_download_guide)
            PriceStep.READ_GUIDE -> return stringResource(R.string.job_read_guide)
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
        SyncPhase.IMAGES_OTHER -> stringResource(R.string.job_images_other, state.done + 1, state.total, label)
        else -> stringResource(R.string.job_item, label, state.done + 1, state.total)
    }
}

/**
 * Where the image shown for a card comes from, read from the name of its file, e.g. "Image: Pokellector". Null when the name does not say
 * (images saved by earlier versions or imported from a backup) or the card has no image.
 * @param path String? - path of the image file.
 * @return String? - the text to show.
 */
@Composable
fun imageSourceLabel(path: String?): String? {
    val id = path?.let { ImageFileNames.sourceOf(it.substringAfterLast('/')) } ?: return null
    val name = when (id) {
        ImageFileNames.MANUAL -> stringResource(R.string.image_source_manual)
        ImageStore.TCGDEX_SOURCE -> "TCGdex"
        ImageStore.CARDTRADER_SOURCE -> "CardTrader"
        else -> CatalogImageSources.ALL.firstOrNull { it.id == id }?.displayName ?: return null
    }
    return stringResource(R.string.image_source_label, name)
}

/**
 * Summary of the last catalog sync.
 * @param state JobState? - state of the job.
 * @return String? - summary, or null if the job is not finished.
 */
@Composable
fun catalogResultText(state: JobState?): String? {
    if (state == null || state.running || state.output.isEmpty()) return null
    val cards = state.output[WorkKeys.CARDS] as? Int ?: 0
    val images = state.output[WorkKeys.IMAGES] as? Int ?: 0
    val errors = state.output[WorkKeys.ERRORS] as? Int ?: 0
    if (state.output[WorkKeys.NO_CATALOG] == true) return stringResource(R.string.msg_no_catalog)
    val emptySets = state.output[WorkKeys.EMPTY_SETS] as? Int ?: 0
    val done = if (errors > 0) stringResource(R.string.catalog_done_errors, cards, images, errors) else stringResource(R.string.catalog_done, cards, images)
    return if (emptySets > 0) done + " " + stringResource(R.string.catalog_empty_sets, emptySets) else done
}

/**
 * Summary of a recorded price update.
 * @param run PriceRunEntity - run.
 * @return String - counts or reason for the interruption.
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
