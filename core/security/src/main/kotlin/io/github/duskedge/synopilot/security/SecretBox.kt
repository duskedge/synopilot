package io.github.duskedge.synopilot.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 里的 AES-256-GCM 密钥加解密敏感数据（DSM 密码、会话令牌、下载器密码）。
 * 密钥在硬件安全模块中生成且不可导出：即使应用数据被整个拷走，也无法在别的设备上解密。
 */
interface SecretBox {
    fun encrypt(plain: String): String
    fun decrypt(sealed: String): String
}

class KeystoreSecretBox(private val alias: String = "synopilot.secrets") : SecretBox {

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(1 + cipher.iv.size + body.size)
        out[0] = cipher.iv.size.toByte()
        System.arraycopy(cipher.iv, 0, out, 1, cipher.iv.size)
        System.arraycopy(body, 0, out, 1 + cipher.iv.size, body.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    override fun decrypt(sealed: String): String {
        val data = Base64.decode(sealed, Base64.NO_WRAP)
        val ivLen = data[0].toInt()
        val iv = data.copyOfRange(1, 1 + ivLen)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(data, 1 + ivLen, data.size - 1 - ivLen), Charsets.UTF_8)
    }

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
