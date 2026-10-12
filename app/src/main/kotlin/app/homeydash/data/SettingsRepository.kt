package app.homeydash.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class SavedHomey(val address: String, val apiKey: String)

private val Context.dataStore by preferencesDataStore(name = "homey")

/** Remembers the Homey address and API key so they are entered only once. */
class SettingsRepository(private val context: Context) {

    val saved: Flow<SavedHomey?> = context.dataStore.data.map { prefs ->
        val address = prefs[ADDRESS]
        val key = prefs[API_KEY]
        if (address.isNullOrBlank() || key.isNullOrBlank()) null else SavedHomey(address, key)
    }

    suspend fun save(homey: SavedHomey) {
        context.dataStore.edit {
            it[ADDRESS] = homey.address
            it[API_KEY] = homey.apiKey
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    private companion object {
        val ADDRESS = stringPreferencesKey("address")
        val API_KEY = stringPreferencesKey("api_key")
    }
}
