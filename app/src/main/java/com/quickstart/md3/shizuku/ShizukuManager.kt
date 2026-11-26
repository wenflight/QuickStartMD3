package com.quickstart.md3.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.quickstart.md3.BuildConfig
import com.quickstart.md3.data.AppShortcutItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

enum class ShizukuStatus {
    NOT_INSTALLED,
    NOT_RUNNING,
    PERMISSION_DENIED,
    READY
}

object ShizukuManager {
    private const val REQ_CODE = 1001
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    private val _status = MutableStateFlow(ShizukuStatus.NOT_RUNNING)
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    private val _serviceBound = MutableStateFlow(false)
    val serviceBound: StateFlow<Boolean> = _serviceBound.asStateFlow()

    private var shellService: IShizukuShellService? = null
    private var pendingBind: CompletableDeferred<IShizukuShellService?>? = null
    private val bindMutex = Mutex()
    private var appContext: Context? = null
    private var initialized = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshStatus()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shellService = null
        _serviceBound.value = false
        refreshStatus()
    }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQ_CODE) {
            refreshStatus()
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                // Service will bind lazily on next command
            }
        }
    }

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, ShizukuShellService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shizuku_service")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val bound = if (service != null && service.pingBinder()) {
                IShizukuShellService.Stub.asInterface(service)
            } else {
                null
            }
            shellService = bound
            _serviceBound.value = bound != null
            pendingBind?.complete(bound)
            pendingBind = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            _serviceBound.value = false
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        if (initialized) {
            refreshStatus()
            return
        }
        initialized = true
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
            refreshStatus()
        }
    }

    fun refreshStatus() {
        val ctx = appContext
        val installed = if (ctx != null) {
            try {
                ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
                true
            } catch (_: Throwable) {
                false
            }
        } else true

        if (!installed) {
            _status.value = ShizukuStatus.NOT_INSTALLED
            return
        }

        val alive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        if (!alive) {
            _status.value = ShizukuStatus.NOT_RUNNING
            return
        }

        val granted = try {
            if (Shizuku.isPreV11()) false
            else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        _status.value = if (granted) ShizukuStatus.READY else ShizukuStatus.PERMISSION_DENIED
    }

    fun requestPermission() {
        refreshStatus()
        if (_status.value == ShizukuStatus.PERMISSION_DENIED) {
            try {
                Shizuku.requestPermission(REQ_CODE)
            } catch (_: Throwable) {
            }
        }
    }

    private suspend fun getOrBindService(): IShizukuShellService? = bindMutex.withLock {
        refreshStatus()
        if (_status.value != ShizukuStatus.READY) return null
        val existing = shellService
        if (existing != null && existing.asBinder().pingBinder()) {
            return existing
        }
        val deferred = CompletableDeferred<IShizukuShellService?>()
        pendingBind = deferred
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (t: Throwable) {
            pendingBind = null
            return null
        }
        return withTimeoutOrNull(4500L) { deferred.await() }
    }

    suspend fun execArgs(vararg args: String, allowRootFallback: Boolean = false): Result<String> =
        withContext(Dispatchers.IO) {
            val svc = getOrBindService()
            if (svc != null) {
                try {
                    val res = svc.execArgs(args)
                    val code = res.getOrNull(0)?.toIntOrNull() ?: -1
                    val out = res.getOrNull(1).orEmpty()
                    val err = res.getOrNull(2).orEmpty()
                    return@withContext if (code == 0) {
                        Result.success(out)
                    } else {
                        Result.failure(IllegalStateException(err.ifBlank { out.ifBlank { "Exit code $code" } }))
                    }
                } catch (t: Throwable) {
                    shellService = null
                    _serviceBound.value = false
                }
            }
            if (allowRootFallback) {
                return@withContext execWithRoot(args.joinToString(" ") { shellQuote(it) })
            }
            Result.failure(IllegalStateException(statusErrorMessage()))
        }

    private fun statusErrorMessage(): String = when (_status.value) {
        ShizukuStatus.NOT_INSTALLED -> "未安装 Shizuku"
        ShizukuStatus.NOT_RUNNING -> "Shizuku 服务未启动"
        ShizukuStatus.PERMISSION_DENIED -> "未授予 Shizuku 权限"
        ShizukuStatus.READY -> "Shizuku 用户服务连接失败"
    }

    private fun execWithRoot(cmd: String): Result<String> {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).start()
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            val code = p.waitFor()
            if (code == 0) Result.success(out)
            else Result.failure(IllegalStateException(err.ifBlank { "Root 命令执行失败($code)" }))
        } catch (t: Throwable) {
            Result.failure(IllegalStateException("Root 不可用: ${t.message}"))
        }
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"

    private fun isAmFailure(out: String, err: String): Boolean {
        val combined = "$out\n$err"
        return combined.contains("SecurityException", ignoreCase = true) ||
            combined.contains("Permission Denial", ignoreCase = true) ||
            combined.contains("Error type", ignoreCase = true) ||
            combined.contains("Error: Activity", ignoreCase = true) ||
            combined.contains("Activity class {", ignoreCase = true) && combined.contains("does not exist")
    }

    suspend fun execAmStart(vararg args: String, allowRootFallback: Boolean = false): Result<String> =
        withContext(Dispatchers.IO) {
            val svc = getOrBindService()
            if (svc != null) {
                try {
                    val res = svc.execArgs(args)
                    val code = res.getOrNull(0)?.toIntOrNull() ?: -1
                    val out = res.getOrNull(1).orEmpty()
                    val err = res.getOrNull(2).orEmpty()
                    return@withContext if (code == 0 && !isAmFailure(out, err)) {
                        Result.success(out)
                    } else {
                        Result.failure(IllegalStateException(err.ifBlank { out.ifBlank { "Exit code $code" } }))
                    }
                } catch (_: Throwable) {
                    shellService = null
                    _serviceBound.value = false
                }
            }
            if (allowRootFallback) {
                val rootRes = execWithRoot(args.joinToString(" ") { shellQuote(it) })
                val out = rootRes.getOrNull().orEmpty()
                if (rootRes.isSuccess && isAmFailure(out, "")) {
                    return@withContext Result.failure(IllegalStateException(out))
                }
                return@withContext rootRes
            }
            Result.failure(IllegalStateException(statusErrorMessage()))
        }

    suspend fun forceStopApp(packageName: String, rootFallback: Boolean = false, userId: Int = 0): Result<Unit> =
        if (userId == 0) {
            execArgs("am", "force-stop", packageName, allowRootFallback = rootFallback).map { }
        } else {
            execArgs("am", "force-stop", "--user", userId.toString(), packageName, allowRootFallback = rootFallback).map { }
        }

    suspend fun freezeApp(packageName: String, rootFallback: Boolean = false, userId: Int = 0): Result<Unit> =
        execArgs("pm", "disable-user", "--user", userId.toString(), packageName, allowRootFallback = rootFallback).map { }

    suspend fun unfreezeApp(packageName: String, rootFallback: Boolean = false, userId: Int = 0): Result<Unit> =
        if (userId == 0) {
            execArgs("pm", "enable", packageName, allowRootFallback = rootFallback).map { }
        } else {
            execArgs("pm", "enable", "--user", userId.toString(), packageName, allowRootFallback = rootFallback).map { }
        }

    suspend fun launchActivity(
        packageName: String,
        activityName: String,
        rootFallback: Boolean = false,
        userId: Int = 0
    ): Result<Unit> {
        val component = if (activityName.startsWith(".")) "$packageName$activityName" else activityName
        return execAmStart(
            "am", "start", "--user", userId.toString(), "-n", "$packageName/$component",
            allowRootFallback = rootFallback
        ).map { }
    }

    suspend fun launchAppForUser(
        packageName: String,
        activityName: String,
        userId: Int,
        rootFallback: Boolean = false
    ): Result<Unit> {
        return if (activityName.isNotBlank()) {
            launchActivity(packageName, activityName, rootFallback, userId)
        } else {
            execAmStart(
                "am", "start", "--user", userId.toString(),
                "-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_LAUNCHER, packageName,
                allowRootFallback = rootFallback
            ).map { }
        }
    }

    fun isColorOsDevice(): Boolean {
        val brand = Build.BRAND.orEmpty().lowercase()
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        if (brand in setOf("oppo", "oneplus", "realme") ||
            manufacturer in setOf("oppo", "oneplus", "realme")
        ) {
            return true
        }
        return try {
            Class.forName("com.oplus.zoomwindow.OplusZoomWindowManager")
            true
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun launchAppFreeform(
        packageName: String,
        activityName: String,
        userId: Int = 0,
        rootFallback: Boolean = false
    ): Result<Unit> {
        // Tier 1: Native Binder path in ShizukuShellService (handles ColorOS OplusZoomWindowManager / ATM Zoom)
        val svc = getOrBindService()
        if (svc != null) {
            val nativeRes = withContext(Dispatchers.IO) {
                runCatching { svc.startFreeform(packageName, activityName, userId) }.getOrNull()
            }
            if (nativeRes != null && nativeRes.getOrNull(0) == "0") {
                return Result.success(Unit)
            }
        }

        // Tier 2: Shell am start fallback (try ColorOS windowingMode 100 first on ColorOS devices, then standard 5)
        val modes = if (isColorOsDevice()) listOf("100", "5") else listOf("5")
        var lastResult: Result<Unit> = Result.failure(IllegalStateException(statusErrorMessage()))
        for (mode in modes) {
            val res = if (activityName.isNotBlank()) {
                val component = if (activityName.startsWith(".")) "$packageName$activityName" else activityName
                execAmStart(
                    "am", "start", "--user", userId.toString(),
                    "--windowingMode", mode, "-n", "$packageName/$component",
                    allowRootFallback = rootFallback
                ).map { }
            } else {
                execAmStart(
                    "am", "start", "--user", userId.toString(),
                    "--windowingMode", mode,
                    "-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_LAUNCHER, packageName,
                    allowRootFallback = rootFallback
                ).map { }
            }
            if (res.isSuccess) return res
            lastResult = res
        }
        return lastResult
    }

    suspend fun uninstallApp(packageName: String, userId: Int = 0, rootFallback: Boolean = false): Result<Unit> =
        execArgs("pm", "uninstall", "--user", userId.toString(), packageName, allowRootFallback = rootFallback).map { }

    suspend fun listSecondaryUsers(rootFallback: Boolean = false): List<Int> {
        val res = execArgs("pm", "list", "users", allowRootFallback = rootFallback).getOrNull() ?: return emptyList()
        val ids = ArrayList<Int>()
        val regex = Regex("""UserInfo\{(\d+):""")
        for (line in res.lineSequence()) {
            val id = regex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
            if (id > 0) ids.add(id)
        }
        return ids.distinct()
    }

    suspend fun listUserPackages(userId: Int, rootFallback: Boolean = false): Set<String> {
        val res = execArgs("pm", "list", "packages", "--user", userId.toString(), allowRootFallback = rootFallback)
            .getOrNull() ?: return emptySet()
        return res.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    suspend fun launchUri(uri: String, rootFallback: Boolean = false, userId: Int = 0): Result<Unit> =
        execAmStart(
            "am", "start", "--user", userId.toString(),
            "-a", Intent.ACTION_VIEW,
            "-d", uri,
            allowRootFallback = rootFallback
        ).map { }

    suspend fun getAppShortcuts(packageName: String, appLabel: String = packageName): Result<List<AppShortcutItem>> {
        val res = execArgs("cmd", "shortcut", "get-shortcuts", "--user", "0", packageName)
        return res.map { raw -> parseShortcutDump(raw, packageName, appLabel) }
    }

    suspend fun launchShortcut(
        context: Context,
        shortcut: AppShortcutItem,
        rootFallback: Boolean = false,
        userId: Int = 0
    ): Result<Unit> {
        // Tier 1: Native Binder ILauncherApps.startShortcut via Shizuku (com.android.shell has ACCESS_SHORTCUTS)
        val svc = getOrBindService()
        if (svc != null && shortcut.id.isNotBlank() && shortcut.packageName.isNotBlank()) {
            val resolvedId = resolveLiveShortcutId(shortcut)
            val nativeRes = withContext(Dispatchers.IO) {
                runCatching { svc.startShortcut(shortcut.packageName, resolvedId, userId) }.getOrNull()
            }
            if (nativeRes != null && nativeRes.getOrNull(0) == "0") {
                return Result.success(Unit)
            }
        }

        // Tier 2: Scheme URI fallback (e.g. Alipay KEY_SCHEME=alipays://..., UnionPay upwallet://...)
        val schemeUri = extractSchemeUri(shortcut)
        if (!schemeUri.isNullOrBlank()) {
            val uriShizuku = launchUri(schemeUri, rootFallback, userId)
            if (uriShizuku.isSuccess) return Result.success(Unit)
            val uriDirect = runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(schemeUri)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            if (uriDirect.isSuccess) return Result.success(Unit)
        }

        // Tier 3: am start with full intent & extras (preserving string types, detecting SecurityException)
        val args = mutableListOf("am", "start", "--user", userId.toString())
        if (!shortcut.action.isNullOrBlank()) {
            args += listOf("-a", shortcut.action)
        }
        if (!shortcut.dataUri.isNullOrBlank()) {
            args += listOf("-d", shortcut.dataUri)
        }
        if (!shortcut.component.isNullOrBlank()) {
            args += listOf("-n", shortcut.component)
        } else if (shortcut.action.isNullOrBlank() && shortcut.dataUri.isNullOrBlank()) {
            args += listOf("-p", shortcut.packageName)
        }
        shortcut.extras.forEach { (k, v) ->
            when {
                v == "true" || v == "false" -> args += listOf("--ez", k, v)
                else -> args += listOf("--es", k, v)
            }
        }
        val shizukuResult = execAmStart(*args.toTypedArray(), allowRootFallback = rootFallback)
        if (shizukuResult.isSuccess) return Result.success(Unit)

        // Tier 4: Direct exported Intent fallback
        return try {
            val intent = Intent().apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!shortcut.action.isNullOrBlank()) action = shortcut.action
                if (!shortcut.dataUri.isNullOrBlank()) data = Uri.parse(shortcut.dataUri)
                if (!shortcut.component.isNullOrBlank()) {
                    ComponentName.unflattenFromString(shortcut.component)?.let { component = it }
                } else {
                    `package` = shortcut.packageName
                }
                shortcut.extras.forEach { (k, v) ->
                    when (v) {
                        "true" -> putExtra(k, true)
                        "false" -> putExtra(k, false)
                        else -> putExtra(k, v)
                    }
                }
            }
            context.startActivity(intent)
            Result.success(Unit)
        } catch (_: Throwable) {
            shizukuResult.map { }
        }
    }

    private suspend fun resolveLiveShortcutId(shortcut: AppShortcutItem): String {
        if (shortcut.packageName == "com.unionpay" && (shortcut.id == "scanCode" || shortcut.id == "codepay")) {
            val destHint = if (shortcut.id == "scanCode") "/upwallet/scan" else "/up_payment/qrCode"
            val live = getAppShortcuts("com.unionpay").getOrNull().orEmpty()
            val matched = live.firstOrNull { item ->
                val extra = item.extras["shortcur_extra"].orEmpty()
                extra.contains(shortcut.id) || extra.contains(destHint)
            }
            if (matched != null) return matched.id
        }
        return shortcut.id
    }

    private fun extractSchemeUri(shortcut: AppShortcutItem): String? {
        val schemeRegex = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://.+""")
        shortcut.dataUri?.takeIf { schemeRegex.matches(it) }?.let { return it }
        val prioritizedKeys = shortcut.extras.entries.sortedByDescending { (k, _) ->
            when {
                k.contains("SCHEME", ignoreCase = true) -> 3
                k.contains("URL", ignoreCase = true) || k.contains("URI", ignoreCase = true) -> 2
                else -> 1
            }
        }
        return prioritizedKeys.firstOrNull { (_, v) -> schemeRegex.matches(v) }?.value
    }

    internal fun parseShortcutDump(
        raw: String,
        targetPkg: String,
        appLabel: String
    ): List<AppShortcutItem> {
        val results = mutableListOf<AppShortcutItem>()
        val blocks = raw.split("ShortcutInfo {")
        for (block in blocks.drop(1)) {
            val id = Regex("""id=([^,\s\}]+)""").find(block)?.groupValues?.getOrNull(1)?.trim()
                ?: continue
            val pkg = Regex("""packageName=([^,\s\}]+)""").find(block)?.groupValues?.getOrNull(1)?.trim()
                ?: targetPkg
            val shortLabel = Regex("""shortLabel=([^,\n\}]+?)(?:,\s*resId=|\n|\})""")
                .find(block)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it != "null" }.orEmpty()
            val longLabel = Regex("""longLabel=([^,\n\}]+?)(?:,\s*resId=|\n|\})""")
                .find(block)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it != "null" }.orEmpty()
            val fallbackAct = Regex("""activity=ComponentInfo\{([^\}]+)\}""")
                .find(block)?.groupValues?.getOrNull(1)?.trim()

            val intentsStart = block.indexOf("intents=[")
            val intentSection = if (intentsStart >= 0) {
                extractBalanced(block, intentsStart + "intents=".length, '[', ']')
            } else {
                ""
            }

            val intentStart = intentSection.indexOf("Intent {")
            val intentInside = if (intentStart >= 0) {
                extractBalanced(intentSection, intentStart + "Intent ".length, '{', '}')
            } else {
                ""
            }

            val action = Regex("""act=([^\s\}]+)""").find(intentInside)?.groupValues?.getOrNull(1)
            val dataUri = Regex("""dat=([^\s\}]+)""").find(intentInside)?.groupValues?.getOrNull(1)
            val cmp = Regex("""cmp=([^\s\}]+)""").find(intentInside)?.groupValues?.getOrNull(1)
                ?: fallbackAct

            val extras = linkedMapOf<String, String>()
            val pbMarker = "PersistableBundle[{"
            val pbIdx = intentSection.indexOf(pbMarker)
            if (pbIdx >= 0) {
                val bundleObj = extractBalanced(intentSection, pbIdx + "PersistableBundle[".length, '{', '}')
                val body = bundleObj.removePrefix("{").removeSuffix("}")
                for (pair in splitTopLevelEntries(body)) {
                    val eq = pair.indexOf('=')
                    if (eq > 0) {
                        val k = pair.substring(0, eq).trim()
                        val v = pair.substring(eq + 1).trim()
                        if (k.isNotEmpty()) extras[k] = v
                    }
                }
            }

            if (shortLabel.isNotEmpty() || longLabel.isNotEmpty() || action != null || cmp != null) {
                results += AppShortcutItem(
                    id = id,
                    packageName = pkg,
                    appLabel = appLabel,
                    shortLabel = shortLabel.ifBlank { longLabel.ifBlank { id } },
                    longLabel = longLabel.ifBlank { shortLabel.ifBlank { id } },
                    action = action,
                    component = cmp,
                    dataUri = dataUri,
                    extras = extras
                )
            }
        }
        return results
    }

    private fun extractBalanced(text: String, openIndex: Int, openChar: Char, closeChar: Char): String {
        if (openIndex < 0 || openIndex >= text.length || text[openIndex] != openChar) return ""
        var depth = 0
        for (i in openIndex until text.length) {
            when (text[i]) {
                openChar -> depth++
                closeChar -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(openIndex, i + 1)
                    }
                }
            }
        }
        return text.substring(openIndex)
    }

    private fun splitTopLevelEntries(body: String): List<String> {
        if (body.isBlank()) return emptyList()
        val result = ArrayList<String>()
        var braceDepth = 0
        var bracketDepth = 0
        var start = 0
        var i = 0
        while (i < body.length) {
            when (body[i]) {
                '{' -> braceDepth++
                '}' -> if (braceDepth > 0) braceDepth--
                '[' -> bracketDepth++
                ']' -> if (bracketDepth > 0) bracketDepth--
                ',' -> if (braceDepth == 0 && bracketDepth == 0) {
                    result.add(body.substring(start, i))
                    start = i + 1
                    while (start < body.length && body[start] == ' ') start++
                    i = start
                    continue
                }
            }
            i++
        }
        if (start < body.length) {
            result.add(body.substring(start))
        }
        return result
    }
}
