package com.quickstart.md3.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.quickstart.md3.QuickStartApp
import com.quickstart.md3.R
import com.quickstart.md3.data.AppSettings
import com.quickstart.md3.data.EdgeTriggerSide
import com.quickstart.md3.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingBubbleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var bubbleView: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var leftEdgeView: FrameLayout? = null
    private var rightEdgeView: FrameLayout? = null
    private var lastEdgeConfigKey: String? = null
    private var bubbleHidden = false
    private val appOverlayHost: OverlayPanelHost
        get() = (application as QuickStartApp).overlayHost
    private val handler = Handler(Looper.getMainLooper())
    private val fadeRunnable = Runnable {
        bubbleView?.animate()?.alpha(0.42f)?.setDuration(260)?.start()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_RESTORE_BUBBLE -> bubbleHidden = false
            ACTION_STOP -> {
                appOverlayHost.hide()
                removeBubble()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        when (action) {
            ACTION_SHOW_OVERLAY -> showOverlay(this)
            ACTION_TOGGLE_OVERLAY -> toggleOverlay(this)
            ACTION_HIDE_OVERLAY -> appOverlayHost.hide()
        }

        scope.launch {
            val s = (application as QuickStartApp).store.settingsFlow.first()
            if (!s.floatingBubbleEnabled && !s.notificationEnabled && !s.edgeTriggerEnabled) {
                removeBubble()
                removeEdgeTriggers()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }
            if (s.floatingBubbleEnabled && !bubbleHidden && Settings.canDrawOverlays(this@FloatingBubbleService)) {
                showOrUpdateBubble(s)
            } else {
                removeBubble()
            }
            syncEdgeTriggers(s)
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIF_ID, buildNotification())
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(fadeRunnable)
        removeBubble()
        removeEdgeTriggers()
        scope.cancel()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "快启动悬浮球与快捷入口",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val pi = if (bubbleHidden) {
            val restoreIntent = Intent(this, FloatingBubbleService::class.java).setAction(ACTION_RESTORE_BUBBLE)
            PendingIntent.getService(this, 1, restoreIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            QuickStartIconWidgetProvider.togglePendingIntent(this, requestCode = 2)
        }

        val openMainPi = QuickStartIconWidgetProvider.togglePendingIntent(this, requestCode = 3)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_qs)
            .setContentTitle("快启动 MD3")
            .setContentText(if (bubbleHidden) "悬浮球已隐藏，点击恢复显示" else "点击极速搜索并启动应用")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
            .addAction(0, "打开快启动", openMainPi)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOrUpdateBubble(settings: AppSettings) {
        val wm = windowManager ?: return
        val sizePx = (settings.floatingBubbleSizeDp * resources.displayMetrics.density).toInt().coerceAtLeast(84)

        if (bubbleView == null) {
            val iv = ImageView(this).apply {
                setImageResource(R.drawable.ic_tile_qs)
                val pad = (sizePx * 0.25f).toInt()
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            ContextCompat.getColor(this@FloatingBubbleService, android.R.color.system_accent1_600)
                        } else {
                            Color.parseColor("#6750A4")
                        }
                    )
                }
                elevation = 10f
            }

            val lp = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24
                y = resources.displayMetrics.heightPixels / 3
            }

            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var moved = false
            var longPressed = false
            val longPressCheck = Runnable {
                if (!moved) {
                    longPressed = true
                    bubbleHidden = true
                    removeBubble()
                    getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification())
                }
            }

            iv.setOnTouchListener { _: View, ev: MotionEvent ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        handler.removeCallbacks(fadeRunnable)
                        iv.alpha = 1f
                        downX = ev.rawX
                        downY = ev.rawY
                        startX = lp.x
                        startY = lp.y
                        moved = false
                        longPressed = false
                        handler.postDelayed(longPressCheck, 550)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (ev.rawX - downX).toInt()
                        val dy = (ev.rawY - downY).toInt()
                        if (abs(dx) > 12 || abs(dy) > 12) {
                            moved = true
                            handler.removeCallbacks(longPressCheck)
                            lp.x = startX + dx
                            lp.y = startY + dy
                            runCatching { wm.updateViewLayout(iv, lp) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(longPressCheck)
                        if (!moved && !longPressed && ev.action == MotionEvent.ACTION_UP) {
                            appOverlayHost.toggle()
                        } else if (moved && settings.floatingBubbleSnapEdge) {
                            val sw = resources.displayMetrics.widthPixels
                            lp.x = if (lp.x + sizePx / 2 < sw / 2) 12 else sw - sizePx - 12
                            runCatching { wm.updateViewLayout(iv, lp) }
                        }
                        if (settings.floatingBubbleAutoFade) {
                            handler.postDelayed(fadeRunnable, 2200)
                        }
                        true
                    }
                    else -> false
                }
            }

            bubbleView = iv
            layoutParams = lp
            runCatching { wm.addView(iv, lp) }
            if (settings.floatingBubbleAutoFade) handler.postDelayed(fadeRunnable, 2200)
        } else {
            layoutParams?.let { lp ->
                lp.width = sizePx
                lp.height = sizePx
                runCatching { wm.updateViewLayout(bubbleView, lp) }
            }
        }
    }

    private fun removeBubble() {
        bubbleView?.let { v ->
            runCatching { windowManager?.removeViewImmediate(v) }
        }
        bubbleView = null
    }

    private fun syncEdgeTriggers(settings: AppSettings) {
        if (!settings.edgeTriggerEnabled || !Settings.canDrawOverlays(this)) {
            removeEdgeTriggers()
            return
        }
        val key = "${settings.edgeTriggerSide}|${settings.edgeTriggerPosition}|${settings.edgeTriggerHeightDp}|${settings.edgeTriggerWidthDp}|${settings.edgeTriggerVisibleHandle}"
        if (key == lastEdgeConfigKey && (leftEdgeView != null || rightEdgeView != null)) {
            return
        }
        removeEdgeTriggers()
        lastEdgeConfigKey = key

        if (settings.edgeTriggerSide == EdgeTriggerSide.LEFT || settings.edgeTriggerSide == EdgeTriggerSide.BOTH) {
            leftEdgeView = createEdgeTriggerView(isLeft = true, settings = settings)
        }
        if (settings.edgeTriggerSide == EdgeTriggerSide.RIGHT || settings.edgeTriggerSide == EdgeTriggerSide.BOTH) {
            rightEdgeView = createEdgeTriggerView(isLeft = false, settings = settings)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createEdgeTriggerView(isLeft: Boolean, settings: AppSettings): FrameLayout? {
        val wm = windowManager ?: return null
        val density = resources.displayMetrics.density
        val widthPx = (settings.edgeTriggerWidthDp.coerceIn(10, 32) * density).toInt()
        val heightPx = (settings.edgeTriggerHeightDp.coerceIn(60, 280) * density).toInt()
        val screenH = resources.displayMetrics.heightPixels
        val yOffset = ((screenH - heightPx).coerceAtLeast(0) * settings.edgeTriggerPosition.coerceIn(0f, 1f)).toInt()

        val container = FrameLayout(this)
        if (settings.edgeTriggerVisibleHandle) {
            val handleWidth = (3f * density).toInt().coerceAtLeast(4)
            val accentColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.getColor(this, android.R.color.system_accent1_400)
            } else {
                Color.parseColor("#6750A4")
            }
            val handleView = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = handleWidth.toFloat()
                    setColor(accentColor)
                }
                alpha = 0.34f
            }
            val handleLp = FrameLayout.LayoutParams(
                handleWidth,
                (heightPx * 0.65f).toInt().coerceAtLeast(36)
            ).apply {
                gravity = (if (isLeft) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
            }
            container.addView(handleView, handleLp)
        }

        val lp = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or (if (isLeft) Gravity.START else Gravity.END)
            x = 0
            y = yOffset
        }

        var downX = 0f
        var downY = 0f
        var triggered = false
        val thresholdPx = 26f * density
        container.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    triggered = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!triggered) {
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        val inwardOk = if (isLeft) dx > thresholdPx else -dx > thresholdPx
                        if (inwardOk && abs(dx) > abs(dy) * 1.15f) {
                            triggered = true
                            vibrateEdgeTick()
                            appOverlayHost.toggle()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        return if (runCatching { wm.addView(container, lp) }.isSuccess) container else null
    }

    private fun vibrateEdgeTick() {
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vib?.vibrate(VibrationEffect.createOneShot(14L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun removeEdgeTriggers() {
        lastEdgeConfigKey = null
        leftEdgeView?.let { v -> runCatching { windowManager?.removeViewImmediate(v) } }
        rightEdgeView?.let { v -> runCatching { windowManager?.removeViewImmediate(v) } }
        leftEdgeView = null
        rightEdgeView = null
    }

    companion object {
        private const val CHANNEL_ID = "quickstart_trigger_channel"
        private const val NOTIF_ID = 10086
        private const val ACTION_RESTORE_BUBBLE = "com.quickstart.md3.RESTORE_BUBBLE"
        private const val ACTION_STOP = "com.quickstart.md3.STOP_TRIGGER"
        private const val ACTION_SHOW_OVERLAY = "com.quickstart.md3.SHOW_OVERLAY"
        private const val ACTION_TOGGLE_OVERLAY = "com.quickstart.md3.TOGGLE_OVERLAY"
        private const val ACTION_HIDE_OVERLAY = "com.quickstart.md3.HIDE_OVERLAY"

        fun showOverlayIntent(context: Context): Intent =
            Intent(context, FloatingBubbleService::class.java).setAction(ACTION_SHOW_OVERLAY)

        fun toggleOverlayIntent(context: Context): Intent =
            Intent(context, FloatingBubbleService::class.java).setAction(ACTION_TOGGLE_OVERLAY)

        fun showOverlay(context: Context) {
            if (Settings.canDrawOverlays(context)) {
                (context.applicationContext as QuickStartApp).overlayHost.show()
            } else {
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        fun toggleOverlay(context: Context) {
            if (Settings.canDrawOverlays(context)) {
                (context.applicationContext as QuickStartApp).overlayHost.toggle()
            } else {
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        fun hideOverlay(context: Context) {
            (context.applicationContext as QuickStartApp).overlayHost.hide()
        }

        fun syncWithSettings(context: Context, settings: AppSettings) {
            val needService = settings.floatingBubbleEnabled ||
                settings.notificationEnabled ||
                settings.edgeTriggerEnabled
            val intent = Intent(context, FloatingBubbleService::class.java)
            if (needService) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.stopService(intent)
            }
        }
    }
}
