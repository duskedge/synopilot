package io.github.duskedge.synopilot.updater

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpdateClientTest {

    private val manifestJson = """
        {
          "versionCode": 1020099,
          "versionName": "1.2.0",
          "minSupportedVersionCode": 1000099,
          "apk": {
            "url": "https://github.com/duskedge/synopilot/releases/download/v1.2.0/SynoPilot-1.2.0.apk",
            "size": 11534336,
            "sha256": "${"ab".repeat(32)}"
          },
          "notes": "- 新功能",
          "extra": "未知字段应被忽略"
        }
    """.trimIndent()

    private fun client(handler: (String) -> Pair<HttpStatusCode, String>): Pair<UpdateClient, MutableList<String>> {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            val url = request.url.toString()
            requested += url
            val (status, body) = handler(url)
            respond(body, status)
        }
        return UpdateClient(HttpClient(engine) { expectSuccess = false }, "duskedge/synopilot") to requested
    }

    @Test
    fun `正式版读取 latest 下载地址`() = runTest {
        val (c, requested) = client { HttpStatusCode.OK to manifestJson }
        val m = c.fetchLatest(UpdateChannel.Stable, mirrorPrefix = null)
        assertEquals(1_020_099L, m.versionCode)
        assertEquals("- 新功能", m.notes)
        assertEquals(listOf("https://github.com/duskedge/synopilot/releases/latest/download/update.json"), requested)
    }

    @Test
    fun `正式版使用镜像前缀`() = runTest {
        val (c, requested) = client { HttpStatusCode.OK to manifestJson }
        c.fetchLatest(UpdateChannel.Stable, mirrorPrefix = "https://m.example.com/")
        assertEquals("https://m.example.com/https://github.com/duskedge/synopilot/releases/latest/download/update.json", requested.single())
    }

    @Test
    fun `没有发布过正式版`() = runTest {
        val (c, _) = client { HttpStatusCode.NotFound to "Not Found" }
        try {
            c.fetchLatest(UpdateChannel.Stable, null)
            fail()
        } catch (e: UpdateException) {
            assertEquals("还没有发布正式版", e.message)
        }
    }

    @Test
    fun `格式错误的清单`() = runTest {
        val (c, _) = client { HttpStatusCode.OK to "{not json" }
        try {
            c.fetchLatest(UpdateChannel.Stable, null)
            fail()
        } catch (e: UpdateException) {
            assertEquals("更新信息格式不对", e.message)
        }
    }

    @Test
    fun `测试版取最新一个带 update-json 的版本（跳过草稿）`() = runTest {
        val releases = """
            [
              {"draft": true, "prerelease": true, "assets": [{"name": "update.json", "browser_download_url": "https://github.com/x/draft.json"}]},
              {"draft": false, "prerelease": true, "assets": [{"name": "SynoPilot-1.3.0-beta.1.apk", "browser_download_url": "https://github.com/x/a.apk"},
                                                            {"name": "update.json", "browser_download_url": "https://github.com/x/beta.json"}]},
              {"draft": false, "prerelease": false, "assets": [{"name": "update.json", "browser_download_url": "https://github.com/x/stable.json"}]}
            ]
        """.trimIndent()
        val (c, requested) = client { url ->
            if (url.startsWith("https://api.github.com/")) HttpStatusCode.OK to releases else HttpStatusCode.OK to manifestJson
        }
        c.fetchLatest(UpdateChannel.Beta, mirrorPrefix = null)
        assertTrue(requested[0].startsWith("https://api.github.com/repos/duskedge/synopilot/releases"))
        assertEquals("https://github.com/x/beta.json", requested[1])
    }

    @Test
    fun `GitHub 接口限流`() = runTest {
        val (c, _) = client { HttpStatusCode.Forbidden to "rate limited" }
        try {
            c.fetchLatest(UpdateChannel.Beta, null)
            fail()
        } catch (e: UpdateException) {
            assertEquals("GitHub 接口请求太频繁，请稍后再试", e.message)
        }
    }
}
