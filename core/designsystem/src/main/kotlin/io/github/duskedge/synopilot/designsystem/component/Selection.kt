package io.github.duskedge.synopilot.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.duskedge.synopilot.designsystem.SpTheme

/** 筛选标签：30dp 高、8dp 圆角；选中时浅蓝底 + 对勾。 */
@Composable
fun SpChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val c = SpTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .height(30.dp)
            .clip(shape)
            .background(if (selected) c.primaryContainer else c.card)
            .then(if (selected) Modifier else Modifier.border(1.dp, c.outlineVariant, shape))
            .selectable(selected = selected, role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        val lead = if (selected) Icons.Outlined.Check else icon
        val fg = if (selected) c.onPrimaryContainer else c.onSurfaceVariant
        if (lead != null) Icon(lead, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Text(text, style = SpTheme.type.label, color = fg, maxLines = 1)
    }
}

/** 一行可横向滚动的筛选标签 */
@Composable
fun <T> SpChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (value, label) -> SpChip(label, value == selected, { onSelect(value) }) }
    }
}

/** 下划线标签页，可带数量角标。 */
@Composable
fun <T> SpTabs(
    options: List<Triple<T, String, Int?>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = SpTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .drawBehind {
                drawLine(c.line, Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx())
            },
    ) {
        options.forEach { (value, label, count) ->
            val on = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(value) }),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(label, style = SpTheme.type.label, color = if (on) c.primary else c.onSurfaceVariant)
                    if (count != null) {
                        Box(
                            Modifier
                                .height(16.dp)
                                .widthIn(min = 16.dp)
                                .clip(CircleShape)
                                .background(if (on) c.primaryContainer else c.surfaceHigh)
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(count.toString(), style = SpTheme.type.caption.copy(fontSize = SpTheme.type.caption.fontSize * 0.88f), color = if (on) c.primary else c.onSurfaceVariant)
                        }
                    }
                }
                if (on) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .width(40.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(c.primary),
                    )
                }
            }
        }
    }
}

enum class SpIconButtonStyle { Plain, Tonal, Filled }

/** 小号图标按钮：32dp，8dp 圆角。 */
@Composable
fun SpIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: SpIconButtonStyle = SpIconButtonStyle.Plain,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    val c = SpTheme.colors
    val (bg, fg) = when (style) {
        SpIconButtonStyle.Plain -> Color.Transparent to c.onSurfaceVariant
        SpIconButtonStyle.Tonal -> c.card2 to c.onSurface
        SpIconButtonStyle.Filled -> c.primary to c.onPrimary
    }
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint ?: fg, modifier = Modifier.size(18.dp))
    }
}

/** 键值表：左列浅色标签，右列等宽内容。 */
@Composable
fun SpKeyValue(rows: List<Pair<String, String>>, modifier: Modifier = Modifier, monoValues: Boolean = true) {
    val c = SpTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.card)
            .border(1.dp, c.line, RoundedCornerShape(12.dp)),
    ) {
        rows.forEachIndexed { i, (k, v) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .drawBehind { if (i > 0) drawLine(c.line, Offset(12.dp.toPx(), 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(k, style = SpTheme.type.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.weight(0.38f))
                Text(
                    v,
                    style = if (monoValues) SpTheme.type.bodySmall.copy(fontFamily = FontFamily.Monospace) else SpTheme.type.bodySmall,
                    color = c.onSurface,
                    modifier = Modifier.weight(0.62f),
                )
            }
        }
    }
}
