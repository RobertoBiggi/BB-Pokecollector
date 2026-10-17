package com.robertobiggi.cardcollection.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.robertobiggi.cardcollection.ui.collection.CollectionScreen
import com.robertobiggi.cardcollection.ui.detail.DetailScreen
import com.robertobiggi.cardcollection.ui.missing.MissingScreen
import com.robertobiggi.cardcollection.ui.settings.SettingsScreen
import com.robertobiggi.cardcollection.ui.stats.StatsScreen
import com.robertobiggi.cardcollection.ui.theme.CardCollectionTheme

/** Unica activity: ospita la navigazione Compose. */
class MainActivity : ComponentActivity() {

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

/** Schede della navigazione in basso. */
private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    COLLECTION("collection", "Collezione", Icons.Filled.GridView),
    MISSING("missing", "Mancanti", Icons.Filled.Star),
    STATS("stats", "Statistiche", Icons.Filled.BarChart),
    SETTINGS("settings", "Impostazioni", Icons.Filled.Settings),
}

/**
 * Radice dell'interfaccia: barra di navigazione, snackbar e grafo di navigazione.
 * @return Unit
 */
@Composable
private fun AppRoot() {
    val vm: MainViewModel = viewModel(factory = MainViewModel.Factory)
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    RequestNotificationPermission()
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (Tab.entries.any { it.route == route }) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(Tab.COLLECTION.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val openCard: (String) -> Unit = { id -> nav.navigate("card/${Uri.encode(id)}") }
        NavHost(nav, startDestination = Tab.COLLECTION.route, modifier = Modifier.padding(padding)) {
            composable(Tab.COLLECTION.route) { CollectionScreen(vm, openCard) }
            composable(Tab.MISSING.route) { MissingScreen(vm, openCard) }
            composable(Tab.STATS.route) { StatsScreen(vm) }
            composable(Tab.SETTINGS.route) { SettingsScreen(vm) }
            composable("card/{cardId}", arguments = listOf(navArgument("cardId") { type = NavType.StringType })) { entry ->
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
