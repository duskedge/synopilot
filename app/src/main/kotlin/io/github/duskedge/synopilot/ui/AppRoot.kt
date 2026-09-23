package io.github.duskedge.synopilot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.duskedge.synopilot.designsystem.SpTheme
import io.github.duskedge.synopilot.designsystem.component.SpBottomBar
import io.github.duskedge.synopilot.designsystem.component.SpNavItem
import io.github.duskedge.synopilot.ui.settings.SettingsScreen
import io.github.duskedge.synopilot.ui.update.UpdateDialog
import io.github.duskedge.synopilot.updater.UpdateManager
import io.github.duskedge.synopilot.updater.UpdateState
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import kotlin.reflect.KClass

@Serializable data object DashboardRoute
@Serializable data object ContainersRoute
@Serializable data object DownloadsRoute
@Serializable data object FilesRoute
@Serializable data object SettingsRoute

private data class Tab(val route: Any, val routeClass: KClass<*>, val item: SpNavItem)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val updateManager: UpdateManager = koinInject()
    val updateState by updateManager.state.collectAsStateWithLifecycle()
    val hasUpdate = updateState is UpdateState.Available || updateState is UpdateState.ReadyToInstall

    val tabs = listOf(
        Tab(DashboardRoute, DashboardRoute::class, SpNavItem("总览", Icons.Outlined.SpaceDashboard, Icons.Filled.SpaceDashboard)),
        Tab(ContainersRoute, ContainersRoute::class, SpNavItem("容器", Icons.Outlined.ViewInAr, Icons.Filled.ViewInAr)),
        Tab(DownloadsRoute, DownloadsRoute::class, SpNavItem("下载", Icons.Outlined.Download, Icons.Filled.Download)),
        Tab(FilesRoute, FilesRoute::class, SpNavItem("文件", Icons.Outlined.Folder, Icons.Filled.Folder)),
        Tab(SettingsRoute, SettingsRoute::class, SpNavItem("设置", Icons.Outlined.Settings, Icons.Filled.Settings, badge = hasUpdate)),
    )
    val backStack by nav.currentBackStackEntryAsState()
    val selected = tabs.indexOfFirst { tab -> backStack?.destination?.hasRoute(tab.routeClass) == true }.coerceAtLeast(0)

    Column(
        Modifier
            .fillMaxSize()
            .background(SpTheme.colors.bg),
    ) {
        TopBar()
        Box(Modifier.weight(1f)) {
            NavHost(navController = nav, startDestination = DashboardRoute) {
                composable<DashboardRoute> { DashboardPlaceholder() }
                composable<ContainersRoute> { ContainersPlaceholder() }
                composable<DownloadsRoute> { DownloadsPlaceholder() }
                composable<FilesRoute> { FilesPlaceholder() }
                composable<SettingsRoute> { SettingsScreen() }
            }
        }
        SpBottomBar(
            items = tabs.map { it.item },
            selectedIndex = selected,
            onSelect = { index ->
                nav.navigate(tabs[index].route) {
                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
        )
    }

    UpdateDialog(updateManager)
}

/** 顶栏：单行、无外框。M1 起这里换成设备切换按钮。 */
@Composable
private fun TopBar() {
    val c = SpTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(c.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Dns, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(17.dp))
        }
        Column {
            Text("SynoPilot", style = SpTheme.type.title, color = c.onSurface)
        }
    }
}
