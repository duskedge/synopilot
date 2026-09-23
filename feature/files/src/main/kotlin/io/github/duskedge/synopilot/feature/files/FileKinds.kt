package io.github.duskedge.synopilot.feature.files

import io.github.duskedge.synopilot.network.RemoteFile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class FileKind { Folder, Image, Video, Audio, Archive, Pdf, Text, Other }

object FileKinds {
    private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tif", "tiff")
    private val VIDEO = setOf("mp4", "mkv", "avi", "mov", "ts", "m2ts", "webm", "wmv", "flv", "rmvb")
    private val AUDIO = setOf("mp3", "flac", "wav", "aac", "m4a", "ogg", "ape", "opus")
    private val ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")
    private val TEXT = setOf(
        "txt", "log", "md", "json", "yml", "yaml", "xml", "conf", "cfg", "ini", "env", "toml", "properties",
        "sh", "py", "js", "ts", "kt", "java", "go", "rs", "c", "h", "cpp", "html", "css", "csv", "sql", "srt", "ass", "nfo",
    )

    fun of(file: RemoteFile): FileKind = when {
        file.isDir -> FileKind.Folder
        file.extension in IMAGE -> FileKind.Image
        file.extension in VIDEO -> FileKind.Video
        file.extension in AUDIO -> FileKind.Audio
        file.extension in ARCHIVE -> FileKind.Archive
        file.extension == "pdf" -> FileKind.Pdf
        file.extension in TEXT || file.name.equals("Dockerfile", true) || file.name.startsWith(".env") -> FileKind.Text
        else -> FileKind.Other
    }

    fun previewKind(file: RemoteFile): PreviewKind = when (of(file)) {
        FileKind.Image -> PreviewKind.Image
        FileKind.Text -> PreviewKind.Text
        else -> PreviewKind.Other
    }

    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE = DateTimeFormatter.ofPattern("M月d日")
    private val FULL = DateTimeFormatter.ofPattern("yyyy/M/d")

    /** 修改时间：今天只显示时间，今年显示月日，更早显示年月日 */
    fun time(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
        if (epochSeconds <= 0) return ""
        val t = Instant.ofEpochSecond(epochSeconds).atZone(zone)
        return when {
            t.toLocalDate() == today -> "今天 " + t.format(TIME)
            t.toLocalDate() == today.minusDays(1) -> "昨天 " + t.format(TIME)
            t.year == today.year -> t.format(DATE)
            else -> t.format(FULL)
        }
    }

    /** 路径拆成面包屑：(显示名, 路径) */
    fun breadcrumbs(path: String): List<Pair<String, String>> {
        val parts = path.split('/').filter { it.isNotEmpty() }
        return listOf("共享文件夹" to "/") + parts.mapIndexed { i, name -> name to "/" + parts.take(i + 1).joinToString("/") }
    }

    /** 新建文件夹 / 重命名时检查名字 */
    fun invalidName(name: String): String? = when {
        name.isBlank() -> "名字不能为空"
        name.any { it in "/\\:*?\"<>|" } -> "不能包含 / \\ : * ? \" < > |"
        name == "." || name == ".." -> "名字不合法"
        name.length > 255 -> "名字太长"
        else -> null
    }
}
