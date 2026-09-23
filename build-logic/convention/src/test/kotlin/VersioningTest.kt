import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VersioningTest {

    @Test
    fun `正式版序号为 99`() {
        val v = Versioning.fromTag("v1.2.0")
        assertEquals("1.2.0", v.name)
        assertEquals(1_020_099, v.code)
        assertFalse(v.isPrerelease)
    }

    @Test
    fun `beta 和 rc 的编号`() {
        assertEquals(1_030_001, Versioning.fromTag("v1.3.0-beta.1").code)
        assertEquals(1_030_050, Versioning.fromTag("v1.3.0-rc.1").code)
        assertTrue(Versioning.fromTag("v1.3.0-rc.1").isPrerelease)
    }

    @Test
    fun `同一版本号下 beta 小于 rc 小于正式版`() {
        val beta = Versioning.fromTag("v1.3.0-beta.49").code
        val rc = Versioning.fromTag("v1.3.0-rc.1").code
        val stable = Versioning.fromTag("v1.3.0").code
        assertTrue(beta < rc)
        assertTrue(rc < Versioning.fromTag("v1.3.0-rc.49").code)
        assertTrue(Versioning.fromTag("v1.3.0-rc.49").code < stable)
    }

    @Test
    fun `新版本总是更大`() {
        assertTrue(Versioning.fromTag("v1.2.99").code < Versioning.fromTag("v1.3.0-beta.1").code)
        assertTrue(Versioning.fromTag("v1.99.99").code < Versioning.fromTag("v2.0.0-beta.1").code)
        assertTrue(Versioning.DEV.code < Versioning.fromTag("v0.1.0-beta.1").code)
    }

    @Test
    fun `不带 v 前缀也可以`() {
        assertEquals(Versioning.fromTag("v0.1.0").code, Versioning.fromTag("0.1.0").code)
    }

    @Test
    fun `非法标签会报错`() {
        listOf("1.2", "v1.2.3-alpha.1", "v1.2.3-beta", "v1.2.3-beta.50", "v1.100.0", "release-1").forEach { tag ->
            assertThrows(IllegalArgumentException::class.java) { Versioning.fromTag(tag) }
        }
    }
}
