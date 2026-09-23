package io.github.duskedge.synopilot.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DockerParsersTest {

    @Test
    fun `容器列表：异常退出排最前，资源占用按名字合并`() {
        val list = DockerParsers.containers(data("docker_containers.json"), data("docker_resources.json"))
        assertEquals(listOf("crashy", "jellyfin", "qbittorrent", "old-backup"), list.map { it.name })
        val crashy = list[0]
        assertEquals(ContainerState.Exited, crashy.state)
        assertEquals(137, crashy.exitCode)
        assertEquals(ContainerState.Stopped, list[3].state)
        val jf = list[1]
        assertEquals(12.5, jf.cpuPercent!!, 0.001)
        assertEquals(524_288_000L, jf.memoryBytes)
        assertNull(jf.memoryLimitBytes)
        assertEquals(PortMapping(8096, 8096, "tcp"), jf.ports.single())
        assertEquals(2_147_483_648L, list[2].memoryLimitBytes)
        assertNull(crashy.cpuPercent)
    }

    @Test
    fun `没有资源接口时照样能列出来`() {
        val list = DockerParsers.containers(data("docker_containers.json"), null)
        assertEquals(4, list.size)
        assertTrue(list.all { it.cpuPercent == null })
    }

    @Test
    fun `Compose 项目：按 id 为键的对象`() {
        val p = DockerParsers.projects(data("docker_projects.json"))
        assertEquals(listOf("arr", "media"), p.map { it.name })
        assertEquals("/docker/arr", p[0].path)
        assertEquals(listOf("jellyfin", "qbittorrent"), p[1].containerNames)
    }

    @Test
    fun `容器详情`() {
        val d = DockerParsers.detail(data("docker_detail.json"))
        assertEquals("TZ" to "Asia/Shanghai", d.env[0])
        assertEquals(2, d.volumes.size)
        assertTrue(d.volumes[1].readOnly)
        assertEquals("unless-stopped", d.restartPolicy)
        assertEquals(listOf("bridge" to "172.17.0.2"), d.networks)
    }

    @Test
    fun `日志去掉行尾换行`() {
        val logs = DockerParsers.logs(data("docker_logs.json"))
        assertEquals("[WRN] slow", logs[0].text)
        assertEquals("stderr", logs[0].stream)
    }
}
