package io.github.duskedge.synopilot.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.duskedge.synopilot.designsystem.SpTheme
import io.github.duskedge.synopilot.designsystem.component.CertificateDialog
import io.github.duskedge.synopilot.designsystem.component.SpBanner
import io.github.duskedge.synopilot.designsystem.component.SpBannerTone
import io.github.duskedge.synopilot.designsystem.component.SpButton
import io.github.duskedge.synopilot.designsystem.component.SpButtonSize
import io.github.duskedge.synopilot.designsystem.component.SpButtonVariant
import io.github.duskedge.synopilot.designsystem.component.SpListGroup
import io.github.duskedge.synopilot.designsystem.component.SpListItem
import io.github.duskedge.synopilot.designsystem.component.SpSwitch
import io.github.duskedge.synopilot.designsystem.component.SpTextField
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 首次接入 / 重新登录。
 * @param deviceId 不为 null 时是重新登录这台设备
 * @param onFinished 完成（或取消重新登录）时调用
 */
@Composable
fun OnboardingScreen(
    deviceId: String? = null,
    onFinished: () -> Unit,
    onCancel: (() -> Unit)? = null,
    viewModel: OnboardingViewModel = koinViewModel(key = deviceId ?: "new") { parametersOf(deviceId) },
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val c = SpTheme.colors
    val titles = mapOf(
        OnboardingStep.Discover to "添加 NAS",
        OnboardingStep.Login to if (ui.relogin) "重新登录" else "登录",
        OnboardingStep.Otp to "两步验证",
        OnboardingStep.Done to "完成",
    )
    val index = OnboardingStep.entries.indexOf(ui.step)

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val canBack = ui.step == OnboardingStep.Otp || (ui.step == OnboardingStep.Login && !ui.relogin)
            if (canBack || onCancel != null) {
                IconButton(onClick = { if (canBack) viewModel.back() else onCancel?.invoke() }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = c.onSurface)
                }
            } else {
                Spacer(Modifier.size(12.dp))
            }
            Text(titles.getValue(ui.step), style = SpTheme.type.title, color = c.onSurface, modifier = Modifier.weight(1f))
            if (!ui.relogin) Text("${index + 1} / 4", style = SpTheme.type.caption, color = c.onSurfaceVariant, modifier = Modifier.padding(end = 16.dp))
        }
        if (!ui.relogin) {
            LinearProgressIndicator(
                progress = { (index + 1) / 4f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = c.primary,
                trackColor = c.surfaceHigh,
                drawStopIndicator = {},
            )
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (ui.step) {
                OnboardingStep.Discover -> DiscoverStep(ui, viewModel)
                OnboardingStep.Login -> LoginStep(ui, viewModel)
                OnboardingStep.Otp -> OtpStep(ui, viewModel)
                OnboardingStep.Done -> DoneStep(ui, viewModel, onFinished)
            }
        }
    }

    ui.certificate?.let { pending ->
        CertificateDialog(
            host = pending.url.substringAfter("://").substringBefore('/'),
            fingerprint = pending.certificate.fingerprint,
            subject = pending.certificate.subject,
            issuer = pending.certificate.issuer,
            notAfter = pending.certificate.notAfter,
            onTrust = viewModel::trustCertificate,
            onCancel = viewModel::rejectCertificate,
        )
    }
}

@Composable
private fun Heading(title: String, message: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = SpTheme.type.numLarge.copy(fontFamily = SpTheme.type.title.fontFamily, fontSize = SpTheme.type.numLarge.fontSize * 0.8f), color = SpTheme.colors.onSurface)
        Text(message, style = SpTheme.type.bodySmall, color = SpTheme.colors.onSurfaceVariant)
    }
}

@Composable
private fun DiscoverStep(ui: OnboardingUi, vm: OnboardingViewModel) {
    val c = SpTheme.colors
    Heading("找到你的 NAS", "正在搜索同一局域网里的群晖设备。也可以手动输入地址。")
    if (!ui.lanAvailable) {
        SpBanner("没有连接 Wi-Fi", message = "连上和 NAS 同一个网络后才能自动发现，也可以直接手动输入地址。", tone = SpBannerTone.Warning)
    }
    if (ui.scanning) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.primary)
            Text("正在搜索…", style = SpTheme.type.bodySmall, color = c.onSurfaceVariant)
        }
    }
    if (ui.discovered.isNotEmpty()) {
        SpListGroup {
            ui.discovered.forEachIndexed { i, nas ->
                SpListItem(
                    title = nas.host,
                    icon = Icons.Outlined.Dns,
                    subtitle = if (nas.needsCertificateTrust) "DSM · HTTPS（需要确认证书）" else "DSM · ${nas.latencyMs ?: "-"} ms",
                    divider = i > 0,
                    onClick = { vm.pick(nas) },
                )
            }
        }
    } else if (!ui.scanning) {
        Text("没有发现设备。可以确认 NAS 已开机、和手机在同一网络，或者手动输入地址。", style = SpTheme.type.bodySmall, color = c.onSurfaceVariant)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SpButton("手动输入地址", onClick = vm::manual, variant = SpButtonVariant.Tonal)
        if (!ui.scanning) SpButton("重新搜索", onClick = vm::startScan, variant = SpButtonVariant.Text)
    }
}

@Composable
private fun LoginStep(ui: OnboardingUi, vm: OnboardingViewModel) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    Heading(
        if (ui.relogin) "需要重新登录" else "登录 DSM",
        if (ui.relogin) "登录已过期，请重新输入密码。" else "建议使用单独的管理员账号，并开启两步验证。",
    )
    SpTextField(
        value = ui.address,
        onValueChange = vm::setAddress,
        label = "地址",
        placeholder = "https://192.168.1.100:5001",
        leadingIcon = Icons.Outlined.Public,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        supportingText = "可以填局域网 IP、域名或 Tailscale 地址，端口默认 5000（HTTP）/ 5001（HTTPS）",
    )
    SpTextField(
        value = ui.account,
        onValueChange = vm::setAccount,
        label = "用户名",
        leadingIcon = Icons.Outlined.Person,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
    SpTextField(
        value = ui.password,
        onValueChange = vm::setPassword,
        label = "密码",
        leadingIcon = Icons.Outlined.Lock,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        trailing = {
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = "显示密码")
            }
        },
    )
    ui.error?.let { SpBanner(it, tone = SpBannerTone.Error) }
    Text(
        "密码经 Android Keystore 加密后只保存在这台手机上，用于登录过期时自动重新登录。",
        style = SpTheme.type.caption,
        color = SpTheme.colors.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    SpButton(
        if (ui.busy) "正在登录…" else "登录",
        onClick = vm::submitLogin,
        enabled = !ui.busy,
        size = SpButtonSize.Block,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun OtpStep(ui: OnboardingUi, vm: OnboardingViewModel) {
    Heading("输入验证码", "打开 Synology Secure SignIn 或其他验证器应用，输入 6 位验证码。")
    SpTextField(
        value = ui.otp,
        onValueChange = vm::setOtp,
        label = "6 位验证码",
        leadingIcon = Icons.Outlined.Lock,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
    )
    SpListGroup {
        SpListItem(
            title = "信任这台手机",
            subtitle = "之后登录不再需要验证码",
            trailing = { SpSwitch(checked = ui.trustDevice, onCheckedChange = vm::setTrustDevice) },
        )
    }
    ui.error?.let { SpBanner(it, tone = SpBannerTone.Error) }
    SpButton(
        if (ui.busy) "正在验证…" else "验证",
        onClick = vm::submitOtp,
        enabled = !ui.busy && ui.otp.length == 6,
        size = SpButtonSize.Block,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DoneStep(ui: OnboardingUi, vm: OnboardingViewModel, onFinished: () -> Unit) {
    val c = SpTheme.colors
    Box(
        Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(c.successContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = c.success, modifier = Modifier.size(30.dp))
    }
    Heading(
        if (ui.relogin) "已重新登录" else "已添加 ${ui.deviceName}",
        if (ui.relogin) "可以继续使用了。" else "之后可以在「设置 → 服务端地址」添加外网地址，离开家也能连上。",
    )
    if (!ui.relogin) {
        SpListGroup {
            SpListItem(
                title = "使用指纹解锁",
                icon = Icons.Outlined.Fingerprint,
                subtitle = "打开 App 时验证",
                trailing = { SpSwitch(checked = ui.biometricLock, onCheckedChange = vm::setBiometricLock) },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    SpButton("完成", onClick = onFinished, size = SpButtonSize.Block, modifier = Modifier.fillMaxWidth())
}
