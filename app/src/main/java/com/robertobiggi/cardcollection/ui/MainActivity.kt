package com.robertobiggi.cardcollection.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.robertobiggi.cardcollection.R
import com.robertobiggi.cardcollection.ui.collection.CollectionScreen
import com.robertobiggi.cardcollection.ui.detail.DetailScreen
import com.robertobiggi.cardcollection.ui.missing.MissingScreen
import com.robertobiggi.cardcollection.ui.home.HomeScreen
import com.robertobiggi.cardcollection.ui.manage.ManageScreen
import com.robertobiggi.cardcollection.ui.settings.SettingsScreen
import com.robertobiggi.cardcollection.ui.stats.StatsScreen
import com.robertobiggi.cardcollection.ui.theme.CardCollectionTheme
import kotlinx.coroutines.flow.first

/**
 * Single activity: hosts the Compose navigation. Extends AppCompatActivity to apply the language
 * chosen in Settings also on Android versions before 13.
 */
class MainActivity : AppCompatActivity() {

    /** Card to open requested by a notification (collection and id), consumed by the navigation. */
    private val cardRequest = mutableStateOf<CardRequest?>(null)

    /**
     * Sets the Compose content to full screen.
     * @param savedInstanceState Bundle? - saved state.
     * @return Unit
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // After a rotation the intent is the same: the card has already been opened.
        if (savedInstanceState == null) cardRequest.value = cardRequestOf(intent)
        setContent { CardCollectionTheme { AppRoot(cardRequest) } }
    }

    /**
     * Notification tapped with the app already open: opens the indicated card.
     * @param intent Intent - intent of the notification.
     * @return Unit
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        cardRequestOf(intent)?.let { cardRequest.value = it }
    }

    companion object {
        /** Id of the card to open (price alerts). */
        const val EXTRA_CARD_ID = "com.robertobiggi.cardcollection.CARD_ID"

        /** Collection in which to open the card. */
        const val EXTRA_COLLECTION_ID = "com.robertobiggi.cardcollection.COLLECTION_ID"

        /**
         * Reads from the intent the card to open, with checks on the values (the intent may come from outside).
         * @param intent Intent? - intent received.
         * @return CardRequest? - card to open, or null if missing or not valid.
         */
        private fun cardRequestOf(intent: Intent?): CardRequest? {
            val cardId = intent?.getStringExtra(EXTRA_CARD_ID)?.takeIf { it.isNotBlank() && it.length <= MAX_CARD_ID } ?: return null
            val collectionId = intent.getLongExtra(EXTRA_COLLECTION_ID, -1L).takeIf { it > 0 } ?: return null
            return CardRequest(collectionId, cardId)
        }

        private const val MAX_CARD_ID = 200
    }
}

/** Card to open: [collectionId] is the collection in which to show it. */
data class CardRequest(val collectionId: Long, val cardId: String)

/** Bottom navigation tabs inside a collection. */
private enum class Tab(val segment: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    CARDS("cards", R.string.tab_cards, Icons.Filled.GridView),
    MISSING("missing", R.string.tab_missing, Icons.Filled.Star),
    STATS("stats", R.string.tab_stats, Icons.Filled.BarChart),
    MANAGE("manage", R.string.tab_manage, Icons.Filled.Settings),
}

/**
 * Root of the interface: list of collections, tabs of the open collection, snackbar.
 * @param cardRequest MutableState<CardRequest?> - card to open requested by a notification; reset after opening.
 * @return Unit
 */
@Composable
private fun AppRoot(cardRequest: MutableState<CardRequest?>) {
    val vm: MainViewModel = viewModel(factory = MainViewModel.Factory)
    val nav = rememberNavController()
    // Notification of a price alert: opens the card in its collection.
    val request = cardRequest.value
    LaunchedEffect(request) {
        if (request == null) return@LaunchedEffect
        // Waits for the NavHost to set the graph (navigating earlier would close the app).
        nav.currentBackStackEntryFlow.first()
        vm.open(request.collectionId)
        nav.navigate("c/${request.collectionId}/${Tab.CARDS.segment}") { popUpTo("home") }
        nav.navigate("c/${request.collectionId}/card/${Uri.encode(request.cardId)}")
        cardRequest.value = null
    }
    val snackbar = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val currentCid = backStack?.arguments?.getLong("cid")

    val context = LocalContext.current
    RequestNotificationPermission()
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it.resolve(context)) } }

    Scaffold(
        // The top inset (status bar) is left to the TopAppBar of each screen: applying it here too left an empty band under the status bar.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            val tab = Tab.entries.firstOrNull { route == "c/{cid}/${it.segment}" }
            if (tab != null && currentCid != null) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = {
                                // No saveState/restoreState: the tab routes share one pattern across collections, so the
                                // restored entry could belong to a previously opened collection (wrong stats/cards).
                                nav.navigate("c/$currentCid/${t.segment}") {
                                    popUpTo("c/{cid}/${Tab.CARDS.segment}")
                                    launchSingleTop = true
                                }
                            },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(stringResource(t.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val cidArg = listOf(navArgument("cid") { type = NavType.LongType })
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") {
                HomeScreen(vm, onOpen = { id -> vm.open(id); nav.navigate("c/$id/${Tab.CARDS.segment}") }, onSettings = { nav.navigate("settings") })
            }
            composable("settings") { SettingsScreen(vm, onBack = { nav.popBackStack() }) }
            Tab.entries.forEach { tab ->
                composable("c/{cid}/${tab.segment}", arguments = cidArg) { entry ->
                    val cid = entry.arguments?.getLong("cid") ?: return@composable
                    LaunchedEffect(cid) { vm.open(cid) }
                    val openCard: (String) -> Unit = { id -> nav.navigate("c/$cid/card/${Uri.encode(id)}") }
                    val toHome: () -> Unit = { nav.popBackStack("home", inclusive = false) }
                    when (tab) {
                        Tab.CARDS -> CollectionScreen(vm, openCard, onHome = toHome)
                        Tab.MISSING -> MissingScreen(vm, openCard)
                        Tab.STATS -> StatsScreen(vm)
                        Tab.MANAGE -> ManageScreen(vm)
                    }
                }
            }
            composable(
                "c/{cid}/card/{cardId}",
                arguments = cidArg + navArgument("cardId") { type = NavType.StringType },
            ) { entry ->
                val cid = entry.arguments?.getLong("cid") ?: return@composable
                LaunchedEffect(cid) { vm.open(cid) }
                DetailScreen(vm, entry.arguments?.getString("cardId").orEmpty(), onBack = { nav.popBackStack() })
            }
        }
    }
}

/**
 * Asks for the permission for progress notifications (Android 13+), once at startup.
 * If the user refuses, updates work anyway without a notification.
 * @return Unit
 */
@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
