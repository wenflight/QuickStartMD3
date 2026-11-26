package com.quickstart.md3.shizuku

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.UserHandle
import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

@Keep
class ShizukuShellService : IShizukuShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun execArgs(args: Array<out String>?): Array<String> {
        if (args.isNullOrEmpty()) return arrayOf("-1", "", "Empty command")
        return runProcess(ProcessBuilder(args.toList()))
    }

    override fun execShell(cmd: String?): Array<String> {
        if (cmd.isNullOrBlank()) return arrayOf("-1", "", "Empty shell command")
        return runProcess(ProcessBuilder("sh", "-c", cmd))
    }

    @SuppressLint("PrivateApi")
    override fun startShortcut(packageName: String?, shortcutId: String?, userId: Int): Array<String> {
        if (packageName.isNullOrBlank() || shortcutId.isNullOrBlank()) {
            return arrayOf("-1", "", "Invalid shortcut package or id")
        }
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val binder = smClass.getMethod("getService", String::class.java)
                .invoke(null, "launcherapps") as? IBinder
                ?: return arrayOf("-1", "", "launcherapps service unavailable")
            val stubClass = Class.forName("android.content.pm.ILauncherApps\$Stub")
            val service = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                ?: return arrayOf("-1", "", "ILauncherApps proxy null")

            val callingPkg = "com.android.shell"
            val candidates = service.javaClass.methods.filter { it.name == "startShortcut" }
            val result = invokeStartShortcutMethod(service, candidates, callingPkg, packageName, shortcutId, userId)
            if (result == true || result == Unit) {
                arrayOf("0", "ok", "")
            } else {
                arrayOf("-1", "", "ILauncherApps.startShortcut returned $result")
            }
        } catch (ite: InvocationTargetException) {
            val cause = ite.cause ?: ite
            arrayOf("-1", "", "${cause.javaClass.simpleName}: ${cause.message}")
        } catch (t: Throwable) {
            arrayOf("-1", "", "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun invokeStartShortcutMethod(
        service: Any,
        methods: List<java.lang.reflect.Method>,
        callingPkg: String,
        pkg: String,
        shortcutId: String,
        userId: Int
    ): Any? {
        for (m in methods) {
            val p = m.parameterTypes
            // Android 11-17: (String callingPkg, String pkg, String featureId, String id, Rect, Bundle, int userId)
            if (p.size == 7 &&
                p[0] == String::class.java && p[1] == String::class.java &&
                p[2] == String::class.java && p[3] == String::class.java &&
                p[4] == Rect::class.java && p[5] == Bundle::class.java &&
                (p[6] == Int::class.javaPrimitiveType || p[6] == UserHandle::class.java)
            ) {
                val userArg: Any = if (p[6] == UserHandle::class.java) {
                    UserHandle.getUserHandleForUid(userId * 100000)
                } else userId
                return m.invoke(service, callingPkg, pkg, null, shortcutId, null, null, userArg)
            }
            // Android 8-10: (String callingPkg, String pkg, String id, Rect, Bundle, int userId)
            if (p.size == 6 &&
                p[0] == String::class.java && p[1] == String::class.java &&
                p[2] == String::class.java &&
                p[3] == Rect::class.java && p[4] == Bundle::class.java &&
                (p[5] == Int::class.javaPrimitiveType || p[5] == UserHandle::class.java)
            ) {
                val userArg: Any = if (p[5] == UserHandle::class.java) {
                    UserHandle.getUserHandleForUid(userId * 100000)
                } else userId
                return m.invoke(service, callingPkg, pkg, shortcutId, null, null, userArg)
            }
        }
        throw NoSuchMethodException("No compatible ILauncherApps.startShortcut overload found")
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    override fun startFreeform(packageName: String?, activityName: String?, userId: Int): Array<String> {
        if (packageName.isNullOrBlank()) {
            return arrayOf("-1", "", "Invalid packageName")
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            if (!activityName.isNullOrBlank()) {
                val fullAct = if (activityName.startsWith(".")) "$packageName$activityName" else activityName
                component = ComponentName(packageName, fullAct)
            } else {
                setPackage(packageName)
            }
        }

        val errors = mutableListOf<String>()

        // Tier 1: ColorOS / OPlus native OplusZoomWindowManager.startZoomWindow
        if (isColorOsDevice()) {
            val zoomRes = tryStartOplusZoomWindow(intent, userId)
            if (zoomRes.first) {
                return arrayOf("0", "ok:oplus_zoom", "")
            } else if (zoomRes.second.isNotBlank()) {
                errors.add("OplusZoomWindowManager: ${zoomRes.second}")
            }

            // Tier 2: ColorOS IActivityTaskManager.startActivityAsUser with ZoomLaunchSpec bundle
            val atmRes = tryStartViaAtmZoom(intent, userId)
            if (atmRes.first) {
                return arrayOf("0", "ok:atm_zoom", "")
            } else if (atmRes.second.isNotBlank()) {
                errors.add("ATMZoom: ${atmRes.second}")
            }
        }

        return arrayOf("-1", "", errors.joinToString("; ").ifBlank { "No native freeform handler" })
    }

    @SuppressLint("PrivateApi")
    private fun isColorOsDevice(): Boolean {
        val hasZoomClass = try {
            Class.forName("com.oplus.zoomwindow.OplusZoomWindowManager")
            true
        } catch (_: Throwable) {
            false
        }
        if (hasZoomClass) return true
        val brand = Build.BRAND.orEmpty().lowercase()
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        return brand in setOf("oppo", "oneplus", "realme") ||
            manufacturer in setOf("oppo", "oneplus", "realme")
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun tryStartOplusZoomWindow(intent: Intent, userId: Int): Pair<Boolean, String> {
        return try {
            val managerClass = Class.forName("com.oplus.zoomwindow.OplusZoomWindowManager")
            val instance = managerClass.getMethod("getInstance").invoke(null)
                ?: return false to "getInstance() returned null"
            val options = Bundle().apply {
                putInt("android:activity.mZoomLaunchFlags", 0)
                putInt("android.activity.launchDisplayId", 0)
                putInt("android.activity.splashScreenStyle", 1)
                putInt("extra_window_mode", 100)
            }
            val startMethod = managerClass.getMethod(
                "startZoomWindow",
                Intent::class.java,
                Bundle::class.java,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            val result = startMethod.invoke(instance, intent, options, userId, "com.android.shell") as? Int
            if (result != null && result >= 0) {
                true to ""
            } else {
                false to "returned $result"
            }
        } catch (ite: InvocationTargetException) {
            val cause = ite.cause ?: ite
            false to "${cause.javaClass.simpleName}: ${cause.message}"
        } catch (t: Throwable) {
            false to "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    @SuppressLint("PrivateApi")
    private fun tryStartViaAtmZoom(intent: Intent, userId: Int): Pair<Boolean, String> {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val binder = smClass.getMethod("getService", String::class.java)
                .invoke(null, "activity_task") as? IBinder
                ?: return false to "activity_task service unavailable"
            val stubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
            val atm = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                ?: return false to "IActivityTaskManager proxy null"

            val options = Bundle().apply {
                putInt("android:activity.mZoomLaunchFlags", 5)
                putInt("extra_window_mode", 100)
                putInt("android.activity.windowingMode", 100)
                putBoolean("androidx.activity.AdjustInputMethod", true)
                putInt("android.activity.LaunchScenario", 1)
                putInt("android.activity.launchDisplayId", 0)
                putInt("android.activity.splashScreenStyle", 1)
            }

            val methods = atm.javaClass.methods.filter { it.name == "startActivityAsUser" }
            for (m in methods) {
                val p = m.parameterTypes
                // Android 11+: (IApplicationThread, String callingPackage, String callingFeatureId, Intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode, int flags, ProfilerInfo, Bundle options, int userId)
                if (p.size == 12 &&
                    p[1] == String::class.java &&
                    p[2] == String::class.java &&
                    p[3] == Intent::class.java &&
                    p[10] == Bundle::class.java &&
                    p[11] == Int::class.javaPrimitiveType
                ) {
                    val res = m.invoke(
                        atm,
                        null, "com.android.shell", null, intent, null,
                        null, null, 0, 0, null, options, userId
                    ) as? Int
                    return if (res != null && res >= 0) true to "" else false to "returned $res"
                }
                // Android 10: 11 params without callingFeatureId
                if (p.size == 11 &&
                    p[1] == String::class.java &&
                    p[2] == Intent::class.java &&
                    p[9] == Bundle::class.java &&
                    p[10] == Int::class.javaPrimitiveType
                ) {
                    val res = m.invoke(
                        atm,
                        null, "com.android.shell", intent, null,
                        null, null, 0, 0, null, options, userId
                    ) as? Int
                    return if (res != null && res >= 0) true to "" else false to "returned $res"
                }
            }
            false to "No compatible startActivityAsUser overload"
        } catch (ite: InvocationTargetException) {
            val cause = ite.cause ?: ite
            false to "${cause.javaClass.simpleName}: ${cause.message}"
        } catch (t: Throwable) {
            false to "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun runProcess(pb: ProcessBuilder): Array<String> {
        return try {
            val process = pb.start()
            val outSb = StringBuilder()
            val errSb = StringBuilder()
            val outThread = Thread {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        outSb.appendLine(line)
                    }
                }
            }
            val errThread = Thread {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        errSb.appendLine(line)
                    }
                }
            }
            outThread.start()
            errThread.start()
            val finished = process.waitFor(12, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                outThread.join(500)
                errThread.join(500)
                return arrayOf("-1", outSb.toString(), "Command timeout")
            }
            outThread.join(1000)
            errThread.join(1000)
            arrayOf(process.exitValue().toString(), outSb.toString(), errSb.toString())
        } catch (t: Throwable) {
            arrayOf("-1", "", t.message ?: t.javaClass.simpleName)
        }
    }
}
