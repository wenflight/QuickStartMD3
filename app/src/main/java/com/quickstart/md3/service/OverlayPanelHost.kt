package com.quickstart.md3.service

import android.app.Application
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.quickstart.md3.BuildConfig
import com.quickstart.md3.QuickStartApp
import com.quickstart.md3.R
import com.quickstart.md3.data.AppSettings
import com.quickstart.md3.data.SearchMode
import com.quickstart.md3.ui.main.LocalUseInWindowDialogs
import com.quickstart.md3.ui.main.MainScreen
import com.quickstart.md3.ui.main.MainTab
import com.quickstart.md3.ui.main.MainViewModel
import com.quickstart.md3.ui.theme.QuickStartMD3Theme

/**
 * Hosts MainScreen directly in a WindowManager TYPE_APPLICATION_OVERLAY window owned by the
 * Application so the launcher panel floats edge-to-edge above any screen with zero Activity
 * launch flash and no foreground-service notification flicker.
 */
class OverlayPanelHost(
    private val app: Application,
    private val onHidden: () -> Unit = {}
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = app.getSystemService(WindowManager::class.java)
    private val sharedViewModelStore = ViewModelStore()
    private val viewModel: MainViewModel by lazy(LazyThreadSafetyMode.NONE) {
        MainViewModel(app)
    }

    private var activeSession: OverlayWindowSession? = null

    val isShowing: Boolean
        get() = activeSession != null

    fun show(initialSearchMode: SearchMode? = null, initialTab: MainTab? = null) {
        runOnMain {
            if (BuildConfig.DEBUG) Log.d(DIAG_TAG, "OverlayPanelHost.show isShowing=${activeSession != null}")
            if (activeSession != null) {
                viewModel.resetForReopen(initialSearchMode, initialTab)
                return@runOnMain
            }
            val wm = windowManager ?: return@runOnMain
            viewModel.resetForReopen(initialSearchMode, initialTab)
            val session = OverlayWindowSession(
                context = app,
                mainHandler = mainHandler,
                viewModel = viewModel,
                sharedStore = sharedViewModelStore,
                onRequestHide = { hide() }
            )
            val attached = session.attach(wm)
            if (BuildConfig.DEBUG) Log.d(DIAG_TAG, "OverlayPanelHost.attach=$attached")
            if (attached) {
                activeSession = session
            } else {
                session.detach(wm)
            }
        }
    }

    fun hide() {
        runOnMain {
            if (BuildConfig.DEBUG) Log.d(DIAG_TAG, "OverlayPanelHost.hide isShowing=${activeSession != null}")
            val session = activeSession ?: return@runOnMain
            activeSession = null
            val wm = windowManager
            session.startHideAnimation {
                if (wm != null) session.detach(wm)
                viewModel.resetForReopen()
                onHidden()
            }
        }
    }

    fun toggle() {
        runOnMain {
            if (BuildConfig.DEBUG) Log.d(DIAG_TAG, "OverlayPanelHost.toggle isShowing=$isShowing")
            if (isShowing) hide() else show()
        }
    }

    companion object {
        private const val DIAG_TAG = "BackTapDiag"
    }

    fun destroy() {
        runOnMain {
            activeSession?.let { session ->
                activeSession = null
                windowManager?.let { session.detach(it) }
            }
            sharedViewModelStore.clear()
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private class OverlayWindowSession(
        context: Context,
        private val mainHandler: Handler,
        private val viewModel: MainViewModel,
        private val sharedStore: ViewModelStore,
        private val onRequestHide: () -> Unit
    ) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner, OnBackPressedDispatcherOwner {

        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        private val backDispatcher = OnBackPressedDispatcher { onRequestHide() }

        private val themedContext = ContextThemeWrapper(context, R.style.Theme_QuickStartMD3)
        private val rootContainer: FrameLayout
        private var isVisible by mutableStateOf(false)
        private var isDetaching = false
        private var windowParams: WindowManager.LayoutParams? = null
        private var attachedWm: WindowManager? = null

        override val lifecycle: Lifecycle
            get() = lifecycleRegistry

        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateController.savedStateRegistry

        override val viewModelStore: ViewModelStore
            get() = sharedStore

        override val onBackPressedDispatcher: OnBackPressedDispatcher
            get() = backDispatcher

        init {
            savedStateController.performAttach()
            savedStateController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED

            rootContainer = object : FrameLayout(themedContext) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            return true
                        }
                        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                            backDispatcher.onBackPressed()
                            return true
                        }
                    }
                    return super.dispatchKeyEvent(event)
                }
            }.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                setViewTreeLifecycleOwner(this@OverlayWindowSession)
                setViewTreeSavedStateRegistryOwner(this@OverlayWindowSession)
                setViewTreeViewModelStoreOwner(this@OverlayWindowSession)
                setViewTreeOnBackPressedDispatcherOwner(this@OverlayWindowSession)
            }

            val composeView = ComposeView(themedContext).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    val state by viewModel.uiState.collectAsStateWithLifecycle()

                    LaunchedEffect(Unit) {
                        isVisible = true
                    }

                    LaunchedEffect(state.settings.blurBehindEnabled, state.settings.blurRadiusDp) {
                        val lp = windowParams
                        val wm = attachedWm
                        if (lp != null && wm != null && !isDetaching) {
                            applyBlur(lp, state.settings)
                            runCatching { wm.updateViewLayout(rootContainer, lp) }
                        }
                    }

                    LaunchedEffect(Unit) {
                        viewModel.closeWindowEvent.collect {
                            onRequestHide()
                        }
                    }

                    val alpha by animateFloatAsState(
                        targetValue = if (isVisible) 1f else 0f,
                        animationSpec = tween(durationMillis = if (isVisible) 100 else 100),
                        label = "overlay_fade"
                    )
                    val scale by animateFloatAsState(
                        targetValue = if (isVisible) 1f else 0.95f,
                        animationSpec = if (isVisible) {
                            spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
                        } else {
                            tween(durationMillis = 100)
                        },
                        label = "overlay_scale"
                    )
                    val shiftY by animateFloatAsState(
                        targetValue = if (isVisible) 0f else 24f,
                        animationSpec = if (isVisible) {
                            spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
                        } else {
                            tween(durationMillis = 100)
                        },
                        label = "overlay_shift_y"
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                this.alpha = alpha
                                this.scaleX = scale
                                this.scaleY = scale
                                this.translationY = shiftY
                            }
                    ) {
                        CompositionLocalProvider(LocalUseInWindowDialogs provides true) {
                            QuickStartMD3Theme(settings = state.settings) {
                                MainScreen(
                                    vm = viewModel,
                                    state = state,
                                    isInMultiWindowMode = false,
                                    onCloseWindow = onRequestHide
                                )
                            }
                        }
                    }
                }
            }

            rootContainer.addView(
                composeView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        private fun applyBlur(lp: WindowManager.LayoutParams, settings: AppSettings) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    val density = themedContext.resources.displayMetrics.density
                    if (settings.blurBehindEnabled && settings.blurRadiusDp > 0) {
                        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                        lp.blurBehindRadius = (settings.blurRadiusDp * density).toInt().coerceIn(0, 120)
                    } else {
                        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                        lp.blurBehindRadius = 0
                    }
                }
            }
        }

        fun attach(wm: WindowManager): Boolean {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR

            val initSettings = (themedContext.applicationContext as QuickStartApp).store.loadInitialSettingsSync()
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.FILL
                @Suppress("DEPRECATION")
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    fitInsetsTypes = 0
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            applyBlur(lp, initSettings)
            windowParams = lp
            attachedWm = wm
            return runCatching {
                wm.addView(rootContainer, lp)
                lifecycleRegistry.currentState = Lifecycle.State.STARTED
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
                rootContainer.requestFocus()
            }.isSuccess
        }

        fun startHideAnimation(onComplete: () -> Unit) {
            if (isDetaching) return
            isDetaching = true
            isVisible = false
            mainHandler.postDelayed(onComplete, 110L)
        }

        fun detach(wm: WindowManager) {
            attachedWm = null
            windowParams = null
            runCatching {
                if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
                    lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
                }
            }
            runCatching { wm.removeViewImmediate(rootContainer) }
        }
    }
}
