package com.quickstart.md3.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.quickstart.md3.data.ActivityEntry
import com.quickstart.md3.data.AppEntry
import com.quickstart.md3.data.AppShortcutItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShortcutsSheet(
    app: AppEntry,
    loading: Boolean,
    shortcuts: List<AppShortcutItem>,
    error: String?,
    onDismiss: () -> Unit,
    onInvoke: (AppShortcutItem) -> Unit,
    onSaveFavorite: (AppShortcutItem) -> Unit,
    onBindT9: (Int, AppShortcutItem) -> Unit,
    onPinDesktop: (AppShortcutItem) -> Unit
) {
    var bindTarget by remember { mutableStateOf<AppShortcutItem?>(null) }

    OverlaySafeBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "${app.displayName} · 应用快捷方式 (Shizuku)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            when {
                loading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                error != null -> {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
                shortcuts.isEmpty() -> {
                    Text(
                        text = "未查询到该应用的系统快捷方式（ShortcutInfo）。可尝试使用「浏览活动」功能。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(shortcuts, key = { it.id }) { sc ->
                            ElevatedCard(
                                onClick = { onInvoke(sc) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = sc.shortLabel,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        FilledTonalButton(
                                            onClick = { onInvoke(sc) },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Text("调用", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    val detail = sc.action ?: sc.component ?: sc.dataUri ?: sc.id
                                    Text(
                                        text = detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = { onSaveFavorite(sc) }) {
                                            Icon(Icons.Rounded.BookmarkAdd, null, Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("加入快捷栏", style = MaterialTheme.typography.labelSmall)
                                        }
                                        TextButton(onClick = { bindTarget = sc }) {
                                            Icon(Icons.Rounded.Dialpad, null, Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("绑T9键", style = MaterialTheme.typography.labelSmall)
                                        }
                                        TextButton(onClick = { onPinDesktop(sc) }) {
                                            Icon(Icons.Rounded.AddToHomeScreen, null, Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("发桌面", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    bindTarget?.let { sc ->
        T9DigitPickerDialog(
            title = "绑定「${sc.shortLabel}」到 T9 键",
            onDismiss = { bindTarget = null },
            onSelect = { digit ->
                onBindT9(digit, sc)
                bindTarget = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppActivitiesSheet(
    app: AppEntry,
    activities: List<ActivityEntry>,
    onDismiss: () -> Unit,
    onLaunch: (ActivityEntry) -> Unit,
    onSaveFavorite: (AppEntry, ActivityEntry) -> Unit,
    onPinDesktop: (ActivityEntry) -> Unit,
    onShowToast: (String) -> Unit
) {
    val ctx = LocalContext.current
    var search by remember { mutableStateOf("") }
    var onlyExported by remember { mutableStateOf(false) }

    val filtered = remember(activities, search, onlyExported) {
        activities.filter { act ->
            (!onlyExported || act.exported) &&
                (search.isBlank() ||
                    act.className.contains(search, true) ||
                    act.label.contains(search, true))
        }
    }

    OverlaySafeBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "${app.displayName} · 活动列表 (${filtered.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("搜索活动名称/类名") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = onlyExported,
                    onClick = { onlyExported = !onlyExported },
                    label = { Text("仅开放") }
                )
            }

            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filtered, key = { it.className }) { act ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().clickable { onLaunch(act) }
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = act.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                AssistChip(
                                    onClick = { onLaunch(act) },
                                    label = {
                                        Text(
                                            if (act.exported) "开放 · 启动" else "Shizuku 强启",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    modifier = Modifier.height(26.dp)
                                )
                            }
                            Text(
                                text = act.className,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { onSaveFavorite(app, act) }) {
                                    Text("加快捷栏", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(onClick = { onPinDesktop(act) }) {
                                    Text("发桌面", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(onClick = {
                                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Activity", "${act.packageName}/${act.className}"))
                                    onShowToast("已复制组件名")
                                }) {
                                    Text("复制", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
