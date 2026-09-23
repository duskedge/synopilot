package io.github.duskedge.synopilot.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.duskedge.synopilot.designsystem.SpTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/** 一条提示；[actionLabel] 不为空时显示一个操作（如「撤销」）。 */
data class SpMessage(
    val text: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
    /** 同样文字的提示连续出现时，用它区分 */
    val id: Long = System.nanoTime(),
)

/** 屏幕底部的深色提示条，3 秒后消失（带操作时 5 秒）。放在 Box 里使用。 */
@Composable
fun BoxScope.SpMessageHost(messages: Flow<SpMessage>, modifier: Modifier = Modifier) {
    var current by remember { mutableStateOf<SpMessage?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(messages) {
        messages.collect { m ->
            current = m
            visible = true
        }
    }
    LaunchedEffect(current?.id) {
        val m = current ?: return@LaunchedEffect
        delay(if (m.actionLabel != null) 5_000 else 3_000)
        if (current?.id == m.id) visible = false
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 12.dp),
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        val m = current ?: return@AnimatedVisibility
        val c = SpTheme.colors
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(c.inverseSurface)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(m.text, style = SpTheme.type.bodySmall, color = c.inverseOnSurface, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            if (m.actionLabel != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            visible = false
                            m.action?.invoke()
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(m.actionLabel, style = SpTheme.type.label, color = c.inversePrimary)
                }
            } else {
                Box(Modifier.padding(end = 10.dp))
            }
        }
    }
}
