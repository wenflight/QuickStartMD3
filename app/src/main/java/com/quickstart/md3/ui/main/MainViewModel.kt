package com.quickstart.md3.ui.main

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.quickstart.md3.QuickStartApp
import com.quickstart.md3.data.*
import com.quickstart.md3.shizuku.ShizukuManager
import com.quickstart.md3.shizuku.ShizukuStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.exp

enum class MainTab(val title: String) {
    APPS("全部"),
    COLLECTIONS("收藏"),
    SHORTCUTS("快捷"),
    BOOKMARKS("书签"),
    CONTACTS("联系人")
}

@Immutable
data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val shizukuStatus: ShizukuStatus = ShizukuStatus.NOT_RUNNING,
    val activeTab: MainTab = MainTab.APPS,
    val searchMode: SearchMode = SearchMode.T9,
    val query: String = "",
    val pullDownActive: Boolean = false,
    val pianoMode: Boolean = false,
    val vaultUnlocked: Boolean = false,
    val showHiddenOnly: Boolean = false,
    val isLoadingApps: Boolean = true,
    val allApps: List<AppEntry> = emptyList(),
    val filteredApps: List<AppEntry> = emptyList(),
    val filteredContacts: List<ContactEntry> = emptyList(),
    val collections: List<AppCollection> = emptyList(),
    val selectedCollectionId: String? = null,
    val savedActivities: List<SavedActivityItem> = emptyList(),
    val quickPresets: List<SavedActivityItem> = AppRepository.builtInQuickPresets(),
    val bookmarks: List<BookmarkItem> = emptyList(),
    val t9QuickKeys: List<T9QuickKey> = emptyList(),
    val polyphones: List<PolyphoneEntry> = emptyList(),
    val selectedApp: AppEntry? = null,
    val shortcutSheetApp: AppEntry? = null,
    val activitySheetApp: AppEntry? = null,
    val shortcutsLoading: Boolean = false,
    val loadedShortcuts: List<AppShortcutItem> = emptyList(),
    val shortcutsError: String? = null,
    val loadedActivities: List<ActivityEntry> = emptyList(),
    val matchedShortcuts: List<SavedActivityItem> = emptyList(),
    val matchedBookmarks: List<BookmarkItem> = emptyList(),
    val mathResult: String? = null,
    val showQuickMenu: Boolean = false,
    val showVaultPasswordDialog: Boolean = false,
    val toastMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as QuickStartApp
    val store = app.store
    val repo = app.appRepo
    val soundPlayer = app.soundPlayer

    private val _rawApps = MutableStateFlow<List<AppEntry>>(emptyList())
    private val _isLoadingApps = MutableStateFlow(true)
    private val _contacts = MutableStateFlow<List<ContactEntry>>(emptyList())
    private val _activeTab = MutableStateFlow(MainTab.APPS)
    private val _searchMode = MutableStateFlow(SearchMode.T9)
    private val _query = MutableStateFlow("")
    private val _pullDownActive = MutableStateFlow(false)
    private val _pianoMode = MutableStateFlow(false)
    private val _vaultUnlocked = MutableStateFlow(false)
    private val _showHiddenOnly = MutableStateFlow(false)
    private val _selectedCollectionId = MutableStateFlow<String?>(null)

    private val _selectedApp = MutableStateFlow<AppEntry?>(null)
    private val _shortcutSheetApp = MutableStateFlow<AppEntry?>(null)
    private val _activitySheetApp = MutableStateFlow<AppEntry?>(null)
    private val _shortcutsLoading = MutableStateFlow(false)
    private val _loadedShortcuts = MutableStateFlow<List<AppShortcutItem>>(emptyList())
    private val _shortcutsError = MutableStateFlow<String?>(null)
    private val _loadedActivities = MutableStateFlow<List<ActivityEntry>>(emptyList())
    private val _showQuickMenu = MutableStateFlow(false)
    private val _showVaultPasswordDialog = MutableStateFlow(false)
    private val _toastMessage = MutableStateFlow<String?>(null)

    private val _closeWindowEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val closeWindowEvent: SharedFlow<Unit> = _closeWindowEvent.asSharedFlow()

    private val initialSettings = store.loadInitialSettingsSync()

    val settingsFlow: StateFlow<AppSettings> = store.settingsFlow.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        initialSettings
    )

    private data class CoreInputs(
        val settings: AppSettings,
        val shizukuStatus: ShizukuStatus,
        val apps: List<AppEntry>,
        val tab: MainTab,
        val mode: SearchMode,
        val query: String,
        val pullDown: Boolean,
        val piano: Boolean,
        val vaultUnlocked: Boolean,
        val hiddenOnly: Boolean,
        val loadingApps: Boolean
    )

    private data class RepoInputs(
        val collections: List<AppCollection>,
        val selectedColId: String?,
        val savedActivities: List<SavedActivityItem>,
        val bookmarks: List<BookmarkItem>,
        val t9Keys: List<T9QuickKey>,
        val polyphones: List<PolyphoneEntry>
    )

    private data class SheetInputs(
        val selectedApp: AppEntry?,
        val shortcutApp: AppEntry?,
        val activityApp: AppEntry?,
        val shortcutsLoading: Boolean,
        val shortcuts: List<AppShortcutItem>,
        val shortcutsError: String?,
        val activities: List<ActivityEntry>,
        val showQuickMenu: Boolean,
        val showVaultPwd: Boolean,
        val toast: String?
    )

    private data class UnifiedMatchResult(
        val mathResult: String? = null,
        val matchedShortcuts: List<SavedActivityItem> = emptyList(),
        val matchedBookmarks: List<BookmarkItem> = emptyList()
    )

    private val filteredAppsFlow: Flow<List<AppEntry>> = combine(
        _rawApps,
        settingsFlow.map { Triple(it.sortMode, it.showFrozenApps, it.superFuzzySearch) }.distinctUntilChanged(),
        _query,
        _searchMode,
        _showHiddenOnly
    ) { apps, sortCfg, query, mode, hiddenOnly ->
        val tempSettings = AppSettings(
            sortMode = sortCfg.first,
            showFrozenApps = sortCfg.second,
            superFuzzySearch = sortCfg.third
        )
        filterAndSortApps(
            apps = apps,
            settings = tempSettings,
            query = query,
            searchMode = mode,
            hiddenOnly = hiddenOnly
        )
    }.flowOn(Dispatchers.Default)

    private val filteredContactsFlow: Flow<List<ContactEntry>> = combine(
        _contacts,
        _query,
        _searchMode,
        settingsFlow.map { it.superFuzzySearch }.distinctUntilChanged()
    ) { contacts, query, mode, superFuzzy ->
        filterContacts(
            contacts = contacts,
            query = query,
            searchMode = mode,
            superFuzzy = superFuzzy
        )
    }.flowOn(Dispatchers.Default)

    private val unifiedMatchesFlow: Flow<UnifiedMatchResult> = combine(
        _query,
        _searchMode,
        _activeTab,
        settingsFlow.map { it.unifiedSearchEnabled to it.superFuzzySearch }.distinctUntilChanged(),
        store.savedActivities,
        store.bookmarks
    ) { arr ->
        val query = arr[0] as String
        val mode = arr[1] as SearchMode
        val tab = arr[2] as MainTab
        @Suppress("UNCHECKED_CAST")
        val flags = arr[3] as Pair<Boolean, Boolean>
        @Suppress("UNCHECKED_CAST")
        val savedActivities = arr[4] as List<SavedActivityItem>
        @Suppress("UNCHECKED_CAST")
        val bookmarks = arr[5] as List<BookmarkItem>

        val isT9 = mode == SearchMode.T9
        val mathRes = if (tab == MainTab.APPS) QuickMathEvaluator.evaluate(query) else null
        val matchUnified = flags.first && query.isNotBlank() && tab == MainTab.APPS
        val matchedSc = if (matchUnified) {
            (savedActivities + AppRepository.builtInQuickPresets())
                .distinctBy { it.id }
                .mapNotNull { sc ->
                    val s = PinyinSearchEngine.scoreTextMatch(
                        text = sc.label,
                        rawQuery = query,
                        isT9 = isT9,
                        superFuzzy = flags.second,
                        extraText = sc.packageName
                    )
                    if (s >= 0) sc to s else null
                }
                .sortedBy { it.second }
                .take(8)
                .map { it.first }
        } else {
            emptyList()
        }
        val matchedBm = if (matchUnified) {
            bookmarks
                .mapNotNull { bm ->
                    val s = PinyinSearchEngine.scoreTextMatch(
                        text = bm.title,
                        rawQuery = query,
                        isT9 = isT9,
                        superFuzzy = flags.second,
                        extraText = bm.url
                    )
                    if (s >= 0) bm to s else null
                }
                .sortedBy { it.second }
                .take(6)
                .map { it.first }
        } else {
            emptyList()
        }
        UnifiedMatchResult(mathRes, matchedSc, matchedBm)
    }.flowOn(Dispatchers.Default)

    private val coreFlow = combine(
        settingsFlow,
        ShizukuManager.status,
        _rawApps,
        _activeTab,
        _searchMode,
        _query,
        _pullDownActive,
        _pianoMode,
        _vaultUnlocked,
        _showHiddenOnly,
        _isLoadingApps
    ) { arr ->
        CoreInputs(
            settings = arr[0] as AppSettings,
            shizukuStatus = arr[1] as ShizukuStatus,
            apps = @Suppress("UNCHECKED_CAST") (arr[2] as List<AppEntry>),
            tab = arr[3] as MainTab,
            mode = arr[4] as SearchMode,
            query = arr[5] as String,
            pullDown = arr[6] as Boolean,
            piano = arr[7] as Boolean,
            vaultUnlocked = arr[8] as Boolean,
            hiddenOnly = arr[9] as Boolean,
            loadingApps = arr[10] as Boolean
        )
    }

    private val repoFlow = combine(
        store.collections,
        _selectedCollectionId,
        store.savedActivities,
        store.bookmarks,
        store.t9QuickKeys,
        store.polyphones
    ) { arr ->
        RepoInputs(
            collections = @Suppress("UNCHECKED_CAST") (arr[0] as List<AppCollection>),
            selectedColId = arr[1] as String?,
            savedActivities = @Suppress("UNCHECKED_CAST") (arr[2] as List<SavedActivityItem>),
            bookmarks = @Suppress("UNCHECKED_CAST") (arr[3] as List<BookmarkItem>),
            t9Keys = @Suppress("UNCHECKED_CAST") (arr[4] as List<T9QuickKey>),
            polyphones = @Suppress("UNCHECKED_CAST") (arr[5] as List<PolyphoneEntry>)
        )
    }

    private val sheetFlow = combine(
        _selectedApp,
        _shortcutSheetApp,
        _activitySheetApp,
        _shortcutsLoading,
        _loadedShortcuts,
        _shortcutsError,
        _loadedActivities,
        _showQuickMenu,
        _showVaultPasswordDialog,
        _toastMessage
    ) { arr ->
        SheetInputs(
            selectedApp = arr[0] as AppEntry?,
            shortcutApp = arr[1] as AppEntry?,
            activityApp = arr[2] as AppEntry?,
            shortcutsLoading = arr[3] as Boolean,
            shortcuts = @Suppress("UNCHECKED_CAST") (arr[4] as List<AppShortcutItem>),
            shortcutsError = arr[5] as String?,
            activities = @Suppress("UNCHECKED_CAST") (arr[6] as List<ActivityEntry>),
            showQuickMenu = arr[7] as Boolean,
            showVaultPwd = arr[8] as Boolean,
            toast = arr[9] as String?
        )
    }

    private val derivedFlow = combine(
        filteredAppsFlow,
        filteredContactsFlow,
        unifiedMatchesFlow
    ) { filtered, filteredContacts, unified ->
        Triple(filtered, filteredContacts, unified)
    }

    val uiState: StateFlow<MainUiState> = combine(coreFlow, repoFlow, sheetFlow, derivedFlow) { core, rp, sh, derived ->
        val activeColId = rp.selectedColId?.takeIf { id -> rp.collections.any { it.id == id } }
            ?: rp.collections.firstOrNull()?.id

        MainUiState(
            settings = core.settings,
            shizukuStatus = core.shizukuStatus,
            activeTab = core.tab,
            searchMode = core.mode,
            query = core.query,
            pullDownActive = core.pullDown,
            pianoMode = core.piano,
            vaultUnlocked = core.vaultUnlocked,
            showHiddenOnly = core.hiddenOnly,
            isLoadingApps = core.loadingApps,
            allApps = core.apps,
            filteredApps = derived.first,
            filteredContacts = derived.second,
            collections = rp.collections,
            selectedCollectionId = activeColId,
            savedActivities = rp.savedActivities,
            bookmarks = rp.bookmarks,
            t9QuickKeys = rp.t9Keys,
            polyphones = rp.polyphones,
            selectedApp = sh.selectedApp,
            shortcutSheetApp = sh.shortcutApp,
            activitySheetApp = sh.activityApp,
            shortcutsLoading = sh.shortcutsLoading,
            loadedShortcuts = sh.shortcuts,
            shortcutsError = sh.shortcutsError,
            loadedActivities = sh.activities,
            matchedShortcuts = derived.third.matchedShortcuts,
            matchedBookmarks = derived.third.matchedBookmarks,
            mathResult = derived.third.mathResult,
            showQuickMenu = sh.showQuickMenu,
            showVaultPasswordDialog = sh.showVaultPwd,
            toastMessage = sh.toast
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState(settings = initialSettings))

    init {
        repo.setActiveIconPack(initialSettings.iconPackPackage)
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { store.loadCachedApps() }
            if (cached.isNotEmpty()) {
                _rawApps.value = cached
                _isLoadingApps.value = false
                launch { repo.prewarmIcons(cached.take(24)) }
            }
            val initSettings = store.settingsFlow.first()
            _searchMode.value = initSettings.defaultSearchMode
            _pianoMode.value = initSettings.pianoModeEnabled
        }
        viewModelScope.launch {
            settingsFlow.map { it.iconPackPackage }.distinctUntilChanged().collect { pack ->
                repo.setActiveIconPack(pack)
            }
        }
        viewModelScope.launch {
            store.awaitLoaded()
            combine(
                store.customNames,
                store.hiddenPackages,
                store.polyphones,
                settingsFlow.map { it.showSelfApp to it.showDualApps }.distinctUntilChanged()
            ) { names, hidden, poly, flags ->
                repo.loadApps(
                    customNames = names,
                    hiddenPackages = hidden,
                    polyphones = poly,
                    usageStats = store.usageStats.value,
                    showSelf = flags.first,
                    showDualApps = flags.second
                )
            }.collect { loaded ->
                _rawApps.value = loaded
                _isLoadingApps.value = false
                store.saveCachedApps(loaded)
            }
        }
        viewModelScope.launch {
            store.awaitLoaded()
            store.usageStats.drop(1).collect { stats ->
                val current = _rawApps.value
                if (current.isNotEmpty()) {
                    var changed = false
                    val updated = current.map { entry ->
                        val st = stats[entry.uniqueKey]
                        if (st != null && (st.launchCount != entry.launchCount || st.lastLaunchTime != entry.lastLaunchTime)) {
                            changed = true
                            entry.copy(launchCount = st.launchCount, lastLaunchTime = st.lastLaunchTime)
                        } else {
                            entry
                        }
                    }
                    if (changed) {
                        _rawApps.value = updated
                    }
                }
            }
        }
        viewModelScope.launch {
            app.packageChangedFlow.collectLatest {
                delay(500L)
                store.awaitLoaded()
                val s = settingsFlow.value
                val loaded = repo.loadApps(
                    customNames = store.customNames.value,
                    hiddenPackages = store.hiddenPackages.value,
                    polyphones = store.polyphones.value,
                    usageStats = store.usageStats.value,
                    showSelf = s.showSelfApp,
                    showDualApps = s.showDualApps
                )
                _rawApps.value = loaded
                store.saveCachedApps(loaded)
            }
        }
    }

    fun refreshApps() {
        viewModelScope.launch {
            repo.clearIconCache()
            val names = store.customNames.value
            val hidden = store.hiddenPackages.value
            val poly = store.polyphones.value
            val stats = store.usageStats.value
            val s = settingsFlow.value
            val loaded = repo.loadApps(names, hidden, poly, stats, s.showSelfApp, s.showDualApps)
            _rawApps.value = loaded
            store.saveCachedApps(loaded)
            showToast("应用列表已刷新")
        }
    }

    fun refreshContacts() {
        viewModelScope.launch {
            _contacts.value = repo.loadContacts(store.polyphones.value)
        }
    }

    private fun filterAndSortApps(
        apps: List<AppEntry>,
        settings: AppSettings,
        query: String,
        searchMode: SearchMode,
        hiddenOnly: Boolean
    ): List<AppEntry> {
        val base = apps.filter { app ->
            if (hiddenOnly) app.isHidden else !app.isHidden
        }.filter { app ->
            settings.showFrozenApps || !app.isFrozen
        }

        val scored = if (query.isBlank()) {
            base.map { it to 0 }
        } else {
            val isT9 = searchMode == SearchMode.T9
            base.mapNotNull { app ->
                val s = PinyinSearchEngine.scoreAppMatch(app, query, isT9, settings.superFuzzySearch)
                if (s >= 0) app to s else null
            }
        }

        val now = System.currentTimeMillis()
        val cmp = Comparator<Pair<AppEntry, Int>> { a, b ->
            if (query.isNotBlank() && a.second != b.second) {
                return@Comparator a.second.compareTo(b.second)
            }
            when (settings.sortMode) {
                SortMode.SMART -> {
                    val c = b.first.launchCount.compareTo(a.first.launchCount)
                    if (c != 0) c else b.first.lastLaunchTime.compareTo(a.first.lastLaunchTime)
                        .let { if (it != 0) it else a.first.displayName.compareTo(b.first.displayName, true) }
                }
                SortMode.FRECENCY -> {
                    val sb = frecencyScore(b.first, now)
                    val sa = frecencyScore(a.first, now)
                    val cmpScore = sb.compareTo(sa)
                    if (cmpScore != 0) cmpScore else a.first.displayName.compareTo(b.first.displayName, true)
                }
                SortMode.LAUNCH_COUNT -> b.first.launchCount.compareTo(a.first.launchCount)
                SortMode.LAST_LAUNCH -> b.first.lastLaunchTime.compareTo(a.first.lastLaunchTime)
                SortMode.INSTALL_TIME -> b.first.firstInstallTime.compareTo(a.first.firstInstallTime)
                SortMode.UPDATE_TIME -> b.first.lastUpdateTime.compareTo(a.first.lastUpdateTime)
                SortMode.NAME -> a.first.displayName.compareTo(b.first.displayName, true)
            }
        }
        return scored.sortedWith(cmp).map { it.first }
    }

    private fun frecencyScore(entry: AppEntry, now: Long): Double {
        val base = entry.launchCount.toDouble()
        val recencyBoost = if (entry.lastLaunchTime > 0L) {
            val days = ((now - entry.lastLaunchTime).coerceAtLeast(0L)) / 86_400_000.0
            6.0 * exp(-days * 0.7)
        } else {
            0.0
        }
        return base + recencyBoost
    }

    private fun filterContacts(
        contacts: List<ContactEntry>,
        query: String,
        searchMode: SearchMode,
        superFuzzy: Boolean
    ): List<ContactEntry> {
        if (query.isBlank()) return contacts
        val isT9 = searchMode == SearchMode.T9
        return contacts.mapNotNull { c ->
            val score = PinyinSearchEngine.scoreContactMatch(c, query, isT9, superFuzzy)
            if (score >= 0) c to score else null
        }.sortedBy { it.second }.map { it.first }
    }

    fun selectTab(tab: MainTab) {
        _activeTab.value = tab
        if (tab == MainTab.CONTACTS && _contacts.value.isEmpty()) {
            refreshContacts()
        }
    }

    fun onQueryChanged(newQuery: String) {
        _query.value = newQuery
    }

    fun onT9DigitTap(digit: Int) {
        playKeyAudio(digit)
        if (_pianoMode.value) return
        _query.value += digit.toString()
    }

    fun onT9DigitLongPressOrSwipeUp(digit: Int, isSwipeUp: Boolean) {
        playKeyAudio(digit)
        if (_pianoMode.value) return
        val s = settingsFlow.value
        if (isSwipeUp && s.swipeUpStartsListApp) {
            val target = uiState.value.filteredApps.getOrNull(digit)
            if (target != null) {
                launchApp(target)
                return
            }
        }
        if (s.t9QuickLaunchEnabled) {
            val quick = store.t9QuickKeys.value.firstOrNull { it.digit == digit }
            if (quick != null) {
                launchQuickKey(quick)
            } else if (!isSwipeUp) {
                showToast("数字键 $digit 尚未绑定快捷键")
            }
        }
    }

    fun playKeyAudio(keyIndex: Int) {
        val s = settingsFlow.value
        if (s.keySoundEnabled || _pianoMode.value) {
            soundPlayer.playKey(keyIndex)
        }
    }

    fun handleGestureAction(action: GestureAction, soundIndex: Int = 10) {
        playKeyAudio(soundIndex)
        if (_pianoMode.value) {
            if (action == GestureAction.TOGGLE_PIANO_MODE || action == GestureAction.OPEN_QUICK_MENU) {
                _pianoMode.value = false
                showToast("演奏模式已关闭")
            }
            return
        }
        when (action) {
            GestureAction.NONE -> Unit
            GestureAction.OPEN_QUICK_MENU -> _showQuickMenu.value = true
            GestureAction.LAUNCH_FIRST -> {
                if (_activeTab.value == MainTab.CONTACTS) {
                    val first = uiState.value.filteredContacts.firstOrNull()
                    if (first != null) callContact(first.phone)
                } else {
                    val first = uiState.value.filteredApps.firstOrNull()
                    if (first != null) launchApp(first)
                }
            }
            GestureAction.BACKSPACE -> {
                val q = _query.value
                if (q.isNotEmpty()) _query.value = q.dropLast(1)
            }
            GestureAction.CLEAR_INPUT -> _query.value = ""
            GestureAction.TOGGLE_SEARCH_MODE -> {
                _searchMode.value = if (_searchMode.value == SearchMode.T9) SearchMode.IME else SearchMode.T9
            }
            GestureAction.TOGGLE_CONTACTS -> {
                _activeTab.value = if (_activeTab.value == MainTab.CONTACTS) MainTab.APPS else MainTab.CONTACTS
                if (_activeTab.value == MainTab.CONTACTS && _contacts.value.isEmpty()) refreshContacts()
            }
            GestureAction.TOGGLE_SORT -> cycleSortMode()
            GestureAction.TOGGLE_SUPER_FUZZY -> viewModelScope.launch {
                val next = !settingsFlow.value.superFuzzySearch
                store.updateSettings { it.copy(superFuzzySearch = next) }
                showToast(if (next) "已开启超级模糊搜索" else "已关闭超级模糊搜索")
            }
            GestureAction.OPEN_SETTINGS -> openSettings()
            GestureAction.QUICK_SCAN -> _activeTab.value = MainTab.SHORTCUTS
            GestureAction.TOGGLE_SHORTCUTS -> {
                _activeTab.value = if (_activeTab.value == MainTab.SHORTCUTS) MainTab.APPS else MainTab.SHORTCUTS
            }
            GestureAction.TOGGLE_PIANO_MODE -> {
                _pianoMode.value = !_pianoMode.value
                showToast(if (_pianoMode.value) "演奏模式已开启，长按飞机键退出" else "演奏模式已关闭")
            }
            GestureAction.CLOSE_WINDOW -> _closeWindowEvent.tryEmit(Unit)
        }
    }

    fun cycleSortMode() {
        viewModelScope.launch {
            val all = SortMode.entries
            val next = all[(all.indexOf(settingsFlow.value.sortMode) + 1) % all.size]
            store.updateSettings { it.copy(sortMode = next) }
            val label = when (next) {
                SortMode.SMART -> "智能排序"
                SortMode.FRECENCY -> "智能常用"
                SortMode.LAUNCH_COUNT -> "使用次数"
                SortMode.LAST_LAUNCH -> "最近打开"
                SortMode.INSTALL_TIME -> "安装时间"
                SortMode.UPDATE_TIME -> "更新时间"
                SortMode.NAME -> "名称排序"
            }
            showToast("排序：$label")
        }
    }

    fun updateWindowVerticalPosition(pos: Float) {
        val clamped = pos.coerceIn(0f, 1f)
        viewModelScope.launch {
            store.updateSettings { it.copy(windowVerticalPosition = clamped) }
        }
    }

    fun setSearchMode(mode: SearchMode) {
        _searchMode.value = mode
    }

    fun togglePullDown() {
        _pullDownActive.value = !_pullDownActive.value
    }

    fun launchApp(app: AppEntry) {
        viewModelScope.launch {
            val res = repo.launchApp(app, settingsFlow.value.rootFallbackEnabled)
            if (res.isSuccess) {
                if (app.isFrozen) refreshApps()
                if (settingsFlow.value.autoCloseOnLaunch) {
                    _query.value = ""
                    _closeWindowEvent.tryEmit(Unit)
                }
            } else {
                showToast(res.exceptionOrNull()?.message ?: "启动失败")
            }
        }
    }

    fun launchQuickKey(key: T9QuickKey) {
        viewModelScope.launch {
            val rootFallback = settingsFlow.value.rootFallbackEnabled
            val res = when (key.targetType) {
                TargetType.APP -> {
                    val found = _rawApps.value.firstOrNull {
                        it.packageName == key.packageName && it.userId == key.userId
                    } ?: _rawApps.value.firstOrNull { it.packageName == key.packageName }
                    if (found != null) repo.launchApp(found, rootFallback)
                    else Result.failure(IllegalStateException("未找到应用"))
                }
                TargetType.ACTIVITY -> ShizukuManager.launchActivity(key.packageName, key.targetValue, rootFallback)
                TargetType.SHORTCUT -> {
                    val sc = key.shortcutData
                    if (sc != null) ShizukuManager.launchShortcut(app, sc, rootFallback)
                    else Result.failure(IllegalStateException("快捷方式无效"))
                }
                TargetType.URI -> ShizukuManager.launchUri(key.targetValue, rootFallback)
            }
            if (res.isSuccess) {
                if (settingsFlow.value.autoCloseOnLaunch) {
                    _query.value = ""
                    _closeWindowEvent.tryEmit(Unit)
                }
            } else {
                showToast(res.exceptionOrNull()?.message ?: "调用失败")
            }
        }
    }

    fun launchSavedItem(item: SavedActivityItem) {
        viewModelScope.launch {
            val rootFallback = settingsFlow.value.rootFallbackEnabled
            val res = when (item.type) {
                TargetType.APP -> {
                    val found = _rawApps.value.firstOrNull { it.packageName == item.packageName }
                    if (found != null) repo.launchApp(found, rootFallback)
                    else Result.failure(IllegalStateException("未找到应用"))
                }
                TargetType.ACTIVITY -> ShizukuManager.launchActivity(item.packageName, item.activityName, rootFallback)
                TargetType.SHORTCUT -> {
                    val sc = item.shortcutData
                    if (sc != null) ShizukuManager.launchShortcut(app, sc, rootFallback)
                    else Result.failure(IllegalStateException("快捷方式数据为空"))
                }
                TargetType.URI -> {
                    val uri = item.uri.orEmpty()
                    val shizukuRes = ShizukuManager.launchUri(uri, rootFallback)
                    if (shizukuRes.isFailure) {
                        runCatching {
                            app.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        }
                    } else shizukuRes
                }
            }
            if (res.isSuccess) {
                if (settingsFlow.value.autoCloseOnLaunch) _closeWindowEvent.tryEmit(Unit)
            } else {
                showToast(res.exceptionOrNull()?.message ?: "启动失败")
            }
        }
    }

    fun selectAppForSheet(appEntry: AppEntry?) {
        _selectedApp.value = appEntry
    }

    fun forceStopApp(appEntry: AppEntry) {
        viewModelScope.launch {
            val res = ShizukuManager.forceStopApp(
                appEntry.packageName,
                settingsFlow.value.rootFallbackEnabled,
                appEntry.userId
            )
            if (res.isSuccess) {
                _selectedApp.value = null
                showToast("已强行停止「${appEntry.displayName}」")
            } else {
                showToast("强行停止失败：${res.exceptionOrNull()?.message ?: "未知错误"}")
            }
        }
    }

    fun launchAppFreeform(appEntry: AppEntry) {
        viewModelScope.launch {
            val res = repo.launchAppFreeform(appEntry, settingsFlow.value.rootFallbackEnabled)
            if (res.isSuccess) {
                _selectedApp.value = null
                if (settingsFlow.value.autoCloseOnLaunch) {
                    _query.value = ""
                    _closeWindowEvent.tryEmit(Unit)
                }
            } else {
                showToast("小窗打开失败：${res.exceptionOrNull()?.message ?: "需要 Shizuku"}")
            }
        }
    }

    fun silentUninstallApp(appEntry: AppEntry) {
        viewModelScope.launch {
            val res = ShizukuManager.uninstallApp(
                appEntry.packageName,
                appEntry.userId,
                settingsFlow.value.rootFallbackEnabled
            )
            if (res.isSuccess) {
                _selectedApp.value = null
                showToast("已静默卸载「${appEntry.displayName}」")
                refreshApps()
            } else {
                showToast("卸载失败：${res.exceptionOrNull()?.message ?: "需要 Shizuku"}")
            }
        }
    }

    fun toggleFreezeApp(appEntry: AppEntry) {
        viewModelScope.launch {
            val root = settingsFlow.value.rootFallbackEnabled
            val res = if (appEntry.isFrozen) {
                ShizukuManager.unfreezeApp(appEntry.packageName, root, appEntry.userId)
            } else {
                ShizukuManager.freezeApp(appEntry.packageName, root, appEntry.userId)
            }
            if (res.isSuccess) {
                showToast(if (appEntry.isFrozen) "已解冻【${appEntry.displayName}】" else "已冻结【${appEntry.displayName}】")
                _selectedApp.value = null
                refreshApps()
            } else {
                showToast(res.exceptionOrNull()?.message ?: "操作失败")
            }
        }
    }

    fun openShortcutsSheet(appEntry: AppEntry) {
        _selectedApp.value = null
        _shortcutSheetApp.value = appEntry
        _shortcutsLoading.value = true
        _shortcutsError.value = null
        _loadedShortcuts.value = emptyList()
        viewModelScope.launch {
            val res = ShizukuManager.getAppShortcuts(appEntry.packageName, appEntry.displayName)
            _shortcutsLoading.value = false
            res.onSuccess { list ->
                _loadedShortcuts.value = list
            }.onFailure { err ->
                _shortcutsError.value = err.message ?: "获取快捷方式失败"
            }
        }
    }

    fun closeShortcutsSheet() {
        _shortcutSheetApp.value = null
    }

    fun invokeShortcut(shortcut: AppShortcutItem) {
        viewModelScope.launch {
            val res = ShizukuManager.launchShortcut(app, shortcut, settingsFlow.value.rootFallbackEnabled)
            if (res.isSuccess) {
                _shortcutSheetApp.value = null
                if (settingsFlow.value.autoCloseOnLaunch) _closeWindowEvent.tryEmit(Unit)
            } else {
                showToast(res.exceptionOrNull()?.message ?: "调用快捷方式失败")
            }
        }
    }

    fun saveShortcutToFavorites(shortcut: AppShortcutItem) {
        viewModelScope.launch {
            store.saveActivityItem(
                SavedActivityItem(
                    id = "sc_${shortcut.packageName}_${shortcut.id}",
                    label = "${shortcut.appLabel} · ${shortcut.shortLabel}",
                    packageName = shortcut.packageName,
                    type = TargetType.SHORTCUT,
                    shortcutData = shortcut
                )
            )
            showToast("已添加到快捷栏")
        }
    }

    fun openActivitiesSheet(appEntry: AppEntry) {
        _selectedApp.value = null
        _activitySheetApp.value = appEntry
        _loadedActivities.value = emptyList()
        viewModelScope.launch {
            _loadedActivities.value = repo.loadActivities(appEntry.packageName)
        }
    }

    fun closeActivitiesSheet() {
        _activitySheetApp.value = null
    }

    fun launchActivity(act: ActivityEntry) {
        viewModelScope.launch {
            val root = settingsFlow.value.rootFallbackEnabled
            val res = if (act.exported) {
                runCatching {
                    app.startActivity(Intent().apply {
                        setClassName(act.packageName, act.className)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }.recoverCatching {
                    ShizukuManager.launchActivity(act.packageName, act.className, root).getOrThrow()
                }
            } else {
                ShizukuManager.launchActivity(act.packageName, act.className, root)
            }
            if (res.isSuccess) {
                _activitySheetApp.value = null
                if (settingsFlow.value.autoCloseOnLaunch) _closeWindowEvent.tryEmit(Unit)
            } else {
                showToast(res.exceptionOrNull()?.message ?: "启动活动失败")
            }
        }
    }

    fun saveActivityToFavorites(appEntry: AppEntry, act: ActivityEntry) {
        viewModelScope.launch {
            store.saveActivityItem(
                SavedActivityItem(
                    id = "act_${act.packageName}_${act.className}",
                    label = "${appEntry.displayName} · ${act.label}",
                    packageName = act.packageName,
                    activityName = act.className,
                    type = TargetType.ACTIVITY
                )
            )
            showToast("已添加到快捷栏")
        }
    }

    fun deleteSavedActivity(id: String) {
        viewModelScope.launch { store.deleteSavedActivity(id) }
    }

    fun renameApp(appEntry: AppEntry, newName: String) {
        viewModelScope.launch {
            store.setCustomName(appEntry.uniqueKey, newName)
            showToast(if (newName.isBlank()) "已恢复默认名称" else "已重命名为 $newName")
        }
    }

    fun toggleHideApp(appEntry: AppEntry) {
        viewModelScope.launch {
            store.toggleHidden(appEntry.uniqueKey)
            showToast(if (appEntry.isHidden) "已取消隐藏【${appEntry.displayName}】" else "已隐藏【${appEntry.displayName}】")
            _selectedApp.value = null
        }
    }

    fun bindAppToT9Key(digit: Int, appEntry: AppEntry) {
        viewModelScope.launch {
            store.setT9QuickKey(
                T9QuickKey(
                    digit = digit,
                    label = appEntry.displayName,
                    targetType = TargetType.APP,
                    packageName = appEntry.packageName,
                    userId = appEntry.userId
                )
            )
            showToast("已将【${appEntry.displayName}】绑定到数字键 $digit")
        }
    }

    fun bindShortcutToT9Key(digit: Int, shortcut: AppShortcutItem) {
        viewModelScope.launch {
            store.setT9QuickKey(
                T9QuickKey(
                    digit = digit,
                    label = "${shortcut.appLabel}·${shortcut.shortLabel}",
                    targetType = TargetType.SHORTCUT,
                    packageName = shortcut.packageName,
                    shortcutData = shortcut
                )
            )
            showToast("已将【${shortcut.shortLabel}】绑定到数字键 $digit")
        }
    }

    fun selectCollection(id: String) {
        _selectedCollectionId.value = id
    }

    fun createCollection(name: String, initialPkg: String? = null) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val col = AppCollection(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                packageNames = listOfNotNull(initialPkg)
            )
            store.upsertCollection(col)
            _selectedCollectionId.value = col.id
            showToast("分类【${col.name}】已创建")
        }
    }

    fun addAppToCollection(colId: String, pkg: String) {
        viewModelScope.launch {
            store.addAppToCollection(colId, pkg)
            showToast("已加入分类收藏")
        }
    }

    fun removeAppFromCollection(colId: String, pkg: String) {
        viewModelScope.launch { store.removeAppFromCollection(colId, pkg) }
    }

    fun freezeCollection(col: AppCollection) {
        viewModelScope.launch {
            val root = settingsFlow.value.rootFallbackEnabled
            var ok = 0
            for (rawPkg in col.packageNames) {
                val (pkg, uid) = parsePackageKey(rawPkg)
                if (ShizukuManager.freezeApp(pkg, root, uid).isSuccess) ok++
            }
            refreshApps()
            showToast("已冻结 $ok/${col.packageNames.size} 个应用")
        }
    }

    fun forceStopCollection(col: AppCollection) {
        viewModelScope.launch {
            val root = settingsFlow.value.rootFallbackEnabled
            var ok = 0
            for (rawPkg in col.packageNames) {
                val (pkg, uid) = parsePackageKey(rawPkg)
                if (ShizukuManager.forceStopApp(pkg, root, uid).isSuccess) ok++
            }
            showToast("已强行停止 $ok/${col.packageNames.size} 个应用")
        }
    }

    private fun parsePackageKey(key: String): Pair<String, Int> {
        val idx = key.indexOf('#')
        return if (idx > 0) {
            key.substring(0, idx) to (key.substring(idx + 1).toIntOrNull() ?: 0)
        } else {
            key to 0
        }
    }

    fun openWebSearch(engine: SearchEngineItem, query: String) {
        repo.openWebSearch(engine.urlTemplate, query)
            .onSuccess { hint ->
                if (!hint.isNullOrBlank()) {
                    showToast(hint)
                }
                if (settingsFlow.value.autoCloseOnLaunch) {
                    _query.value = ""
                    _closeWindowEvent.tryEmit(Unit)
                }
            }
            .onFailure { showToast("无法打开浏览器或应用") }
    }

    fun copyText(text: String, toastMsg: String) {
        runCatching {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("QuickStartMD3", text))
        }
        showToast(toastMsg)
    }

    fun shareApk(appEntry: AppEntry) {
        viewModelScope.launch {
            val res = repo.shareApk(appEntry)
            res.onSuccess { intent ->
                _selectedApp.value = null
                _closeWindowEvent.tryEmit(Unit)
                app.startActivity(Intent.createChooser(intent, "分享 ${appEntry.displayName} 安装包").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }.onFailure { showToast(it.message ?: "提取 APK 失败") }
        }
    }

    fun exportIcon(appEntry: AppEntry) {
        viewModelScope.launch {
            val res = repo.exportAppIcon(appEntry)
            res.onSuccess { path -> showToast("图标已保存：$path") }
                .onFailure { showToast(it.message ?: "导出图标失败") }
        }
    }

    fun pinDesktopShortcut(
        id: String,
        label: String,
        packageName: String,
        targetType: TargetType,
        activityName: String = "",
        shortcut: AppShortcutItem? = null
    ) {
        val res = repo.pinDesktopShortcut(id, label, packageName, targetType, activityName, shortcut)
        res.onSuccess { showToast("已发送桌面快捷方式请求") }
            .onFailure { showToast(it.message ?: "添加失败") }
    }

    fun upsertBookmark(id: String?, title: String, url: String) {
        if (title.isBlank() || url.isBlank()) return
        viewModelScope.launch {
            store.upsertBookmark(
                BookmarkItem(
                    id = id ?: UUID.randomUUID().toString(),
                    title = title.trim(),
                    url = url.trim()
                )
            )
        }
    }

    fun deleteBookmark(id: String) {
        viewModelScope.launch { store.deleteBookmark(id) }
    }

    fun openBookmark(url: String) {
        repo.openBookmark(url)
            .onSuccess { _closeWindowEvent.tryEmit(Unit) }
            .onFailure { showToast("打开网页失败") }
    }

    fun callContact(phone: String) {
        runCatching { repo.callContact(phone) }
            .onSuccess { _closeWindowEvent.tryEmit(Unit) }
            .onFailure { showToast("拨号失败") }
    }

    fun smsContact(phone: String) {
        runCatching { repo.smsContact(phone) }
            .onSuccess { _closeWindowEvent.tryEmit(Unit) }
            .onFailure { showToast("打开短信失败") }
    }

    fun requestHideWindow() {
        _closeWindowEvent.tryEmit(Unit)
    }

    fun dismissQuickMenu() {
        _showQuickMenu.value = false
    }

    fun resetForReopen(initialSearchMode: SearchMode? = null, initialTab: MainTab? = null) {
        _query.value = ""
        _selectedApp.value = null
        _shortcutSheetApp.value = null
        _activitySheetApp.value = null
        _showQuickMenu.value = false
        _showVaultPasswordDialog.value = false
        if (initialSearchMode != null) {
            _searchMode.value = initialSearchMode
        }
        if (initialTab != null) {
            _activeTab.value = initialTab
        } else if (initialSearchMode != null) {
            _activeTab.value = MainTab.APPS
        }
    }

    fun enterPrivacyVault() {
        _showQuickMenu.value = false
        if (_showHiddenOnly.value) {
            _showHiddenOnly.value = false
            _vaultUnlocked.value = false
            return
        }
        val pwdHash = settingsFlow.value.vaultPasswordHash
        if (pwdHash.isEmpty() || _vaultUnlocked.value) {
            _showHiddenOnly.value = true
        } else {
            _showVaultPasswordDialog.value = true
        }
    }

    fun verifyVaultPassword(input: String) {
        val hash = DataStoreRepository.hashPassword(input)
        if (hash == settingsFlow.value.vaultPasswordHash) {
            _vaultUnlocked.value = true
            _showVaultPasswordDialog.value = false
            _showHiddenOnly.value = true
        } else {
            showToast("密码错误")
        }
    }

    fun dismissVaultPasswordDialog() {
        _showVaultPasswordDialog.value = false
    }

    fun exitHiddenView() {
        _showHiddenOnly.value = false
    }

    fun openSettings() {
        _showQuickMenu.value = false
        _closeWindowEvent.tryEmit(Unit)
        app.startActivity(Intent(app, com.quickstart.md3.ui.settings.SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private var currentToast: Toast? = null

    fun showToast(msg: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            currentToast?.cancel()
            currentToast = Toast.makeText(app, msg, Toast.LENGTH_SHORT).also { it.show() }
        }
    }

    fun consumeToast() {
        _toastMessage.value = null
    }
}
