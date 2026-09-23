package io.github.duskedge.synopilot.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.duskedge.synopilot.designsystem.SpTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 请用户确认是否信任 NAS 的证书。
 * [changed] 为 true 表示和之前信任的证书不一致（更需要警惕）。
 */
@Composable
fun CertificateDialog(
    host: String,
    fingerprint: String,
    subject: String,
    issuer: String,
    notAfter: Long,
    onTrust: () -> Unit,
    onCancel: () -> Unit,
    changed: Boolean = false,
) {
    val c = SpTheme.colors
    AlertDialog(
        onDismissRequest = onCancel,
        shape = RoundedCornerShape(20.dp),
        containerColor = c.card,
        title = { Text(if (changed) "NAS 的证书变了" else "信任这个证书吗？", style = SpTheme.type.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (changed) {
                        "$host 出示的证书和之前信任的不一样。如果你最近没有更换过 NAS 的证书，可能有人在拦截连接，请不要信任。"
                    } else {
                        "$host 使用的证书不受系统信任（通常是 DSM 的自签名证书）。请到 DSM「控制面板 → 安全性 → 证书」核对下面的指纹一致后再信任。"
                    },
                    style = SpTheme.type.bodySmall,
                    color = if (changed) c.error else c.onSurfaceVariant,
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.card2)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("SHA-256 指纹", style = SpTheme.type.caption, color = c.onSurfaceVariant)
                    Text(fingerprint, style = SpTheme.type.caption.copy(fontFamily = FontFamily.Monospace), color = c.onSurface)
                    Text("颁发给：${subject.cn()}", style = SpTheme.type.caption, color = c.onSurfaceVariant)
                    Text("颁发者：${issuer.cn()}", style = SpTheme.type.caption, color = c.onSurfaceVariant)
                    Text(
                        "有效期至：${SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(notAfter))}",
                        style = SpTheme.type.caption,
                        color = c.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SpButton("取消", onClick = onCancel, variant = SpButtonVariant.Text, size = SpButtonSize.Small)
                SpButton(
                    "信任并继续",
                    onClick = onTrust,
                    variant = if (changed) SpButtonVariant.Danger else SpButtonVariant.Filled,
                    size = SpButtonSize.Small,
                )
            }
        },
    )
}

/** 从 X.500 名称里取 CN，取不到就原样返回 */
private fun String.cn(): String = split(',').map { it.trim() }.firstOrNull { it.startsWith("CN=") }?.removePrefix("CN=") ?: this
