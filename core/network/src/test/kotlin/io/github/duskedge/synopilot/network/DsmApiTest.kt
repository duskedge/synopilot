package io.github.duskedge.synopilot.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DsmApiTest {

    private class Recorded(val url: String, val form: Parameters?, val token: String?)

    private fun api(handler: (url: String, form: Parameters?) -> String): Pair<DsmApi, MutableList<Recorded>> {
        val calls = mutableListOf<Recorded>()
        val engine = MockEngine { request ->
            val form = (request.body as? FormDataContent)?.formData
            calls += Recorded(request.url.toString(), form, request.headers["X-SYNO-TOKEN"])
            respond(handler(request.url.toString(), form), HttpStatusCode.OK)
        }
        return DsmApi(HttpClient(engine) { expectSuccess = false }, "https://nas.example.com:5001/") to calls
    }

    private fun router(url: String, form: Parameters?, login: String = """{"success":true,"data":{"sid":"SID1","synotoken":"TOK1","did":"DID1"}}"""): String = when {
        url.contains("query.cgi") -> fixture("api_info.json")
        form?.get("api") == "SYNO.API.Auth" -> login
        form?.get("api") == "SYNO.DSM.Info" -> fixture("dsm_info.json")
        else -> """{"success":false,"error":{"code":103}}"""
    }

    @Test
    fun `地址规范化`() {
        assertEquals("https://nas.example.com:5001", DsmApi.normalizeBaseUrl("https://nas.example.com:5001/"))
        assertEquals("http://192.168.1.2:5000", DsmApi.normalizeBaseUrl(" http://192.168.1.2:5000/webapi "))
        assertEquals("https://nas.example.com", DsmApi.normalizeBaseUrl("nas.example.com"))
    }

    @Test
    fun `登录用表单 POST，密码不出现在 URL 里`() = runTest {
        val (api, calls) = api { u, f -> router(u, f) }
        val r = DsmAuth.login(api, "admin", "p@ss word", trustDevice = false)
        assertTrue(r is LoginResult.Success)
        r as LoginResult.Success
        assertEquals(DsmSession("SID1", "TOK1"), r.session)
        val login = calls.last()
        assertEquals("https://nas.example.com:5001/webapi/entry.cgi", login.url)
        assertEquals("p@ss word", login.form?.get("passwd"))
        assertEquals("6", login.form?.get("version"))
        assertEquals("sid", login.form?.get("format"))
        assertTrue(calls.none { it.url.contains("passwd") })
    }

    @Test
    fun `需要两步验证码`() = runTest {
        val (api, _) = api { u, f -> router(u, f, login = """{"success":false,"error":{"code":403}}""") }
        assertEquals(LoginResult.NeedsOtp, DsmAuth.login(api, "admin", "x"))
    }

    @Test
    fun `带验证码并信任设备时返回设备令牌`() = runTest {
        val (api, calls) = api { u, f -> router(u, f) }
        val r = DsmAuth.login(api, "admin", "x", otp = "482913", trustDevice = true) as LoginResult.Success
        assertEquals("DID1", r.deviceToken)
        assertEquals("482913", calls.last().form?.get("otp_code"))
        assertEquals("yes", calls.last().form?.get("enable_device_token"))
    }

    @Test
    fun `密码错误给出中文提示`() = runTest {
        val (api, _) = api { u, f -> router(u, f, login = """{"success":false,"error":{"code":400}}""") }
        val r = DsmAuth.login(api, "admin", "bad") as LoginResult.Failed
        assertEquals("账号或密码不正确", r.message)
    }

    @Test
    fun `会话参数和令牌头，版本限制在 DSM 支持范围内`() = runTest {
        val (api, calls) = api { u, f -> router(u, f) }
        val data = api.call("SYNO.DSM.Info", "getinfo", version = 9, session = DsmSession("S", "T"))
        assertEquals("DS923+", DsmParsers.systemInfo(data).model)
        val c = calls.last()
        assertEquals("2", c.form?.get("version"))
        assertEquals("S", c.form?.get("_sid"))
        assertEquals("T", c.token)
    }

    @Test
    fun `接口不存在和会话过期`() = runTest {
        val (api, _) = api { u, f ->
            if (f?.get("api") == "SYNO.Core.System.Utilization") """{"success":false,"error":{"code":119}}""" else router(u, f)
        }
        try {
            api.call("SYNO.Docker.Container", "list", 1)
            fail()
        } catch (e: DsmException) {
            assertEquals(102, e.code)
        }
        try {
            api.call("SYNO.Core.System.Utilization", "get", 1)
            fail()
        } catch (e: DsmException) {
            assertTrue(e.isSessionExpired)
        }
    }

    @Test
    fun `不是 DSM 的地址`() = runTest {
        val (api, _) = api { _, _ -> "<html>router login</html>" }
        try {
            api.apiInfo()
            fail()
        } catch (e: DsmException) {
            assertEquals("这个地址返回的不是 DSM 接口数据", e.message)
        }
    }
}
