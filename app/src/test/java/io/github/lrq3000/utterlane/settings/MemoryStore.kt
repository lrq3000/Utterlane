package io.github.lrq3000.utterlane.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// DataStore 1.0 uses File.renameTo to replace files, which is not portable to
// Windows JVM tests. Exercise repository transactions against an in-memory store;
// Android still owns the real on-disk DataStore and its atomic file replacement.
internal class MemoryStore : DataStore<Preferences> {
    override val data = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()
    var beforeUpdate: suspend () -> Unit = {}
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        beforeUpdate()
        return mutex.withLock { transform(data.value).also { data.value = it } }
    }
}
