package de.sofahub.app.core.storage

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import de.sofahub.app.core.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.InputStream
import java.io.OutputStream

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    /** Atomare Änderung: DataStore serialisiert konkurrierende Schreibzugriffe. */
    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings
}

object AppSettingsSerializer : Serializer<AppSettings> {
    override val defaultValue: AppSettings = AppSettings()

    override suspend fun readFrom(input: InputStream): AppSettings {
        val text = input.readBytes().toString(Charsets.UTF_8)
        if (text.isBlank()) return defaultValue
        return try {
            SettingsCodec.decode(text)
        } catch (e: IllegalStateException) {
            throw CorruptionException("Einstellungen nicht lesbar", e)
        }
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        output.write(SettingsCodec.encode(t).toByteArray(Charsets.UTF_8))
    }
}

class DataStoreSettingsRepository(private val store: DataStore<AppSettings>) : SettingsRepository {
    override val settings: Flow<AppSettings> = store.data
    override suspend fun current(): AppSettings = store.data.first()
    override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings = store.updateData(transform)

    companion object {
        fun create(context: Context, scope: CoroutineScope): DataStoreSettingsRepository {
            val store = DataStoreFactory.create(
                serializer = AppSettingsSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { AppSettings() },
                scope = scope,
                produceFile = { File(context.noBackupFilesDir, "settings.json") },
            )
            return DataStoreSettingsRepository(store)
        }
    }
}
