package io.github.duskedge.synopilot.security

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 敏感操作（重启、关机）前的身份验证：指纹 / 面容，或者锁屏密码。 */
object Identity {
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    enum class Result { Success, Cancelled, Unavailable }

    /**
     * 手机没有设置锁屏时返回 [Result.Unavailable]，由调用方决定是否继续（界面上已经有二次确认）。
     */
    suspend fun confirm(context: Context, title: String, subtitle: String): Result {
        val activity = context.findActivity() ?: return Result.Unavailable
        if (BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) return Result.Unavailable
        return suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(Result.Success)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (cont.isActive) cont.resume(Result.Cancelled)
                    }
                },
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(AUTHENTICATORS)
                    .build(),
            )
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }

    private tailrec fun Context.findActivity(): FragmentActivity? = when (this) {
        is FragmentActivity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
