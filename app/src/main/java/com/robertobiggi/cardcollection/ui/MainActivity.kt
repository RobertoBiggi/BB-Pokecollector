package com.robertobiggi.cardcollection.ui

import android.Manifest
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

/**
 * Unica activity: ospita la navigazione Compose. Estende AppCompatActivity per applicare la lingua
 * scelta in Impostazioni anche sulle versioni di Android precedenti alla 13.
 */
class MainActivity : AppCompatActivity() {

    /**
     * Imposta il contenuto Compose a tutto schermo.
     * @param savedInstanceState Bundle? - stato salvato.
     * @return Unit
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CardCollectionTheme { AppRoot() } }
    }
}

/** Schede della navigazione in basso dentro una collezione. */
private enum class Tab(val segment: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    CARDS("cards", R.string.tab_cards, Icons.Filled.GridView),
    MISSING("missing", R.string.tab_missing, Icons.Filled.Star),
    STATS("stats", R.string.tab_stats, Icons.Filled.BarChart),
    MANAGE("manage", R.string.tab_manage, Icons.Filled.Settings),
}

/**
 * Radice dell'interfaccia: elenco collezioni, schede della collezione aperta, snackbar.
 * @return Unit
 */
@Composable
private fun AppRoot() {
    val vm: MainViewModel = viewModel(factory = MainViewModel.Factory)
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val currentCid = backStack?.arguments?.getLong("cid")

    val context = LocalContext.current
    RequestNotificationPermission()
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it.resolve(context)) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            val tab = Tab.entries.firstOrNull { route == "c/{cid}/${it.segment}" }
            if (tab != null && currentCid != null) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = {
                                nav.navigate("c/$currentCid/${t.segment}") {
                                    popUpTo("c/{cid}/${Tab.CARDS.segment}") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
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
 * Chiede il permesso per le notifiche di avanzamento (Android 13+), una volta all'avvio.
 * Se l'utente rifiuta, gli aggiornamenti funzionano comunque senza notifica.
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
