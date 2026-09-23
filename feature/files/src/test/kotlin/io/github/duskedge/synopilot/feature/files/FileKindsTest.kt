package io.github.duskedge.synopilot.feature.files

import io.github.duskedge.synopilot.network.RemoteFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class FileKindsTest {
    private fun file(name: String, dir: Boolean = false) = RemoteFile(name, "/x/$name", dir, 0, 0)

    @Test
    fun `按扩展名分类`() {
        assertEquals(FileKind.Folder, FileKinds.of(file("photos", dir = true)))
        assertEquals(FileKind.Image, FileKinds.of(file("IMG_1.HEIC")))
        assertEquals(FileKind.Video, FileKinds.of(file("a.mkv")))
        assertEquals(FileKind.Text, FileKinds.of(file("compose.yaml")))
        assertEquals(FileKind.Text, FileKinds.of(file("Dockerfile")))
        assertEquals(FileKind.Other, FileKinds.of(file("data.bin")))
        assertEquals(PreviewKind.Other, FileKinds.previewKind(file("a.mkv")))
    }

    @Test
    fun `面包屑`() {
        assertEquals(listOf("共享文件夹" to "/"), FileKinds.breadcrumbs("/"))
        assertEquals(
            listOf("共享文件夹" to "/", "video" to "/video", "Movies" to "/video/Movies"),
            FileKinds.breadcrumbs("/video/Movies"),
        )
    }

    @Test
    fun `修改时间`() {
        val today = LocalDate.of(2026, 9, 23)
        val noon = today.atTime(12, 5).toEpochSecond(ZoneOffset.UTC)
        assertEquals("今天 12:05", FileKinds.time(noon, ZoneOffset.UTC, today))
        assertEquals("昨天 12:05", FileKinds.time(noon - 86_400, ZoneOffset.UTC, today))
        assertEquals("3月1日", FileKinds.time(LocalDate.of(2026, 3, 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC), ZoneOffset.UTC, today))
        assertEquals("2024/5/6", FileKinds.time(LocalDate.of(2024, 5, 6).atStartOfDay().toEpochSecond(ZoneOffset.UTC), ZoneOffset.UTC, today))
    }

    @Test
    fun `文件名校验`() {
        assertNull(FileKinds.invalidName("新建文件夹"))
        assertEquals("不能包含 / \\ : * ? \" < > |", FileKinds.invalidName("a/b"))
    }
}
