package com.locapeer.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.locapeer.geofence.GeofenceListScreen
import com.locapeer.invite.InviteScreen
import com.locapeer.invite.ScanScreen
import com.locapeer.map.MapScreen
import com.locapeer.messaging.ChatScreen
import com.locapeer.messaging.ConversationListScreen
import com.locapeer.settings.SettingsScreen

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Map : Screen("map", "Map", Icons.Default.Map)
    object Messages : Screen("messages", "Messages", Icons.Default.Message)
    object Invite : Screen("invite", "Share", Icons.Default.QrCode)
    object Scan : Screen("scan", "Scan", Icons.Default.LocationOn)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

private val bottomNavItems = listOf(
    Screen.Map,
    Screen.Messages,
    Screen.Invite,
    Screen.Scan,
    Screen.Settings
)

@Composable
fun LocaPeerNavHost() {
    val navController = rememberNavController()
    val backstackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backstackEntry?.destination?.route

    val showBottomBar = bottomNavItems.any { currentRoute == it.route }

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
                            label = { Text(screen.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Map.route
        ) {
            composable(Screen.Map.route) {
                MapScreen(
                    onNavigateToChat = { peerId ->
                        navController.navigate("chat/$peerId/Unknown")
                    }
                )
            }
            composable(Screen.Messages.route) {
                ConversationListScreen(
                    onOpenChat = { peerId ->
                        navController.navigate("chat/$peerId/Chat")
                    }
                )
            }
            composable(Screen.Invite.route) {
                InviteScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(Screen.Scan.route) {
                ScanScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToGeofences = { navController.navigate("geofences") }
                )
            }
            composable(
                route = "chat/{peerId}/{peerName}",
                arguments = listOf(
                    navArgument("peerId") { type = NavType.StringType },
                    navArgument("peerName") { type = NavType.StringType }
                )
            ) { entry ->
                ChatScreen(
                    peerId = entry.arguments?.getString("peerId") ?: "",
                    peerName = entry.arguments?.getString("peerName") ?: "",
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("geofences") {
                GeofenceListScreen(onNavigateBack = { navController.popBackStack() })
            }
        }
    }
}
