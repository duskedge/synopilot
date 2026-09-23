package io.github.duskedge.synopilot.network

sealed interface LoginResult {
    data class Success(val session: DsmSession, val deviceToken: String?) : LoginResult
    data object NeedsOtp : LoginResult
    data class Failed(val message: String, val code: Int? = null) : LoginResult
}

object DsmAuth {
    const val SESSION_NAME = "SynoPilot"

    /**
     * 登录。
     * @param otp 两步验证码（第一次登录返回 [LoginResult.NeedsOtp] 后再带上）
     * @param deviceToken 之前「信任这台设备」拿到的令牌，带上后不再需要验证码
     * @param trustDevice 这次登录成功后是否信任本机（返回新的设备令牌）
     */
    suspend fun login(
        api: DsmApi,
        account: String,
        password: String,
        otp: String? = null,
        deviceToken: String? = null,
        trustDevice: Boolean = true,
        deviceName: String = "SynoPilot",
    ): LoginResult {
        val params = buildMap {
            put("account", account)
            put("passwd", password)
            put("session", SESSION_NAME)
            put("format", "sid")
            put("enable_syno_token", "yes")
            if (!otp.isNullOrBlank()) put("otp_code", otp)
            if (!deviceToken.isNullOrBlank()) {
                put("device_id", deviceToken)
                put("device_name", deviceName)
            }
            if (trustDevice && !otp.isNullOrBlank()) {
                put("enable_device_token", "yes")
                put("device_name", deviceName)
            }
        }
        return try {
            val data = api.call(DsmException.AUTH_API, "login", version = 6, params = params).obj()
            val sid = data.str("sid") ?: return LoginResult.Failed("DSM 没有返回会话")
            LoginResult.Success(
                session = DsmSession(sid, data.str("synotoken")),
                deviceToken = data.str("did")?.takeIf { it.isNotBlank() } ?: deviceToken,
            )
        } catch (e: DsmException) {
            if (e.needsOtp) LoginResult.NeedsOtp else LoginResult.Failed(e.message ?: "登录失败", e.code)
        }
    }

    suspend fun logout(api: DsmApi, session: DsmSession) {
        runCatching { api.call(DsmException.AUTH_API, "logout", version = 6, params = mapOf("session" to SESSION_NAME), session = session) }
    }
}
