package io.github.duskedge.synopilot.designsystem

import java.util.Locale

/** 界面上的数字格式（中文习惯，1 位小数）。 */
object SpFormat {
    private val UNITS = listOf("B", "KB", "MB", "GB", "TB", "PB")

    fun bytes(value: Long): String {
        if (value < 1024) return "$value B"
        var v = value.toDouble()
        var i = 0
        while (v >= 1024 && i < UNITS.lastIndex) {
            v /= 1024
            i++
        }
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, UNITS[i])
    }

    /** 拆成数值和单位，便于分开设置字号 */
    fun bytesParts(value: Long): Pair<String, String> = bytes(value).split(' ').let { it[0] to it.getOrElse(1) { "" } }

    fun speed(bytesPerSec: Long): String = bytes(bytesPerSec) + "/s"

    fun speedParts(bytesPerSec: Long): Pair<String, String> = bytesParts(bytesPerSec).let { (n, u) -> n to "$u/s" }

    fun uptime(seconds: Long): String {
        val days = seconds / 86_400
        val hours = seconds % 86_400 / 3_600
        val minutes = seconds % 3_600 / 60
        return when {
            days > 0 -> "$days 天 $hours 小时"
            hours > 0 -> "$hours 小时 $minutes 分钟"
            else -> "$minutes 分钟"
        }
    }

    /** 「3 分钟前」之类的相对时间 */
    fun ago(time: Long, now: Long = System.currentTimeMillis()): String {
        val s = ((now - time) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "刚刚"
            s < 3_600 -> "${s / 60} 分钟前"
            s < 86_400 -> "${s / 3_600} 小时前"
            else -> "${s / 86_400} 天前"
        }
    }
}
