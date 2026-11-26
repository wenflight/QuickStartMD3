package com.quickstart.md3.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickstart.md3.data.*
import kotlinx.coroutines.delay

@Composable
fun MainScreen(
    vm: MainViewModel,
    state: MainUiState,
    isInMultiWindowMode: Boolean = false,
    onCloseWindow: () -> Unit
) {
    val s = state.settings
    BackHandler(enabled = !s.launcherModeEnabled && !isInMultiWindowMode) {
        onCloseWindow()
    }
    var localVerticalPos by remember(s.windowVerticalPosition) {
        mutableFloatStateOf(s.windowVerticalPosition.coerceIn(0f, 1f))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenMaxHeight = maxHeight
        val isSmallWindow = isInMultiWindowMode || (maxWidth < 360.dp && screenMaxHeight < 640.dp)
        val isLandscape = !isSmallWindow && maxWidth > screenMaxHeight && maxWidth >= 520.dp
        val isFull = s.windowMode == WindowMode.FULLSCREEN
        val fillEntireWindow = isFull || isSmallWindow

        val effectiveSettings = remember(s, isSmallWindow, isLandscape) {
            when {
                isSmallWindow -> s.copy(
                    gridColumns = s.gridColumns.coerceIn(3, 4),
                    iconSizeDp = minOf(s.iconSizeDp, 38),
                    labelSizeSp = minOf(s.labelSizeSp, 11)
                )
                isLandscape -> s.copy(
                    gridColumns = s.gridColumns.coerceIn(3, 5),
                    iconSizeDp = minOf(s.iconSizeDp, 44)
                )
                else -> s
            }
        }

        val backdropColor = when {
            fillEntireWindow -> Color.Transparent
            s.outsideDimAlpha <= 0.01f -> Color.Transparent
            else -> Color.Black.copy(alpha = s.outsideDimAlpha.coerceIn(0f, 0.8f))
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backdropColor)
                .then(
                    if (!fillEntireWindow) {
                        Modifier
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .imePadding()
                            .padding(horizontal = 6.dp, vertical = 6.dp)
                    } else {
                        Modifier
                    }
                )
                .clickable(
                    enabled = !fillEntireWindow && s.clickOutsideClose,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onCloseWindow() }
        ) {
            val widthFraction = when {
                fillEntireWindow -> 1f
                isLandscape -> when (s.windowMode) {
                    WindowMode.CUSTOM -> (s.customWidthPercent / 100f).coerceIn(0.70f, 0.98f)
                    WindowMode.CENTER_SMALL, WindowMode.BOTTOM_SMALL -> 0.84f
                    else -> 0.94f
                }
                else -> when (s.windowMode) {
                    WindowMode.BOTTOM_LARGE, WindowMode.BOTTOM_SMALL -> 0.98f
                    WindowMode.CENTER_LARGE -> 0.96f
                    WindowMode.CENTER_SMALL -> 0.88f
                    WindowMode.CUSTOM -> (s.customWidthPercent / 100f).coerceIn(0.65f, 1f)
                    WindowMode.FULLSCREEN -> 1f
                }
            }

            val heightFraction = when {
                fillEntireWindow -> 1f
                isLandscape -> when (s.windowMode) {
                    WindowMode.CUSTOM -> (s.customHeightPercent / 100f).coerceIn(0.60f, 0.96f)
                    WindowMode.CENTER_SMALL, WindowMode.BOTTOM_SMALL -> 0.78f
                    else -> 0.90f
                }
                else -> when (s.windowMode) {
                    WindowMode.BOTTOM_LARGE, WindowMode.CENTER_LARGE -> 0.78f
                    WindowMode.BOTTOM_SMALL, WindowMode.CENTER_SMALL -> 0.62f
                    WindowMode.CUSTOM -> (s.customHeightPercent / 100f).coerceIn(0.45f, 1f)
                    WindowMode.FULLSCREEN -> 1f
                }
            }

            val cardAlignment = if (fillEntireWindow) {
                Alignment.Center
            } else {
                BiasAlignment(
                    horizontalBias = 0f,
                    verticalBias = (localVerticalPos * 2f - 1f).coerceIn(-1f, 1f)
                )
            }

            val cardModifier = if (fillEntireWindow) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .align(cardAlignment)
                    .fillMaxWidth(widthFraction)
                    .fillMaxHeight(heightFraction)
            }

            val density = LocalDensity.current
            val availableHeightPx = with(density) { (screenMaxHeight * (1f - heightFraction)).toPx().coerceAtLeast(120f) }

            val keySpacing = if (isSmallWindow || isLandscape) 4.dp else 6.dp
            val defaultKeyHeight = if (isSmallWindow) 38.dp else 50.dp
            val isDarkTheme = when (s.themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            val resolvedCardAlpha = (if (isDarkTheme) s.darkCardAlpha else s.cardAlpha).coerceIn(0.45f, 1f)

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = resolvedCardAlpha),
                tonalElevation = if (isSmallWindow || resolvedCardAlpha < 0.99f) 0.dp else 4.dp,
                shadowElevation = if (fillEntireWindow || resolvedCardAlpha < 0.99f) 0.dp else 12.dp,
                shape = if (fillEntireWindow) RoundedCornerShape(0.dp) else RoundedCornerShape(s.cornerRadiusDp.dp),
                modifier = cardModifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { /* absorb outside click */ }
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .let { if (isFull) it.windowInsetsPadding(WindowInsets.safeDrawing) else it }
                            .padding(
                                horizontal = if (isSmallWindow) 8.dp else 12.dp,
                                vertical = if (isSmallWindow) 6.dp else 8.dp
                            ),
                        verticalArrangement = Arrangement.spacedBy(if (isSmallWindow) 5.dp else 8.dp)
                    ) {
                        if (!fillEntireWindow) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(16.dp)
                                    .pointerInput(availableHeightPx) {
                                        detectVerticalDragGestures(
                                            onDragEnd = { vm.updateWindowVerticalPosition(localVerticalPos) },
                                            onDragCancel = { vm.updateWindowVerticalPosition(localVerticalPos) }
                                        ) { _, dragAmount ->
                                            localVerticalPos = (localVerticalPos + dragAmount / availableHeightPx)
                                                .coerceIn(0f, 1f)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(40.dp)
                                        .height(4.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                                )
                            }
                        }

                        if (isLandscape) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1.15f)
                                        .fillMaxHeight(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    HeaderSection(state = state, settings = effectiveSettings, vm = vm)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                    ) {
                                        MainTabContent(
                                            state = state,
                                            settings = effectiveSettings,
                                            vm = vm
                                        )
                                    }
                                }

                                VerticalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )

                                BoxWithConstraints(
                                    modifier = Modifier
                                        .weight(0.85f)
                                        .widthIn(min = 240.dp, max = 340.dp)
                                        .fillMaxHeight(),
                                    contentAlignment = Alignment.BottomCenter
                                ) {
                                    val dynamicKeyHeight = ((maxHeight - 54.dp - keySpacing * 4) / 4f)
                                        .coerceIn(28.dp, 46.dp)
                                    SearchAndInputFooter(
                                        state = state,
                                        vm = vm,
                                        keyHeight = dynamicKeyHeight,
                                        keySpacing = keySpacing,
                                        onOpenFirst = {
                                            if (state.activeTab == MainTab.CONTACTS) {
                                                state.filteredContacts.firstOrNull()?.let { vm.callContact(it.phone) }
                                            } else {
                                                state.filteredApps.firstOrNull()?.let { vm.launchApp(it) }
                                            }
                                        }
                                    )
                                }
                            }
                        } else {
                            HeaderSection(state = state, settings = effectiveSettings, vm = vm)

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                MainTabContent(
                                    state = state,
                                    settings = effectiveSettings,
                                    vm = vm
                                )
                            }

                            SearchAndInputFooter(
                                state = state,
                                vm = vm,
                                keyHeight = if (isSmallWindow) ((screenMaxHeight * 0.44f - 48.dp) / 4f).coerceIn(30.dp, 40.dp) else defaultKeyHeight,
                                keySpacing = keySpacing,
                                onOpenFirst = {
                                    if (state.activeTab == MainTab.CONTACTS) {
                                        state.filteredContacts.firstOrNull()?.let { vm.callContact(it.phone) }
                                    } else {
                                        state.filteredApps.firstOrNull()?.let { vm.launchApp(it) }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Bottom Sheets & Dialogs
    state.selectedApp?.let { app ->
        AppActionSheet(
            app = app,
            repo = vm.repo,
            collections = state.collections,
            shizukuStatus = state.shizukuStatus,
            enabledActions = s.enabledMenuActions,
            onDismiss = { vm.selectAppForSheet(null) },
            onForceStop = vm::forceStopApp,
            onToggleFreeze = vm::toggleFreezeApp,
            onLaunchFreeform = vm::launchAppFreeform,
            onSilentUninstall = vm::silentUninstallApp,
            onOpenShortcuts = vm::openShortcutsSheet,
            onOpenActivities = vm::openActivitiesSheet,
            onRename = vm::renameApp,
            onToggleHide = vm::toggleHideApp,
            onBindT9 = vm::bindAppToT9Key,
            onAddToCollection = vm::addAppToCollection,
            onCreateCollection = vm::createCollection,
            onShareApk = vm::shareApk,
            onExportIcon = vm::exportIcon,
            onPinShortcut = { entry ->
                vm.pinDesktopShortcut(
                    id = "app_${entry.packageName}",
                    label = entry.displayName,
                    packageName = entry.packageName,
                    targetType = TargetType.APP
                )
            },
            onShowToast = vm::showToast,
            onExternalLaunched = vm::requestHideWindow
        )
    }

    state.shortcutSheetApp?.let { app ->
        AppShortcutsSheet(
            app = app,
            loading = state.shortcutsLoading,
            shortcuts = state.loadedShortcuts,
            error = state.shortcutsError,
            onDismiss = vm::closeShortcutsSheet,
            onInvoke = vm::invokeShortcut,
            onSaveFavorite = vm::saveShortcutToFavorites,
            onBindT9 = vm::bindShortcutToT9Key,
            onPinDesktop = { sc ->
                vm.pinDesktopShortcut(
                    id = "sc_${sc.packageName}_${sc.id}",
                    label = sc.shortLabel,
                    packageName = sc.packageName,
                    targetType = TargetType.SHORTCUT,
                    shortcut = sc
                )
            }
        )
    }

    state.activitySheetApp?.let { app ->
        AppActivitiesSheet(
            app = app,
            activities = state.loadedActivities,
            onDismiss = vm::closeActivitiesSheet,
            onLaunch = vm::launchActivity,
            onSaveFavorite = vm::saveActivityToFavorites,
            onPinDesktop = { act ->
                vm.pinDesktopShortcut(
                    id = "act_${act.packageName}_${act.className}",
                    label = act.label,
                    packageName = act.packageName,
                    targetType = TargetType.ACTIVITY,
                    activityName = act.className
                )
            },
            onShowToast = vm::showToast
        )
    }

    if (state.showQuickMenu) {
        QuickMenuDialog(
            vaultName = s.vaultEntryName,
            inHiddenView = state.showHiddenOnly,
            superFuzzy = s.superFuzzySearch,
            pianoMode = state.pianoMode,
            onDismiss = vm::dismissQuickMenu,
            onEnterVault = vm::enterPrivacyVault,
            onCycleSort = {
                vm.dismissQuickMenu()
                vm.cycleSortMode()
            },
            onToggleSuperFuzzy = {
                vm.dismissQuickMenu()
                vm.handleGestureAction(GestureAction.TOGGLE_SUPER_FUZZY)
            },
            onTogglePiano = {
                vm.dismissQuickMenu()
                vm.handleGestureAction(GestureAction.TOGGLE_PIANO_MODE)
            },
            onQuickScan = {
                vm.dismissQuickMenu()
                vm.selectTab(MainTab.SHORTCUTS)
            },
            onRefresh = {
                vm.dismissQuickMenu()
                vm.refreshApps()
            },
            onOpenSettings = vm::openSettings
        )
    }

    if (state.showVaultPasswordDialog) {
        var pwd by remember { mutableStateOf("") }
        OverlaySafeDialog(
            onDismissRequest = vm::dismissVaultPasswordDialog,
            title = { Text("输入${s.vaultEntryName}密码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.vaultPasswordHint.isNotBlank()) {
                        Text("提示：${s.vaultPasswordHint}", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(
                        value = pwd,
                        onValueChange = { pwd = it },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        label = { Text("访问密码") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.verifyVaultPassword(pwd) }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissVaultPasswordDialog) { Text("取消") }
            }
        )
    }
    }
}

@Composable
private fun HeaderSection(
    state: MainUiState,
    settings: AppSettings,
    vm: MainViewModel
) {
    if (state.showHiddenOnly) {
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${settings.vaultEntryName} (${state.filteredApps.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                TextButton(onClick = { vm.exitHiddenView() }) {
                    Text("返回全部")
                }
            }
        }
    } else if (!settings.hideTopTabs) {
        TopTabHeader(
            activeTab = state.activeTab,
            pullDownActive = state.pullDownActive,
            pullDownEnabled = settings.pullDownEnabled,
            onSelectTab = vm::selectTab,
            onTogglePullDown = vm::togglePullDown,
            onCycleSort = vm::cycleSortMode,
            onOpenSettings = vm::openSettings
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainTabContent(
    state: MainUiState,
    settings: AppSettings,
    vm: MainViewModel
) {
    when (state.activeTab) {
        MainTab.APPS -> AppsContentRegion(
            apps = state.filteredApps,
            isLoading = state.isLoadingApps,
            searchMode = state.searchMode,
            query = state.query,
            mathResult = state.mathResult,
            matchedShortcuts = state.matchedShortcuts,
            matchedBookmarks = state.matchedBookmarks,
            settings = settings,
            pullDownActive = state.pullDownActive,
            repo = vm.repo,
            onAppClick = vm::launchApp,
            onAppLongClick = vm::selectAppForSheet,
            onCopyMath = { res -> vm.copyText(res, "已复制计算结果：$res") },
            onInvokeShortcut = vm::launchSavedItem,
            onOpenBookmark = vm::openBookmark,
            onOpenWebSearch = { eng -> vm.openWebSearch(eng, state.query) }
        )
        MainTab.COLLECTIONS -> CollectionsContentRegion(
            collections = state.collections,
            selectedCollectionId = state.selectedCollectionId,
            allApps = state.allApps,
            settings = settings,
            repo = vm.repo,
            onSelectCollection = vm::selectCollection,
            onCreateCollection = { name -> vm.createCollection(name, null) },
            onFreezeCollection = vm::freezeCollection,
            onForceStopCollection = vm::forceStopCollection,
            onAppClick = vm::launchApp,
            onAppLongClick = vm::selectAppForSheet
        )
        MainTab.SHORTCUTS -> ShortcutsContentRegion(
            presets = state.quickPresets,
            saved = state.savedActivities,
            repo = vm.repo,
            onInvoke = vm::launchSavedItem,
            onDelete = vm::deleteSavedActivity
        )
        MainTab.BOOKMARKS -> BookmarksContentRegion(
            bookmarks = state.bookmarks,
            onOpen = vm::openBookmark,
            onSave = vm::upsertBookmark,
            onDelete = vm::deleteBookmark
        )
        MainTab.CONTACTS -> ContactsContentRegion(
            contacts = state.filteredContacts,
            onCall = vm::callContact,
            onSms = vm::smsContact,
            onRefresh = vm::refreshContacts
        )
    }
}

@Composable
private fun TopTabHeader(
    activeTab: MainTab,
    pullDownActive: Boolean,
    pullDownEnabled: Boolean,
    onSelectTab: (MainTab) -> Unit,
    onTogglePullDown: () -> Unit,
    onCycleSort: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MainTab.entries.forEach { tab ->
                FilterChip(
                    selected = tab == activeTab,
                    onClick = { onSelectTab(tab) },
                    label = { Text(tab.title, style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.height(32.dp)
                )
            }
        }

        if (pullDownEnabled) {
            IconButton(onClick = onTogglePullDown, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = if (pullDownActive) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "下拉单手",
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        IconButton(onClick = onCycleSort, modifier = Modifier.size(30.dp)) {
            Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "切换排序", modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Rounded.Tune, contentDescription = "设置", modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppsContentRegion(
    apps: List<AppEntry>,
    isLoading: Boolean = false,
    searchMode: SearchMode = SearchMode.T9,
    query: String = "",
    mathResult: String? = null,
    matchedShortcuts: List<SavedActivityItem> = emptyList(),
    matchedBookmarks: List<BookmarkItem> = emptyList(),
    settings: AppSettings,
    pullDownActive: Boolean,
    repo: AppRepository,
    onAppClick: (AppEntry) -> Unit,
    onAppLongClick: (AppEntry) -> Unit,
    onCopyMath: (String) -> Unit = {},
    onInvokeShortcut: (SavedActivityItem) -> Unit = {},
    onOpenBookmark: (String) -> Unit = {},
    onOpenWebSearch: (SearchEngineItem) -> Unit = {}
) {
    val hasExtras = mathResult != null || matchedShortcuts.isNotEmpty() || matchedBookmarks.isNotEmpty()

    if (apps.isEmpty() && !hasExtras) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isLoading) {
                CircularProgressIndicator()
            } else if (query.isNotBlank() && settings.webSearchEnabled && settings.searchEngines.isNotEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text(
                        "未找到本地应用，点击直达搜索「${query}」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        settings.searchEngines.forEach { engine ->
                            ElevatedAssistChip(
                                onClick = { onOpenWebSearch(engine) },
                                label = { Text(if (engine.id == "gemini") "问 ${engine.name}" else engine.name) },
                                leadingIcon = {
                                    Icon(
                                        if (engine.id == "gemini") Icons.Rounded.AutoAwesome else Icons.Rounded.TravelExplore,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            } else {
                Text("没有匹配的应用", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val topSpacerHeight = if (pullDownActive && settings.pullDownEnabled) 120.dp else 0.dp

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (mathResult != null) {
            Surface(
                onClick = { onCopyMath(mathResult) },
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Rounded.Calculate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "= $mathResult",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "点击复制",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.78f)
                    )
                }
            }
        }

        if (matchedShortcuts.isNotEmpty() || matchedBookmarks.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(matchedShortcuts, key = { "sc_${it.id}" }) { sc ->
                    val scIcon = rememberAppIcon(
                        repo = repo,
                        packageName = sc.packageName,
                        iconPackPackage = settings.iconPackPackage
                    )
                    ElevatedAssistChip(
                        onClick = { onInvokeShortcut(sc) },
                        label = { Text(sc.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = {
                            if (scIcon != null) {
                                Image(
                                    bitmap = scIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))
                                )
                            } else {
                                Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    )
                }
                items(matchedBookmarks, key = { "bm_${it.id}" }) { bm ->
                    AssistChip(
                        onClick = { onOpenBookmark(bm.url) },
                        label = { Text(bm.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = {
                            Icon(Icons.Rounded.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                }
            }
        }

        if (query.isNotBlank() && settings.webSearchEnabled && settings.searchEngines.isNotEmpty() &&
            (apps.isEmpty() || searchMode == SearchMode.IME)
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                items(settings.searchEngines, key = { "eng_${it.id}" }) { engine ->
                    ElevatedAssistChip(
                        onClick = { onOpenWebSearch(engine) },
                        label = { Text(if (engine.id == "gemini") "问 ${engine.name}" else "搜${engine.name}") },
                        leadingIcon = {
                            Icon(
                                if (engine.id == "gemini") Icons.Rounded.AutoAwesome else Icons.Rounded.Search,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }
        }

        val listState = rememberLazyListState()
        val gridState = rememberLazyGridState()
        val topKey = apps.firstOrNull()?.uniqueKey
        LaunchedEffect(query, topKey, settings.sortMode, settings.isListMode) {
            if (settings.isListMode) {
                listState.scrollToItem(0, 0)
            } else {
                gridState.scrollToItem(0, 0)
            }
        }

        val now = remember(apps) { System.currentTimeMillis() }
        if (settings.isListMode) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(top = topSpacerHeight, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(
                    items = apps,
                    key = { _, a -> a.uniqueKey },
                    contentType = { _, _ -> "app_row" }
                ) { idx, app ->
                    AppListRow(
                        app = app,
                        indexBadge = if (settings.swipeUpStartsListApp && idx < 10) idx else null,
                        settings = settings,
                        repo = repo,
                        onClick = { onAppClick(app) },
                        onLongClick = { onAppLongClick(app) }
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(settings.gridColumns.coerceIn(2, 8)),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(top = topSpacerHeight, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(
                    items = apps,
                    key = { _, a -> a.uniqueKey },
                    contentType = { _, _ -> "app_grid" }
                ) { idx, app ->
                    AppGridCell(
                        app = app,
                        indexBadge = if (settings.swipeUpStartsListApp && idx < 10) idx else null,
                        now = now,
                        settings = settings,
                        repo = repo,
                        onClick = { onAppClick(app) },
                        onLongClick = { onAppLongClick(app) }
                    )
                }
            }
        }
    }
}

private val SystemIconShape = RoundedCornerShape(12.dp)
private val RoundedIconShape = RoundedCornerShape(14.dp)
private val SquircleIconShape = RoundedCornerShape(percent = 34)
private val ItemCardShape = RoundedCornerShape(14.dp)
private val GrayScaleColorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

private fun iconClipShape(shape: IconShape): Shape = when (shape) {
    IconShape.SYSTEM -> SystemIconShape
    IconShape.ROUNDED -> RoundedIconShape
    IconShape.SQUIRCLE -> SquircleIconShape
    IconShape.CIRCLE -> CircleShape
}

@Composable
private fun rememberAppIcon(
    repo: AppRepository,
    packageName: String,
    launcherActivity: String = "",
    iconPackPackage: String = ""
): ImageBitmap? {
    val cached = remember(packageName, iconPackPackage) {
        if (packageName.isNotBlank()) repo.getCachedIcon(packageName) else null
    }
    val icon by produceState(initialValue = cached, packageName, launcherActivity, iconPackPackage) {
        if (value == null && packageName.isNotBlank()) {
            value = repo.getAppIconSuspend(packageName, launcherActivity)
        }
    }
    return icon
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppGridCell(
    app: AppEntry,
    indexBadge: Int?,
    now: Long,
    settings: AppSettings,
    repo: AppRepository,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val icon = rememberAppIcon(
        repo = repo,
        packageName = app.packageName,
        launcherActivity = app.launcherActivity,
        iconPackPackage = settings.iconPackPackage
    )
    val clipShape = iconClipShape(settings.iconShape)
    val recentMs = settings.recentTimeRangeHours * 3600_000L
    val isRecentInstall = settings.showInstallDot && (now - app.firstInstallTime in 0..recentMs)
    val isRecentUpdate = settings.showUpdateDot && !isRecentInstall && (now - app.lastUpdateTime in 0..recentMs)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ItemCardShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = app.displayName,
                    colorFilter = if (app.isFrozen) GrayScaleColorFilter else null,
                    modifier = Modifier
                        .size(settings.iconSizeDp.dp)
                        .clip(clipShape)
                        .alpha(if (app.isFrozen) 0.6f else 1f)
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Android,
                    contentDescription = app.displayName,
                    modifier = Modifier.size(settings.iconSizeDp.dp)
                )
            }

            if (app.isClone) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.align(Alignment.BottomStart).size(15.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("分", fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (app.isFrozen) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.align(Alignment.BottomEnd).size(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AcUnit,
                        contentDescription = "已冻结",
                        modifier = Modifier.padding(2.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            } else if (isRecentInstall || isRecentUpdate) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(8.dp)
                        .background(
                            if (isRecentInstall) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.tertiary,
                            CircleShape
                        )
                )
            }

            if (indexBadge != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape,
                    modifier = Modifier.align(Alignment.TopStart).size(16.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(indexBadge.toString(), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Text(
            text = app.displayName,
            fontSize = settings.labelSizeSp.sp,
            lineHeight = (settings.labelSizeSp + 3).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = if (app.isFrozen) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppListRow(
    app: AppEntry,
    indexBadge: Int?,
    settings: AppSettings,
    repo: AppRepository,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val icon = rememberAppIcon(
        repo = repo,
        packageName = app.packageName,
        launcherActivity = app.launcherActivity,
        iconPackPackage = settings.iconPackPackage
    )
    val clipShape = iconClipShape(settings.iconShape)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = ItemCardShape,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = app.displayName,
                    modifier = Modifier
                        .size((settings.iconSizeDp - 6).coerceAtLeast(28).dp)
                        .clip(clipShape)
                )
            } else {
                Icon(Icons.Rounded.Android, null, Modifier.size(36.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.displayName,
                    fontSize = (settings.labelSizeSp + 2).sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (app.isFrozen) {
                Icon(Icons.Rounded.AcUnit, contentDescription = "已冻结", modifier = Modifier.size(16.dp))
            }
            if (indexBadge != null) {
                Badge { Text(indexBadge.toString()) }
            }
        }
    }
}

@Composable
private fun CollectionsContentRegion(
    collections: List<AppCollection>,
    selectedCollectionId: String?,
    allApps: List<AppEntry>,
    settings: AppSettings,
    repo: AppRepository,
    onSelectCollection: (String) -> Unit,
    onCreateCollection: (String) -> Unit,
    onFreezeCollection: (AppCollection) -> Unit,
    onForceStopCollection: (AppCollection) -> Unit,
    onAppClick: (AppEntry) -> Unit,
    onAppLongClick: (AppEntry) -> Unit
) {
    var showNewDialog by remember { mutableStateOf(false) }
    val activeCol = collections.firstOrNull { it.id == selectedCollectionId }
    val appByKey = remember(allApps) { allApps.associateBy { it.uniqueKey } }
    val appByPkg = remember(allApps) { allApps.associateBy { it.packageName } }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(collections, key = { it.id }) { col ->
                    FilterChip(
                        selected = col.id == activeCol?.id,
                        onClick = { onSelectCollection(col.id) },
                        label = { Text("${col.name} (${col.packageNames.size})") }
                    )
                }
            }
            if (activeCol != null && activeCol.packageNames.isNotEmpty()) {
                FilledTonalIconButton(onClick = { onForceStopCollection(activeCol) }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.StopCircle, contentDescription = "一键强停分类", modifier = Modifier.size(18.dp))
                }
                FilledTonalIconButton(onClick = { onFreezeCollection(activeCol) }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.AcUnit, contentDescription = "一键冻结分类", modifier = Modifier.size(18.dp))
                }
            }
            FilledTonalIconButton(onClick = { showNewDialog = true }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Rounded.Add, contentDescription = "新建分类", modifier = Modifier.size(18.dp))
            }
        }

        if (activeCol == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("点击右上角 + 创建应用分类，或长按应用加入收藏", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val colApps = activeCol.packageNames.mapNotNull { appByKey[it] ?: appByPkg[it] }
            AppsContentRegion(
                apps = colApps,
                settings = settings,
                pullDownActive = false,
                repo = repo,
                onAppClick = onAppClick,
                onAppLongClick = onAppLongClick
            )
        }
    }

    if (showNewDialog) {
        var name by remember { mutableStateOf("") }
        OverlaySafeDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text("新建应用分类") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("分类名称") })
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    onCreateCollection(name)
                    showNewDialog = false
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showNewDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ShortcutsContentRegion(
    presets: List<SavedActivityItem>,
    saved: List<SavedActivityItem>,
    repo: AppRepository,
    onInvoke: (SavedActivityItem) -> Unit,
    onDelete: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("快捷扫码 & 支付 (Shizuku 直达)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        items(presets.chunked(2), key = { row -> row.joinToString("_") { it.id } }) { rowPresets ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowPresets.forEach { p ->
                    val pIcon = rememberAppIcon(repo = repo, packageName = p.packageName)
                    Surface(
                        onClick = { onInvoke(p) },
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (pIcon != null) {
                                Image(
                                    bitmap = pIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.QrCodeScanner,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Text(
                                text = p.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (rowPresets.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        item {
            Text("已收藏的快捷方式与活动 (${saved.size})", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (saved.isEmpty()) {
            item {
                Text(
                    "长按任意应用 -> 点击「快捷方式」或「浏览活动」，即可将常用功能收藏到这里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(saved, key = { it.id }) { item ->
                val itemIcon = rememberAppIcon(repo = repo, packageName = item.packageName)
                Surface(
                    onClick = { onInvoke(item) },
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(30.dp)) {
                            if (itemIcon != null) {
                                Image(
                                    bitmap = itemIcon,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                                )
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = CircleShape,
                                    modifier = Modifier.align(Alignment.BottomEnd).size(13.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.Bolt,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(1.5.dp)
                                    )
                                }
                            } else {
                                Icon(Icons.Rounded.Bolt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.Center))
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                item.activityName.ifBlank { item.shortcutData?.shortLabel ?: item.packageName },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { onDelete(item.id) }) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarksContentRegion(
    bookmarks: List<BookmarkItem>,
    onOpen: (String) -> Unit,
    onSave: (String?, String, String) -> Unit,
    onDelete: (String) -> Unit
) {
    var editing by remember { mutableStateOf<BookmarkItem?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("网页书签 (${bookmarks.size})", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            FilledTonalButton(onClick = { editing = null; showDialog = true }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("新增", style = MaterialTheme.typography.labelMedium)
            }
        }
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(bookmarks, key = { it.id }) { bm ->
                Surface(
                    onClick = { onOpen(bm.url) },
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Public, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(bm.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(bm.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { editing = bm; showDialog = true }) {
                            Icon(Icons.Rounded.Edit, contentDescription = "编辑")
                        }
                        IconButton(onClick = { onDelete(bm.id) }) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除")
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        var title by remember { mutableStateOf(editing?.title.orEmpty()) }
        var url by remember { mutableStateOf(editing?.url ?: "https://") }
        OverlaySafeDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(if (editing == null) "新增书签" else "编辑书签") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("标题") }, singleLine = true)
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("网址") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank() && url.isNotBlank(), onClick = {
                    onSave(editing?.id, title, url)
                    showDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ContactsContentRegion(
    contacts: List<ContactEntry>,
    onCall: (String) -> Unit,
    onSms: (String) -> Unit,
    onRefresh: () -> Unit
) {
    if (contacts.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("暂无匹配的联系人（请确认已授予通讯录权限）", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onRefresh) { Text("刷新联系人") }
        }
        return
    }
    val contactListState = rememberLazyListState()
    LaunchedEffect(contacts.firstOrNull()?.id) {
        contactListState.scrollToItem(0, 0)
    }
    LazyColumn(
        state = contactListState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(contacts, key = { it.id }) { c ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(c.phone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onSms(c.phone) }) {
                        Icon(Icons.Rounded.Sms, contentDescription = "发短信")
                    }
                    IconButton(onClick = { onCall(c.phone) }) {
                        Icon(Icons.Rounded.Call, contentDescription = "拨号", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchAndInputFooter(
    state: MainUiState,
    vm: MainViewModel,
    keyHeight: Dp = 50.dp,
    keySpacing: Dp = 6.dp,
    onOpenFirst: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(keySpacing)) {
        if (state.searchMode == SearchMode.IME) {
            val focusRequester = remember { FocusRequester() }
            val keyboardController = LocalSoftwareKeyboardController.current
            LaunchedEffect(state.searchMode) {
                delay(80L)
                runCatching {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQueryChanged,
                placeholder = { Text("输入拼音、首字母或应用名...") },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    Row {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { vm.onQueryChanged("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = "清空")
                            }
                        }
                        IconButton(onClick = { vm.setSearchMode(SearchMode.T9) }) {
                            Icon(Icons.Rounded.Dialpad, contentDescription = "切回 T9 键盘")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onOpenFirst() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
            )
        } else {
            // T9 Query Pill + T9 Keypad
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = 12.dp,
                        vertical = if (keyHeight < 44.dp) 6.dp else 8.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = state.query.ifEmpty {
                            if (state.activeTab == MainTab.CONTACTS) "T9 键搜索联系人..."
                            else "T9 键搜索应用（支持全拼/简拼${if (state.settings.superFuzzySearch) "/超级模糊" else ""}）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.query.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.query.isNotEmpty()) {
                        Text(
                            text = "${if (state.activeTab == MainTab.CONTACTS) state.filteredContacts.size else state.filteredApps.size} 项",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            T9Keypad(
                settings = state.settings,
                t9QuickKeys = state.t9QuickKeys,
                pianoMode = state.pianoMode,
                contactsActive = state.activeTab == MainTab.CONTACTS,
                onDigitTap = vm::onT9DigitTap,
                onDigitLongPress = { d -> vm.onT9DigitLongPressOrSwipeUp(d, isSwipeUp = false) },
                onDigitSwipeUp = { d -> vm.onT9DigitLongPressOrSwipeUp(d, isSwipeUp = true) },
                onGestureAction = vm::handleGestureAction,
                onToggleIme = { vm.setSearchMode(SearchMode.IME) },
                keyHeight = keyHeight,
                keySpacing = keySpacing,
                getAppIcon = vm.repo::getAppIcon
            )
        }
    }
}

@Composable
private fun QuickMenuDialog(
    vaultName: String,
    inHiddenView: Boolean,
    superFuzzy: Boolean,
    pianoMode: Boolean,
    onDismiss: () -> Unit,
    onEnterVault: () -> Unit,
    onCycleSort: () -> Unit,
    onToggleSuperFuzzy: () -> Unit,
    onTogglePiano: () -> Unit,
    onQuickScan: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit
) {
    OverlaySafeDialog(
        onDismissRequest = onDismiss,
        title = { Text("快捷功能菜单") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ListItem(
                    headlineContent = { Text(if (inHiddenView) "退出$vaultName" else vaultName) },
                    leadingContent = { Icon(Icons.Rounded.Lock, null) },
                    modifier = Modifier.clickable { onEnterVault() }
                )
                ListItem(
                    headlineContent = { Text("切换排序方式") },
                    leadingContent = { Icon(Icons.AutoMirrored.Rounded.Sort, null) },
                    modifier = Modifier.clickable { onCycleSort() }
                )
                ListItem(
                    headlineContent = { Text(if (superFuzzy) "关闭超级模糊搜索" else "开启超级模糊搜索") },
                    leadingContent = { Icon(Icons.Rounded.AutoAwesome, null) },
                    modifier = Modifier.clickable { onToggleSuperFuzzy() }
                )
                ListItem(
                    headlineContent = { Text("快捷扫码 & 支付") },
                    leadingContent = { Icon(Icons.Rounded.QrCodeScanner, null) },
                    modifier = Modifier.clickable { onQuickScan() }
                )
                ListItem(
                    headlineContent = { Text(if (pianoMode) "关闭演奏模式" else "开启演奏模式（弹琴）") },
                    leadingContent = { Icon(Icons.Rounded.MusicNote, null) },
                    modifier = Modifier.clickable { onTogglePiano() }
                )
                ListItem(
                    headlineContent = { Text("强制刷新应用图标与列表") },
                    leadingContent = { Icon(Icons.Rounded.Refresh, null) },
                    modifier = Modifier.clickable { onRefresh() }
                )
                ListItem(
                    headlineContent = { Text("打开全部设置") },
                    leadingContent = { Icon(Icons.Rounded.Settings, null) },
                    modifier = Modifier.clickable { onOpenSettings() }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
