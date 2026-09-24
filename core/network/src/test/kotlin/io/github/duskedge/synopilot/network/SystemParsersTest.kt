package io.github.duskedge.synopilot.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemParsersTest {
    private fun env(json: String) = DsmApi.parseEnvelope("""{"success":true,"data":$json}""", "t")

    @Test
    fun `定时开关机：解析和编码往返`() {
        val s = SystemParsers.powerSchedule(
            env("""{"poweron_tasks":[{"enabled":true,"hour":8,"min":0,"weekdays":"0,1,2,3,4,5,6"}],"poweroff_tasks":[{"enabled":false,"hour":1,"min":30,"weekdays":"0,1,2,3,4"}]}"""),
        )
        assertEquals(PowerTask(true, 8, 0, (0..6).toSet()), s.powerOn.single())
        assertEquals(PowerTask(false, 1, 30, setOf(0, 1, 2, 3, 4)), s.powerOff.single())
        val encoded = SystemParsers.encodeTasks(s.powerOff).toString()
        assertEquals("""[{"enabled":false,"hour":1,"min":30,"weekdays":"0,1,2,3,4"}]""", encoded)
    }

    @Test
    fun `网卡 MAC`() {
        val list = SystemParsers.interfaces(env("""[{"ifname":"eth0","mac":"00-11-32-aa-bb-cc","ip":"192.168.1.10"},{"ifname":"lo","mac":""}]"""))
        assertEquals(listOf(NetworkInterface("eth0", "00:11:32:AA:BB:CC", "192.168.1.10")), list)
    }

    @Test
    fun `DSM 更新两种格式`() {
        val a = SystemParsers.update(env("""{"update":{"available":true,"version":"DSM 7.2.2-72806 Update 3","reboot":"now"}}"""))
        assertTrue(a.available)
        assertEquals("DSM 7.2.2-72806 Update 3", a.version)
        assertFalse(SystemParsers.update(env("""{"available":false}""")).available)
    }

    @Test
    fun `备份任务`() {
        val t = SystemParsers.backups(
            env("""{"task_list":[{"task_id":1,"name":"照片到 C2","target_type":"cloud","last_bkp_result":"failed","last_bkp_time":"2026/09/22 01:30","next_bkp_time":"2026/09/23 01:30"}]}"""),
        ).single()
        assertEquals("1", t.id)
        assertEquals(BackupResult.Failed, t.result)
        assertTrue(t.lastTime > 0 && t.nextTime - t.lastTime == 86_400L)
    }

    @Test
    fun `证书到期时间`() {
        val c = SystemParsers.certificates(
            env("""{"certificates":[{"id":"a","is_default":true,"subject":{"common_name":"*.homelab.me","sub_alt_name":["homelab.me"]},"issuer":{"common_name":"R11"},"valid_till":"Dec 20 23:59:59 2026 GMT"}]}"""),
        ).single()
        assertEquals("*.homelab.me", c.subject)
        assertEquals(listOf("homelab.me"), c.alternateNames)
        assertEquals(1_797_811_199L, c.validTill)
    }

    @Test
    fun `进程按 CPU 排序，内存从 KB 换算`() {
        val p = SystemParsers.processes(env("""{"process":[{"pid":1,"command":"a","cpu":"1.5","mem":1024},{"pid":2,"command":"b","cpu":"9.0","mem":2048}]}"""))
        assertEquals(listOf("b", "a"), p.map { it.name })
        assertEquals(2048L * 1024, p[0].memoryBytes)
    }
}
