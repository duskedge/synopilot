package io.github.duskedge.synopilot.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.duskedge.synopilot.network.TrustedPins
import io.github.duskedge.synopilot.security.SecretBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

private val Context.deviceStore: DataStore<Preferences> by preferencesDataStore(name = "devices")

/**
 * 设备配置和加密凭证的存储。
 * - 设备列表（地址、证书指纹、地址历史）以 JSON 明文保存，不含敏感信息；
 * - 密码、会话令牌、设备令牌用 [SecretBox]（Android Keystore）加密后保存。
 */
class DeviceRepository(context: Context, private val secretBox: SecretBox) {
    private val store = context.applicationContext.deviceStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val devices: Flow<List<Device>> = store.data.map { p ->
        p[DEVICES]?.let { runCatching { json.decodeFromString<List<Device>>(it) }.getOrNull() }.orEmpty()
    }

    val currentDeviceId: Flow<String?> = store.data.map { it[CURRENT] }

    val currentDevice: Flow<Device?> = combine(devices, currentDeviceId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    val appSettings: Flow<AppSettings> = store.data.map { p ->
        p[SETTINGS]?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() } ?: AppSettings()
    }

    // ---------- 证书固定 ----------

    private val pins = ConcurrentHashMap.newKeySet<String>()
    private val temporaryPins = ConcurrentHashMap.newKeySet<String>()

    /** 给 HTTP 客户端用：已保存设备的证书 + 首次接入过程中临时信任的证书 */
    val trustedPins = TrustedPins { sha -> sha in pins || sha in temporaryPins }

    fun startPinSync(scope: CoroutineScope) {
        scope.launch {
            devices.collect { list ->
                val all = list.flatMap { it.pinnedCerts }.toSet()
                pins.retainAll(all)
                pins.addAll(all)
            }
        }
    }

    /** 首次接入时用户确认信任的证书，保存设备前临时生效 */
    fun trustTemporarily(sha256: String) {
        temporaryPins += sha256
    }

    // ---------- 设备 ----------

    suspend fun device(id: String): Device? = devices.first().firstOrNull { it.id == id }

    suspend fun upsert(device: Device) {
        store.edit { p ->
            val list = decodeDevices(p).filterNot { it.id == device.id } + device
            p[DEVICES] = json.encodeToString(list)
        }
        pins.addAll(device.pinnedCerts)
    }

    suspend fun update(id: String, transform: (Device) -> Device) {
        store.edit { p ->
            val list = decodeDevices(p).map { if (it.id == id) transform(it) else it }
            p[DEVICES] = json.encodeToString(list)
        }
    }

    suspend fun remove(id: String) {
        store.edit { p ->
            p[DEVICES] = json.encodeToString(decodeDevices(p).filterNot { it.id == id })
            p.remove(secretKey(id))
            if (p[CURRENT] == id) p.remove(CURRENT)
        }
    }

    suspend fun setCurrent(id: String) {
        store.edit { it[CURRENT] = id }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val current = p[SETTINGS]?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() } ?: AppSettings()
            p[SETTINGS] = json.encodeToString(transform(current))
        }
    }

    // ---------- 凭证（加密） ----------

    suspend fun secrets(id: String): DeviceSecrets? {
        val sealed = store.data.first()[secretKey(id)] ?: return null
        return runCatching { json.decodeFromString<DeviceSecrets>(secretBox.decrypt(sealed)) }.getOrNull()
    }

    suspend fun saveSecrets(id: String, secrets: DeviceSecrets) {
        val sealed = secretBox.encrypt(json.encodeToString(secrets))
        store.edit { it[secretKey(id)] = sealed }
    }

    private fun decodeDevices(p: Preferences): List<Device> =
        p[DEVICES]?.let { runCatching { json.decodeFromString<List<Device>>(it) }.getOrNull() }.orEmpty()

    private fun secretKey(id: String) = stringPreferencesKey("secrets_$id")

    private companion object {
        val DEVICES = stringPreferencesKey("devices_json")
        val CURRENT = stringPreferencesKey("current_device_id")
        val SETTINGS = stringPreferencesKey("app_settings")
    }
}
