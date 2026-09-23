package io.github.duskedge.synopilot.updater

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.updaterStore: DataStore<Preferences> by preferencesDataStore(name = "updater")

data class UpdateSettings(
    val lastCheckAt: Long = 0,
    val skippedVersionCode: Long = 0,
    /** 最近一次后台通知过的版本，避免同一版本每天重复通知 */
    val notifiedVersionCode: Long = 0,
    val betaChannel: Boolean = false,
    val mirrorPrefix: String = "",
) {
    val channel: UpdateChannel get() = if (betaChannel) UpdateChannel.Beta else UpdateChannel.Stable
}

class UpdatePreferences(context: Context) {
    private val store = context.applicationContext.updaterStore

    val settings: Flow<UpdateSettings> = store.data.map { p ->
        UpdateSettings(
            lastCheckAt = p[LAST_CHECK_AT] ?: 0,
            skippedVersionCode = p[SKIPPED] ?: 0,
            notifiedVersionCode = p[NOTIFIED] ?: 0,
            betaChannel = p[BETA] ?: false,
            mirrorPrefix = p[MIRROR] ?: "",
        )
    }

    suspend fun setLastCheckAt(time: Long) {
        store.edit { it[LAST_CHECK_AT] = time }
    }
    suspend fun setSkipped(versionCode: Long) {
        store.edit { it[SKIPPED] = versionCode }
    }
    suspend fun setNotified(versionCode: Long) {
        store.edit { it[NOTIFIED] = versionCode }
    }
    suspend fun setBetaChannel(enabled: Boolean) {
        store.edit {
            it[BETA] = enabled
            it[LAST_CHECK_AT] = 0 // 切换通道后下次打开立即检查
        }
    }
    suspend fun setMirrorPrefix(prefix: String) {
        store.edit { it[MIRROR] = prefix.trim() }
    }

    private companion object {
        val LAST_CHECK_AT = longPreferencesKey("last_check_at")
        val SKIPPED = longPreferencesKey("skipped_version_code")
        val NOTIFIED = longPreferencesKey("notified_version_code")
        val BETA = booleanPreferencesKey("beta_channel")
        val MIRROR = stringPreferencesKey("mirror_prefix")
    }
}
