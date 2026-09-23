package io.github.duskedge.synopilot.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字号整体偏紧凑（运维类 App 信息密度优先）。
 * 数字用稍宽的 Archivo（wdth 112），读起来像设备铭牌；中文使用系统字体。
 */
object SpTypography {
    private val archivo = FontFamily(
        Font(
            resId = R.font.archivo,
            weight = FontWeight.SemiBold,
            variationSettings = FontVariation.Settings(
                FontVariation.weight(600),
                FontVariation.width(112f),
            ),
        ),
    )

    /** 大读数，如下载速度 */
    val numLarge = TextStyle(fontFamily = archivo, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.3).sp)

    /** 指标读数，如 CPU 24% */
    val num = TextStyle(fontFamily = archivo, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp)

    val title = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp)
    val section = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
    val bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val label = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp)

    internal val material = Typography(
        titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
        titleMedium = title,
        bodyLarge = body,
        bodyMedium = bodySmall,
        bodySmall = caption,
        labelLarge = label,
    )
}
