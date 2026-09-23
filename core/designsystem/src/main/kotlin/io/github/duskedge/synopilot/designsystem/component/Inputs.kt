package io.github.duskedge.synopilot.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.duskedge.synopilot.designsystem.SpTheme

/** 输入框：DSM 配色、10dp 圆角、紧凑高度。 */
@Composable
fun SpTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val c = SpTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = c.outline) } },
        leadingIcon = leadingIcon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(20.dp)) } },
        trailingIcon = trailing,
        supportingText = supportingText?.let { { Text(it) } },
        isError = isError,
        enabled = enabled,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        textStyle = SpTheme.type.body,
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.primary,
            unfocusedBorderColor = c.outlineVariant,
            focusedLabelColor = c.primary,
            unfocusedLabelColor = c.onSurfaceVariant,
            focusedContainerColor = c.card,
            unfocusedContainerColor = c.card,
            errorBorderColor = c.error,
            errorLabelColor = c.error,
            errorSupportingTextColor = c.error,
            cursorColor = c.primary,
            focusedLeadingIconColor = c.onSurfaceVariant,
            unfocusedLeadingIconColor = c.onSurfaceVariant,
        ),
    )
}

/** 底部面板：标题 + 副标题 + 关闭按钮，内容可滚动。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpSheet(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = SpTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.bg,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = SpTheme.type.title.copy(fontSize = SpTheme.type.numLarge.fontSize * 0.66f), color = c.onSurface)
                if (subtitle != null) Text(subtitle, style = SpTheme.type.bodySmall, color = c.onSurfaceVariant)
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = c.onSurfaceVariant) }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

enum class SpBannerTone { Info, Warning, Error }

/** 横幅提示：离线、证书需要确认、需要重新登录等。 */
@Composable
fun SpBanner(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector? = null,
    tone: SpBannerTone = SpBannerTone.Info,
    actions: (@Composable () -> Unit)? = null,
) {
    val c = SpTheme.colors
    val (bg, fg) = when (tone) {
        SpBannerTone.Info -> c.card to c.onSurface
        SpBannerTone.Warning -> c.warningContainer to c.onSurface
        SpBannerTone.Error -> c.errorContainer to c.onSurface
    }
    val accent = when (tone) {
        SpBannerTone.Info -> c.primary
        SpBannerTone.Warning -> c.warning
        SpBannerTone.Error -> c.error
    }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .then(if (tone == SpBannerTone.Info) Modifier.border(1.dp, c.line, shape) else Modifier)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = SpTheme.type.label, color = fg)
            if (message != null) Text(message, style = SpTheme.type.bodySmall, color = c.onSurfaceVariant)
            if (actions != null) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
            }
        }
    }
}

/** 网络曲线：下载（主色，带面积）+ 上传（第二色）。 */
@Composable
fun SpNetworkChart(
    download: List<Long>,
    upload: List<Long>,
    modifier: Modifier = Modifier,
    height: Int = 44,
) {
    val c = SpTheme.colors
    val primary = c.primary
    val second = c.chart2
    Canvas(modifier = modifier.fillMaxWidth().height(height.dp)) {
        val count = maxOf(download.size, upload.size)
        if (count < 2) return@Canvas
        val max = (download + upload).maxOrNull()?.coerceAtLeast(1) ?: 1
        fun path(values: List<Long>): Path {
            val p = Path()
            values.forEachIndexed { i, v ->
                val x = size.width * i / (count - 1)
                val y = size.height - (v.toFloat() / max) * (size.height - 4.dp.toPx()) - 2.dp.toPx()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            return p
        }
        val down = path(download)
        val area = Path().apply {
            addPath(down)
            lineTo(size.width * (download.size - 1) / (count - 1), size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(primary.copy(alpha = 0.22f), Color.Transparent)))
        drawPath(down, primary, style = Stroke(width = 1.8.dp.toPx(), join = StrokeJoin.Round))
        drawPath(path(upload), second, style = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Round))
        drawLine(c.line, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
    }
}

/** 竖条状态点（硬盘概况用）：良好绿、警告橙、危险红、其他灰。 */
@Composable
fun SpStatusBars(statuses: List<SpStatus>, modifier: Modifier = Modifier) {
    val c = SpTheme.colors
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        statuses.forEach { s ->
            Box(
                Modifier
                    .size(width = 6.dp, height = 12.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        when (s) {
                            SpStatus.Ok -> c.success
                            SpStatus.Warning -> c.warning
                            SpStatus.Error -> c.error
                            SpStatus.Neutral -> c.outline
                        },
                    ),
            )
        }
    }
}
