package com.locapeer.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.annotation.StringRes
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import android.net.Uri
import com.locapeer.R
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.locapeer.NavTarget
import com.locapeer.about.AboutScreen
import com.locapeer.about.AboutViewModel
import com.locapeer.geofence.GeofenceListScreen
import com.locapeer.invite.IncomingShareRequestScreen
import com.locapeer.invite.InviteScreen
import com.locapeer.invite.PendingRequestsScreen
import com.locapeer.map.MapScreen
import com.locapeer.messaging.ChatScreen
import com.locapeer.messaging.ConversationListScreen
import com.locapeer.proximity.ProximityAlertsScreen
import com.locapeer.history.HistoryReportScreen
import com.locapeer.settings.AppPreferences
import com.locapeer.settings.PermissionsScreen
import com.locapeer.settings.SettingsScreen
import com.locapeer.contacts.ContactsScreen
import com.locapeer.circles.CircleEditScreen
import com.locapeer.circles.CircleListScreen
import com.locapeer.circles.GroupChatScreen
import com.locapeer.sharing.PeerSharingScreen
import com.locapeer.sharing.ScheduleScreen
import javax.inject.Inject
import javax.inject.Singleton

sealed class Screen(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    object Map      : Screen("map",          R.string.tab_map,      Icons.Default.Map)
    object Messages : Screen("messages",     R.string.tab_messages, Icons.AutoMirrored.Filled.Message)
    object Contacts : Screen("contacts",     R.string.tab_contacts, Icons.Default.People)
    object Invite   : Screen("invite",       R.string.contacts_cd_qr_invite, Icons.Default.QrCode)
    object Settings : Screen("settings",     R.string.tab_settings, Icons.Default.Settings)
    object History  : Screen("history-tab",  R.string.tab_history,  Icons.Default.History)
}

/** All tabs that can appear in the bottom nav, in their canonical order. */
val ALL_NAV_SCREENS: List<Screen> = listOf(
    Screen.Map,
    Screen.Messages,
    Screen.Contacts,
    Screen.Invite,
    Screen.Settings,
    Screen.History
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
        val ids = settings?.navTabIds ?: listOf("map", "messages", "history-tab", "contacts", "invite", "settings")
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
                navController.navigate("chat/$peerId/${Uri.encode(target.peerName.ifBlank { "Chat" })}")
            }
            "map" -> {
                val peerId = target.peerId
                if (peerId != null) {
                    navController.navigate("${Screen.Map.route}?peerId=$peerId")
                } else {
                    navController.navigate(Screen.Map.route) {
                        popUpTo(Screen.Map.route) { inclusive = true }
                    }
                }
            }
            "scan" -> {
                val data = target.peerId ?: ""
                navController.navigate("${Screen.Invite.route}?inviteData=$data")
            }
            "share-request" -> {
                val pubkey = target.peerId ?: return@LaunchedEffect
                val name = Uri.encode(target.peerName.ifBlank { "Unknown" })
                val relay = Uri.encode(target.extra ?: "")
                val requestedRole = Uri.encode(target.requestedRole ?: "")
                navController.navigate("share-request?pubkey=$pubkey&name=$name&relay=$relay&isRoleChange=${target.isRoleChange}&requestedRole=$requestedRole")
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
                            icon = { Icon(screen.icon, contentDescription = stringResource(screen.labelRes)) },
                            label = { Text(stringResource(screen.labelRes), style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        // Apply (and consume) only the bottom padding: screens draw their own top bars
        // under the status bar, and consuming the bottom inset stops nested Scaffolds
        // (e.g. the history screen) from re-adding it as a white gap above the nav bar.
        val bottomPadding = padding.calculateBottomPadding()
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .padding(bottom = bottomPadding)
                .consumeWindowInsets(PaddingValues(bottom = bottomPadding)),
            enterTransition = { fadeEnter },
            exitTransition = { fadeExit },
            popEnterTransition = { fadeEnter },
            popExitTransition = { fadeExit }
        ) {
            composable(
                route = "${Screen.Map.route}?lat={lat}&lng={lng}&peerId={peerId}",
                arguments = listOf(
                    navArgument("lat") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("lng") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("peerId") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) {
                MapScreen(
                    onNavigateToChat = { peerId, peerName ->
                        navController.navigate("chat/$peerId/${Uri.encode(peerName.ifBlank { "Chat" })}")
                    },
                    onNavigateToHistory = { peerId ->
                        navController.navigate("history-report?peerId=$peerId")
                    }
                )
            }
            composable(Screen.Messages.route) {
                ConversationListScreen(
                    onOpenChat = { peerId, peerName ->
                        navController.navigate("chat/$peerId/${Uri.encode(peerName.ifBlank { "Chat" })}")
                    },
                    onOpenGroup = { circleId -> navController.navigate("groupchat/$circleId") },
                    onCreateCircle = { navController.navigate("circle-edit") }
                )
            }
            composable(Screen.Contacts.route) {
                ContactsScreen(
                    onNavigateToChat = { peerId, peerName ->
                        navController.navigate("chat/$peerId/${Uri.encode(peerName.ifBlank { "Chat" })}")
                    },
                    onNavigateToSharingSettings = { peerId, peerName ->
                        navController.navigate("peer-sharing/$peerId/${Uri.encode(peerName.ifBlank { "Contact" })}")
                    },
                    onNavigateToPendingRequests = {
                        navController.navigate("pending-requests")
                    },
                    onNavigateToHistory = { peerId ->
                        navController.navigate("history-report?peerId=$peerId")
                    },
                    onNavigateToInvite = {
                        navController.navigate(Screen.Invite.route) {
                            popUpTo(startDestination) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
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
                        navController.navigate("peer-sharing/$peerId/${Uri.encode(peerName.ifBlank { "Person" })}")
                    },
                    onNavigateToAbout = { navController.navigate("about") },
                    onNavigateToCustomizeNav = { navController.navigate("customize-nav") },
                    onNavigateToGlobalSchedule = { navController.navigate("schedule?scope=global") },
                    onNavigateToGeofences = { navController.navigate("geofences") },
                    onNavigateToMyHistory = { pubkeyHex ->
                        navController.navigate("history-report?peerId=$pubkeyHex")
                    },
                    onNavigateToPermissions = { navController.navigate("permissions") }
                )
            }
            composable(
                "permissions",
                enterTransition = { slideEnter },
                exitTransition = { slideExit },
                popEnterTransition = { slidePopEnter },
                popExitTransition = { slidePopExit }
            ) {
                PermissionsScreen(onNavigateBack = { navController.popBackStack() })
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
                "circles",
                enterTransition = { slideEnter },
                exitTransition = { slideExit },
                popEnterTransition = { slidePopEnter },
                popExitTransition = { slidePopExit }
            ) {
                CircleListScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onOpenGroup = { circleId -> navController.navigate("groupchat/$circleId") },
                    onCreate = { navController.navigate("circle-edit") },
                    onEdit = { circleId -> navController.navigate("circle-edit?circleId=$circleId") }
                )
            }
            composable(
                route = "circle-edit?circleId={circleId}",
                arguments = listOf(navArgument("circleId") { type = NavType.StringType; nullable = true; defaultValue = null }),
                enterTransition = { slideEnter },
                exitTransition = { slideExit },
                popEnterTransition = { slidePopEnter },
                popExitTransition = { slidePopExit }
            ) { entry ->
                CircleEditScreen(
                    circleId = entry.arguments?.getString("circleId"),
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "groupchat/{circleId}",
                arguments = listOf(navArgument("circleId") { type = NavType.StringType }),
                enterTransition = { slideEnter },
                exitTransition = { slideExit },
                popEnterTransition = { slidePopEnter },
                popExitTransition = { slidePopExit }
            ) { entry ->
                GroupChatScreen(
                    circleId = entry.arguments?.getString("circleId") ?: "",
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToMap = { lat, lng ->
                        navController.navigate("${Screen.Map.route}?lat=$lat&lng=$lng") {
                            popUpTo(startDestination) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onManageMembers = { circleId -> navController.navigate("circle-edit?circleId=$circleId") }
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
            composable(Screen.History.route) {
                HistoryReportScreen(isOwnHistoryMode = true, onNavigateBack = null)
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
                    aboutVm = aboutVm,
                    onNavigateBack = { navController.popBackStack() },
                    versionName = com.locapeer.BuildConfig.VERSION_NAME,
                    versionCode = com.locapeer.BuildConfig.VERSION_CODE
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
                    onNavigateToMap = { lat, lng ->
                        navController.navigate("${Screen.Map.route}?lat=$lat&lng=$lng") {
                            popUpTo(startDestination) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToSchedule = {
                        navController.navigate("schedule?scope=peer&peerId=$peerId&peerName=${Uri.encode(peerName)}")
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
            composable(
                "pending-requests",
                enterTransition = { slideEnter },
                exitTransition = { slideExit },
                popEnterTransition = { slidePopEnter },
                popExitTransition = { slidePopExit }
            ) {
                PendingRequestsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onOpenRequest = { pubkey, name, relay, isRoleChange, requestedRole ->
                        val encodedName = android.net.Uri.encode(name)
                        val encodedRelay = android.net.Uri.encode(relay)
                        val encodedRole = android.net.Uri.encode(requestedRole ?: "")
                        navController.navigate("share-request?pubkey=$pubkey&name=$encodedName&relay=$encodedRelay&isRoleChange=$isRoleChange&requestedRole=$encodedRole")
                    }
                )
            }
            composable(
                route = "share-request?pubkey={pubkey}&name={name}&relay={relay}&isRoleChange={isRoleChange}&requestedRole={requestedRole}",
                arguments = listOf(
                    navArgument("pubkey") { type = NavType.StringType },
                    navArgument("name") { type = NavType.StringType; defaultValue = "" },
                    navArgument("relay") { type = NavType.StringType; defaultValue = "" },
                    navArgument("isRoleChange") { type = NavType.BoolType; defaultValue = false },
                    navArgument("requestedRole") { type = NavType.StringType; defaultValue = "" }
                ),
                enterTransition = { slideEnter },
                exitTransition = { slideExit },
                popEnterTransition = { slidePopEnter },
                popExitTransition = { slidePopExit }
            ) { entry ->
                val requestedRoleArg = entry.arguments?.getString("requestedRole")?.takeIf { it.isNotBlank() }
                IncomingShareRequestScreen(
                    senderPubkey = entry.arguments?.getString("pubkey") ?: "",
                    senderName = entry.arguments?.getString("name")?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.fallback_unknown),
                    senderRelay = entry.arguments?.getString("relay") ?: "",
                    isRoleChange = entry.arguments?.getBoolean("isRoleChange") ?: false,
                    requestedRole = requestedRoleArg,
                    onDone = { navController.popBackStack() }
                )
            }
        }
    }
}
