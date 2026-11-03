package com.ywpc.tlkey.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** 非敏感设置与诊断摘要。 */
class AppPreferences(private val context: Context) {

    private object Keys {
        val LAST_ACTION = stringPreferencesKey("last_action")
        val LAST_TRACE = stringPreferencesKey("last_trace")
    }

    suspend fun saveLastResult(action: String, traceSummary: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.LAST_ACTION] = action
            prefs[Keys.LAST_TRACE] = traceSummary
        }
    }

    suspend fun lastResult(): Pair<String, String>? {
        val prefs = context.settingsDataStore.data.first()
        val action = prefs[Keys.LAST_ACTION] ?: return null
        val trace = prefs[Keys.LAST_TRACE] ?: return null
        return action to trace
    }
}
