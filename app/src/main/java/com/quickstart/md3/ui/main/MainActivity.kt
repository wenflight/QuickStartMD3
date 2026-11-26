package com.quickstart.md3.ui.main

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickstart.md3.QuickStartApp
import com.quickstart.md3.data.SearchMode
import com.quickstart.md3.data.WindowMode
import com.quickstart.md3.service.FloatingBubbleService
import com.quickstart.md3.ui.theme.QuickStartMD3Theme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var isMultiWindowState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requestedMode = parseSearchMode(intent)
        val requestedTab = parseInitialTab(intent)
        val initialSettings = (application as QuickStartApp).store.loadInitialSettingsSync()
        if (!isInMultiWindowMode && !initialSettings.launcherModeEnabled && Settings.canDrawOverlays(this)) {
            (application as QuickStartApp).overlayHost.show(requestedMode, requestedTab)
            finish()
            disableTransitions()
            return
        }

        isMultiWindowState = isInMultiWindowMode
        applyWindowChrome()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { setTranslucent(!isMultiWindowState) }
        }
        if (requestedMode != null || requestedTab != null) {
            viewModel.resetForReopen(requestedMode, requestedTab)
        }

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(state.settings.excludeFromRecents) {
                applyExcludeFromRecents(state.settings.excludeFromRecents)
            }

            LaunchedEffect(Unit) {
                viewModel.closeWindowEvent.collect {
                    val s = viewModel.uiState.value.settings
                    if (!s.launcherModeEnabled &&
                        (isMultiWindowState || s.windowMode != WindowMode.FULLSCREEN)
                    ) {
                        hideWindow()
                    }
                }
            }

            QuickStartMD3Theme(settings = state.settings) {
                MainScreen(
                    vm = viewModel,
                    state = state,
                    isInMultiWindowMode = isMultiWindowState,
                    onCloseWindow = {
                        if (!viewModel.uiState.value.settings.launcherModeEnabled) hideWindow()
                    }
                )
            }
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        isMultiWindowState = isInMultiWindowMode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { setTranslucent(!isInMultiWindowMode) }
        }
        applyWindowChrome()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requestedMode = parseSearchMode(intent)
        val requestedTab = parseInitialTab(intent)
        if (!isInMultiWindowMode && !viewModel.uiState.value.settings.launcherModeEnabled && Settings.canDrawOverlays(this)) {
            (application as QuickStartApp).overlayHost.show(requestedMode, requestedTab)
            finish()
            disableTransitions()
            return
        }
        isMultiWindowState = isInMultiWindowMode
        applyWindowChrome()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { setTranslucent(!isMultiWindowState) }
        }
        viewModel.resetForReopen(requestedMode, requestedTab)
    }

    private fun parseSearchMode(intent: Intent?): SearchMode? {
        val raw = intent?.getStringExtra(EXTRA_SEARCH_MODE) ?: return null
        return runCatching { SearchMode.valueOf(raw) }.getOrNull()
    }

    private fun parseInitialTab(intent: Intent?): MainTab? {
        val raw = intent?.getStringExtra(EXTRA_INITIAL_TAB) ?: return null
        return runCatching { MainTab.valueOf(raw) }.getOrNull()
    }

    companion object {
        const val EXTRA_SEARCH_MODE = "com.quickstart.md3.EXTRA_SEARCH_MODE"
        const val EXTRA_INITIAL_TAB = "com.quickstart.md3.EXTRA_INITIAL_TAB"
    }

    private fun disableTransitions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun hideWindow() {
        viewModel.resetForReopen()
        if (isMultiWindowState) {
            finish()
        } else {
            moveTaskToBack(true)
        }
    }

    private fun applyWindowChrome() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        window.decorView.setBackgroundColor(AndroidColor.TRANSPARENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
    }

    private fun applyExcludeFromRecents(exclude: Boolean) {
        runCatching {
            val am = getSystemService(ActivityManager::class.java)
            am?.appTasks?.forEach { task ->
                task.setExcludeFromRecents(exclude)
            }
        }
    }
}

object LauncherAliasHelper {
    fun setEnabled(context: Context, enabled: Boolean) {
        val cn = ComponentName(context, "com.quickstart.md3.ui.main.LauncherAlias")
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        context.packageManager.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP)
    }
}
