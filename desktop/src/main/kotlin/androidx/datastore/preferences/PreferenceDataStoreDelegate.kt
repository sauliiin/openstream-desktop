package androidx.datastore.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.mdblisthub.desktop.AppDirs
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import okio.Path.Companion.toOkioPath

private val stores = ConcurrentHashMap<String, DataStore<Preferences>>()

/** Android's `by preferencesDataStore(name)`, writing to the app's data folder. */
fun preferencesDataStore(name: String): ReadOnlyProperty<Context, DataStore<Preferences>> =
    object : ReadOnlyProperty<Context, DataStore<Preferences>> {
        override fun getValue(thisRef: Context, property: KProperty<*>): DataStore<Preferences> =
            stores.getOrPut(name) {
                PreferenceDataStoreFactory.createWithPath(
                    produceFile = { File(AppDirs.datastore, "$name.preferences_pb").toOkioPath() },
                )
            }
    }
