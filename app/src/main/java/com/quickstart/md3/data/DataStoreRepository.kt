package com.quickstart.md3.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "quickstart_md3_prefs")

class DataStoreRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dataDir = File(appContext.filesDir, "quickstart_data").apply { mkdirs() }
    private val fileLock = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadedDeferred = CompletableDeferred<Unit>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
    private val prettyJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val keySettings = stringPreferencesKey("app_settings_json")

    val settingsFlow: Flow<AppSettings> = appContext.dataStore.data.map { prefs ->
        val raw = prefs[keySettings]
        val resolved = if (raw.isNullOrBlank()) AppSettings()
        else runCatching {
            val decoded = json.decodeFromString<AppSettings>(raw)
            migrateSettings(raw, decoded)
        }.getOrDefault(AppSettings())
        if (!File(dataDir, "settings_sync.json").exists()) {
            writeJsonFile("settings_sync.json", resolved)
        }
        resolved
    }

    private fun migrateSettings(raw: String, decoded: AppSettings): AppSettings {
        var s = decoded
        if (s.settingsVersion < 2 || (!raw.contains("\"settingsVersion\":2") && s.settingsVersion < 3)) {
            s = s.copy(settingsVersion = 2, outsideDimAlpha = 0.0f)
        }
        if (s.settingsVersion < 3) {
            val actions = s.enabledMenuActions.toMutableList()
            if (AppMenuAction.LAUNCH_FREEFORM !in actions) {
                actions.add(AppMenuAction.LAUNCH_FREEFORM)
            }
            if (AppMenuAction.SILENT_UNINSTALL !in actions) {
                actions.add(AppMenuAction.SILENT_UNINSTALL)
            }
            s = s.copy(
                settingsVersion = 3,
                enabledMenuActions = actions
            )
        }
        if (s.settingsVersion < 4) {
            val updatedAirplane = if (
                s.airplaneKey.click == GestureAction.OPEN_QUICK_MENU &&
                s.airplaneKey.longPress == GestureAction.TOGGLE_PIANO_MODE
            ) {
                s.airplaneKey.copy(
                    click = GestureAction.TOGGLE_SHORTCUTS,
                    longPress = GestureAction.OPEN_QUICK_MENU
                )
            } else {
                s.airplaneKey
            }
            s = s.copy(
                settingsVersion = 4,
                airplaneKey = updatedAirplane
            )
        }
        if (s.settingsVersion < 5) {
            val hasGemini = s.searchEngines.any {
                it.id == "gemini" || it.name.equals("Gemini", ignoreCase = true)
            }
            val updatedEngines = if (hasGemini) {
                s.searchEngines
            } else {
                listOf(SearchEngineItem("gemini", "Gemini", "https://gemini.google.com/app?q=%s")) + s.searchEngines
            }
            s = s.copy(
                settingsVersion = 5,
                searchEngines = updatedEngines
            )
        }
        return s
    }

    private val _collections = MutableStateFlow<List<AppCollection>>(emptyList())
    val collections: StateFlow<List<AppCollection>> = _collections.asStateFlow()

    private val _savedActivities = MutableStateFlow<List<SavedActivityItem>>(emptyList())
    val savedActivities: StateFlow<List<SavedActivityItem>> = _savedActivities.asStateFlow()

    private val _t9QuickKeys = MutableStateFlow<List<T9QuickKey>>(emptyList())
    val t9QuickKeys: StateFlow<List<T9QuickKey>> = _t9QuickKeys.asStateFlow()

    private val _customNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val customNames: StateFlow<Map<String, String>> = _customNames.asStateFlow()

    private val _hiddenPackages = MutableStateFlow<Set<String>>(emptySet())
    val hiddenPackages: StateFlow<Set<String>> = _hiddenPackages.asStateFlow()

    private val _polyphones = MutableStateFlow<List<PolyphoneEntry>>(emptyList())
    val polyphones: StateFlow<List<PolyphoneEntry>> = _polyphones.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<BookmarkItem>>(emptyList())
    val bookmarks: StateFlow<List<BookmarkItem>> = _bookmarks.asStateFlow()

    private val _usageStats = MutableStateFlow<Map<String, UsageStat>>(emptyMap())
    val usageStats: StateFlow<Map<String, UsageStat>> = _usageStats.asStateFlow()

    init {
        ioScope.launch {
            try {
                _collections.value = readJsonFile("collections.json", emptyList())
                _savedActivities.value = readJsonFile("saved_activities.json", emptyList())
                _t9QuickKeys.value = readJsonFile("t9_quick_keys.json", emptyList())
                _customNames.value = readJsonFile("custom_names.json", emptyMap())
                _hiddenPackages.value = readJsonFile("hidden_packages.json", emptySet())
                _polyphones.value = readJsonFile("polyphones.json", emptyList())
                _bookmarks.value = readJsonFile("bookmarks.json", defaultBookmarks())
                _usageStats.value = readJsonFile("usage_stats.json", emptyMap())
            } finally {
                loadedDeferred.complete(Unit)
            }
        }
    }

    suspend fun awaitLoaded() {
        loadedDeferred.await()
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings {
        var updatedValue = AppSettings()
        appContext.dataStore.edit { prefs ->
            val raw = prefs[keySettings]
            val current = raw?.let {
                runCatching {
                    val dec = json.decodeFromString<AppSettings>(it)
                    migrateSettings(it, dec)
                }.getOrNull()
            } ?: AppSettings()
            val next = transform(current).copy(settingsVersion = 5)
            updatedValue = next
            prefs[keySettings] = json.encodeToString(next)
        }
        writeJsonFile("settings_sync.json", updatedValue)
        return updatedValue
    }

    fun loadInitialSettingsSync(): AppSettings {
        val file = File(dataDir, "settings_sync.json")
        if (!file.exists()) return AppSettings()
        return runCatching {
            val raw = file.readText()
            migrateSettings(raw, json.decodeFromString(raw))
        }.getOrDefault(AppSettings())
    }

    suspend fun recordAppLaunch(packageName: String) = withContext(Dispatchers.IO) {
        val current = _usageStats.value.toMutableMap()
        val prev = current[packageName] ?: UsageStat()
        current[packageName] = UsageStat(
            launchCount = prev.launchCount + 1,
            lastLaunchTime = System.currentTimeMillis()
        )
        _usageStats.value = current
        writeJsonFile("usage_stats.json", current)
    }

    suspend fun setCustomName(packageName: String, customName: String) = withContext(Dispatchers.IO) {
        val map = _customNames.value.toMutableMap()
        if (customName.isBlank()) map.remove(packageName) else map[packageName] = customName.trim()
        _customNames.value = map
        writeJsonFile("custom_names.json", map)
    }

    suspend fun toggleHidden(packageName: String) = withContext(Dispatchers.IO) {
        val set = _hiddenPackages.value.toMutableSet()
        if (packageName in set) set.remove(packageName) else set.add(packageName)
        _hiddenPackages.value = set
        writeJsonFile("hidden_packages.json", set)
    }

    suspend fun setHidden(packageName: String, hidden: Boolean) = withContext(Dispatchers.IO) {
        val set = _hiddenPackages.value.toMutableSet()
        if (hidden) set.add(packageName) else set.remove(packageName)
        _hiddenPackages.value = set
        writeJsonFile("hidden_packages.json", set)
    }

    suspend fun upsertCollection(collection: AppCollection) = withContext(Dispatchers.IO) {
        val list = _collections.value.toMutableList()
        val idx = list.indexOfFirst { it.id == collection.id }
        if (idx >= 0) list[idx] = collection else list.add(collection)
        _collections.value = list
        writeJsonFile("collections.json", list)
    }

    suspend fun deleteCollection(id: String) = withContext(Dispatchers.IO) {
        val list = _collections.value.filterNot { it.id == id }
        _collections.value = list
        writeJsonFile("collections.json", list)
    }

    suspend fun addAppToCollection(collectionId: String, packageName: String) = withContext(Dispatchers.IO) {
        val list = _collections.value.map { col ->
            if (col.id == collectionId && packageName !in col.packageNames) {
                col.copy(packageNames = col.packageNames + packageName)
            } else col
        }
        _collections.value = list
        writeJsonFile("collections.json", list)
    }

    suspend fun removeAppFromCollection(collectionId: String, packageName: String) = withContext(Dispatchers.IO) {
        val list = _collections.value.map { col ->
            if (col.id == collectionId) col.copy(packageNames = col.packageNames - packageName) else col
        }
        _collections.value = list
        writeJsonFile("collections.json", list)
    }

    suspend fun saveActivityItem(item: SavedActivityItem) = withContext(Dispatchers.IO) {
        val list = _savedActivities.value.toMutableList()
        val idx = list.indexOfFirst { it.id == item.id }
        if (idx >= 0) list[idx] = item else list.add(0, item)
        _savedActivities.value = list
        writeJsonFile("saved_activities.json", list)
    }

    suspend fun deleteSavedActivity(id: String) = withContext(Dispatchers.IO) {
        val list = _savedActivities.value.filterNot { it.id == id }
        _savedActivities.value = list
        writeJsonFile("saved_activities.json", list)
    }

    suspend fun setT9QuickKey(item: T9QuickKey) = withContext(Dispatchers.IO) {
        val list = _t9QuickKeys.value.filterNot { it.digit == item.digit } + item
        val sorted = list.sortedBy { it.digit }
        _t9QuickKeys.value = sorted
        writeJsonFile("t9_quick_keys.json", sorted)
    }

    suspend fun removeT9QuickKey(digit: Int) = withContext(Dispatchers.IO) {
        val list = _t9QuickKeys.value.filterNot { it.digit == digit }
        _t9QuickKeys.value = list
        writeJsonFile("t9_quick_keys.json", list)
    }

    suspend fun clearAllT9QuickKeys() = withContext(Dispatchers.IO) {
        _t9QuickKeys.value = emptyList()
        writeJsonFile("t9_quick_keys.json", emptyList<T9QuickKey>())
    }

    suspend fun upsertPolyphone(entry: PolyphoneEntry) = withContext(Dispatchers.IO) {
        val list = _polyphones.value.filterNot { it.word == entry.word } + entry
        _polyphones.value = list
        writeJsonFile("polyphones.json", list)
    }

    suspend fun deletePolyphone(word: String) = withContext(Dispatchers.IO) {
        val list = _polyphones.value.filterNot { it.word == word }
        _polyphones.value = list
        writeJsonFile("polyphones.json", list)
    }

    suspend fun upsertBookmark(item: BookmarkItem) = withContext(Dispatchers.IO) {
        val list = _bookmarks.value.toMutableList()
        val idx = list.indexOfFirst { it.id == item.id }
        if (idx >= 0) list[idx] = item else list.add(0, item)
        _bookmarks.value = list
        writeJsonFile("bookmarks.json", list)
    }

    suspend fun deleteBookmark(id: String) = withContext(Dispatchers.IO) {
        val list = _bookmarks.value.filterNot { it.id == id }
        _bookmarks.value = list
        writeJsonFile("bookmarks.json", list)
    }

    fun loadCachedApps(): List<AppEntry> = readJsonFile("apps_cache.json", emptyList())

    suspend fun saveCachedApps(apps: List<AppEntry>) = withContext(Dispatchers.IO) {
        writeJsonFile("apps_cache.json", apps)
    }

    suspend fun exportBackupJson(): String = withContext(Dispatchers.IO) {
        val currentSettings = settingsFlow.first()
        val bundle = BackupBundle(
            version = 1,
            exportedAt = System.currentTimeMillis(),
            settings = currentSettings,
            collections = _collections.value,
            savedActivities = _savedActivities.value,
            t9QuickKeys = _t9QuickKeys.value,
            customNames = _customNames.value,
            hiddenPackages = _hiddenPackages.value,
            polyphones = _polyphones.value,
            bookmarks = _bookmarks.value,
            usageStats = _usageStats.value
        )
        prettyJson.encodeToString(bundle)
    }

    suspend fun importBackupJson(raw: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val bundle = json.decodeFromString<BackupBundle>(raw)
            updateSettings { bundle.settings }
            _collections.value = bundle.collections
            writeJsonFile("collections.json", bundle.collections)
            _savedActivities.value = bundle.savedActivities
            writeJsonFile("saved_activities.json", bundle.savedActivities)
            _t9QuickKeys.value = bundle.t9QuickKeys
            writeJsonFile("t9_quick_keys.json", bundle.t9QuickKeys)
            _customNames.value = bundle.customNames
            writeJsonFile("custom_names.json", bundle.customNames)
            _hiddenPackages.value = bundle.hiddenPackages
            writeJsonFile("hidden_packages.json", bundle.hiddenPackages)
            _polyphones.value = bundle.polyphones
            writeJsonFile("polyphones.json", bundle.polyphones)
            _bookmarks.value = bundle.bookmarks
            writeJsonFile("bookmarks.json", bundle.bookmarks)
            _usageStats.value = bundle.usageStats
            writeJsonFile("usage_stats.json", bundle.usageStats)
        }
    }

    private inline fun <reified T> readJsonFile(name: String, default: T): T {
        val file = File(dataDir, name)
        if (!file.exists()) return default
        return runCatching {
            json.decodeFromString<T>(file.readText())
        }.getOrDefault(default)
    }

    private inline fun <reified T> writeJsonFile(name: String, value: T) {
        val encoded = json.encodeToString(value)
        synchronized(fileLock) {
            if (!dataDir.exists()) dataDir.mkdirs()
            val file = File(dataDir, name)
            val tmp = File(dataDir, "$name.tmp")
            runCatching {
                tmp.writeText(encoded)
                if (!tmp.renameTo(file)) {
                    file.writeText(encoded)
                    tmp.delete()
                }
            }.onFailure {
                runCatching { file.writeText(encoded) }
                runCatching { tmp.delete() }
            }
        }
    }

    companion object {
        fun hashPassword(password: String): String {
            if (password.isEmpty()) return ""
            val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun defaultBookmarks() = listOf(
            BookmarkItem("bm_coolapk", "酷安", "https://www.coolapk.com"),
            BookmarkItem("bm_github", "GitHub", "https://github.com"),
            BookmarkItem("bm_bilibili", "哔哩哔哩", "https://www.bilibili.com")
        )
    }
}
