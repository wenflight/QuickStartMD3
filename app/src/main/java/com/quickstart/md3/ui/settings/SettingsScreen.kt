package com.quickstart.md3.ui.settings

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickstart.md3.BuildConfig
import com.quickstart.md3.QuickStartApp
import com.quickstart.md3.data.*
import com.quickstart.md3.service.FloatingBubbleService
import com.quickstart.md3.service.QuickStartIconWidgetProvider
import com.quickstart.md3.shizuku.ShizukuManager
import com.quickstart.md3.shizuku.ShizukuStatus
import com.quickstart.md3.ui.theme.SeedPalettes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.roundToInt

private enum class SettingsSubPage(val title: String) {
    HOME("设置"),
    T9_KEYS("T9 快捷键管理"),
    GESTURES("功能键手势配置"),
    POLYPHONE("自定义多音字字典"),
    VAULT("隐私应用与密码"),
    MENU_ITEMS("长按菜单管理"),
    SEARCH_ENGINES("搜索引擎管理")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    app: QuickStartApp,
    settings: AppSettings,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = app.store
    val repo = app.appRepo

    val shizukuStatus by ShizukuManager.status.collectAsStateWithLifecycle()
    val t9Keys by store.t9QuickKeys.collectAsStateWithLifecycle()
    val savedActivities by store.savedActivities.collectAsStateWithLifecycle()
    val polyphones by store.polyphones.collectAsStateWithLifecycle()
    val hiddenPackages by store.hiddenPackages.collectAsStateWithLifecycle()

    var subPage by remember { mutableStateOf(SettingsSubPage.HOME) }
    val snackbarHostState = remember { SnackbarHostState() }

    fun toast(msg: String) {
        scope.launch { snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short) }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { store.updateSettings(transform) }
    }

    val notificationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            update { it.copy(notificationEnabled = true) }
            FloatingBubbleService.syncWithSettings(ctx, settings.copy(notificationEnabled = true))
        } else {
            toast("未授予通知权限")
        }
    }

    val contactsPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val ok = result.values.all { it }
        toast(if (ok) "联系人与拨号权限已开启" else "部分权限被拒绝")
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val data = store.exportBackupJson()
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(data.toByteArray()) }
                        ?: error("无法写入文件")
                }.onSuccess { toast("备份导出成功") }
                    .onFailure { toast("导出失败：${it.message}") }
            }
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val raw = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                        ?: error("无法读取文件")
                    store.importBackupJson(raw).getOrThrow()
                }.onSuccess { toast("数据恢复成功") }
                    .onFailure { toast("恢复失败：${it.message}") }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(subPage.title) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (subPage == SettingsSubPage.HOME) onBack() else subPage = SettingsSubPage.HOME
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        when (subPage) {
            SettingsSubPage.HOME -> SettingsHomeList(
                modifier = Modifier.padding(innerPadding),
                app = app,
                settings = settings,
                shizukuStatus = shizukuStatus,
                t9Count = t9Keys.size,
                polyCount = polyphones.size,
                hiddenCount = hiddenPackages.size,
                onUpdate = ::update,
                onToast = ::toast,
                onNavigate = { subPage = it },
                onRequestNotification = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        val next = !settings.notificationEnabled
                        update { it.copy(notificationEnabled = next) }
                        FloatingBubbleService.syncWithSettings(ctx, settings.copy(notificationEnabled = next))
                    }
                },
                onRequestContacts = {
                    contactsPermLauncher.launch(
                        arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
                    )
                },
                onExportBackup = {
                    exportBackupLauncher.launch("QuickStartMD3_backup_${System.currentTimeMillis() / 1000}.json")
                },
                onImportBackup = {
                    importBackupLauncher.launch(arrayOf("application/json", "*/*"))
                },
                onClearApkCache = {
                    val freed = repo.clearApkShareCache()
                    toast("已清理 ${freed / 1024} KB 缓存")
                }
            )
            SettingsSubPage.T9_KEYS -> T9KeysManagePage(
                modifier = Modifier.padding(innerPadding),
                app = app,
                settings = settings,
                t9Keys = t9Keys,
                savedActivities = savedActivities,
                onBindKey = { key -> scope.launch { store.setT9QuickKey(key) } },
                onRemove = { digit -> scope.launch { store.removeT9QuickKey(digit) } },
                onClearAll = { scope.launch { store.clearAllT9QuickKeys() } }
            )
            SettingsSubPage.GESTURES -> GesturesConfigPage(
                modifier = Modifier.padding(innerPadding),
                settings = settings,
                onUpdate = ::update
            )
            SettingsSubPage.POLYPHONE -> PolyphoneManagePage(
                modifier = Modifier.padding(innerPadding),
                entries = polyphones,
                onSave = { w, p -> scope.launch { store.upsertPolyphone(PolyphoneEntry(w, p)) } },
                onDelete = { w -> scope.launch { store.deletePolyphone(w) } }
            )
            SettingsSubPage.VAULT -> PrivacyVaultPage(
                modifier = Modifier.padding(innerPadding),
                app = app,
                settings = settings,
                hiddenPackages = hiddenPackages,
                onUpdate = ::update,
                onUnhide = { pkg -> scope.launch { store.setHidden(pkg, false) } },
                onToast = ::toast
            )
            SettingsSubPage.MENU_ITEMS -> MenuCustomizePage(
                modifier = Modifier.padding(innerPadding),
                settings = settings,
                onUpdate = ::update
            )
            SettingsSubPage.SEARCH_ENGINES -> SearchEnginesPage(
                modifier = Modifier.padding(innerPadding),
                settings = settings,
                onUpdate = ::update,
                onToast = ::toast
            )
        }
    }
}

@Composable
private fun SettingsHomeList(
    modifier: Modifier,
    app: QuickStartApp,
    settings: AppSettings,
    shizukuStatus: ShizukuStatus,
    t9Count: Int,
    polyCount: Int,
    hiddenCount: Int,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onToast: (String) -> Unit,
    onNavigate: (SettingsSubPage) -> Unit,
    onRequestNotification: () -> Unit,
    onRequestContacts: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onClearApkCache: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Shizuku Status Card
        item {
            val containerColor = when (shizukuStatus) {
                ShizukuStatus.READY -> MaterialTheme.colorScheme.primaryContainer
                ShizukuStatus.PERMISSION_DENIED -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.errorContainer
            }
            val contentColor = when (shizukuStatus) {
                ShizukuStatus.READY -> MaterialTheme.colorScheme.onPrimaryContainer
                ShizukuStatus.PERMISSION_DENIED -> MaterialTheme.colorScheme.onTertiaryContainer
                else -> MaterialTheme.colorScheme.onErrorContainer
            }
            Surface(
                color = containerColor,
                contentColor = contentColor,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(28.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when (shizukuStatus) {
                                    ShizukuStatus.READY -> "Shizuku 已就绪"
                                    ShizukuStatus.PERMISSION_DENIED -> "Shizuku 未授权"
                                    ShizukuStatus.NOT_RUNNING -> "Shizuku 未运行"
                                    ShizukuStatus.NOT_INSTALLED -> "未安装 Shizuku"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "支持免 Root 强行停止应用、冻结/解冻、查询并调用系统快捷方式 (ShortcutInfo) 与非导出活动。",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (shizukuStatus) {
                            ShizukuStatus.PERMISSION_DENIED -> {
                                Button(onClick = { ShizukuManager.requestPermission() }) {
                                    Text("立即授权")
                                }
                            }
                            ShizukuStatus.NOT_RUNNING -> {
                                Button(onClick = {
                                    val launch = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                    if (launch != null) ctx.startActivity(launch)
                                    else onToast("请先启动 Shizuku")
                                }) { Text("打开 Shizuku") }
                            }
                            ShizukuStatus.READY -> {
                                FilledTonalButton(onClick = {
                                    scope.launch {
                                        val res = ShizukuManager.execArgs("id")
                                        res.onSuccess { onToast("Shizuku 连通正常：${it.trim()}") }
                                            .onFailure { onToast("测试失败：${it.message}") }
                                    }
                                }) { Text("测试 Shizuku 连通性") }
                            }
                            ShizukuStatus.NOT_INSTALLED -> Unit
                        }
                        TextButton(onClick = { ShizukuManager.refreshStatus() }) {
                            Text("刷新状态")
                        }
                    }
                }
            }
        }

        // 2. Monet & Appearance
        item { SectionTitle("莫奈取色与 MD3 外观") }
        item {
            SettingsCard {
                SwitchRow(
                    title = "莫奈动态取色 (Dynamic Color)",
                    subtitle = "跟随系统壁纸自动生成 MD3 配色方案 (Android 12+)",
                    checked = settings.useDynamicColor,
                    onCheckedChange = { v -> onUpdate { it.copy(useDynamicColor = v) } }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("深浅主题模式", style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            ThemeMode.SYSTEM to "跟随系统",
                            ThemeMode.LIGHT to "浅色",
                            ThemeMode.DARK to "深色"
                        ).forEach { (mode, label) ->
                            FilterChip(
                                selected = settings.themeMode == mode,
                                onClick = { onUpdate { it.copy(themeMode = mode) } },
                                label = { Text(label) }
                            )
                        }
                    }
                }
                if (!settings.useDynamicColor || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("预设 MD3 种子色板", style = MaterialTheme.typography.bodyLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(SeedPalettes) { idx, pal ->
                                val selected = settings.seedColorIndex == idx
                                Surface(
                                    onClick = { onUpdate { it.copy(seedColorIndex = idx) } },
                                    color = pal.primaryLight,
                                    shape = CircleShape,
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    if (selected) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("窗口模式", style = MaterialTheme.typography.bodyLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val modes = listOf(
                            WindowMode.BOTTOM_LARGE to "底部大窗",
                            WindowMode.BOTTOM_SMALL to "底部小窗",
                            WindowMode.CENTER_LARGE to "居中大窗",
                            WindowMode.CENTER_SMALL to "居中小窗",
                            WindowMode.FULLSCREEN to "全屏模式",
                            WindowMode.CUSTOM to "自定义"
                        )
                        items(modes) { (m, label) ->
                            FilterChip(
                                selected = settings.windowMode == m,
                                onClick = {
                                    val newVPos = when (m) {
                                        WindowMode.BOTTOM_LARGE, WindowMode.BOTTOM_SMALL -> 1.0f
                                        WindowMode.CENTER_LARGE, WindowMode.CENTER_SMALL -> 0.5f
                                        else -> settings.windowVerticalPosition
                                    }
                                    onUpdate { it.copy(windowMode = m, windowVerticalPosition = newVPos) }
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                }
                if (settings.windowMode != WindowMode.FULLSCREEN) {
                    SliderRow(
                        title = "窗口上下位置 (${(settings.windowVerticalPosition * 100).roundToInt()}% · 可在主窗口顶部横条直接上下拖拽)",
                        value = settings.windowVerticalPosition,
                        range = 0f..1f,
                        onChange = { v -> onUpdate { it.copy(windowVerticalPosition = v) } }
                    )
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(0f to "贴顶", 0.5f to "居中", 1f to "贴底").forEach { (pos, lbl) ->
                            FilterChip(
                                selected = kotlin.math.abs(settings.windowVerticalPosition - pos) < 0.04f,
                                onClick = { onUpdate { it.copy(windowVerticalPosition = pos) } },
                                label = { Text(lbl) }
                            )
                        }
                    }
                }
                if (settings.windowMode == WindowMode.CUSTOM) {
                    SliderRow(
                        title = "自定义窗口宽度 (${settings.customWidthPercent}%)",
                        value = settings.customWidthPercent.toFloat(),
                        range = 70f..100f,
                        onChange = { v -> onUpdate { it.copy(customWidthPercent = v.roundToInt()) } }
                    )
                    SliderRow(
                        title = "自定义窗口高度 (${settings.customHeightPercent}%)",
                        value = settings.customHeightPercent.toFloat(),
                        range = 50f..100f,
                        onChange = { v -> onUpdate { it.copy(customHeightPercent = v.roundToInt()) } }
                    )
                }
                SliderRow(
                    title = "窗口圆角 (${settings.cornerRadiusDp} dp)",
                    value = settings.cornerRadiusDp.toFloat(),
                    range = 0f..36f,
                    onChange = { v -> onUpdate { it.copy(cornerRadiusDp = v.roundToInt()) } }
                )
                SliderRow(
                    title = "窗外背景暗度 (${(settings.outsideDimAlpha * 100).roundToInt()}% · 0% 为完全透明)",
                    value = settings.outsideDimAlpha,
                    range = 0f..0.8f,
                    onChange = { v -> onUpdate { it.copy(outsideDimAlpha = v) } }
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SwitchRow(
                        title = "背景高斯模糊（毛玻璃）",
                        subtitle = "悬浮窗开启时对背后桌面或软件应用模糊效果 (Android 12+)",
                        checked = settings.blurBehindEnabled,
                        onCheckedChange = { v -> onUpdate { it.copy(blurBehindEnabled = v) } }
                    )
                    if (settings.blurBehindEnabled) {
                        SliderRow(
                            title = "背景模糊强度 (${settings.blurRadiusDp} dp)",
                            value = settings.blurRadiusDp.toFloat(),
                            range = 0f..48f,
                            onChange = { v -> onUpdate { it.copy(blurRadiusDp = v.roundToInt()) } }
                        )
                    }
                }
                SliderRow(
                    title = "浅色模式卡片不透明度 (${(settings.cardAlpha * 100).roundToInt()}%)",
                    value = settings.cardAlpha,
                    range = 0.55f..1f,
                    onChange = { v -> onUpdate { it.copy(cardAlpha = v) } }
                )
                SliderRow(
                    title = "深色模式卡片不透明度 (${(settings.darkCardAlpha * 100).roundToInt()}%)",
                    value = settings.darkCardAlpha,
                    range = 0.55f..1f,
                    onChange = { v -> onUpdate { it.copy(darkCardAlpha = v) } }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("应用图标形状", style = MaterialTheme.typography.bodyLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(IconShape.entries) { shapeOpt ->
                            FilterChip(
                                selected = settings.iconShape == shapeOpt,
                                onClick = { onUpdate { it.copy(iconShape = shapeOpt) } },
                                label = { Text(shapeOpt.label) }
                            )
                        }
                    }
                }
                var showIconPackDialog by remember { mutableStateOf(false) }
                val installedPacks = remember { IconPackManager.getInstalledIconPacks(ctx) }
                val currentPackLabel = remember(settings.iconPackPackage, installedPacks) {
                    if (settings.iconPackPackage.isBlank()) "系统默认"
                    else installedPacks.firstOrNull { it.packageName == settings.iconPackPackage }?.label
                        ?: settings.iconPackPackage
                }
                NavRow(
                    icon = Icons.Rounded.Palette,
                    title = "第三方图标包",
                    subtitle = "当前：$currentPackLabel（支持 ADW / Nova / GO 图标包）",
                    onClick = { showIconPackDialog = true }
                )
                if (showIconPackDialog) {
                    AlertDialog(
                        onDismissRequest = { showIconPackDialog = false },
                        title = { Text("选择图标包") },
                        text = {
                            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                                item {
                                    ListItem(
                                        headlineContent = { Text("系统默认图标") },
                                        trailingContent = if (settings.iconPackPackage.isBlank()) {
                                            { Icon(Icons.Rounded.Check, null) }
                                        } else null,
                                        modifier = Modifier.clickable {
                                            app.appRepo.setActiveIconPack("")
                                            onUpdate { it.copy(iconPackPackage = "") }
                                            showIconPackDialog = false
                                        }
                                    )
                                }
                                if (installedPacks.isEmpty()) {
                                    item {
                                        ListItem(
                                            headlineContent = { Text("未检测到第三方图标包") },
                                            supportingContent = { Text("安装任意常见图标包后即可在此切换") }
                                        )
                                    }
                                } else {
                                    items(installedPacks, key = { it.packageName }) { pack ->
                                        ListItem(
                                            headlineContent = { Text(pack.label) },
                                            supportingContent = { Text(pack.packageName) },
                                            trailingContent = if (settings.iconPackPackage == pack.packageName) {
                                                { Icon(Icons.Rounded.Check, null) }
                                            } else null,
                                            modifier = Modifier.clickable {
                                                app.appRepo.setActiveIconPack(pack.packageName)
                                                onUpdate { it.copy(iconPackPackage = pack.packageName) }
                                                showIconPackDialog = false
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showIconPackDialog = false }) { Text("关闭") }
                        }
                    )
                }
                SwitchRow(
                    title = "列表样式展示",
                    subtitle = "关闭则为网格样式",
                    checked = settings.isListMode,
                    onCheckedChange = { v -> onUpdate { it.copy(isListMode = v) } }
                )
                if (!settings.isListMode) {
                    SliderRow(
                        title = "网格列数 (${settings.gridColumns} 列)",
                        value = settings.gridColumns.toFloat(),
                        range = 2f..8f,
                        onChange = { v -> onUpdate { it.copy(gridColumns = v.roundToInt()) } }
                    )
                }
                SliderRow(
                    title = "应用图标大小 (${settings.iconSizeDp} dp)",
                    value = settings.iconSizeDp.toFloat(),
                    range = 32f..72f,
                    onChange = { v -> onUpdate { it.copy(iconSizeDp = v.roundToInt()) } }
                )
                SwitchRow(
                    title = "隐藏顶部标签栏",
                    subtitle = "极简界面仅保留搜索结果与键盘",
                    checked = settings.hideTopTabs,
                    onCheckedChange = { v -> onUpdate { it.copy(hideTopTabs = v) } }
                )
                SwitchRow(
                    title = "显示最近安装/更新圆点",
                    subtitle = "48 小时内新安装或更新的应用显示状态圆点",
                    checked = settings.showInstallDot,
                    onCheckedChange = { v -> onUpdate { it.copy(showInstallDot = v, showUpdateDot = v) } }
                )
                SwitchRow(
                    title = "T9 按键字母水平排列",
                    subtitle = "默认垂直显示数字与字母",
                    checked = settings.horizontalKeyLayout,
                    onCheckedChange = { v -> onUpdate { it.copy(horizontalKeyLayout = v) } }
                )
            }
        }

        // 3. Search & Behavior
        item { SectionTitle("搜索、手势与操作习惯") }
        item {
            SettingsCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("默认应用排序方式", style = MaterialTheme.typography.bodyLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val sorts = listOf(
                            SortMode.FRECENCY to "智能常用",
                            SortMode.SMART to "使用优先",
                            SortMode.LAST_LAUNCH to "最近打开",
                            SortMode.LAUNCH_COUNT to "启动次数",
                            SortMode.INSTALL_TIME to "安装时间",
                            SortMode.UPDATE_TIME to "更新时间",
                            SortMode.NAME to "名称"
                        )
                        items(sorts) { (m, label) ->
                            FilterChip(
                                selected = settings.sortMode == m,
                                onClick = { onUpdate { it.copy(sortMode = m) } },
                                label = { Text(label) }
                            )
                        }
                    }
                }
                SwitchRow(
                    title = "全局聚合搜索",
                    subtitle = "在首页搜索时同时匹配已收藏快捷方式、扫码与网页书签",
                    checked = settings.unifiedSearchEnabled,
                    onCheckedChange = { v -> onUpdate { it.copy(unifiedSearchEnabled = v) } }
                )
                SwitchRow(
                    title = "无匹配时一键网页搜索",
                    subtitle = "输入关键词无本地应用匹配时显示百度、Google、酷安等直达胶囊",
                    checked = settings.webSearchEnabled,
                    onCheckedChange = { v -> onUpdate { it.copy(webSearchEnabled = v) } }
                )
                NavRow(
                    icon = Icons.Rounded.TravelExplore,
                    title = "自定义搜索引擎",
                    subtitle = "已配置 ${settings.searchEngines.size} 个搜索直达入口",
                    onClick = { onNavigate(SettingsSubPage.SEARCH_ENGINES) }
                )
                SwitchRow(
                    title = "超级模糊搜索",
                    subtitle = "关键词跳跃匹配（如 kqdog 匹配 kuaiqidong）",
                    checked = settings.superFuzzySearch,
                    onCheckedChange = { v -> onUpdate { it.copy(superFuzzySearch = v) } }
                )
                SwitchRow(
                    title = "默认使用系统输入法搜索",
                    subtitle = "打开时自动弹出全键盘而不是 T9 键盘",
                    checked = settings.defaultSearchMode == SearchMode.IME,
                    onCheckedChange = { v ->
                        onUpdate { it.copy(defaultSearchMode = if (v) SearchMode.IME else SearchMode.T9) }
                    }
                )
                SwitchRow(
                    title = "显示应用分身 / 双开应用",
                    subtitle = "识别并独立展示多用户/ColorOS 分身应用（配合 Shizuku 更佳）",
                    checked = settings.showDualApps,
                    onCheckedChange = { v -> onUpdate { it.copy(showDualApps = v) } }
                )
                SwitchRow(
                    title = "显示已冻结应用",
                    subtitle = "点击已冻结应用时自动通过 Shizuku 解冻并启动",
                    checked = settings.showFrozenApps,
                    onCheckedChange = { v -> onUpdate { it.copy(showFrozenApps = v) } }
                )
                SwitchRow(
                    title = "启动应用后自动关闭窗口",
                    checked = settings.autoCloseOnLaunch,
                    onCheckedChange = { v -> onUpdate { it.copy(autoCloseOnLaunch = v) } }
                )
                SwitchRow(
                    title = "点击窗外区域关闭窗口",
                    checked = settings.clickOutsideClose,
                    onCheckedChange = { v -> onUpdate { it.copy(clickOutsideClose = v) } }
                )
                SwitchRow(
                    title = "从最近任务列表中隐藏",
                    checked = settings.excludeFromRecents,
                    onCheckedChange = { v -> onUpdate { it.copy(excludeFromRecents = v) } }
                )
                SwitchRow(
                    title = "开启单手下拉开关",
                    subtitle = "在顶部栏点击向下箭头将列表下移方便单手点选",
                    checked = settings.pullDownEnabled,
                    onCheckedChange = { v -> onUpdate { it.copy(pullDownEnabled = v) } }
                )
                SwitchRow(
                    title = "上滑 T9 数字键直接启动列表应用",
                    subtitle = "开启后在列表角标显示 0-9，上滑直接打开对应应用",
                    checked = settings.swipeUpStartsListApp,
                    onCheckedChange = { v -> onUpdate { it.copy(swipeUpStartsListApp = v) } }
                )
                SwitchRow(
                    title = "按键震动反馈",
                    checked = settings.hapticEnabled,
                    onCheckedChange = { v -> onUpdate { it.copy(hapticEnabled = v) } }
                )
                SwitchRow(
                    title = "Root 兜底模式",
                    subtitle = "Shizuku 不可用时尝试通过 su 执行高级命令",
                    checked = settings.rootFallbackEnabled,
                    onCheckedChange = { v -> onUpdate { it.copy(rootFallbackEnabled = v) } }
                )
                NavRow(
                    icon = Icons.Rounded.Dialpad,
                    title = "T9 快捷键管理",
                    subtitle = "已绑定 $t9Count 个数字快捷键",
                    onClick = { onNavigate(SettingsSubPage.T9_KEYS) }
                )
                NavRow(
                    icon = Icons.Rounded.Gesture,
                    title = "四大功能键手势配置",
                    subtitle = "飞机键、联系人键、设置键、删除键的点击/长按/四向滑动",
                    onClick = { onNavigate(SettingsSubPage.GESTURES) }
                )
                NavRow(
                    icon = Icons.Rounded.MenuOpen,
                    title = "应用长按菜单管理",
                    subtitle = "自定义长按应用弹窗显示哪些操作",
                    onClick = { onNavigate(SettingsSubPage.MENU_ITEMS) }
                )
            }
        }

        // 4. Global Triggers
        item { SectionTitle("全局唤醒方式") }
        item {
            var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                canDrawOverlays = Settings.canDrawOverlays(ctx)
            }
            SettingsCard {
                NavRow(
                    icon = Icons.Rounded.Layers,
                    title = "悬浮窗零闪屏模式（推荐）",
                    subtitle = if (canDrawOverlays) {
                        "已授权 · 弹窗直接悬浮在原界面上方，零白屏遮挡"
                    } else {
                        "未授权 · 点此开启悬浮窗权限，彻底告别冷启动白屏"
                    },
                    onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                        )
                    }
                )
                NavRow(
                    icon = Icons.Rounded.AddToHomeScreen,
                    title = "添加 1×1 零闪屏桌面图标（小组件）",
                    subtitle = "放到桌面替代原图标，冷/热启动均直接弹出悬浮窗，零白屏",
                    onClick = {
                        val awm = AppWidgetManager.getInstance(ctx)
                        val provider = ComponentName(ctx, QuickStartIconWidgetProvider::class.java)
                        if (awm.isRequestPinAppWidgetSupported) {
                            val requested = awm.requestPinAppWidget(provider, null, null)
                            if (!requested) {
                                onToast("请长按桌面空白处 -> 添加小组件「快启动 1×1 悬浮图标」")
                            }
                        } else {
                            onToast("当前桌面不支持自动添加，请从桌面小组件中添加「快启动 1×1 悬浮图标」")
                        }
                    }
                )
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1×1 桌面图标样式", style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WidgetIconStyle.entries.forEach { st ->
                            FilterChip(
                                selected = settings.widgetIconStyle == st,
                                onClick = {
                                    onUpdate { it.copy(widgetIconStyle = st) }
                                    QuickStartIconWidgetProvider.refreshAll(ctx)
                                },
                                label = { Text(st.label) }
                            )
                        }
                    }
                }
                if (settings.widgetIconStyle != WidgetIconStyle.TRANSPARENT) {
                    SliderRow(
                        title = "1×1 图标透明度 (${(settings.widgetIconAlpha * 100).roundToInt()}%)",
                        value = settings.widgetIconAlpha,
                        range = 0.2f..1f,
                        onChange = { v ->
                            onUpdate { it.copy(widgetIconAlpha = v) }
                            QuickStartIconWidgetProvider.refreshAll(ctx)
                        }
                    )
                    SwitchRow(
                        title = "显示 1×1 图标下方文字",
                        checked = settings.widgetShowLabel,
                        onCheckedChange = { v ->
                            onUpdate { it.copy(widgetShowLabel = v) }
                            QuickStartIconWidgetProvider.refreshAll(ctx)
                        }
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchRow(
                    title = "边缘隐形侧滑触发条",
                    subtitle = "在屏幕左/右边缘向内滑动即可零闪屏呼出（需悬浮窗权限）",
                    checked = settings.edgeTriggerEnabled,
                    onCheckedChange = { v ->
                        if (v && !Settings.canDrawOverlays(ctx)) {
                            ctx.startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                            )
                            onToast("请先授予悬浮窗权限")
                        } else {
                            val updated = settings.copy(edgeTriggerEnabled = v)
                            onUpdate { updated }
                            FloatingBubbleService.syncWithSettings(ctx, updated)
                        }
                    }
                )
                if (settings.edgeTriggerEnabled) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("触发边缘位置", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EdgeTriggerSide.entries.forEach { side ->
                                FilterChip(
                                    selected = settings.edgeTriggerSide == side,
                                    onClick = {
                                        val updated = settings.copy(edgeTriggerSide = side)
                                        onUpdate { updated }
                                        FloatingBubbleService.syncWithSettings(ctx, updated)
                                    },
                                    label = { Text(side.label) }
                                )
                            }
                        }
                    }
                    SliderRow(
                        title = "触发条上下位置 (${(settings.edgeTriggerPosition * 100).roundToInt()}%)",
                        value = settings.edgeTriggerPosition,
                        range = 0f..1f,
                        onChange = { v ->
                            val updated = settings.copy(edgeTriggerPosition = v)
                            onUpdate { updated }
                            FloatingBubbleService.syncWithSettings(ctx, updated)
                        }
                    )
                    SliderRow(
                        title = "触发条长度 (${settings.edgeTriggerHeightDp} dp)",
                        value = settings.edgeTriggerHeightDp.toFloat(),
                        range = 60f..260f,
                        onChange = { v ->
                            val updated = settings.copy(edgeTriggerHeightDp = v.roundToInt())
                            onUpdate { updated }
                            FloatingBubbleService.syncWithSettings(ctx, updated)
                        }
                    )
                    SliderRow(
                        title = "触发热区宽度/灵敏度 (${settings.edgeTriggerWidthDp} dp)",
                        value = settings.edgeTriggerWidthDp.toFloat(),
                        range = 10f..28f,
                        onChange = { v ->
                            val updated = settings.copy(edgeTriggerWidthDp = v.roundToInt())
                            onUpdate { updated }
                            FloatingBubbleService.syncWithSettings(ctx, updated)
                        }
                    )
                    SwitchRow(
                        title = "显示微弱侧边提示条",
                        subtitle = "关闭则完全隐形盲滑触发",
                        checked = settings.edgeTriggerVisibleHandle,
                        onCheckedChange = { v ->
                            val updated = settings.copy(edgeTriggerVisibleHandle = v)
                            onUpdate { updated }
                            FloatingBubbleService.syncWithSettings(ctx, updated)
                        }
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SwitchRow(
                    title = "开启悬浮球",
                    subtitle = "全局悬浮快捷入口（需授予悬浮窗权限）",
                    checked = settings.floatingBubbleEnabled,
                    onCheckedChange = { v ->
                        if (v && !Settings.canDrawOverlays(ctx)) {
                            ctx.startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                            )
                            onToast("请先授予悬浮窗权限")
                        } else {
                            val updated = settings.copy(floatingBubbleEnabled = v)
                            onUpdate { updated }
                            FloatingBubbleService.syncWithSettings(ctx, updated)
                        }
                    }
                )
                SwitchRow(
                    title = "悬浮球自动贴边",
                    checked = settings.floatingBubbleSnapEdge,
                    onCheckedChange = { v ->
                        val updated = settings.copy(floatingBubbleSnapEdge = v)
                        onUpdate { updated }
                        FloatingBubbleService.syncWithSettings(ctx, updated)
                    }
                )
                SwitchRow(
                    title = "开启通知栏常驻入口",
                    subtitle = "在通知栏常驻快捷点击呼出快启动",
                    checked = settings.notificationEnabled,
                    onCheckedChange = { onRequestNotification() }
                )
                SwitchRow(
                    title = "开机自启悬浮球 / 通知栏",
                    checked = settings.floatingBubbleBootStart || settings.notificationBootStart,
                    onCheckedChange = { v ->
                        onUpdate { it.copy(floatingBubbleBootStart = v, notificationBootStart = v) }
                    }
                )
                SwitchRow(
                    title = "桌面启动器模式 (Home)",
                    subtitle = "开启后可在系统设置中将快启动设为默认桌面",
                    checked = settings.launcherModeEnabled,
                    onCheckedChange = { v ->
                        onUpdate { it.copy(launcherModeEnabled = v) }
                        app.appRepo.setLauncherModeEnabled(v)
                        onToast(if (v) "已启用桌面入口，可在系统默认桌面中选择" else "已关闭桌面入口")
                    }
                )
                NavRow(
                    icon = Icons.Rounded.Assistant,
                    title = "设为系统语音助手 / 侧滑唤醒",
                    subtitle = "支持系统 ACTION_ASSIST，可在默认应用 -> 语音助手中选择快启动",
                    onClick = {
                        runCatching {
                            ctx.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                        }.onFailure {
                            runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                        }
                    }
                )
                NavRow(
                    icon = Icons.Rounded.Contacts,
                    title = "授予通讯录与直拨权限",
                    subtitle = "用于 T9 搜索联系人、一键拨号及发短信",
                    onClick = onRequestContacts
                )
            }
        }

        // 5. Extensions & Privacy
        item { SectionTitle("扩展功能与隐私") }
        item {
            SettingsCard {
                NavRow(
                    icon = Icons.Rounded.Translate,
                    title = "自定义多音字字典",
                    subtitle = "已配置 $polyCount 条自定义词条（解决多音字搜索）",
                    onClick = { onNavigate(SettingsSubPage.POLYPHONE) }
                )
                NavRow(
                    icon = Icons.Rounded.Lock,
                    title = "隐私应用隐藏与访问密码",
                    subtitle = "已隐藏 $hiddenCount 个应用 · 入口名称「${settings.vaultEntryName}」",
                    onClick = { onNavigate(SettingsSubPage.VAULT) }
                )
                SwitchRow(
                    title = "开启键盘音效",
                    subtitle = "内置五声音阶合成音，也支持自定义音源目录：\n${app.soundPlayer.customAudioDirPath()}",
                    checked = settings.keySoundEnabled,
                    onCheckedChange = { v -> onUpdate { it.copy(keySoundEnabled = v) } }
                )
                NavRow(
                    icon = Icons.Rounded.Refresh,
                    title = "重新加载自定义键盘音源",
                    subtitle = "放置 0.mp3 ~ 14.mp3 到 KeyAudio 目录后点此加载",
                    onClick = {
                        app.soundPlayer.reloadCustomSounds()
                        onToast("音源已重新加载")
                    }
                )
            }
        }

        // 6. Backup & About
        item { SectionTitle("备份与恢复") }
        item {
            SettingsCard {
                NavRow(
                    icon = Icons.Rounded.UploadFile,
                    title = "导出全部配置与收藏 (JSON)",
                    subtitle = "含外观、手势、T9快捷键、多音字典、分类、书签、隐藏应用",
                    onClick = onExportBackup
                )
                NavRow(
                    icon = Icons.Rounded.Download,
                    title = "从备份文件恢复",
                    subtitle = "选择之前导出的 JSON 备份文件",
                    onClick = onImportBackup
                )
                NavRow(
                    icon = Icons.Rounded.CleaningServices,
                    title = "清除 APK 分享缓存",
                    subtitle = "清理提取安装包时产生的临时缓存",
                    onClick = onClearApkCache
                )
                ListItem(
                    headlineContent = { Text("快启动 MD3 · v${BuildConfig.VERSION_NAME}") },
                    supportingContent = { Text("Material Design 3 · 莫奈动态取色 · Shizuku 驱动") },
                    leadingContent = { Icon(Icons.Rounded.Bolt, null) }
                )
            }
        }
    }
}

@Composable
private fun T9KeysManagePage(
    modifier: Modifier,
    app: QuickStartApp,
    settings: AppSettings,
    t9Keys: List<T9QuickKey>,
    savedActivities: List<SavedActivityItem>,
    onBindKey: (T9QuickKey) -> Unit,
    onRemove: (Int) -> Unit,
    onClearAll: () -> Unit
) {
    val quickMap = remember(t9Keys) { t9Keys.associateBy { it.digit } }
    var editingDigit by remember { mutableStateOf<Int?>(null) }
    val allApps by produceState<List<AppEntry>>(initialValue = emptyList()) {
        val cached = withContext(Dispatchers.IO) { app.store.loadCachedApps() }
        if (cached.isNotEmpty()) {
            value = cached.sortedByDescending { it.launchCount }
        }
        app.store.awaitLoaded()
        val loaded = withContext(Dispatchers.IO) {
            app.appRepo.loadApps(
                customNames = app.store.customNames.value,
                hiddenPackages = emptySet(),
                polyphones = app.store.polyphones.value,
                usageStats = app.store.usageStats.value,
                showSelf = settings.showSelfApp,
                showDualApps = settings.showDualApps
            )
        }
        value = loaded.sortedByDescending { it.launchCount }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "点击下方九宫格中任意数字键，即可直接绑定应用、分身或已保存的快捷方式；主页长按数字键极速直达。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val rows = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(0))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { rowDigits ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowDigits.forEach { digit ->
                        val bound = quickMap[digit]
                        val iconPkg = bound?.packageName.orEmpty()
                            .ifBlank { bound?.shortcutData?.packageName.orEmpty() }
                        val iconBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = iconPkg) {
                            value = if (iconPkg.isNotBlank()) {
                                withContext(Dispatchers.IO) { app.appRepo.getAppIcon(iconPkg) }
                            } else null
                        }
                        Surface(
                            onClick = { editingDigit = digit },
                            color = if (bound != null) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            border = if (bound != null) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                            } else null,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f).height(68.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = digit.toString(),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(3.dp))
                                if (bound != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        iconBitmap?.let { bmp ->
                                            Image(
                                                bitmap = bmp,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp).clip(RoundedCornerShape(3.5.dp))
                                            )
                                        }
                                        Text(
                                            text = bound.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "点击绑定",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (t9Keys.isNotEmpty()) {
            OutlinedButton(
                onClick = onClearAll,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("清空全部快捷键")
            }
        }
    }

    editingDigit?.let { digit ->
        val current = quickMap[digit]
        var tabIndex by remember(digit) { mutableIntStateOf(0) }
        var searchText by remember(digit) { mutableStateOf("") }
        var renameLabel by remember(digit, current) { mutableStateOf(current?.label.orEmpty()) }

        AlertDialog(
            onDismissRequest = { editingDigit = null },
            title = { Text("配置数字键 $digit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (current != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedTextField(
                                value = renameLabel,
                                onValueChange = { renameLabel = it },
                                label = { Text("自定义显示标签") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                enabled = renameLabel.isNotBlank(),
                                onClick = {
                                    onBindKey(current.copy(label = renameLabel.trim()))
                                    editingDigit = null
                                }
                            ) { Text("改名") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = tabIndex == 0, onClick = { tabIndex = 0 }, label = { Text("选择应用") })
                        FilterChip(selected = tabIndex == 1, onClick = { tabIndex = 1 }, label = { Text("快捷方式") })
                    }
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        placeholder = { Text("筛选名称或包名...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val q = searchText.trim().lowercase()
                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                        if (tabIndex == 0) {
                            val filtered = if (q.isEmpty()) allApps else allApps.filter {
                                it.displayName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
                            }
                            items(filtered.take(60), key = { it.uniqueKey }) { appEntry ->
                                ListItem(
                                    headlineContent = { Text(appEntry.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(appEntry.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.clickable {
                                        onBindKey(
                                            T9QuickKey(
                                                digit = digit,
                                                label = appEntry.displayName,
                                                targetType = TargetType.APP,
                                                packageName = appEntry.packageName,
                                                userId = appEntry.userId
                                            )
                                        )
                                        editingDigit = null
                                    }
                                )
                            }
                        } else {
                            val combined = (savedActivities + AppRepository.builtInQuickPresets()).distinctBy { it.id }
                            val filtered = if (q.isEmpty()) combined else combined.filter {
                                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
                            }
                            items(filtered, key = { it.id }) { item ->
                                ListItem(
                                    headlineContent = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(item.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.clickable {
                                        onBindKey(
                                            T9QuickKey(
                                                digit = digit,
                                                label = item.label,
                                                targetType = item.type,
                                                packageName = item.packageName,
                                                targetValue = if (item.type == TargetType.URI) item.uri.orEmpty() else item.activityName,
                                                shortcutData = item.shortcutData
                                            )
                                        )
                                        editingDigit = null
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (current != null) {
                    TextButton(onClick = {
                        onRemove(digit)
                        editingDigit = null
                    }) { Text("解绑", color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = {
                TextButton(onClick = { editingDigit = null }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun GesturesConfigPage(
    modifier: Modifier,
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit
) {
    var editingTarget by remember { mutableStateOf<Pair<String, KeyGestureConfig>?>(null) }
    var editingSlot by remember { mutableStateOf<String?>(null) }

    val items = listOf(
        "飞机键 (快捷)" to settings.airplaneKey,
        "联系人键" to settings.contactKey,
        "设置键" to settings.settingKey,
        "删除键" to settings.deleteKey
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items) { (title, cfg) ->
            SettingsCard {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val slots = listOf(
                        "click" to ("点击" to cfg.click),
                        "longPress" to ("长按" to cfg.longPress),
                        "swipeUp" to ("上滑" to cfg.swipeUp),
                        "swipeDown" to ("下滑" to cfg.swipeDown),
                        "swipeLeft" to ("左滑" to cfg.swipeLeft),
                        "swipeRight" to ("右滑" to cfg.swipeRight)
                    )
                    slots.forEach { (slotKey, pair) ->
                        ListItem(
                            headlineContent = { Text(pair.first) },
                            trailingContent = { Text(pair.second.label, color = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.clickable {
                                editingTarget = title to cfg
                                editingSlot = slotKey
                            }
                        )
                    }
                }
            }
        }
    }

    val target = editingTarget
    val slot = editingSlot
    if (target != null && slot != null) {
        AlertDialog(
            onDismissRequest = { editingTarget = null; editingSlot = null },
            title = { Text("选择「${target.first}」动作") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(GestureAction.entries) { act ->
                        ListItem(
                            headlineContent = { Text(act.label) },
                            modifier = Modifier.clickable {
                                val old = target.second
                                val updated = when (slot) {
                                    "click" -> old.copy(click = act)
                                    "longPress" -> old.copy(longPress = act)
                                    "swipeUp" -> old.copy(swipeUp = act)
                                    "swipeDown" -> old.copy(swipeDown = act)
                                    "swipeLeft" -> old.copy(swipeLeft = act)
                                    else -> old.copy(swipeRight = act)
                                }
                                onUpdate { s ->
                                    when (target.first) {
                                        "飞机键 (快捷)" -> s.copy(airplaneKey = updated)
                                        "联系人键" -> s.copy(contactKey = updated)
                                        "设置键" -> s.copy(settingKey = updated)
                                        else -> s.copy(deleteKey = updated)
                                    }
                                }
                                editingTarget = null
                                editingSlot = null
                            }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { editingTarget = null; editingSlot = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun PolyphoneManagePage(
    modifier: Modifier,
    entries: List<PolyphoneEntry>,
    onSave: (String, String) -> Unit,
    onDelete: (String) -> Unit
) {
    var word by remember { mutableStateOf("") }
    var pinyin by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("格式示例：词语填「重庆」，拼音按空格分隔填「chong qing」。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = word, onValueChange = { word = it }, label = { Text("词语") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = pinyin, onValueChange = { pinyin = it }, label = { Text("拼音(空格分隔)") }, singleLine = true, modifier = Modifier.weight(1.4f))
        }
        Button(
            enabled = word.isNotBlank() && pinyin.isNotBlank(),
            onClick = {
                onSave(word.trim(), pinyin.trim().lowercase())
                word = ""
                pinyin = ""
            }
        ) { Text("添加多音词条") }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(entries, key = { it.word }) { entry ->
                SettingsCard {
                    ListItem(
                        headlineContent = { Text(entry.word) },
                        supportingContent = { Text(entry.pinyin) },
                        trailingContent = {
                            IconButton(onClick = { onDelete(entry.word) }) {
                                Icon(Icons.Rounded.DeleteOutline, "删除")
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivacyVaultPage(
    modifier: Modifier,
    app: QuickStartApp,
    settings: AppSettings,
    hiddenPackages: Set<String>,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onUnhide: (String) -> Unit,
    onToast: (String) -> Unit
) {
    var entryName by remember(settings.vaultEntryName) { mutableStateOf(settings.vaultEntryName) }
    var hint by remember(settings.vaultPasswordHint) { mutableStateOf(settings.vaultPasswordHint) }
    var newPwd by remember { mutableStateOf("") }
    var confirmPwd by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SettingsCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = entryName,
                        onValueChange = { entryName = it },
                        label = { Text("快捷菜单入口名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = hint,
                        onValueChange = { hint = it },
                        label = { Text("密码提示语") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = {
                        onUpdate { it.copy(vaultEntryName = entryName.ifBlank { "隐私应用" }, vaultPasswordHint = hint) }
                        onToast("入口设置已保存")
                    }) { Text("保存入口名称与提示") }

                    HorizontalDivider()

                    OutlinedTextField(
                        value = newPwd,
                        onValueChange = { newPwd = it },
                        label = { Text(if (settings.vaultPasswordHash.isEmpty()) "设置访问密码" else "重设访问密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmPwd,
                        onValueChange = { confirmPwd = it },
                        label = { Text("再次确认密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = newPwd.isNotBlank() && newPwd == confirmPwd,
                            onClick = {
                                onUpdate { it.copy(vaultPasswordHash = DataStoreRepository.hashPassword(newPwd)) }
                                newPwd = ""
                                confirmPwd = ""
                                onToast("密码设置成功")
                            }
                        ) { Text("保存密码") }
                        if (settings.vaultPasswordHash.isNotEmpty()) {
                            OutlinedButton(onClick = {
                                onUpdate { it.copy(vaultPasswordHash = "") }
                                onToast("已清除密码")
                            }) { Text("清除密码") }
                        }
                    }
                }
            }
        }
        item { SectionTitle("已隐藏的应用 (${hiddenPackages.size})") }
        items(hiddenPackages.toList(), key = { it }) { pkg ->
            val purePkg = pkg.substringBefore('#')
            val suffix = if (pkg.contains('#')) "（分身 ${pkg.substringAfter('#')}）" else ""
            val label = remember(pkg) {
                runCatching {
                    val pm = app.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(purePkg, 0)).toString() + suffix
                }.getOrDefault(pkg)
            }
            SettingsCard {
                ListItem(
                    headlineContent = { Text(label) },
                    supportingContent = { Text(pkg) },
                    trailingContent = {
                        TextButton(onClick = { onUnhide(pkg) }) { Text("恢复显示") }
                    }
                )
            }
        }
    }
}

@Composable
private fun MenuCustomizePage(
    modifier: Modifier,
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(AppMenuAction.entries) { action ->
            val checked = action in settings.enabledMenuActions
            SettingsCard {
                SwitchRow(
                    title = action.label,
                    subtitle = null,
                    checked = checked,
                    onCheckedChange = { enable ->
                        onUpdate { s ->
                            val next = if (enable) {
                                (s.enabledMenuActions + action).distinct()
                            } else {
                                s.enabledMenuActions - action
                            }
                            s.copy(enabledMenuActions = next)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SearchEnginesPage(
    modifier: Modifier,
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onToast: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var urlTemplate by remember { mutableStateOf("https://") }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "搜索直达模板支持使用 %s 代表搜索关键词（例如：https://www.baidu.com/s?wd=%s）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.weight(0.9f)
            )
            OutlinedTextField(
                value = urlTemplate,
                onValueChange = { urlTemplate = it },
                label = { Text("网址模板 (%s)") },
                singleLine = true,
                modifier = Modifier.weight(1.6f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = name.isNotBlank() && urlTemplate.isNotBlank(),
                onClick = {
                    val fixed = if (urlTemplate.startsWith("http://") || urlTemplate.startsWith("https://")) {
                        urlTemplate.trim()
                    } else {
                        "https://${urlTemplate.trim()}"
                    }
                    val item = SearchEngineItem(
                        id = UUID.randomUUID().toString(),
                        name = name.trim(),
                        urlTemplate = fixed
                    )
                    onUpdate { s -> s.copy(searchEngines = s.searchEngines + item) }
                    name = ""
                    urlTemplate = "https://"
                    onToast("已添加搜索引擎")
                }
            ) { Text("添加搜索引擎") }
            OutlinedButton(onClick = {
                onUpdate { it.copy(searchEngines = defaultSearchEngines()) }
                onToast("已恢复默认搜索引擎")
            }) { Text("恢复默认") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(settings.searchEngines, key = { it.id }) { eng ->
                SettingsCard {
                    ListItem(
                        headlineContent = { Text(eng.name) },
                        supportingContent = { Text(eng.urlTemplate, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            IconButton(onClick = {
                                onUpdate { s -> s.copy(searchEngines = s.searchEngines.filterNot { it.id == eng.id }) }
                            }) {
                                Icon(Icons.Rounded.DeleteOutline, "删除")
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (!subtitle.isNullOrBlank()) {
            { Text(subtitle, style = MaterialTheme.typography.bodySmall) }
        } else null,
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        modifier = Modifier.clickable { onCheckedChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    )
}

@Composable
private fun NavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    )
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
