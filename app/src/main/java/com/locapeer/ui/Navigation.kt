package com.locapeer.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.automirrored.filled.Message
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.locapeer.NavTarget
import com.locapeer.about.AboutScreen
import com.locapeer.about.AboutViewModel
import com.locapeer.geofence.GeofenceListScreen
import com.locapeer.invite.InviteScreen
import com.locapeer.map.MapScreen
import com.locapeer.messaging.ChatScreen
import com.locapeer.messaging.ConversationListScreen
import com.locapeer.proximity.ProximityAlertsScreen
import com.locapeer.history.HistoryReportScreen
import com.locapeer.settings.AppPreferences
import com.locapeer.settings.SettingsScreen
import com.locapeer.contacts.ContactsScreen
import com.locapeer.sharing.PeerSharingScreen
import com.locapeer.sharing.ScheduleScreen
import javax.inject.Inject
import javax.inject.Singleton

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Map      : Screen("map",      "Map",      Icons.Default.Map)
    object Messages : Screen("messages", "Messages", Icons.AutoMirrored.Filled.Message)
    object Contacts : Screen("contacts", "Contacts", Icons.Default.People)
    object Invite   : Screen("invite",   "QR",       Icons.Default.QrCode)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

/** All tabs that can appear in the bottom nav, in their canonical order. */
val ALL_NAV_SCREENS: List<Screen> = listOf(
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
    onNavTargetConsumed: () -> Unit = {},
    prefs: AppPreferences
) {
    val settings by prefs.settings.collectAsState(initial = null)
    val navController = rememberNavController()
    val backstackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backstackEntry?.destination?.route

    val bottomNavItems = remember(settings?.navTabIds) {
        val ids = settings?.navTabIds ?: listOf("map", "messages", "contacts", "invite", "settings")
        val screenByRoute = ALL_NAV_SCREENS.associateBy { it.route }
        // Always ensure Map is present
        val ordered = ids.mapNotNull { screenByRoute[it] }
        if (ordered.none { it.route == Screen.Map.route })
            listOf(Screen.Map) + ordered else ordered
    }

    val startDestination = remember(settings?.startRoute, bottomNavItems) {
        val preferred = settings?.startRoute ?: Screen.Map.route
        // Fall back to Map if the preferred start tab is no longer active
        if (bottomNavItems.any { it.route == preferred }) preferred else Screen.Map.route
    }

    val showBottomBar = bottomNavItems.any { currentRoute?.substringBefore('?') == it.route }

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
                navController.navigate("${Screen.Invite.route}?inviteData=$data")
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
                            selected = currentRoute?.substringBefore('?') == screen.route,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(startDestination) { saveState = true }
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
            startDestination = startDestination,
            modifier = Modifier.padding(padding),
            enterTransition = { fadeEnter },
            exitTransition = { fadeExit },
            popEnterTransition = { fadeEnter },
            popExitTransition = { fadeExit }
        ) {
            composable(
                route = "${Screen.Map.route}?lat={lat}&lng={lng}",
                arguments = listOf(
                    navArgument("lat") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("lng") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) {
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
            composable(
                route = "${Screen.Invite.route}?inviteData={inviteData}",
                arguments = listOf(navArgument("inviteData") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                })
            ) { entry ->
                InviteScreen(
                    onNavigateBack = { navController.popBackStack() },
                    inviteData = entry.arguments?.getString("inviteData")
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToPeerSharing = { peerId, peerName ->
                        navController.navigate("peer-sharing/$peerId/${peerName.ifBlank { "Person" }}")
                    },
                    onNavigateToAbout = { navController.navigate("about") },
                    onNavigateToCustomizeNav = { navController.navigate("customize-nav") },
                    onNavigateToGlobalSchedule = { navController.navigate("schedule?scope=global") }
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
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToMap = { lat, lng ->
                        navController.navigate("${Screen.Map.route}?lat=$lat&lng=$lng") {
                            popUpTo(startDestination) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(
                "geofences?peerId={peerId}",
                arguments = listOf(navArgument("peerId") { type = NavType.StringType; nullable = true; defaultValue = null }),
                enterTransition = { slideEnter },
                exitTransition = { slideExit },
                popEnterTransition = { slidePopEnter },
                popExitTransition = { slidePopExit }
            ) { entry ->
                GeofenceListScreen(
                    peerId = entry.arguments?.getString("peerId"),
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(
                "history-report?peerId={peerId}",
                arguments = listOf(navArgument("peerId") { type = NavType.StringType; nullable = true; defaultValue = null }),
                enterTransition = { slideEnter },
                exitTransition = { slideExit },
                popEnterTransition = { slidePopEnter },
                popExitTransition = { slidePopExit }
            ) { entry ->
                HistoryReportScreen(
                    peerId = entry.arguments?.getString("peerId"),
                    onNavigateBack = { navController.popBackStack() }
                )
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
                val peerId = entry.arguments?.getString("peerId") ?: ""
                val peerName = entry.arguments?.getString("peerName") ?: ""
                PeerSharingScreen(
                    peerId = peerId,
                    peerName = peerName,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToSchedule = {
                        navController.navigate("schedule?scope=peer&peerId=$peerId&peerName=$peerName")
                    },
                    onNavigateToGeofences = { id ->
                        navController.navigate("geofences?peerId=$id")
                    },
                    onNavigateToHistory = { id ->
                        navController.navigate("history-report?peerId=$id")
                    }
                )
            }
            composable(
                route = "schedule?scope={scope}&peerId={peerId}&peerName={peerName}",
                arguments = listOf(
                    navArgument("scope") { type = NavType.StringType; defaultValue = "global" },
                    navArgument("peerId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("peerName") { type = NavType.StringType; defaultValue = "" }
                ),
                enterTransition = { slideEnter },
                exitTransition = { slideExit },
                popEnterTransition = { slidePopEnter },
                popExitTransition = { slidePopExit }
            ) {
                ScheduleScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(
                "customize-nav",
                enterTransition = { slideEnter },
                exitTransition = { slideExit },
                popEnterTransition = { slidePopEnter },
                popExitTransition = { slidePopExit }
            ) {
                CustomizeNavScreen(
                    prefs = prefs,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
