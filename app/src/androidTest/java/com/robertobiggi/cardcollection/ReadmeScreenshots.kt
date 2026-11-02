package com.robertobiggi.cardcollection

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.robertobiggi.cardcollection.core.model.Language
import com.robertobiggi.cardcollection.data.repo.CardUi
import com.robertobiggi.cardcollection.data.repo.CreateResult
import com.robertobiggi.cardcollection.ui.MainActivity
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.time.Duration.Companion.minutes

/**
 * Takes the screenshots of the README on a real device or emulator: creates a "Mawile" collection with the real
 * catalog, images and prices (network needed), marks some cards as owned, then opens the main screens in Italian.
 * The PNG files end up in the app's files/screenshots, from where the workflow screenshots.yml copies them to docs/screenshots.
 * It is not part of the normal CI: it only runs from that workflow.
 */
@RunWith(AndroidJUnit4::class)
class ReadmeScreenshots {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<CardCollectionApp>()
    private val container get() = app.container
    private val outDir = File(app.filesDir, "screenshots")

    /** Italian resources, used to find buttons and tabs by their text. */
    private val italian: Context = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.ITALIAN) })

    /**
     * Collection prepared for the screenshots.
     * @property collectionId id of the collection.
     * @property cardId card shown in the detail screenshots.
     */
    private data class Seeded(val collectionId: Long, val cardId: String)

    /**
     * Prepares the data, then saves one screenshot per screen.
     * @return Unit
     */
    @Test
    fun captureReadmeScreens() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("it"))
        }
        val seeded = runBlocking { withTimeout(20.minutes) { seed() } }
        outDir.deleteRecursively()
        outDir.mkdirs()

        ActivityScenario.launch(MainActivity::class.java).use {
            settle()
            shot("01-collezioni")
            compose.onAllNodesWithText(COLLECTION_NAME).onFirst().performClick()
            settle()
            shot("02-carte")
            openTab(R.string.tab_missing)
            shot("03-mancanti")
            openTab(R.string.tab_stats)
            shot("04-statistiche")
            openTab(R.string.tab_manage)
            shot("05-gestione")
        }

        val detail = Intent(app, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_CARD_ID, seeded.cardId)
            .putExtra(MainActivity.EXTRA_COLLECTION_ID, seeded.collectionId)
        ActivityScenario.launch<MainActivity>(detail).use {
            settle()
            shot("06-dettaglio")
            // The alert is on the last variant: right above the "remove from collection" button.
            compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text(R.string.remove_from_collection)))
            compose.onAllNodesWithText(text(R.string.alert_button)).onLast().performClick()
            settle()
            compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text(R.string.remove_from_collection)))
            settle()
            shot("07-avviso-prezzo")
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            settle()
            compose.onNode(hasContentDescription(text(R.string.home_settings))).performClick()
            settle()
            shot("08-impostazioni")
        }
    }

    /**
     * Creates the collection with real data: catalog and images from the card database and TCGdex, prices from
     * Cardmarket; about two thirds of the variants are marked as owned and one card gets a price alert.
     * @return Seeded - collection and card to show.
     */
    private suspend fun seed(): Seeded {
        container.catalogStore.load()
        container.catalogStore.info.filterNotNull().first()
        val id = when (val created = container.collection.createCollection(COLLECTION_NAME, listOf(Language.EN), COLLECTION_NAME)) {
            is CreateResult.Created -> created.id
            is CreateResult.Invalid -> error("Collezione non creata: ${created.error}")
        }
        container.catalog.sync(id) { }
        container.prices.runPriceCheck(id) { }

        val library = container.collection.library(id).first().sortedBy { it.printedCode }
        check(library.isNotEmpty()) { "Catalogo vuoto: il database delle carte non è nell'APK?" }
        library.forEachIndexed { i, card ->
            card.variants.forEachIndexed { j, variant ->
                if ((i + j) % 3 != 0) container.collection.changeQuantity(id, variant.id, if ((i + j) % 7 == 0) 2 else 1)
            }
        }

        val shown = library.filter { it.card.imageHighPath != null || it.card.imageLowPath != null }
            .maxByOrNull { bestEur(it) } ?: library.first()
        container.settings.setPriceAlertsEnabled(true)
        val variant = shown.variants.last()
        val target = bestEur(shown).takeIf { it > 0 }?.let { (it * 0.8).toInt().coerceAtLeast(1).toDouble() }
        container.prices.saveAlert(variant.id, notifyChanges = true, changePercent = 10, targetEur = target)
        return Seeded(id, shown.card.id)
    }

    /**
     * Highest Cardmarket price among the variants of a card.
     * @param card CardUi - card.
     * @return Double - price in euro, 0 if there is none.
     */
    private fun bestEur(card: CardUi): Double = card.variants.maxOfOrNull { it.latestEur?.eur ?: 0.0 } ?: 0.0

    /**
     * Opens a tab of the bottom bar.
     * @param label Int - string resource of the tab label.
     * @return Unit
     */
    private fun openTab(label: Int) {
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(text(label))
        compose.onNode(tab).performClick()
        settle()
    }

    /**
     * Italian text of a string resource.
     * @param id Int - string resource.
     * @return String - text.
     */
    private fun text(id: Int): String = italian.getString(id)

    /**
     * Waits for the interface to be idle and for the images (loaded asynchronously by Coil) to appear.
     * @return Unit
     */
    private fun settle() {
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
    }

    /**
     * Saves the screen as PNG, scaled down to [WIDTH_PX] wide to keep the repository light.
     * @param name String - file name without extension (fixed by this test).
     * @return Unit
     */
    private fun shot(name: String) {
        val full = compose.onRoot().captureToImage().asAndroidBitmap()
        val height = full.height * WIDTH_PX / full.width
        val scaled = Bitmap.createScaledBitmap(full, WIDTH_PX, height, true)
        File(outDir, "$name.png").outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val COLLECTION_NAME = "Mawile"
        const val SETTLE_MS = 2_500L
        const val WIDTH_PX = 540
    }
}
