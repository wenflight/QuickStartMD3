package com.quickstart.md3.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
enum class WindowMode {
    BOTTOM_LARGE,
    BOTTOM_SMALL,
    CENTER_LARGE,
    CENTER_SMALL,
    FULLSCREEN,
    CUSTOM
}

@Serializable
enum class SortMode {
    SMART,
    FRECENCY,
    LAUNCH_COUNT,
    LAST_LAUNCH,
    INSTALL_TIME,
    UPDATE_TIME,
    NAME
}

@Serializable
enum class IconShape(val label: String) {
    SYSTEM("默认"),
    ROUNDED("圆角矩形"),
    SQUIRCLE("超椭圆"),
    CIRCLE("圆形")
}

@Serializable
enum class EdgeTriggerSide(val label: String) {
    LEFT("左侧"),
    RIGHT("右侧"),
    BOTH("双侧")
}

@Serializable
enum class WidgetIconStyle(val label: String) {
    MONET_SOLID("莫奈实心"),
    MONET_OUTLINE("莫奈描边"),
    TRANSPARENT("隐形透明")
}

@Serializable
enum class BackTapModel(
    val label: String,
    val assetPath: String,
    val desc: String,
    val isSamsung: Boolean = false
) {
    SAMSUNG(
        "通用旗舰 (推荐)",
        "samsung/backtap_20221018-160917.tflite",
        "三星 RegiStar 通用引擎，仅需加速度计更省电，大屏/国产旗舰识别率更高",
        isSamsung = true
    ),
    CROSSHATCH(
        "大屏完整版 6.2\"+",
        "columbus/tap7cls_crosshatch.tflite",
        "Pixel 3 XL 660KB 完整模型，识别率高于轻量版"
    ),
    CORAL("大屏轻量 6.3\"+", "columbus/tap7cls_coral.tflite", "Pixel 4 XL 轻量模型，适合主流 6.3 英寸以上大屏手机"),
    BRAMBLE("中屏 6.1\"", "columbus/tap7cls_bramble.tflite", "Pixel 4a 5G 模型，适合中等尺寸机身"),
    FLAME("小屏 5.8\"", "columbus/tap7cls_flame.tflite", "Pixel 4 模型，适合小屏机身"),
    REDFIN("迷你 5.7\"", "columbus/tap7cls_redfin.tflite", "Pixel 5 模型，适合紧凑小屏机身");

    val requiresGyroscope: Boolean get() = !isSamsung
}

@Immutable
@Serializable
data class SearchEngineItem(
    val id: String,
    val name: String,
    val urlTemplate: String
)

fun defaultSearchEngines(): List<SearchEngineItem> = listOf(
    SearchEngineItem("gemini", "Gemini", "https://gemini.google.com/app?q=%s"),
    SearchEngineItem("baidu", "百度", "https://www.baidu.com/s?wd=%s"),
    SearchEngineItem("google", "Google", "https://www.google.com/search?q=%s"),
    SearchEngineItem("bing", "必应", "https://www.bing.com/search?q=%s"),
    SearchEngineItem("coolapk", "酷安", "https://www.coolapk.com/search?q=%s"),
    SearchEngineItem("bilibili", "哔哩哔哩", "https://search.bilibili.com/all?keyword=%s"),
    SearchEngineItem("taobao", "淘宝", "https://s.taobao.com/search?q=%s")
)

@Serializable
enum class SearchMode { T9, IME }

@Serializable
enum class GestureAction(val label: String) {
    NONE("无动作"),
    OPEN_QUICK_MENU("打开快捷菜单"),
    LAUNCH_FIRST("启动首个结果"),
    BACKSPACE("退格删除"),
    CLEAR_INPUT("清空输入"),
    TOGGLE_SEARCH_MODE("切换 T9 / 输入法搜索"),
    TOGGLE_CONTACTS("切换联系人模式"),
    TOGGLE_SORT("切换排序方式"),
    TOGGLE_SUPER_FUZZY("开关超级模糊搜索"),
    OPEN_SETTINGS("打开设置"),
    QUICK_SCAN("打开快捷扫码"),
    TOGGLE_SHORTCUTS("切换快捷方式 / 全部"),
    TOGGLE_PIANO_MODE("开关演奏模式"),
    CLOSE_WINDOW("关闭窗口")
}

@Immutable
@Serializable
data class KeyGestureConfig(
    val click: GestureAction = GestureAction.NONE,
    val longPress: GestureAction = GestureAction.NONE,
    val swipeUp: GestureAction = GestureAction.NONE,
    val swipeDown: GestureAction = GestureAction.NONE,
    val swipeLeft: GestureAction = GestureAction.NONE,
    val swipeRight: GestureAction = GestureAction.NONE
)

@Serializable
enum class TargetType { APP, ACTIVITY, SHORTCUT, URI }

@Immutable
@Serializable
data class AppShortcutItem(
    val id: String,
    val packageName: String,
    val appLabel: String = "",
    val shortLabel: String,
    val longLabel: String = "",
    val action: String? = null,
    val component: String? = null,
    val dataUri: String? = null,
    val extras: Map<String, String> = emptyMap()
)

@Immutable
@Serializable
data class SavedActivityItem(
    val id: String,
    val label: String,
    val packageName: String,
    val activityName: String = "",
    val type: TargetType = TargetType.ACTIVITY,
    val shortcutData: AppShortcutItem? = null,
    val uri: String? = null
)

@Immutable
@Serializable
data class AppCollection(
    val id: String,
    val name: String,
    val packageNames: List<String> = emptyList()
)

@Immutable
@Serializable
data class T9QuickKey(
    val digit: Int,
    val label: String,
    val targetType: TargetType = TargetType.APP,
    val packageName: String = "",
    val targetValue: String = "",
    val shortcutData: AppShortcutItem? = null,
    val userId: Int = 0
)

@Immutable
@Serializable
data class PolyphoneEntry(
    val word: String,
    val pinyin: String
)

@Immutable
@Serializable
data class BookmarkItem(
    val id: String,
    val title: String,
    val url: String
)

@Immutable
@Serializable
data class UsageStat(
    val launchCount: Int = 0,
    val lastLaunchTime: Long = 0L
)

@Immutable
@Serializable
data class AppEntry(
    val packageName: String,
    val label: String,
    val customName: String = "",
    val versionName: String = "",
    val versionCode: Long = 0L,
    val firstInstallTime: Long = 0L,
    val lastUpdateTime: Long = 0L,
    val isSystemApp: Boolean = false,
    val isFrozen: Boolean = false,
    val isHidden: Boolean = false,
    val launchCount: Int = 0,
    val lastLaunchTime: Long = 0L,
    val sourceDir: String = "",
    val dataDir: String = "",
    val launcherActivity: String = "",
    val pinyinFullList: List<String> = emptyList(),
    val pinyinInitialsList: List<String> = emptyList(),
    val t9FullList: List<String> = emptyList(),
    val t9InitialsList: List<String> = emptyList(),
    val userId: Int = 0
) {
    val displayName: String get() = customName.ifBlank { label }
    val uniqueKey: String get() = if (userId == 0) packageName else "$packageName#$userId"
    val isClone: Boolean get() = userId != 0
}

@Immutable
@Serializable
data class ContactEntry(
    val id: String,
    val name: String,
    val phone: String,
    val pinyinFullList: List<String> = emptyList(),
    val pinyinInitialsList: List<String> = emptyList(),
    val t9FullList: List<String> = emptyList(),
    val t9InitialsList: List<String> = emptyList()
)

@Immutable
@Serializable
data class ActivityEntry(
    val packageName: String,
    val className: String,
    val label: String,
    val exported: Boolean
)

@Serializable
enum class AppMenuAction(val label: String) {
    FORCE_STOP("强行停止 (Shizuku)"),
    FREEZE_TOGGLE("冻结 / 解冻 (Shizuku)"),
    LAUNCH_FREEFORM("小窗打开 (Shizuku)"),
    SHORTCUTS("快捷方式 (Shizuku)"),
    ACTIVITIES("浏览活动"),
    ADD_TO_COLLECTION("加入分类收藏"),
    BIND_T9_KEY("绑定 T9 快捷键"),
    RENAME("自定义名称"),
    HIDE_TOGGLE("隐藏 / 取消隐藏"),
    PIN_SHORTCUT("发送桌面快捷方式"),
    SHARE_APK("分享 / 提取 APK"),
    EXPORT_ICON("导出应用图标"),
    APP_DETAILS("应用详细信息"),
    OPEN_MARKET("跳转应用市场"),
    SYSTEM_INFO("系统应用信息"),
    UNINSTALL("卸载应用"),
    SILENT_UNINSTALL("静默卸载 (Shizuku)")
}

@Immutable
@Serializable
data class AppSettings(
    // Theme & Monet
    val useDynamicColor: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val seedColorIndex: Int = 0,
    val blurBehindEnabled: Boolean = true,
    val blurRadiusDp: Int = 24,
    val cardAlpha: Float = 0.96f,
    val darkCardAlpha: Float = 0.92f,
    val iconShape: IconShape = IconShape.ROUNDED,
    val iconPackPackage: String = "",
    val widgetIconStyle: WidgetIconStyle = WidgetIconStyle.MONET_SOLID,
    val widgetIconAlpha: Float = 1.0f,
    val widgetShowLabel: Boolean = true,

    // Window
    val settingsVersion: Int = 7,
    val windowMode: WindowMode = WindowMode.BOTTOM_LARGE,
    val windowVerticalPosition: Float = 1.0f,
    val customWidthPercent: Int = 92,
    val customHeightPercent: Int = 78,
    val cornerRadiusDp: Int = 28,
    val outsideDimAlpha: Float = 0.0f,
    val clickOutsideClose: Boolean = true,
    val autoCloseOnLaunch: Boolean = true,
    val excludeFromRecents: Boolean = false,

    // List / Grid
    val isListMode: Boolean = false,
    val gridColumns: Int = 4,
    val iconSizeDp: Int = 48,
    val labelSizeSp: Int = 12,
    val hideTopTabs: Boolean = false,
    val showFrozenApps: Boolean = true,
    val showSelfApp: Boolean = false,
    val showInstallDot: Boolean = true,
    val showUpdateDot: Boolean = true,
    val recentTimeRangeHours: Int = 48,
    val sortMode: SortMode = SortMode.SMART,
    val pullDownEnabled: Boolean = false,
    val pullDownRows: Int = 2,

    // Search & T9
    val defaultSearchMode: SearchMode = SearchMode.T9,
    val superFuzzySearch: Boolean = false,
    val showKeyDigits: Boolean = true,
    val showKeyLetters: Boolean = true,
    val horizontalKeyLayout: Boolean = false,
    val t9QuickLaunchEnabled: Boolean = true,
    val showT9KeyBadges: Boolean = true,
    val swipeUpStartsListApp: Boolean = false,
    val hapticEnabled: Boolean = true,
    val hapticStrength: Int = 2,
    val keySoundEnabled: Boolean = false,
    val pianoModeEnabled: Boolean = false,

    // Function Key Gestures
    val airplaneKey: KeyGestureConfig = KeyGestureConfig(
        click = GestureAction.TOGGLE_SHORTCUTS,
        longPress = GestureAction.OPEN_QUICK_MENU,
        swipeUp = GestureAction.LAUNCH_FIRST,
        swipeLeft = GestureAction.TOGGLE_SEARCH_MODE,
        swipeRight = GestureAction.QUICK_SCAN
    ),
    val contactKey: KeyGestureConfig = KeyGestureConfig(
        click = GestureAction.TOGGLE_CONTACTS,
        longPress = GestureAction.TOGGLE_SORT,
        swipeUp = GestureAction.TOGGLE_SUPER_FUZZY
    ),
    val settingKey: KeyGestureConfig = KeyGestureConfig(
        click = GestureAction.OPEN_SETTINGS,
        longPress = GestureAction.OPEN_QUICK_MENU
    ),
    val deleteKey: KeyGestureConfig = KeyGestureConfig(
        click = GestureAction.BACKSPACE,
        longPress = GestureAction.CLEAR_INPUT,
        swipeLeft = GestureAction.CLEAR_INPUT,
        swipeUp = GestureAction.CLEAR_INPUT
    ),

    // Triggers & Shizuku
    val floatingBubbleEnabled: Boolean = false,
    val floatingBubbleSnapEdge: Boolean = true,
    val floatingBubbleAutoFade: Boolean = true,
    val floatingBubbleSizeDp: Int = 52,
    val floatingBubbleBootStart: Boolean = false,
    val notificationEnabled: Boolean = false,
    val notificationBootStart: Boolean = false,
    val launcherModeEnabled: Boolean = false,
    val rootFallbackEnabled: Boolean = false,
    val showDualApps: Boolean = true,
    val unifiedSearchEnabled: Boolean = true,
    val webSearchEnabled: Boolean = true,
    val searchEngines: List<SearchEngineItem> = defaultSearchEngines(),

    // Edge trigger bar
    val edgeTriggerEnabled: Boolean = false,
    val edgeTriggerSide: EdgeTriggerSide = EdgeTriggerSide.RIGHT,
    val edgeTriggerPosition: Float = 0.55f,
    val edgeTriggerHeightDp: Int = 130,
    val edgeTriggerWidthDp: Int = 16,
    val edgeTriggerVisibleHandle: Boolean = true,

    // Double-Tap Back (Columbus / TapTap engine)
    val backTapEnabled: Boolean = false,
    val backTapSensitivity: Int = 6,
    val backTapModel: BackTapModel = BackTapModel.SAMSUNG,
    val backTapBootStart: Boolean = true,
    val backTapHapticEnabled: Boolean = true,
    val backTapPocketGate: Boolean = true,
    val backTapPauseOnLockScreen: Boolean = true,
    val backTapPauseInBatterySaver: Boolean = true,
    val backTapTableGate: Boolean = false,
    val backTapLowPowerDelegate: Boolean = false,

    // Privacy Vault
    val vaultPasswordHash: String = "",
    val vaultEntryName: String = "隐私应用",
    val vaultPasswordHint: String = "",

    // Menu customization
    val enabledMenuActions: List<AppMenuAction> = AppMenuAction.entries
)

@Serializable
data class BackupBundle(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val settings: AppSettings = AppSettings(),
    val collections: List<AppCollection> = emptyList(),
    val savedActivities: List<SavedActivityItem> = emptyList(),
    val t9QuickKeys: List<T9QuickKey> = emptyList(),
    val customNames: Map<String, String> = emptyMap(),
    val hiddenPackages: Set<String> = emptySet(),
    val polyphones: List<PolyphoneEntry> = emptyList(),
    val bookmarks: List<BookmarkItem> = emptyList(),
    val usageStats: Map<String, UsageStat> = emptyMap()
)
