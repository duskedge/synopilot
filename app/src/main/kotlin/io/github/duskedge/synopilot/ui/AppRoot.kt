package io.github.duskedge.synopilot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.duskedge.synopilot.data.AlertStore
import io.github.duskedge.synopilot.data.AppLinks
import io.github.duskedge.synopilot.data.ConnectionManager
import io.github.duskedge.synopilot.data.ConnectionState
import io.github.duskedge.synopilot.data.Device
import io.github.duskedge.synopilot.data.DeviceRepository
import io.github.duskedge.synopilot.designsystem.SpTheme
import io.github.duskedge.synopilot.designsystem.component.SpBottomBar
import io.github.duskedge.synopilot.designsystem.component.SpMessageBus
import io.github.duskedge.synopilot.designsystem.component.SpMessageHost
import io.github.duskedge.synopilot.designsystem.component.SpNavItem
import io.github.duskedge.synopilot.feature.containers.ContainersScreen
import io.github.duskedge.synopilot.feature.dashboard.DashboardScreen
import io.github.duskedge.synopilot.feature.downloads.DownloadersScreen
import io.github.duskedge.synopilot.feature.downloads.DownloadsScreen
import io.github.duskedge.synopilot.feature.files.FilesScreen
import io.github.duskedge.synopilot.feature.system.SystemPage
import io.github.duskedge.synopilot.feature.system.SystemPageScreen
import io.github.duskedge.synopilot.feature.system.SystemSettingsSections
import io.github.duskedge.synopilot.feature.onboarding.OnboardingScreen
import io.github.duskedge.synopilot.feature.settings.DeviceSwitcherSheet
import io.github.duskedge.synopilot.feature.settings.ServerAddressScreen
import io.github.duskedge.synopilot.feature.settings.SettingsScreen
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
@Serializable data object ServerAddressRoute
@Serializable data object DownloadersRoute
@Serializable data class SystemRoute(val page: SystemPage)
@Serializable data object AddDeviceRoute
@Serializable data class ReloginRoute(val deviceId: String)

/** 外部要求打开的页面（见 AppLinks） */
data class OpenRequest(val target: String, val seq: Int)

private data class Tab(val route: Any, val routeClass: KClass<*>, val item: SpNavItem)

@Composable
fun AppRoot(openRequest: OpenRequest? = null) {
    val repository: DeviceRepository = koinInject()
    val devices by repository.devices.collectAsStateWithLifecycle(initialValue = null)
    val updateManager: UpdateManager = koinInject()
    // 第一次打开（还没有设备）时显示接入流程；保存设备后仍停留在「完成」步骤，点「完成」才进入主界面
    var firstRun by rememberSaveable { mutableStateOf<Boolean?>(null) }
    if (firstRun == null && devices != null) firstRun = devices!!.isEmpty()

    when {
        devices == null || firstRun == null -> Box(Modifier.fillMaxSize().background(SpTheme.colors.bg))
        firstRun == true || devices!!.isEmpty() -> OnboardingScreen(onFinished = { firstRun = false })
        else -> MainScaffold(updateManager, openRequest)
    }
    UpdateDialog(updateManager)
}

@Composable
private fun MainScaffold(updateManager: UpdateManager, openRequest: OpenRequest?) {
    val nav = rememberNavController()
    val alertStore: AlertStore = koinInject()
    val unreadAlerts by alertStore.unread.collectAsStateWithLifecycle(initialValue = 0)
    // 从通知、小部件、磁贴点进来时打开对应页面
    LaunchedEffect(openRequest) {
        when (openRequest?.target) {
            AppLinks.ALERTS -> nav.navigate(SystemRoute(SystemPage.Notifications)) { launchSingleTop = true }
            AppLinks.DOWNLOADS -> nav.navigate(DownloadsRoute) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
            }
            AppLinks.DASHBOARD -> nav.backToTabs()
        }
    }
    val connection: ConnectionManager = koinInject()
    val connectionState by connection.state.collectAsStateWithLifecycle()
    val updateState by updateManager.state.collectAsStateWithLifecycle()
    val hasUpdate = updateState is UpdateState.Available || updateState is UpdateState.ReadyToInstall
    var switching by rememberSaveable { mutableStateOf(false) }

    val tabs = listOf(
        Tab(DashboardRoute, DashboardRoute::class, SpNavItem("总览", Icons.Outlined.SpaceDashboard, Icons.Filled.SpaceDashboard)),
        Tab(ContainersRoute, ContainersRoute::class, SpNavItem("容器", Icons.Outlined.ViewInAr, Icons.Filled.ViewInAr)),
        Tab(DownloadsRoute, DownloadsRoute::class, SpNavItem("下载", Icons.Outlined.Download, Icons.Filled.Download)),
        Tab(FilesRoute, FilesRoute::class, SpNavItem("文件", Icons.Outlined.Folder, Icons.Filled.Folder)),
        Tab(SettingsRoute, SettingsRoute::class, SpNavItem("设置", Icons.Outlined.Settings, Icons.Filled.Settings, badge = hasUpdate)),
    )
    val backStackEntry = nav.currentBackStackEntryAsState().value
    val destination = backStackEntry?.destination
    val tabIndex = tabs.indexOfFirst { destination?.hasRoute(it.routeClass) == true }
    val fullScreen = destination?.hasRoute(AddDeviceRoute::class) == true || destination?.hasRoute(ReloginRoute::class) == true
    val subPageTitle = when {
        destination?.hasRoute(ServerAddressRoute::class) == true -> "服务端地址"
        destination?.hasRoute(DownloadersRoute::class) == true -> "下载器"
        destination?.hasRoute(SystemRoute::class) == true -> backStackEntry?.toRoute<SystemRoute>()?.page?.title
        else -> null
    }

    Column(Modifier.fillMaxSize().background(SpTheme.colors.bg)) {
        if (!fullScreen) {
            if (subPageTitle != null) {
                SubPageBar(subPageTitle, onBack = { nav.popBackStack() })
            } else {
                DeviceBar(
                    connectionState,
                    unreadAlerts = unreadAlerts,
                    onClick = { switching = true },
                    onBell = { nav.navigate(SystemRoute(SystemPage.Notifications)) { launchSingleTop = true } },
                )
            }
        }
        Box(Modifier.weight(1f)) {
            NavHost(navController = nav, startDestination = DashboardRoute) {
                composable<DashboardRoute> {
                    DashboardScreen(
                        onRelogin = { nav.navigate(ReloginRoute(it)) },
                        onOpenServerAddress = { nav.navigate(ServerAddressRoute) },
                    )
                }
                composable<ContainersRoute> { ContainersScreen() }
                composable<DownloadsRoute> { DownloadsScreen(onOpenDownloaders = { nav.navigate(DownloadersRoute) }) }
                composable<FilesRoute> { FilesScreen() }
                composable<SettingsRoute> {
                    SettingsScreen(
                        onOpenServerAddress = { nav.navigate(ServerAddressRoute) },
                        onAddDevice = { nav.navigate(AddDeviceRoute) },
                        onOpenDownloaders = { nav.navigate(DownloadersRoute) },
                        systemSections = { SystemSettingsSections(onOpen = { nav.navigate(SystemRoute(it)) }) },
                    )
                }
                composable<ServerAddressRoute> { ServerAddressScreen() }
                composable<DownloadersRoute> { DownloadersScreen() }
                composable<SystemRoute> { entry -> SystemPageScreen(entry.toRoute<SystemRoute>().page) }
                composable<AddDeviceRoute> {
                    OnboardingScreen(onFinished = { nav.backToTabs() }, onCancel = { nav.popBackStack() })
                }
                composable<ReloginRoute> { entry ->
                    val route = entry.toRoute<ReloginRoute>()
                    OnboardingScreen(deviceId = route.deviceId, onFinished = { nav.backToTabs() }, onCancel = { nav.popBackStack() })
                }
            }
            SpMessageHost(SpMessageBus.messages)
        }
        if (!fullScreen && subPageTitle == null) {
            SpBottomBar(
                items = tabs.map { it.item },
                selectedIndex = tabIndex.coerceAtLeast(0),
                onSelect = { index ->
                    nav.navigate(tabs[index].route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }
    }

    if (switching) {
        DeviceSwitcherSheet(onDismiss = { switching = false }, onAddDevice = { nav.navigate(AddDeviceRoute) })
    }
}

private fun NavHostController.backToTabs() {
    if (!popBackStack(DashboardRoute, inclusive = false)) navigate(DashboardRoute)
}

/** 顶栏：设备名 + 连接状态点，点击切换设备。单行、无外框。 */
@Composable
private fun DeviceBar(state: ConnectionState, unreadAlerts: Int, onClick: () -> Unit, onBell: () -> Unit) {
    val c = SpTheme.colors
    val device: Device? = state.deviceOrNull
    val dot = when (state) {
        is ConnectionState.Connected -> c.success
        is ConnectionState.Connecting -> c.warning
        is ConnectionState.Failed -> c.outline
        ConnectionState.NoDevice -> c.outline
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(c.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Dns, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(17.dp))
                }
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(10.dp)
                        .background(c.bg, CircleShape)
                        .padding(2.dp)
                        .background(dot, CircleShape),
                )
            }
            Text(device?.name ?: "SynoPilot", style = SpTheme.type.title, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "切换设备", tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Box(Modifier.weight(1f))
        if (state is ConnectionState.Connecting) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.onSurfaceVariant)
        }
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "通知", onClick = onBell),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Notifications, contentDescription = if (unreadAlerts > 0) "通知，$unreadAlerts 条未读" else "通知", tint = c.onSurface, modifier = Modifier.size(21.dp))
            if (unreadAlerts > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-7).dp, y = 7.dp)
                        .size(8.dp)
                        .background(c.bg, CircleShape)
                        .padding(1.5.dp)
                        .background(c.error, CircleShape),
                )
            }
        }
    }
}

@Composable
private fun SubPageBar(title: String, onBack: () -> Unit) {
    val c = SpTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(52.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = c.onSurface) }
        Text(title, style = SpTheme.type.title, color = c.onSurface)
    }
}

/** 指纹解锁前的遮挡页 */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val c = SpTheme.colors
    Column(
        Modifier.fillMaxSize().background(c.bg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(c.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Dns, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Text("SynoPilot 已锁定", style = SpTheme.type.title, color = c.onSurface, modifier = Modifier.padding(top = 16.dp))
        Text(
            "点按解锁",
            style = SpTheme.type.bodySmall,
            color = c.primary,
            modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onUnlock).padding(8.dp),
        )
    }
}
