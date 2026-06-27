package com.locapeer.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import androidx.hilt.navigation.compose.hiltViewModel
import com.locapeer.NavTarget
import com.locapeer.about.AboutScreen
import com.locapeer.about.AboutViewModel
import com.locapeer.geofence.GeofenceListScreen
import com.locapeer.invite.InviteScreen
import com.locapeer.invite.ScanScreen
import com.locapeer.map.MapScreen
import com.locapeer.messaging.ChatScreen
import com.locapeer.messaging.ConversationListScreen
import com.locapeer.proximity.ProximityAlertsScreen
import com.locapeer.history.HistoryReportScreen
import com.locapeer.settings.SettingsScreen
import com.locapeer.contacts.ContactsScreen
import com.locapeer.sharing.PeerSharingScreen

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Map : Screen("map", "Map", Icons.Default.Map)
    object Messages : Screen("messages", "Messages", Icons.Default.Message)
    object Contacts : Screen("contacts", "Contacts", Icons.Default.People)
    object Invite : Screen("invite", "Share", Icons.Default.QrCode)
    object Scan : Screen("scan?inviteData={inviteData}", "Scan", Icons.Default.LocationOn)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

private val bottomNavItems = listOf(
    Screen.Map,
    Screen.Messages,
    Screen.Contacts,
    Screen.Invite,
    Screen.Settings
)

private val fadeEnter = fadeIn(tween(220))
private val fadeExit = fadeOut(tween(180))
private val slideEnter = slideInHorizontally(tween(280)) { it / 3 } + fadeIn(tween(280))
private val slideExit = slideOutHorizontally(tween(250)) { -it / 3 } + fadeOut(tween(250))
private val slidePopEnter = slideInHorizontally(tween(280)) { -it / 3 } + fadeIn(tween(280))
private val slidePopExit = slideOutHorizontally(tween(250)) { it / 3 } + fadeOut(tween(250))

@Composable
fun LocaPeerNavHost(
    initialNavTarget: NavTarget? = null,
    onNavTargetConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val backstackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backstackEntry?.destination?.route

    val showBottomBar = bottomNavItems.any { currentRoute == it.route }

    // Deep-link from notification
    LaunchedEffect(initialNavTarget) {
        val target = initialNavTarget ?: return@LaunchedEffect
        when (target.route) {
            "chat" -> {
                val peerId = target.peerId ?: return@LaunchedEffect
                navController.navigate("chat/$peerId/${target.peerName.ifBlank { "Chat" }}")
            }
            "map" -> {
                navController.navigate(Screen.Map.route) {
                    popUpTo(Screen.Map.route) { inclusive = true }
                }
            }
            "scan" -> {
                val data = target.peerId ?: ""
                navController.navigate("scan?inviteData=$data")
            }
        }
        onNavTargetConsumed()
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { screen ->
                        NavigationBarItem(
                            selected = currentRoute == screen.route,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(Screen.Map.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(screen.icon, contentDescription = screen.label) },
                            label = { Text(screen.label, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Map.route,
            modifier = Modifier.padding(padding),
            enterTransition = { fadeEnter },
            exitTransition = { fadeExit },
            popEnterTransition = { fadeEnter },
            popExitTransition = { fadeExit }
        ) {
            composable(Screen.Map.route) {
                MapScreen(onNavigateToChat = { peerId, peerName ->
                    navController.navigate("chat/$peerId/${peerName.ifBlank { "Chat" }}")
                })
            }
            composable(Screen.Messages.route) {
                ConversationListScreen(onOpenChat = { peerId ->
                    navController.navigate("chat/$peerId/Chat")
                })
            }
            composable(Screen.Contacts.route) {
                ContactsScreen(
                    onNavigateToChat = { peerId, peerName ->
                        navController.navigate("chat/$peerId/${peerName.ifBlank { "Chat" }}")
                    },
                    onNavigateToSharingSettings = { peerId, peerName ->
                        navController.navigate("peer-sharing/$peerId/${peerName.ifBlank { "Contact" }}")
                    }
                )
            }
            composable(Screen.Invite.route) {
                InviteScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(
                Screen.Scan.route,
                arguments = listOf(navArgument("inviteData") {
                    type = NavType.StringType
                    nullable = true
                })
            ) { entry ->
                ScanScreen(
                    inviteData = entry.arguments?.getString("inviteData"),
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToGeofences = { navController.navigate("geofences") },
                    onNavigateToProximityAlerts = { navController.navigate("proximity-alerts") },
                    onNavigateToPeerSharing = { peerId, peerName ->
                        navController.navigate("peer-sharing/$peerId/${peerName.ifBlank { "Person" }}")
                    },
                    onNavigateToHistoryReport = { navController.navigate("history-report") },
                    onNavigateToAbout = { navController.navigate("about") }
                )
            }
            composable(
                route = "chat/{peerId}/{peerName}",
                arguments = listOf(
                    navArgument("peerId") { type = NavType.StringType },
                    navArgument("peerName") { type = NavType.StringType }
                ),
                enterTransition = { slideEnter },
                exitTransition = { slideExit },
                popEnterTransition = { slidePopEnter },
                popExitTransition = { slidePopExit }
            ) { entry ->
                ChatScreen(
                    peerId = entry.arguments?.getString("peerId") ?: "",
                    peerName = entry.arguments?.getString("peerName") ?: "",
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(
                "geofences",
                enterTransition = { slideEnter },
                exitTransition = { slideExit },
                popEnterTransition = { slidePopEnter },
                popExitTransition = { slidePopExit }
            ) {
                GeofenceListScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(
                "proximity-alerts",
                enterTransition = { slideEnter },
                exitTransition = { slideExit },
                popEnterTransition = { slidePopEnter },
                popExitTransition = { slidePopExit }
            ) {
                ProximityAlertsScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(
                "history-report",
                enterTransition = { slideEnter },
                exitTransition = { slideExit },
                popEnterTransition = { slidePopEnter },
                popExitTransition = { slidePopExit }
            ) {
                HistoryReportScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(
                "about",
                enterTransition = { slideEnter },
                exitTransition = { slideExit },
                popEnterTransition = { slidePopEnter },
                popExitTransition = { slidePopExit }
            ) {
                val aboutVm: AboutViewModel = hiltViewModel()
                AboutScreen(
                    relayClient = aboutVm.relayClient,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "peer-sharing/{peerId}/{peerName}",
                arguments = listOf(
                    navArgument("peerId") { type = NavType.StringType },
                    navArgument("peerName") { type = NavType.StringType }
                ),
                enterTransition = { slideEnter },
                exitTransition = { slideExit },
                popEnterTransition = { slidePopEnter },
                popExitTransition = { slidePopExit }
            ) { entry ->
                PeerSharingScreen(
                    peerId = entry.arguments?.getString("peerId") ?: "",
                    peerName = entry.arguments?.getString("peerName") ?: "",
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
