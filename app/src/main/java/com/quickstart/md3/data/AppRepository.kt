package com.quickstart.md3.data

import android.Manifest
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.quickstart.md3.BuildConfig
import com.quickstart.md3.shizuku.ShizukuManager
import com.quickstart.md3.shizuku.ShizukuStatus
import com.quickstart.md3.ui.main.LauncherAliasHelper
import com.quickstart.md3.ui.main.ShortcutProxyActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Stable
class AppRepository(
    private val context: Context,
    private val store: DataStoreRepository
) {
    private val appContext = context.applicationContext
    private val pm: PackageManager = appContext.packageManager
    private val iconCache = LruCache<String, ImageBitmap>(320)
    @Volatile
    private var activeIconPack: String = ""

    fun setActiveIconPack(iconPackPkg: String) {
        if (activeIconPack != iconPackPkg) {
            activeIconPack = iconPackPkg
            iconCache.evictAll()
        }
    }

    fun evictIcon(packageName: String) {
        if (packageName.isBlank()) return
        val snapshot = iconCache.snapshot().keys
        for (k in snapshot) {
            if (k == packageName || k.endsWith("|$packageName")) {
                iconCache.remove(k)
            }
        }
    }

    suspend fun loadApps(
        customNames: Map<String, String>,
        hiddenPackages: Set<String>,
        polyphones: List<PolyphoneEntry>,
        usageStats: Map<String, UsageStat>,
        showSelf: Boolean,
        showDualApps: Boolean = true
    ): List<AppEntry> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val flags = PackageManager.MATCH_ALL or PackageManager.MATCH_DISABLED_COMPONENTS
        val resolveInfos = pm.queryIntentActivities(intent, flags)
        val seen = HashSet<String>()
        val result = ArrayList<AppEntry>(resolveInfos.size)

        // Also check disabled apps via getInstalledApplications
        val launcherActivityByPkg = HashMap<String, String>()
        for (ri in resolveInfos) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (!launcherActivityByPkg.containsKey(pkg)) {
                launcherActivityByPkg[pkg] = ri.activityInfo.name
            }
        }

        val installedApps = pm.getInstalledApplications(
            PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
        )
        val mainEntryByPkg = HashMap<String, AppEntry>(installedApps.size)
        for (ai in installedApps) {
            val pkg = ai.packageName
            if (!showSelf && pkg == BuildConfig.APPLICATION_ID) continue
            if (!seen.add(pkg)) continue
            val isFrozen = if (!ai.enabled) {
                true
            } else {
                val enabledState = pm.getApplicationEnabledSetting(pkg)
                enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                    enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            val hasLauncher = launcherActivityByPkg.containsKey(pkg) || isFrozen
            if (!hasLauncher) continue

            // Filter out non-launchable system components when disabled check caught pure services
            if (!launcherActivityByPkg.containsKey(pkg) && (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && !isFrozen) {
                continue
            }
            if (!launcherActivityByPkg.containsKey(pkg) && pm.getLaunchIntentForPackage(pkg) == null && !isFrozen) {
                continue
            }

            val pkgInfo: PackageInfo? = runCatching {
                pm.getPackageInfo(pkg, 0)
            }.getOrNull()

            val rawLabel = ai.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
            val custom = customNames[pkg].orEmpty()
            val display = custom.ifBlank { rawLabel }
            val tokens = PinyinSearchEngine.buildTokens(display, polyphones)
            val stat = usageStats[pkg] ?: UsageStat()

            @Suppress("DEPRECATION")
            val verCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo?.longVersionCode ?: 0L
            } else {
                pkgInfo?.versionCode?.toLong() ?: 0L
            }

            val entry = AppEntry(
                packageName = pkg,
                label = rawLabel,
                customName = custom,
                versionName = pkgInfo?.versionName.orEmpty(),
                versionCode = verCode,
                firstInstallTime = pkgInfo?.firstInstallTime ?: 0L,
                lastUpdateTime = pkgInfo?.lastUpdateTime ?: 0L,
                isSystemApp = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                isFrozen = isFrozen,
                isHidden = pkg in hiddenPackages,
                launchCount = stat.launchCount,
                lastLaunchTime = stat.lastLaunchTime,
                sourceDir = ai.sourceDir.orEmpty(),
                dataDir = ai.dataDir.orEmpty(),
                launcherActivity = launcherActivityByPkg[pkg].orEmpty(),
                pinyinFullList = tokens.fullPinyin,
                pinyinInitialsList = tokens.initials,
                t9FullList = tokens.t9Full,
                t9InitialsList = tokens.t9Initials,
                userId = 0
            )
            result.add(entry)
            mainEntryByPkg[pkg] = entry
        }

        if (showDualApps) {
            appendDualApps(
                result = result,
                mainEntryByPkg = mainEntryByPkg,
                customNames = customNames,
                hiddenPackages = hiddenPackages,
                polyphones = polyphones,
                usageStats = usageStats
            )
        }
        result
    }

    private suspend fun appendDualApps(
        result: MutableList<AppEntry>,
        mainEntryByPkg: Map<String, AppEntry>,
        customNames: Map<String, String>,
        hiddenPackages: Set<String>,
        polyphones: List<PolyphoneEntry>,
        usageStats: Map<String, UsageStat>
    ) {
        val addedKeys = HashSet<String>()
        val coveredUsers = HashSet<Int>()
        coveredUsers.add(0)

        val um = appContext.getSystemService(UserManager::class.java)
        val la = appContext.getSystemService(LauncherApps::class.java)
        val myHandle = Process.myUserHandle()

        if (um != null && la != null) {
            for (handle in um.userProfiles) {
                if (handle == myHandle) continue
                val uid = parseUserId(handle)
                if (uid <= 0) continue
                coveredUsers.add(uid)
                val activities = runCatching { la.getActivityList(null, handle) }.getOrDefault(emptyList())
                for (act in activities) {
                    val pkg = act.applicationInfo.packageName
                    val uniqueKey = "$pkg#$uid"
                    if (!addedKeys.add(uniqueKey)) continue
                    val rawLabel = act.label?.toString()?.trim().orEmpty()
                        .ifBlank { mainEntryByPkg[pkg]?.label ?: pkg }
                    val custom = customNames[uniqueKey].orEmpty()
                    val baseDisplay = custom.ifBlank { "$rawLabel(分身)" }
                    val tokens = PinyinSearchEngine.buildTokens(baseDisplay, polyphones)
                    val stat = usageStats[uniqueKey] ?: UsageStat()
                    val main = mainEntryByPkg[pkg]
                    result.add(
                        AppEntry(
                            packageName = pkg,
                            label = "$rawLabel(分身)",
                            customName = custom,
                            versionName = main?.versionName.orEmpty(),
                            versionCode = main?.versionCode ?: 0L,
                            firstInstallTime = main?.firstInstallTime ?: 0L,
                            lastUpdateTime = main?.lastUpdateTime ?: 0L,
                            isSystemApp = main?.isSystemApp ?: false,
                            isFrozen = false,
                            isHidden = uniqueKey in hiddenPackages,
                            launchCount = stat.launchCount,
                            lastLaunchTime = stat.lastLaunchTime,
                            sourceDir = main?.sourceDir.orEmpty(),
                            dataDir = main?.dataDir.orEmpty(),
                            launcherActivity = act.componentName.className,
                            pinyinFullList = tokens.fullPinyin,
                            pinyinInitialsList = tokens.initials,
                            t9FullList = tokens.t9Full,
                            t9InitialsList = tokens.t9Initials,
                            userId = uid
                        )
                    )
                }
            }
        }

        // ColorOS / OEM dual-app users (such as user 999) may not appear in UserManager.userProfiles;
        // query via Shizuku only when Shizuku is already READY.
        if (ShizukuManager.status.value == ShizukuStatus.READY) {
            val secondaryUsers = ShizukuManager.listSecondaryUsers()
            for (uid in secondaryUsers) {
                if (uid in coveredUsers && addedKeys.any { it.endsWith("#$uid") }) continue
                val userPkgs = ShizukuManager.listUserPackages(uid)
                for (pkg in userPkgs) {
                    val main = mainEntryByPkg[pkg] ?: continue
                    if (main.isSystemApp || main.launcherActivity.isBlank()) continue
                    val uniqueKey = "$pkg#$uid"
                    if (!addedKeys.add(uniqueKey)) continue
                    val rawLabel = "${main.label}(分身)"
                    val custom = customNames[uniqueKey].orEmpty()
                    val display = custom.ifBlank { rawLabel }
                    val tokens = PinyinSearchEngine.buildTokens(display, polyphones)
                    val stat = usageStats[uniqueKey] ?: UsageStat()
                    result.add(
                        main.copy(
                            label = rawLabel,
                            customName = custom,
                            isFrozen = false,
                            isHidden = uniqueKey in hiddenPackages,
                            launchCount = stat.launchCount,
                            lastLaunchTime = stat.lastLaunchTime,
                            pinyinFullList = tokens.fullPinyin,
                            pinyinInitialsList = tokens.initials,
                            t9FullList = tokens.t9Full,
                            t9InitialsList = tokens.t9Initials,
                            userId = uid
                        )
                    )
                }
            }
        }
    }

    private fun parseUserId(handle: UserHandle): Int {
        val text = handle.toString()
        val match = Regex("""\d+""").find(text)?.value?.toIntOrNull()
        return match ?: handle.hashCode()
    }

    fun getCachedIcon(packageName: String): ImageBitmap? {
        if (packageName.isBlank()) return null
        return iconCache.get("$activeIconPack|$packageName")
    }

    suspend fun getAppIconSuspend(packageName: String, launcherActivity: String = ""): ImageBitmap? {
        getCachedIcon(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            getAppIcon(packageName, launcherActivity)
        }
    }

    suspend fun prewarmIcons(apps: List<AppEntry>) = withContext(Dispatchers.IO) {
        coroutineScope {
            apps.map { entry ->
                async {
                    if (getCachedIcon(entry.packageName) == null) {
                        getAppIcon(entry.packageName, entry.launcherActivity)
                    }
                }
            }.awaitAll()
        }
    }

    fun getAppIcon(packageName: String, launcherActivity: String = ""): ImageBitmap? {
        val cacheKey = "$activeIconPack|$packageName"
        iconCache.get(cacheKey)?.let { return it }
        return try {
            val packDrawable = if (activeIconPack.isNotBlank()) {
                IconPackManager.loadIconDrawable(appContext, activeIconPack, packageName, launcherActivity)
            } else {
                null
            }
            val drawable = packDrawable ?: pm.getApplicationIcon(packageName)
            val bmp = drawableToBitmap(drawable, 128)
            val img = bmp.asImageBitmap()
            iconCache.put(cacheKey, img)
            img
        } catch (_: Throwable) {
            null
        }
    }

    fun clearIconCache() {
        iconCache.evictAll()
        IconPackManager.clearCache()
    }

    suspend fun launchApp(app: AppEntry, rootFallback: Boolean): Result<Unit> {
        if (app.userId != 0) {
            val um = appContext.getSystemService(UserManager::class.java)
            val la = appContext.getSystemService(LauncherApps::class.java)
            val handle = um?.userProfiles?.firstOrNull { parseUserId(it) == app.userId }
            if (handle != null && la != null && app.launcherActivity.isNotBlank()) {
                val direct = runCatching {
                    la.startMainActivity(
                        ComponentName(app.packageName, app.launcherActivity),
                        handle,
                        null,
                        null
                    )
                    store.recordAppLaunch(app.uniqueKey)
                }
                if (direct.isSuccess) return direct
            }
            val shizukuRes = ShizukuManager.launchAppForUser(
                app.packageName,
                app.launcherActivity,
                app.userId,
                rootFallback
            )
            if (shizukuRes.isSuccess) store.recordAppLaunch(app.uniqueKey)
            return shizukuRes
        }

        if (app.isFrozen) {
            val unfreeze = ShizukuManager.unfreezeApp(app.packageName, rootFallback, app.userId)
            if (unfreeze.isFailure) return unfreeze
        }
        val intent = pm.getLaunchIntentForPackage(app.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            return runCatching {
                appContext.startActivity(intent)
                store.recordAppLaunch(app.uniqueKey)
            }.recoverCatching {
                if (app.launcherActivity.isNotBlank()) {
                    ShizukuManager.launchActivity(app.packageName, app.launcherActivity, rootFallback, app.userId).getOrThrow()
                    store.recordAppLaunch(app.uniqueKey)
                } else {
                    throw it
                }
            }
        } else if (app.launcherActivity.isNotBlank()) {
            val res = ShizukuManager.launchActivity(app.packageName, app.launcherActivity, rootFallback, app.userId)
            if (res.isSuccess) store.recordAppLaunch(app.uniqueKey)
            return res
        }
        return Result.failure(IllegalStateException("无法启动 ${app.displayName}"))
    }

    suspend fun launchAppFreeform(app: AppEntry, rootFallback: Boolean): Result<Unit> {
        if (app.isFrozen) {
            val unfreeze = ShizukuManager.unfreezeApp(app.packageName, rootFallback, app.userId)
            if (unfreeze.isFailure) return unfreeze
        }
        val resolvedActivity = app.launcherActivity.ifBlank {
            pm.getLaunchIntentForPackage(app.packageName)?.component?.className.orEmpty()
        }
        val res = ShizukuManager.launchAppFreeform(
            packageName = app.packageName,
            activityName = resolvedActivity,
            userId = app.userId,
            rootFallback = rootFallback
        )
        if (res.isSuccess) {
            store.recordAppLaunch(app.uniqueKey)
            return res
        }
        // Fallback for ColorOS when Shizuku is unavailable on primary user
        if (app.userId == 0 && ShizukuManager.isColorOsDevice()) {
            val directRes = runCatching {
                val intent = pm.getLaunchIntentForPackage(app.packageName)
                    ?: error("无法获取启动入口")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                val opts = Bundle().apply {
                    putInt("android:activity.mZoomLaunchFlags", 5)
                    putInt("extra_window_mode", 100)
                    putInt("android.activity.windowingMode", 100)
                    putBoolean("androidx.activity.AdjustInputMethod", true)
                    putInt("android.activity.LaunchScenario", 1)
                    putInt("android.activity.launchDisplayId", 0)
                    putInt("android.activity.splashScreenStyle", 1)
                }
                appContext.startActivity(intent, opts)
                store.recordAppLaunch(app.uniqueKey)
            }
            if (directRes.isSuccess) return directRes
        }
        return res
    }

    fun openWebSearch(urlTemplate: String, query: String): Result<String?> {
        val trimmed = query.trim()
        if (urlTemplate.contains("gemini.google.com", ignoreCase = true)) {
            runCatching {
                val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("GeminiPrompt", trimmed))
            }
            val bardPkg = "com.google.android.apps.bard"
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, trimmed)
                setPackage(bardPkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val opened = if (pm.resolveActivity(sendIntent, 0) != null) {
                runCatching { appContext.startActivity(sendIntent) }.isSuccess
            } else {
                false
            }
            if (opened) {
                return Result.success("已分享到 Gemini（如未自动填入请直接粘贴）")
            }
            val launchIntent = pm.getLaunchIntentForPackage(bardPkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { appContext.startActivity(launchIntent) }.isSuccess) {
                    return Result.success("已复制提问内容，在 Gemini 中粘贴即可")
                }
            }
            return openBookmark("https://gemini.google.com/app").map {
                "已复制提问内容，在 Gemini 网页中粘贴即可"
            }
        }

        val encoded = Uri.encode(trimmed)
        val finalUrl = if (urlTemplate.contains("%s")) {
            urlTemplate.replace("%s", encoded)
        } else {
            urlTemplate + encoded
        }
        return openBookmark(finalUrl).map { null }
    }

    suspend fun loadActivities(packageName: String): List<ActivityEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val info = pm.getPackageInfo(
                packageName,
                PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS
            )
            info.activities?.map { act ->
                val label = runCatching { act.loadLabel(pm).toString() }.getOrDefault("")
                ActivityEntry(
                    packageName = packageName,
                    className = act.name,
                    label = label.ifBlank { act.name.substringAfterLast('.') },
                    exported = act.exported
                )
            }?.sortedByDescending { it.exported } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    suspend fun shareApk(app: AppEntry): Result<Intent> = withContext(Dispatchers.IO) {
        runCatching {
            val src = File(app.sourceDir)
            require(src.exists()) { "找不到 APK 文件" }
            val cacheDir = File(appContext.cacheDir, "apk_share").apply { mkdirs() }
            val safeName = app.displayName.replace(Regex("""[\\/:*?"<>|]"""), "_")
            val dest = File(cacheDir, "${safeName}_${app.versionName.ifBlank { "base" }}.apk")
            src.copyTo(dest, overwrite = true)
            val uri = FileProvider.getUriForFile(appContext, "${BuildConfig.APPLICATION_ID}.fileprovider", dest)
            Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun clearApkShareCache(): Long {
        val dir = File(appContext.cacheDir, "apk_share")
        if (!dir.exists()) return 0L
        var freed = 0L
        dir.listFiles()?.forEach {
            freed += it.length()
            it.delete()
        }
        return freed
    }

    suspend fun exportAppIcon(app: AppEntry): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val drawable = pm.getApplicationIcon(app.packageName)
            val bmp = drawableToBitmap(drawable, 512)
            val fileName = "${app.packageName}_icon.png"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/QuickStartMD3")
                }
                val uri = appContext.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("无法创建图片文件")
                appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                } ?: error("无法写入图片")
                "Pictures/QuickStartMD3/$fileName"
            } else {
                val dir = File(appContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Icons").apply { mkdirs() }
                val file = File(dir, fileName)
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                file.absolutePath
            }
        }
    }

    fun pinDesktopShortcut(
        id: String,
        label: String,
        packageName: String,
        targetType: TargetType,
        activityName: String = "",
        shortcut: AppShortcutItem? = null,
        uri: String? = null
    ): Result<Unit> = runCatching {
        val sm = appContext.getSystemService(ShortcutManager::class.java)
            ?: error("系统不支持固定快捷方式")
        if (!sm.isRequestPinShortcutSupported) {
            error("当前桌面不支持添加快捷方式")
        }
        val launchIntent = ShortcutProxyActivity.createLaunchIntent(
            context = appContext,
            targetType = targetType,
            packageName = packageName,
            activityName = activityName,
            shortcut = shortcut,
            uri = uri
        )
        val icon = runCatching {
            val drawable = pm.getApplicationIcon(packageName)
            Icon.createWithBitmap(drawableToBitmap(drawable, 192))
        }.getOrElse {
            Icon.createWithResource(appContext, com.quickstart.md3.R.drawable.ic_tile_qs)
        }
        val info = ShortcutInfo.Builder(appContext, id)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(icon)
            .setIntent(launchIntent)
            .build()
        val cbIntent = sm.createShortcutResultIntent(info)
        val pi = PendingIntent.getBroadcast(
            appContext, id.hashCode(), cbIntent, PendingIntent.FLAG_IMMUTABLE
        )
        sm.requestPinShortcut(info, pi.intentSender)
    }

    fun openSystemAppInfo(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }

    fun openAppMarket(packageName: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { appContext.startActivity(intent) }
    }

    fun requestUninstall(packageName: String) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }

    fun openBookmark(url: String): Result<Unit> = runCatching {
        val fixed = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fixed)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }

    fun setLauncherModeEnabled(enabled: Boolean) {
        LauncherAliasHelper.setEnabled(appContext, enabled)
    }

    suspend fun loadContacts(polyphones: List<PolyphoneEntry>): List<ContactEntry> = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext emptyList()
        }
        val list = mutableListOf<ContactEntry>()
        val seen = HashSet<String>()
        val cursor = appContext.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )
        cursor?.use { c ->
            val idIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val id = if (idIdx >= 0) c.getString(idIdx).orEmpty() else ""
                val name = if (nameIdx >= 0) c.getString(nameIdx).orEmpty().trim() else ""
                val phone = if (numIdx >= 0) c.getString(numIdx).orEmpty().trim() else ""
                if (name.isBlank() || phone.isBlank()) continue
                val key = "$name|$phone"
                if (!seen.add(key)) continue
                val tokens = PinyinSearchEngine.buildTokens(name, polyphones)
                list.add(
                    ContactEntry(
                        id = "${id}_${phone}",
                        name = name,
                        phone = phone,
                        pinyinFullList = tokens.fullPinyin,
                        pinyinInitialsList = tokens.initials,
                        t9FullList = tokens.t9Full,
                        t9InitialsList = tokens.t9Initials
                    )
                )
            }
        }
        list
    }

    fun callContact(phone: String) {
        val hasCall = ContextCompat.checkSelfPermission(appContext, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val action = if (hasCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:${Uri.encode(phone)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }

    fun smsContact(phone: String) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(phone)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }

    private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val bmp = drawable.bitmap
            if (bmp.width <= sizePx && bmp.height <= sizePx) {
                return bmp
            }
            val scale = minOf(sizePx.toFloat() / bmp.width, sizePx.toFloat() / bmp.height)
            val dstW = (bmp.width * scale).toInt().coerceAtLeast(1)
            val dstH = (bmp.height * scale).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(bmp, dstW, dstH, true)
        }
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: sizePx
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: sizePx
        val scaledW = minOf(w, sizePx)
        val scaledH = minOf(h, sizePx)
        val bitmap = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    companion object {
        fun builtInQuickPresets(): List<SavedActivityItem> = listOf(
            SavedActivityItem(
                id = "preset_wx_scan",
                label = "微信扫一扫",
                packageName = "com.tencent.mm",
                type = TargetType.SHORTCUT,
                shortcutData = AppShortcutItem(
                    id = "launch_type_scan_qrcode",
                    packageName = "com.tencent.mm",
                    appLabel = "微信",
                    shortLabel = "扫一扫",
                    action = "com.tencent.mm.ui.ShortCutDispatchAction",
                    component = "com.tencent.mm/com.tencent.mm.ui.LauncherUI",
                    extras = mapOf("LauncherUI.Shortcut.LaunchType" to "launch_type_scan_qrcode")
                )
            ),
            SavedActivityItem(
                id = "preset_wx_pay",
                label = "微信收付款",
                packageName = "com.tencent.mm",
                type = TargetType.SHORTCUT,
                shortcutData = AppShortcutItem(
                    id = "launch_type_offline_wallet",
                    packageName = "com.tencent.mm",
                    appLabel = "微信",
                    shortLabel = "收付款",
                    action = "com.tencent.mm.ui.ShortCutDispatchAction",
                    component = "com.tencent.mm/com.tencent.mm.ui.LauncherUI",
                    extras = mapOf("LauncherUI.Shortcut.LaunchType" to "launch_type_offline_wallet")
                )
            ),
            SavedActivityItem(
                id = "preset_zfb_scan",
                label = "支付宝扫一扫",
                packageName = "com.eg.android.AlipayGphone",
                type = TargetType.URI,
                uri = "alipays://platformapi/startapp?appId=10000007"
            ),
            SavedActivityItem(
                id = "preset_zfb_pay",
                label = "支付宝收付款",
                packageName = "com.eg.android.AlipayGphone",
                type = TargetType.URI,
                uri = "alipays://platformapi/startapp?appId=20000056"
            ),
            SavedActivityItem(
                id = "preset_ysf_scan",
                label = "云闪付扫一扫",
                packageName = "com.unionpay",
                type = TargetType.SHORTCUT,
                shortcutData = AppShortcutItem(
                    id = "scanCode",
                    packageName = "com.unionpay",
                    appLabel = "云闪付",
                    shortLabel = "扫一扫",
                    action = "android.intent.action.VIEW",
                    dataUri = "upwallet://native/scanCode",
                    component = "com.unionpay/.activity.UPActivity3DTouch",
                    extras = mapOf(
                        "shortcur_extra" to """{"chName":"扫一扫","dest":"/upwallet/scan","multilingual":"touch_3d_scan","select":"0","shortcutId":"scanCode","type":"native"}"""
                    )
                )
            ),
            SavedActivityItem(
                id = "preset_ysf_pay",
                label = "云闪付付款码",
                packageName = "com.unionpay",
                type = TargetType.SHORTCUT,
                shortcutData = AppShortcutItem(
                    id = "codepay",
                    packageName = "com.unionpay",
                    appLabel = "云闪付",
                    shortLabel = "付款码",
                    action = "android.intent.action.VIEW",
                    dataUri = "upwallet://pay",
                    component = "com.unionpay/.activity.UPActivity3DTouch",
                    extras = mapOf(
                        "shortcur_extra" to """{"chName":"收付款","dest":"/up_payment/qrCode","multilingual":"touch_3d_code_pay","select":"0","shortcutId":"codepay","type":"native"}"""
                    )
                )
            )
        )
    }
}
