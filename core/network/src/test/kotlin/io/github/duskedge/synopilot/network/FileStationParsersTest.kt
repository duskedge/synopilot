package io.github.duskedge.synopilot.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileStationParsersTest {

    @Test
    fun `文件夹在前，其余按名字排序（不区分大小写）`() {
        val list = FileStationParsers.list(data("fs_list.json"), "files")
        assertEquals(listOf("Movies", "A.txt", "b.mkv"), list.files.map { it.name })
        assertTrue(list.files[0].isDir)
        assertEquals(12L, list.files[1].size)
        assertEquals(1_790_000_200L, list.files[1].modified)
        assertEquals("mkv", list.files[2].extension)
        assertEquals(3, list.total)
    }

    @Test
    fun `分享链接`() {
        val json = DsmApi.parseEnvelope("""{"success":true,"data":{"links":[{"id":"x1","url":"https://gofile.me/a/x1","qrcode":"data:image/png;base64,AA==","date_expired":""}]}}""", "t")
        val link = FileStationParsers.links(json).single()
        assertEquals("https://gofile.me/a/x1", link.url)
        assertEquals(null, link.expires)
    }
}
