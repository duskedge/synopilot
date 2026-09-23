/**
 * 从 Git 标签计算 versionName / versionCode。
 *
 * versionCode = major * 1_000_000 + minor * 10_000 + patch * 100 + 序号
 *   正式版序号 99；beta.N 为 N（1–49）；rc.N 为 49 + N（50–98）。
 * 保证同一版本号下 beta < rc < 正式版，且新版本的 versionCode 一定更大。
 */
data class AppVersion(val name: String, val code: Int, val isPrerelease: Boolean)

object Versioning {
    private val TAG = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-(beta|rc)\.(\d+))?$""")

    /** 没有标签时（本地开发）使用的版本，versionCode 为 1，任何正式发布的版本都比它大。 */
    val DEV = AppVersion(name = "0.0.0-dev", code = 1, isPrerelease = true)

    fun fromTag(tag: String): AppVersion {
        val match = TAG.matchEntire(tag.trim())
            ?: throw IllegalArgumentException("版本标签格式应为 v1.2.3、v1.2.3-beta.1 或 v1.2.3-rc.1，实际是「$tag」")
        val (major, minor, patch) = match.destructured.toList().take(3).map { it.toInt() }
        val channel = match.groupValues[4]
        val n = match.groupValues[5].toIntOrNull()

        require(major in 0..2099) { "major 超出范围（0–2099）：$major" }
        require(minor in 0..99) { "minor 超出范围（0–99）：$minor" }
        require(patch in 0..99) { "patch 超出范围（0–99）：$patch" }

        val sequence = when (channel) {
            "" -> 99
            "beta" -> n!!.also { require(it in 1..49) { "beta 序号应在 1–49：$it" } }
            "rc" -> 49 + n!!.also { require(it in 1..49) { "rc 序号应在 1–49：$it" } }
            else -> error("未知通道：$channel")
        }
        return AppVersion(
            name = tag.trim().removePrefix("v"),
            code = major * 1_000_000 + minor * 10_000 + patch * 100 + sequence,
            isPrerelease = channel.isNotEmpty(),
        )
    }
}
