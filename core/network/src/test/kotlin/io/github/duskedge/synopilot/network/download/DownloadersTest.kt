package io.github.duskedge.synopilot.network.download

import io.github.duskedge.synopilot.network.DsmApi
import io.github.duskedge.synopilot.network.data
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DownloadersTest {

    @Test
    fun `状态映射`() {
        assertEquals(TaskState.Downloading, TaskStates.qbittorrent("stalledDL"))
        assertEquals(TaskState.Paused, TaskStates.qbittorrent("stoppedDL"))
        assertEquals(TaskState.Completed, TaskStates.qbittorrent("pausedUP"))
        assertEquals(TaskState.Seeding, TaskStates.qbittorrent("stalledUP"))
        assertEquals(TaskState.Error, TaskStates.qbittorrent("missingFiles"))
        assertEquals(TaskState.Completed, TaskStates.transmission(0, 1.0, 0))
        assertEquals(TaskState.Paused, TaskStates.transmission(0, 0.4, 0))
        assertEquals(TaskState.Error, TaskStates.transmission(4, 0.4, 3))
        assertEquals(TaskState.Seeding, TaskStates.transmission(6, 1.0, null))
        assertEquals(TaskState.Checking, TaskStates.downloadStation("hash_checking"))
        assertEquals(TaskState.Completed, TaskStates.downloadStation("finished"))
    }

    @Test
    fun `qBittorrent 任务解析，无穷大 ETA 视为未知`() {
        val json = DsmApi.json.parseToJsonElement(
            """[{"hash":"abc","name":"a","size":100,"progress":0.5,"dlspeed":10,"upspeed":1,"eta":8640000,"state":"downloading",
               "ratio":0.1,"save_path":"/downloads","num_seeds":3,"num_leechs":1,"category":""},
               {"hash":"def","name":"b","size":10,"progress":1,"eta":0,"state":"uploading","category":"movie"}]""",
        )
        val t = QBittorrentClient.parseTorrents("qb", json)
        assertEquals("qb:abc", t[0].key)
        assertNull(t[0].etaSec)
        assertNull(t[0].category)
        assertEquals(TaskState.Seeding, t[1].state)
        assertEquals("movie", t[1].category)
    }

    @Test
    fun `Transmission 任务解析`() {
        val json = DsmApi.json.parseToJsonElement(
            """{"torrents":[{"id":7,"name":"x","totalSize":1000,"percentDone":0.25,"rateDownload":5,"eta":-1,"status":4,
               "uploadRatio":-1,"downloadDir":"/dl","error":0,"errorString":"","labels":["tv"]}]}""",
        )
        val t = TransmissionClient.parseTorrents("tr", json).single()
        assertEquals("7", t.nativeId)
        assertEquals(TaskState.Downloading, t.state)
        assertNull(t.etaSec)
        assertNull(t.ratio)
        assertNull(t.error)
        assertEquals("tv", t.category)
    }

    @Test
    fun `Download Station 任务解析`() {
        val t = DownloadStationClient.parseTasks("ds", data("ds_tasks.json"))
        assertEquals(0.5, t[0].progress, 0.001)
        assertEquals(300L, t[0].etaSec)
        assertEquals("downloads/iso", t[0].savePath)
        assertEquals(12, t[0].seeds)
        assertEquals(TaskState.Error, t[1].state)
        assertEquals("broken_link", t[1].error)
    }

    @Test
    fun `qBittorrent 登录带 Referer，SID 放进后续 Cookie，新版用 stop`() = runTest {
        val seen = mutableListOf<String>()
        val engine = MockEngine { r ->
            val path = r.url.encodedPath
            seen += "$path ${r.headers[HttpHeaders.Cookie]} ${r.headers[HttpHeaders.Referrer]}"
            when (path) {
                "/api/v2/auth/login" -> respond("Ok.", HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "SID=s1; HttpOnly; path=/"))
                "/api/v2/app/webapiVersion" -> respond("2.11.2")
                else -> respond("")
            }
        }
        val qb = QBittorrentClient("qb", HttpClient(engine) { expectSuccess = false }, "http://nas:8080/", "admin", "pw")
        qb.pause(listOf("a", "b"))
        assertEquals("/api/v2/auth/login null http://nas:8080", seen[0])
        assertEquals("/api/v2/torrents/stop SID=s1 http://nas:8080", seen.last())
    }

    @Test
    fun `qBittorrent 5 的会话 Cookie 叫 QBT_SID_端口，登录成功返回 204`() = runTest {
        assertEquals(
            "QBT_SID_28888=abc",
            QBittorrentClient.sessionCookie(listOf("QBT_SID_28888=abc; HttpOnly; expires=Thu, 24-Sep-2026 02:37:31 GMT; path=/")),
        )
        assertEquals("SID=x", QBittorrentClient.sessionCookie(listOf("other=1; path=/", "SID=x; HttpOnly")))
        assertNull(QBittorrentClient.sessionCookie(listOf("other=1")))

        val cookies = mutableListOf<String?>()
        val engine = MockEngine { r ->
            when (r.url.encodedPath) {
                "/api/v2/auth/login" -> respond("", HttpStatusCode.NoContent, headersOf(HttpHeaders.SetCookie, "QBT_SID_28888=s5; HttpOnly; path=/"))
                else -> {
                    cookies += r.headers[HttpHeaders.Cookie]
                    respond("v5.2.3")
                }
            }
        }
        val qb = QBittorrentClient("qb", HttpClient(engine) { expectSuccess = false }, "https://qb.example.com:4443", "admin", "pw")
        assertEquals("v5.2.3", qb.version().app)
        assertTrue(cookies.all { it == "QBT_SID_28888=s5" })
    }

    @Test
    fun `qBittorrent 密码错误`() = runTest {
        val engine = MockEngine { respond("Fails.") }
        val qb = QBittorrentClient("qb", HttpClient(engine) { expectSuccess = false }, "http://nas:8080", "admin", "bad")
        try {
            qb.list()
            fail()
        } catch (e: DownloaderException) {
            assertTrue(e.authFailed)
        }
    }

    @Test
    fun `Transmission 409 后带上会话 ID 重发`() = runTest {
        val ids = mutableListOf<String?>()
        val engine = MockEngine { r ->
            val sid = r.headers["X-Transmission-Session-Id"]
            ids += sid
            assertTrue((r.body as TextContent).text.contains("\"torrent-stop\""))
            if (sid == null) {
                respond("", HttpStatusCode.Conflict, headersOf("X-Transmission-Session-Id", "tok"))
            } else {
                respond("""{"result":"success","arguments":{}}""")
            }
        }
        val tr = TransmissionClient("tr", HttpClient(engine) { expectSuccess = false }, "http://nas:9091", "", "")
        tr.pause(listOf("1"))
        tr.pause(listOf("2"))
        assertEquals(listOf(null, "tok", "tok"), ids)
    }
}
