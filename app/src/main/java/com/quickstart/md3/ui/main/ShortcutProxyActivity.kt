package com.quickstart.md3.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.quickstart.md3.data.AppShortcutItem
import com.quickstart.md3.data.TargetType
import com.quickstart.md3.shizuku.ShizukuManager
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ShortcutProxyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuManager.init(this)

        val typeName = intent.getStringExtra(EXTRA_TYPE) ?: TargetType.APP.name
        val type = runCatching { TargetType.valueOf(typeName) }.getOrDefault(TargetType.APP)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val act = intent.getStringExtra(EXTRA_ACTIVITY).orEmpty()
        val uri = intent.getStringExtra(EXTRA_URI).orEmpty()
        val scJson = intent.getStringExtra(EXTRA_SHORTCUT_JSON)

        lifecycleScope.launch {
            val res = when (type) {
                TargetType.APP -> {
                    val launch = packageManager.getLaunchIntentForPackage(pkg)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { startActivity(launch) }
                    } else {
                        ShizukuManager.unfreezeApp(pkg).mapCatching {
                            val retry = packageManager.getLaunchIntentForPackage(pkg)
                                ?: error("无法启动 $pkg")
                            retry.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(retry)
                        }
                    }
                }
                TargetType.ACTIVITY -> ShizukuManager.launchActivity(pkg, act)
                TargetType.SHORTCUT -> {
                    val sc = scJson?.let { runCatching { Json.decodeFromString<AppShortcutItem>(it) }.getOrNull() }
                    if (sc != null) ShizukuManager.launchShortcut(this@ShortcutProxyActivity, sc)
                    else Result.failure(IllegalStateException("快捷方式数据无效"))
                }
                TargetType.URI -> {
                    val sRes = ShizukuManager.launchUri(uri)
                    if (sRes.isFailure) {
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        }
                    } else sRes
                }
            }
            res.onFailure {
                Toast.makeText(this@ShortcutProxyActivity, it.message ?: "启动失败", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    companion object {
        private const val EXTRA_TYPE = "qs_type"
        private const val EXTRA_PACKAGE = "qs_pkg"
        private const val EXTRA_ACTIVITY = "qs_act"
        private const val EXTRA_URI = "qs_uri"
        private const val EXTRA_SHORTCUT_JSON = "qs_sc_json"

        fun createLaunchIntent(
            context: Context,
            targetType: TargetType,
            packageName: String,
            activityName: String = "",
            shortcut: AppShortcutItem? = null,
            uri: String? = null
        ): Intent = Intent(context, ShortcutProxyActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_TYPE, targetType.name)
            putExtra(EXTRA_PACKAGE, packageName)
            putExtra(EXTRA_ACTIVITY, activityName)
            putExtra(EXTRA_URI, uri.orEmpty())
            if (shortcut != null) {
                putExtra(EXTRA_SHORTCUT_JSON, Json.encodeToString(shortcut))
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }
}
