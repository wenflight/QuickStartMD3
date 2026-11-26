package com.quickstart.md3.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.quickstart.md3.data.*
import com.quickstart.md3.shizuku.ShizukuStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppActionSheet(
    app: AppEntry,
    repo: AppRepository,
    collections: List<AppCollection>,
    shizukuStatus: ShizukuStatus,
    enabledActions: List<AppMenuAction>,
    onDismiss: () -> Unit,
    onForceStop: (AppEntry) -> Unit,
    onToggleFreeze: (AppEntry) -> Unit,
    onLaunchFreeform: (AppEntry) -> Unit = {},
    onSilentUninstall: (AppEntry) -> Unit = {},
    onOpenShortcuts: (AppEntry) -> Unit,
    onOpenActivities: (AppEntry) -> Unit,
    onRename: (AppEntry, String) -> Unit,
    onToggleHide: (AppEntry) -> Unit,
    onBindT9: (Int, AppEntry) -> Unit,
    onAddToCollection: (String, String) -> Unit,
    onCreateCollection: (String, String) -> Unit,
    onShareApk: (AppEntry) -> Unit,
    onExportIcon: (AppEntry) -> Unit,
    onPinShortcut: (AppEntry) -> Unit,
    onShowToast: (String) -> Unit,
    onExternalLaunched: () -> Unit = {}
) {
    val icon = remember(app.uniqueKey) { repo.getAppIcon(app.packageName, app.launcherActivity) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showT9Picker by remember { mutableStateOf(false) }
    var showCollectionPicker by remember { mutableStateOf(false) }
    var showAppInfoDialog by remember { mutableStateOf(false) }
    var showSilentUninstallConfirm by remember { mutableStateOf(false) }

    OverlaySafeBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // App Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = app.displayName,
                        modifier = Modifier.size(48.dp)
                    )
                } else {
                    Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(48.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = app.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (app.isClone) {
                            AssistChip(
                                onClick = {},
                                label = { Text("分身", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(24.dp)
                            )
                        }
                        if (app.isFrozen) {
                            AssistChip(
                                onClick = { onToggleFreeze(app) },
                                label = { Text("已冻结", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(24.dp)
                            )
                        }
                    }
                    Text(
                        text = "${app.packageName} · v${app.versionName.ifBlank { "?" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = {
                    repo.openSystemAppInfo(app.packageName)
                    onDismiss()
                    onExternalLaunched()
                }) {
                    Icon(Icons.Rounded.Info, contentDescription = "系统应用信息")
                }
            }

            // Shizuku Privileged Row
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Shizuku 高级控制",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = if (shizukuStatus == ShizukuStatus.READY) "已连接" else "未就绪",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (shizukuStatus == ShizukuStatus.READY)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ShizukuActionChip(
                            icon = Icons.Rounded.StopCircle,
                            label = "强行停止",
                            onClick = { onForceStop(app) },
                            modifier = Modifier.weight(1f)
                        )
                        ShizukuActionChip(
                            icon = if (app.isFrozen) Icons.Rounded.LockOpen else Icons.Rounded.AcUnit,
                            label = if (app.isFrozen) "解冻" else "冻结",
                            onClick = { onToggleFreeze(app) },
                            modifier = Modifier.weight(1f)
                        )
                        ShizukuActionChip(
                            icon = Icons.Rounded.Bolt,
                            label = "快捷方式",
                            onClick = { onOpenShortcuts(app) },
                            modifier = Modifier.weight(1f)
                        )
                        ShizukuActionChip(
                            icon = Icons.Rounded.Layers,
                            label = "浏览活动",
                            onClick = { onOpenActivities(app) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    val showFreeform = AppMenuAction.LAUNCH_FREEFORM in enabledActions
                    val showSilentUninstall = AppMenuAction.SILENT_UNINSTALL in enabledActions
                    if (showFreeform || showSilentUninstall) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (showFreeform) {
                                ShizukuActionChip(
                                    icon = Icons.Rounded.PictureInPictureAlt,
                                    label = "小窗打开",
                                    onClick = { onLaunchFreeform(app) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (showSilentUninstall) {
                                ShizukuActionChip(
                                    icon = Icons.Rounded.DeleteForever,
                                    label = "静默卸载",
                                    onClick = { showSilentUninstallConfirm = true },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // Management Actions List
            val managementActions = enabledActions.filterNot {
                it == AppMenuAction.FORCE_STOP ||
                    it == AppMenuAction.FREEZE_TOGGLE ||
                    it == AppMenuAction.LAUNCH_FREEFORM ||
                    it == AppMenuAction.SILENT_UNINSTALL ||
                    it == AppMenuAction.SHORTCUTS ||
                    it == AppMenuAction.ACTIVITIES
            }
            LazyColumn(
                modifier = Modifier.heightIn(max = 280.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(managementActions) { action ->
                    val (iconVec, text) = when (action) {
                        AppMenuAction.ADD_TO_COLLECTION -> Icons.Rounded.BookmarkAdd to "添加到分类收藏"
                        AppMenuAction.BIND_T9_KEY -> Icons.Rounded.Dialpad to "绑定到 T9 数字快捷键"
                        AppMenuAction.RENAME -> Icons.Rounded.Edit to "自定义显示名称"
                        AppMenuAction.HIDE_TOGGLE -> (if (app.isHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff) to
                            (if (app.isHidden) "取消隐藏此应用" else "隐藏此应用")
                        AppMenuAction.PIN_SHORTCUT -> Icons.Rounded.AddToHomeScreen to "发送桌面快捷方式"
                        AppMenuAction.SHARE_APK -> Icons.Rounded.Share to "分享 / 提取 APK 安装包"
                        AppMenuAction.EXPORT_ICON -> Icons.Rounded.Image to "导出应用图标到相册"
                        AppMenuAction.APP_DETAILS -> Icons.Rounded.Description to "查看详情（包名/路径/时间）"
                        AppMenuAction.OPEN_MARKET -> Icons.Rounded.Shop to "在应用市场查看"
                        AppMenuAction.SYSTEM_INFO -> Icons.Rounded.SettingsApplications to "打开系统应用信息页"
                        AppMenuAction.UNINSTALL -> Icons.Rounded.DeleteOutline to "卸载应用"
                        else -> Icons.Rounded.Apps to action.label
                    }
                    ListItem(
                        headlineContent = { Text(text, style = MaterialTheme.typography.bodyMedium) },
                        leadingContent = { Icon(iconVec, contentDescription = null) },
                        modifier = Modifier.clickable {
                            when (action) {
                                AppMenuAction.ADD_TO_COLLECTION -> showCollectionPicker = true
                                AppMenuAction.BIND_T9_KEY -> showT9Picker = true
                                AppMenuAction.RENAME -> showRenameDialog = true
                                AppMenuAction.HIDE_TOGGLE -> onToggleHide(app)
                                AppMenuAction.PIN_SHORTCUT -> onPinShortcut(app)
                                AppMenuAction.SHARE_APK -> onShareApk(app)
                                AppMenuAction.EXPORT_ICON -> onExportIcon(app)
                                AppMenuAction.APP_DETAILS -> showAppInfoDialog = true
                                AppMenuAction.OPEN_MARKET -> {
                                    repo.openAppMarket(app.packageName)
                                    onDismiss()
                                    onExternalLaunched()
                                }
                                AppMenuAction.SYSTEM_INFO -> {
                                    repo.openSystemAppInfo(app.packageName)
                                    onDismiss()
                                    onExternalLaunched()
                                }
                                AppMenuAction.UNINSTALL -> {
                                    repo.requestUninstall(app.packageName)
                                    onDismiss()
                                    onExternalLaunched()
                                }
                                else -> Unit
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                    )
                }
            }
        }
    }

    if (showRenameDialog) {
        var nameInput by remember { mutableStateOf(app.customName.ifBlank { app.label }) }
        OverlaySafeDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("自定义名称") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("仅在快启动内生效，留空则恢复原名称（${app.label}）。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        singleLine = true,
                        label = { Text("应用名称") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(app, nameInput)
                    showRenameDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = {
                    onRename(app, "")
                    showRenameDialog = false
                }) { Text("恢复默认") }
            }
        )
    }

    if (showT9Picker) {
        T9DigitPickerDialog(
            title = "绑定到 T9 数字键",
            onDismiss = { showT9Picker = false },
            onSelect = { digit ->
                onBindT9(digit, app)
                showT9Picker = false
            }
        )
    }

    if (showCollectionPicker) {
        var newColName by remember { mutableStateOf("") }
        OverlaySafeDialog(
            onDismissRequest = { showCollectionPicker = false },
            title = { Text("添加到分类收藏") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    collections.forEach { col ->
                        ListItem(
                            headlineContent = { Text(col.name) },
                            supportingContent = { Text("${col.packageNames.size} 个应用") },
                            modifier = Modifier.clickable {
                                onAddToCollection(col.id, app.uniqueKey)
                                showCollectionPicker = false
                            }
                        )
                    }
                    OutlinedTextField(
                        value = newColName,
                        onValueChange = { newColName = it },
                        label = { Text("或新建分类名称") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newColName.isNotBlank(),
                    onClick = {
                        onCreateCollection(newColName, app.uniqueKey)
                        showCollectionPicker = false
                    }
                ) { Text("新建并添加") }
            },
            dismissButton = {
                TextButton(onClick = { showCollectionPicker = false }) { Text("关闭") }
            }
        )
    }

    if (showAppInfoDialog) {
        val ctx = LocalContext.current
        val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
        val entries = listOf(
            "应用名" to app.label,
            "包名" to app.packageName,
            "版本" to "${app.versionName} (${app.versionCode})",
            "入口活动" to app.launcherActivity.ifBlank { "无" },
            "APK 路径" to app.sourceDir,
            "数据目录" to app.dataDir,
            "首次安装" to fmt.format(Date(app.firstInstallTime)),
            "最近更新" to fmt.format(Date(app.lastUpdateTime))
        )
        OverlaySafeDialog(
            onDismissRequest = { showAppInfoDialog = false },
            title = { Text("应用详细信息（点击复制）") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(entries) { (k, v) ->
                        ListItem(
                            headlineContent = { Text(k, style = MaterialTheme.typography.labelMedium) },
                            supportingContent = { Text(v, style = MaterialTheme.typography.bodySmall) },
                            modifier = Modifier.clickable {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText(k, v))
                                onShowToast("已复制$k")
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppInfoDialog = false }) { Text("关闭") }
            }
        )
    }

    if (showSilentUninstallConfirm) {
        OverlaySafeDialog(
            onDismissRequest = { showSilentUninstallConfirm = false },
            title = { Text("确认静默卸载") },
            text = {
                Text(
                    "确定要通过 Shizuku 静默卸载「${app.displayName}」" +
                        (if (app.isClone) "（分身用户 ${app.userId}）" else "") +
                        "吗？此操作不可撤销。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSilentUninstallConfirm = false
                    onSilentUninstall(app)
                }) { Text("确认卸载", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showSilentUninstallConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ShizukuActionChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun T9DigitPickerDialog(
    title: String,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    OverlaySafeDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val rows = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(0))
                rows.forEach { r ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        r.forEach { d ->
                            FilledTonalButton(
                                onClick = { onSelect(d) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(d.toString(), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
