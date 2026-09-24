package com.screenlink.app.core

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "screenlink_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val port = intPreferencesKey("port")
        val accessKey = stringPreferencesKey("access_key")
        val captureWidth = intPreferencesKey("capture_width")
        val jpegQuality = intPreferencesKey("jpeg_quality")
        val maxFps = intPreferencesKey("max_fps")
        val lastHost = stringPreferencesKey("last_host")
        val lastPort = intPreferencesKey("last_port")
        val keepAwake = booleanPreferencesKey("keep_awake")
        val injectMode = stringPreferencesKey("inject_mode")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p -> p.toSettings() }

    suspend fun snapshot(): AppSettings = settings.first()

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.themeMode] = mode.name }
    suspend fun setDynamicColor(on: Boolean) = edit { it[Keys.dynamicColor] = on }
    suspend fun setPort(port: Int) = edit { it[Keys.port] = port }
    suspend fun setAccessKey(key: String) = edit { it[Keys.accessKey] = key }
    suspend fun setCaptureWidth(width: Int) = edit { it[Keys.captureWidth] = width }
    suspend fun setJpegQuality(quality: Int) = edit { it[Keys.jpegQuality] = quality }
    suspend fun setMaxFps(fps: Int) = edit { it[Keys.maxFps] = fps }
    suspend fun setLastHost(host: String) = edit { it[Keys.lastHost] = host }
    suspend fun setLastPort(port: Int) = edit { it[Keys.lastPort] = port }
    suspend fun setKeepAwake(on: Boolean) = edit { it[Keys.keepAwake] = on }
    suspend fun setInjectMode(mode: String) = edit { it[Keys.injectMode] = mode }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { prefs -> block(prefs) }
    }

    private fun Preferences.toSettings(): AppSettings {
        val mode = this[Keys.themeMode]?.let { name ->
            runCatching { ThemeMode.valueOf(name) }.getOrNull()
        } ?: ThemeMode.SYSTEM
        return AppSettings(
            themeMode = mode,
            dynamicColor = this[Keys.dynamicColor] ?: true,
            port = this[Keys.port] ?: 27100,
            accessKey = this[Keys.accessKey] ?: "",
            captureWidth = this[Keys.captureWidth] ?: 720,
            jpegQuality = this[Keys.jpegQuality] ?: 45,
            maxFps = this[Keys.maxFps] ?: 15,
            lastHost = this[Keys.lastHost] ?: "",
            lastPort = this[Keys.lastPort] ?: 27100,
            keepAwake = this[Keys.keepAwake] ?: true,
            injectMode = this[Keys.injectMode] ?: "AUTO",
        )
    }
}
