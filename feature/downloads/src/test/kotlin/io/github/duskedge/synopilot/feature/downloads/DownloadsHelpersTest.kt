package io.github.duskedge.synopilot.feature.downloads

import io.github.duskedge.synopilot.network.download.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsHelpersTest {

    @Test
    fun `剪贴板识别下载链接`() {
        assertTrue(DownloadsViewModel.isLink("magnet:?xt=urn:btih:abc"))
        assertTrue(DownloadsViewModel.isLink("https://example.com/a.torrent?key=1"))
        assertTrue(DownloadsViewModel.isLink("ed2k://|file|a.iso|1|abc|/"))
        assertFalse(DownloadsViewModel.isLink("https://example.com/page"))
        assertFalse(DownloadsViewModel.isLink("随便一段文字"))
    }

    @Test
    fun `磁力链接显示 dn`() {
        assertEquals("Shogun S01", linkTitle("magnet:?xt=urn:btih:abc&dn=Shogun+S01&tr=x"))
        assertEquals("plain.iso", linkTitle("plain.iso"))
    }

    @Test
    fun `剩余时间`() {
        assertEquals("45 秒", eta(45))
        assertEquals("12 分钟", eta(12 * 60 + 5))
        assertEquals("1.5 小时", eta(5400))
        assertEquals("2 天 3 小时", eta(2 * 86_400 + 3 * 3600))
    }

    @Test
    fun `状态归入标签页：暂停和出错都在「下载中」`() {
        assertEquals(TaskTab.Active, TaskState.Paused.tab())
        assertEquals(TaskTab.Active, TaskState.Error.tab())
        assertEquals(TaskTab.Queued, TaskState.Queued.tab())
        assertEquals(TaskTab.Done, TaskState.Completed.tab())
    }
}
