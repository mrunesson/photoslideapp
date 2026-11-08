package org.linuxalert.photo_slide_app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

interface ServerAddressStore {
    val address: Flow<String>
    suspend fun save(address: String)
}

@Singleton
class PreferenceServerAddressStore @Inject constructor(
    @ApplicationContext context: Context,
) : ServerAddressStore {
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("server_settings") },
    )

    override val address: Flow<String> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> preferences[SERVER_ADDRESS] ?: DEFAULT_SERVER_ADDRESS }

    override suspend fun save(address: String) {
        val normalized = validateServerAddress(address)
        dataStore.edit { preferences -> preferences[SERVER_ADDRESS] = normalized }
    }

    companion object {
        val SERVER_ADDRESS = stringPreferencesKey("server_address")
        const val DEFAULT_SERVER_ADDRESS = "http://10.0.2.2:8080"
    }
}

fun validateServerAddress(address: String): String {
    val trimmed = address.trim().trimEnd('/')
    val parsed = trimmed.toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Enter a valid server URL, such as http://192.168.1.10:8080")
    require(parsed.scheme == "http" || parsed.scheme == "https") {
        "The server URL must start with http:// or https://"
    }
    require(parsed.encodedPath == "/" && parsed.query == null && parsed.fragment == null &&
        parsed.username.isEmpty() && parsed.password.isEmpty()
    ) {
        "Enter the server address without a path, query, or credentials"
    }
    return parsed.toString().trimEnd('/')
}