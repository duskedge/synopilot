package io.github.duskedge.synopilot.network

/** DSM WebAPI 返回的错误。[code] 为 DSM 错误码。 */
class DsmException(
    val code: Int,
    val api: String,
    message: String = describe(code, api),
) : Exception(message) {

    /** 会话过期或失效，需要重新登录 */
    val isSessionExpired: Boolean get() = code in SESSION_CODES

    /** 需要两步验证码 */
    val needsOtp: Boolean get() = api == AUTH_API && code == 403

    companion object {
        const val AUTH_API = "SYNO.API.Auth"
        private val SESSION_CODES = setOf(106, 107, 119)

        fun describe(code: Int, api: String): String {
            if (api == AUTH_API) {
                AUTH_ERRORS[code]?.let { return it }
            }
            return COMMON_ERRORS[code] ?: "操作失败（DSM 错误码 $code）"
        }

        private val COMMON_ERRORS = mapOf(
            100 to "DSM 返回未知错误",
            101 to "请求缺少参数",
            102 to "NAS 上没有这个功能（可能需要安装对应套件）",
            103 to "NAS 不支持这个操作",
            104 to "DSM 版本不支持这个功能",
            105 to "没有权限，请使用管理员账号",
            106 to "登录已过期",
            107 to "登录被中断（可能在其他地方重复登录）",
            114 to "请求参数有误",
            117 to "需要管理员权限",
            119 to "登录已失效",
            120 to "请求参数有误",
            150 to "当前 IP 不允许访问",
        )

        private val AUTH_ERRORS = mapOf(
            400 to "账号或密码不正确",
            401 to "账号已被停用",
            402 to "没有权限登录",
            403 to "需要两步验证码",
            404 to "两步验证码不正确",
            406 to "这个账号必须先启用两步验证",
            407 to "当前 IP 已被 DSM 封锁，请稍后再试或在 DSM 中解除",
            408 to "密码已过期，请先在 DSM 网页端修改",
            409 to "密码已过期，请先在 DSM 网页端修改",
            410 to "密码必须修改，请先在 DSM 网页端修改",
        )
    }
}

/** 找原因链中的某种异常（Ktor / OkHttp 会把底层异常包几层）。 */
inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var e: Throwable? = this
    val seen = HashSet<Throwable>()
    while (e != null && seen.add(e)) {
        if (e is T) return e
        e = e.cause
    }
    return null
}
