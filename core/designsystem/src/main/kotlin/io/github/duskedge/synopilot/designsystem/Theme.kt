package io.github.duskedge.synopilot.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * SynoPilot 配色：沿用群晖 DSM 7（DSM 蓝 + 蓝灰底 + 白色面板）。
 * 状态色按 DSM 存储管理器的习惯：良好绿、警告橙黄、危险红，只用于状态。
 * 数值与 doc/prototype 原型中的 CSS token 一一对应。
 */
@Immutable
data class SpColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    /** 页面底色 */
    val bg: Color,
    /** 卡片、列表底色 */
    val card: Color,
    /** 卡片内的次级色块（如指标格子、图标底） */
    val card2: Color,
    val surfaceHigh: Color,
    val surfaceHighest: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    /** 卡片边框、分隔线 */
    val line: Color,
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val error: Color,
    val errorContainer: Color,
    val onError: Color,
    /** 图表第二系列（如上传速度） */
    val chart2: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val inversePrimary: Color,
    val isDark: Boolean,
)

val LightColors = SpColors(
    primary = Color(0xFF0B7EE0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1EFFC),
    onPrimaryContainer = Color(0xFF063B6B),
    bg = Color(0xFFEEF2F6),
    card = Color.White,
    card2 = Color(0xFFF4F6F9),
    surfaceHigh = Color(0xFFE3E8EE),
    surfaceHighest = Color(0xFFD5DCE4),
    onSurface = Color(0xFF1F2329),
    onSurfaceVariant = Color(0xFF6B7480),
    outline = Color(0xFF98A2AE),
    outlineVariant = Color(0xFFDBE1E8),
    line = Color(0xFFE0E6EC),
    success = Color(0xFF25A55F),
    successContainer = Color(0xFFDEF5E8),
    warning = Color(0xFFE8900C),
    warningContainer = Color(0xFFFDF0DA),
    error = Color(0xFFE5484D),
    errorContainer = Color(0xFFFDE6E7),
    onError = Color.White,
    chart2 = Color(0xFF8FA3B8),
    inverseSurface = Color(0xFF232830),
    inverseOnSurface = Color(0xFFF0F3F6),
    inversePrimary = Color(0xFF6AB4FF),
    isDark = false,
)

val DarkColors = SpColors(
    primary = Color(0xFF3D9BFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0E2D4D),
    onPrimaryContainer = Color(0xFFCFE6FF),
    bg = Color(0xFF14171B),
    card = Color(0xFF1E2227),
    card2 = Color(0xFF262B31),
    surfaceHigh = Color(0xFF282D33),
    surfaceHighest = Color(0xFF333941),
    onSurface = Color(0xFFE8EBEF),
    onSurfaceVariant = Color(0xFF9AA4B0),
    outline = Color(0xFF69737F),
    outlineVariant = Color(0xFF2F353D),
    line = Color(0xFF292E35),
    success = Color(0xFF4CC885),
    successContainer = Color(0xFF123524),
    warning = Color(0xFFF5A93A),
    warningContainer = Color(0xFF3D2A0A),
    error = Color(0xFFFF6B6F),
    errorContainer = Color(0xFF481519),
    onError = Color(0xFF2E0507),
    chart2 = Color(0xFF6F8398),
    inverseSurface = Color(0xFFE8EBEF),
    inverseOnSurface = Color(0xFF1E2227),
    inversePrimary = Color(0xFF0B7EE0),
    isDark = true,
)

internal val LocalSpColors = staticCompositionLocalOf { LightColors }

object SpTheme {
    val colors: SpColors
        @Composable get() = LocalSpColors.current
    val type: SpTypography
        get() = SpTypography
}

@Composable
fun SynoPilotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val c = if (darkTheme) DarkColors else LightColors
    // 让 Material 组件（文本选择、输入框光标、对话框等）也使用同一套颜色
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = c.primary, onPrimary = c.onPrimary,
            primaryContainer = c.primaryContainer, onPrimaryContainer = c.onPrimaryContainer,
            background = c.bg, onBackground = c.onSurface,
            surface = c.card, onSurface = c.onSurface, onSurfaceVariant = c.onSurfaceVariant,
            surfaceContainerHigh = c.card, surfaceContainerHighest = c.surfaceHighest,
            outline = c.outline, outlineVariant = c.outlineVariant,
            error = c.error, onError = c.onError, errorContainer = c.errorContainer,
            inverseSurface = c.inverseSurface, inverseOnSurface = c.inverseOnSurface, inversePrimary = c.inversePrimary,
        )
    } else {
        lightColorScheme(
            primary = c.primary, onPrimary = c.onPrimary,
            primaryContainer = c.primaryContainer, onPrimaryContainer = c.onPrimaryContainer,
            background = c.bg, onBackground = c.onSurface,
            surface = c.card, onSurface = c.onSurface, onSurfaceVariant = c.onSurfaceVariant,
            surfaceContainerHigh = c.card, surfaceContainerHighest = c.surfaceHighest,
            outline = c.outline, outlineVariant = c.outlineVariant,
            error = c.error, onError = c.onError, errorContainer = c.errorContainer,
            inverseSurface = c.inverseSurface, inverseOnSurface = c.inverseOnSurface, inversePrimary = c.inversePrimary,
        )
    }
    CompositionLocalProvider(LocalSpColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = SpTypography.material, content = content)
    }
}
