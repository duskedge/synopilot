package io.github.duskedge.synopilot.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.duskedge.synopilot.designsystem.SpTheme

enum class SpButtonVariant { Filled, Tonal, Outlined, Text, Danger }

enum class SpButtonSize(val height: Int, val radius: Int, val hPadding: Int) {
    Medium(36, 10, 14),
    Small(30, 8, 12),
    Block(44, 12, 16),
}

/** 按钮：圆角矩形（不是胶囊），尺寸比 Material 默认小一档。 */
@Composable
fun SpButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: SpButtonVariant = SpButtonVariant.Filled,
    size: SpButtonSize = SpButtonSize.Medium,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = SpTheme.colors
    val (bg, fg, border) = when (variant) {
        SpButtonVariant.Filled -> Triple(c.primary, c.onPrimary, null)
        SpButtonVariant.Tonal -> Triple(c.card2, c.onSurface, c.line)
        SpButtonVariant.Outlined -> Triple(Color.Transparent, c.onSurface, c.outlineVariant)
        SpButtonVariant.Text -> Triple(Color.Transparent, c.primary, null)
        SpButtonVariant.Danger -> Triple(c.error, c.onError, null)
    }
    val shape = RoundedCornerShape(size.radius.dp)
    Row(
        modifier = modifier
            .height(size.height.dp)
            .clip(shape)
            .background(bg)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = size.hPadding.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(17.dp))
        Text(text, style = SpTheme.type.label.copy(fontSize = if (size == SpButtonSize.Block) SpTheme.type.body.fontSize else SpTheme.type.label.fontSize), color = fg, maxLines = 1)
    }
}

/** 开关：36×22，比 Material 默认小一档。 */
@Composable
fun SpSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = SpTheme.colors
    val track by animateColorAsState(if (checked) c.primary else c.surfaceHighest, label = "track")
    val thumbX by animateDpAsState(if (checked) 17.dp else 3.dp, label = "thumb")
    Box(
        modifier = modifier
            .size(width = 36.dp, height = 22.dp)
            .clip(CircleShape)
            .background(track)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset { IntOffset(thumbX.roundToPx(), 0) }
                .size(16.dp)
                .shadow(1.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

/** 分段控件：浅灰底 + 选中项白色滑块。 */
@Composable
fun <T> SpSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = SpTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.surfaceHigh)
            .padding(2.dp),
    ) {
        options.forEach { (value, label) ->
            val isOn = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (isOn) Modifier.shadow(1.dp, RoundedCornerShape(8.dp)) else Modifier)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isOn) c.card else Color.Transparent)
                    .selectable(selected = isOn, role = Role.Tab, onClick = { onSelect(value) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = SpTheme.type.label, color = if (isOn) c.onSurface else c.onSurfaceVariant)
            }
        }
    }
}

/**
 * 格栅计量条：一排小方格按比例点亮。全 App 统一用它表达进度和占用
 * （CPU、内存、存储、下载进度），颜色按阈值变化。
 */
@Composable
fun SpMeter(
    fraction: Float,
    modifier: Modifier = Modifier,
    cells: Int = 16,
    cellHeight: Int = 8,
    color: Color = SpTheme.colors.primary,
) {
    val c = SpTheme.colors
    val on = (fraction.coerceIn(0f, 1f) * cells).let { kotlin.math.round(it).toInt() }
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(cells) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(cellHeight.dp)
                    .clip(RoundedCornerShape((cellHeight / 4).coerceAtLeast(1).dp))
                    .background(if (i < on) color else c.surfaceHigh),
            )
        }
    }
}

/** 按阈值选择计量条颜色：≥80% 危险，≥60% 警告。 */
@Composable
fun meterColorFor(fraction: Float): Color = when {
    fraction >= 0.8f -> SpTheme.colors.error
    fraction >= 0.6f -> SpTheme.colors.warning
    else -> SpTheme.colors.primary
}

enum class SpStatus { Ok, Warning, Error, Neutral }

/** 状态：小圆点 + 文字。 */
@Composable
fun SpStatusText(status: SpStatus, text: String, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(text, style = SpTheme.type.label.copy(fontSize = SpTheme.type.caption.fontSize), color = color)
    }
}

/** 小标签，如「默认」「新」。 */
@Composable
fun SpTag(text: String, modifier: Modifier = Modifier, status: SpStatus = SpStatus.Neutral) {
    val c = SpTheme.colors
    val (bg, fg) = when (status) {
        SpStatus.Ok -> c.successContainer to c.success
        SpStatus.Warning -> c.warningContainer to c.warning
        SpStatus.Error -> c.errorContainer to c.error
        SpStatus.Neutral -> c.primaryContainer to c.primary
    }
    Text(
        text = text,
        style = SpTheme.type.caption.copy(fontWeight = SpTheme.type.label.fontWeight),
        color = fg,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun statusColor(status: SpStatus): Color = when (status) {
    SpStatus.Ok -> SpTheme.colors.success
    SpStatus.Warning -> SpTheme.colors.warning
    SpStatus.Error -> SpTheme.colors.error
    SpStatus.Neutral -> SpTheme.colors.onSurfaceVariant
}
