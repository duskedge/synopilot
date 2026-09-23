package io.github.duskedge.synopilot.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    private fun manifest(code: Long, minSupported: Long = 0) = UpdateManifest(
        versionCode = code,
        versionName = "x",
        minSupportedVersionCode = minSupported,
        apk = UpdateManifest.Apk("https://github.com/a/b/x.apk", 10, "a".repeat(64)),
    )

    @Test
    fun `版本号不大于当前版本时是最新`() {
        assertEquals(UpdateCheck.UpToDate, UpdatePolicy.evaluate(100, manifest(100)))
        assertEquals(UpdateCheck.UpToDate, UpdatePolicy.evaluate(100, manifest(99)))
    }

    @Test
    fun `有新版本`() {
        val r = UpdatePolicy.evaluate(100, manifest(101))
        assertTrue(r is UpdateCheck.Available && !r.forced)
    }

    @Test
    fun `低于最低支持版本时强制更新`() {
        val r = UpdatePolicy.evaluate(100, manifest(200, minSupported = 150))
        assertTrue(r is UpdateCheck.Available && r.forced)
        val r2 = UpdatePolicy.evaluate(150, manifest(200, minSupported = 150))
        assertTrue(r2 is UpdateCheck.Available && !r2.forced)
    }

    @Test
    fun `跳过的版本不再提醒，但强制更新例外`() {
        val normal = UpdatePolicy.evaluate(100, manifest(101))
        assertFalse(UpdatePolicy.shouldPrompt(normal, skippedVersionCode = 101))
        assertTrue(UpdatePolicy.shouldPrompt(normal, skippedVersionCode = 0))

        val forced = UpdatePolicy.evaluate(100, manifest(101, minSupported = 101))
        assertTrue(UpdatePolicy.shouldPrompt(forced, skippedVersionCode = 101))
        assertFalse(UpdatePolicy.shouldPrompt(UpdateCheck.UpToDate, 0))
    }

    @Test
    fun `自动检查间隔 24 小时`() {
        val day = UpdatePolicy.AUTO_CHECK_INTERVAL_MS
        assertTrue(UpdatePolicy.isAutoCheckDue(lastCheckAt = 0, now = 1_000))
        assertFalse(UpdatePolicy.isAutoCheckDue(lastCheckAt = 1_000, now = 1_000 + day - 1))
        assertTrue(UpdatePolicy.isAutoCheckDue(lastCheckAt = 1_000, now = 1_000 + day))
        // 手机时间被调回过去时也要检查
        assertTrue(UpdatePolicy.isAutoCheckDue(lastCheckAt = 5_000, now = 1_000))
    }

    @Test
    fun `镜像只改写 GitHub 地址`() {
        val gh = "https://github.com/duskedge/synopilot/releases/latest/download/update.json"
        assertEquals(gh, MirrorUrl.apply(gh, null))
        assertEquals(gh, MirrorUrl.apply(gh, "  "))
        assertEquals("https://m.example.com/$gh", MirrorUrl.apply(gh, "https://m.example.com/"))
        assertEquals("https://m.example.com/$gh", MirrorUrl.apply(gh, "https://m.example.com"))
        val api = "https://api.github.com/repos/a/b/releases"
        assertEquals(api, MirrorUrl.apply(api, "https://m.example.com"))
    }

    @Test
    fun `镜像前缀校验`() {
        assertTrue(MirrorUrl.isValidPrefix(""))
        assertTrue(MirrorUrl.isValidPrefix("https://m.example.com/"))
        assertFalse(MirrorUrl.isValidPrefix("http://m.example.com/"))
        assertFalse(MirrorUrl.isValidPrefix("https://"))
        assertFalse(MirrorUrl.isValidPrefix("https://a b"))
    }

    @Test
    fun `SHA-256`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex("abc".toByteArray()),
        )
        assertEquals(Sha256.hex("abc".toByteArray()), Sha256.hex("abc".byteInputStream()))
    }

    @Test(expected = UpdateException::class)
    fun `清单校验：SHA-256 格式不对`() {
        manifest(1).copy(apk = UpdateManifest.Apk("https://x", 1, "abc")).validate()
    }

    @Test(expected = UpdateException::class)
    fun `清单校验：必须 https`() {
        manifest(1).copy(apk = UpdateManifest.Apk("http://x", 1, "a".repeat(64))).validate()
    }
}
