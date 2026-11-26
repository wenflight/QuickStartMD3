package com.quickstart.md3.backtap

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.quickstart.md3.data.AppSettings
import com.quickstart.md3.data.BackTapModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min

enum class BackTapStatus(val label: String) {
    DISABLED("未开启"),
    ACTIVE("运行中 · 亮屏待命"),
    PAUSED_SCREEN_OFF("已休眠 · 息屏零耗电"),
    PAUSED_LOCKED("已暂停 · 锁屏保护中"),
    PAUSED_BATTERY_SAVER("已暂停 · 系统省电模式"),
    UNSUPPORTED("当前设备缺少加速度计或陀螺仪")
}

data class BackTapRuntimeState(
    val status: BackTapStatus = BackTapStatus.DISABLED,
    val lastTriggeredAtMs: Long = 0L
)

/**
 * Manages the lifecycle, background sensor thread, FIFO batching, and power gates
 * for the integrated Columbus double-tap detector.
 */
class BackTapEngine(
    context: Context,
    private val onDoubleTap: () -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguardManager = appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val proximitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private val sensorThread = HandlerThread("QuickStartBackTap", Process.THREAD_PRIORITY_BACKGROUND).apply {
        start()
    }
    private val sensorHandler = Handler(sensorThread.looper)

    private val proximityThread = HandlerThread("QuickStartBackTapProx", Process.THREAD_PRIORITY_BACKGROUND).apply {
        start()
    }
    private val proximityHandler = Handler(proximityThread.looper)

    @Volatile
    private var currentSettings = AppSettings()
    private var classifier: TfliteBackTapClassifier? = null
    private var detector: BackTapDetector? = null
    private var loadedModel: BackTapModel? = null
    private var loadedLowPower: Boolean? = null

    private var isListening = false
    private var lastTriggerElapsedMs = 0L
    private var receiverRegistered = false
    private var destroyed = false

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null || !isListening || destroyed) return
            val det = detector ?: return
            val doubleTapped = det.onSensorSample(
                sensorType = event.sensor.type,
                x = event.values[0],
                y = event.values[1],
                z = event.values[2],
                timestampNs = event.timestamp
            )
            if (doubleTapped) {
                handleDoubleTapCandidate(det)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (destroyed) return
            val forceResubscribe = intent?.action == Intent.ACTION_SCREEN_ON ||
                intent?.action == Intent.ACTION_USER_PRESENT
            sensorHandler.post {
                evaluateStateLocked(forceResubscribe = forceResubscribe)
            }
        }
    }

    fun updateConfig(settings: AppSettings) {
        if (destroyed) return
        currentSettings = settings
        if (settings.backTapEnabled) {
            ensureReceiverRegistered()
        } else {
            unregisterReceiverIfNeeded()
        }

        sensorHandler.post {
            if (destroyed) return@post
            if (!settings.backTapEnabled) {
                stopListeningLocked()
                releaseModelLocked()
                publishStatus(BackTapStatus.DISABLED)
                return@post
            }

            if (accelerometer == null || gyroscope == null) {
                stopListeningLocked()
                publishStatus(BackTapStatus.UNSUPPORTED)
                return@post
            }

            val needReloadModel = classifier == null ||
                loadedModel != settings.backTapModel ||
                loadedLowPower != settings.backTapLowPowerDelegate

            if (needReloadModel) {
                val wasListening = isListening
                stopListeningLocked()
                releaseModelLocked()
                val newClassifier = TfliteBackTapClassifier(
                    context = appContext,
                    assetPath = settings.backTapModel.assetPath,
                    useLowPowerDelegate = settings.backTapLowPowerDelegate
                )
                classifier = newClassifier
                loadedModel = settings.backTapModel
                loadedLowPower = settings.backTapLowPowerDelegate

                val existingDetector = detector
                if (existingDetector == null) {
                    detector = BackTapDetector(newClassifier).apply {
                        configure(settings.backTapSensitivity)
                    }
                } else {
                    existingDetector.replaceClassifier(newClassifier)
                    existingDetector.configure(settings.backTapSensitivity)
                }
                if (wasListening) {
                    evaluateStateLocked(forceResubscribe = true)
                    return@post
                }
            } else {
                detector?.setSensitivity(settings.backTapSensitivity)
            }

            evaluateStateLocked(forceResubscribe = false)
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        unregisterReceiverIfNeeded()
        sensorHandler.post {
            stopListeningLocked()
            releaseModelLocked()
            publishStatus(BackTapStatus.DISABLED)
            sensorThread.quitSafely()
            proximityThread.quitSafely()
        }
    }

    private fun evaluateStateLocked(forceResubscribe: Boolean) {
        if (destroyed) return
        val s = currentSettings
        if (!s.backTapEnabled) {
            stopListeningLocked()
            publishStatus(BackTapStatus.DISABLED)
            return
        }
        if (accelerometer == null || gyroscope == null) {
            stopListeningLocked()
            publishStatus(BackTapStatus.UNSUPPORTED)
            return
        }
        if (!powerManager.isInteractive) {
            stopListeningLocked()
            publishStatus(BackTapStatus.PAUSED_SCREEN_OFF)
            return
        }
        if (s.backTapPauseOnLockScreen && keyguardManager.isKeyguardLocked) {
            stopListeningLocked()
            publishStatus(BackTapStatus.PAUSED_LOCKED)
            return
        }
        if (s.backTapPauseInBatterySaver && powerManager.isPowerSaveMode) {
            stopListeningLocked()
            publishStatus(BackTapStatus.PAUSED_BATTERY_SAVER)
            return
        }

        if (forceResubscribe || !isListening) {
            startListeningLocked()
        }
        publishStatus(if (isListening) BackTapStatus.ACTIVE else BackTapStatus.UNSUPPORTED)
    }

    private fun startListeningLocked() {
        val acc = accelerometer ?: return
        val gyro = gyroscope ?: return
        if (isListening) {
            sensorManager.unregisterListener(sensorListener)
            isListening = false
        }
        detector?.reset()

        val targetPeriodUs = maxOf(
            TARGET_SAMPLING_PERIOD_US,
            acc.minDelay.coerceAtLeast(0),
            gyro.minDelay.coerceAtLeast(0)
        )

        val ok = registerSensors(acc, gyro, targetPeriodUs, FIFO_BATCH_LATENCY_US) ||
            registerSensors(acc, gyro, targetPeriodUs, 0) ||
            registerSensors(acc, gyro, FALLBACK_SAMPLING_PERIOD_US, 0)

        isListening = ok
    }

    private fun registerSensors(
        acc: Sensor,
        gyro: Sensor,
        periodUs: Int,
        batchLatencyUs: Int
    ): Boolean {
        return try {
            val accOk = sensorManager.registerListener(
                sensorListener,
                acc,
                periodUs,
                batchLatencyUs,
                sensorHandler
            )
            val gyroOk = sensorManager.registerListener(
                sensorListener,
                gyro,
                periodUs,
                batchLatencyUs,
                sensorHandler
            )
            if (accOk && gyroOk) {
                true
            } else {
                sensorManager.unregisterListener(sensorListener)
                false
            }
        } catch (_: SecurityException) {
            sensorManager.unregisterListener(sensorListener)
            false
        } catch (_: Throwable) {
            sensorManager.unregisterListener(sensorListener)
            false
        }
    }

    private fun stopListeningLocked() {
        if (isListening) {
            runCatching { sensorManager.unregisterListener(sensorListener) }
            isListening = false
        }
        detector?.reset()
    }

    private fun releaseModelLocked() {
        classifier?.close()
        classifier = null
        loadedModel = null
        loadedLowPower = null
    }

    private fun handleDoubleTapCandidate(det: BackTapDetector) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerElapsedMs < TRIGGER_COOLDOWN_MS) return

        val s = currentSettings
        if (s.backTapTableGate && det.isFlatOnTable) return
        if (s.backTapPocketGate && isPocketBlocked()) return

        lastTriggerElapsedMs = now
        _runtimeState.value = _runtimeState.value.copy(lastTriggeredAtMs = System.currentTimeMillis())
        mainHandler.post {
            if (!destroyed) {
                onDoubleTap()
            }
        }
    }

    /**
     * Checks proximity on-demand without holding a persistent proximity listener.
     */
    private fun isPocketBlocked(): Boolean {
        val prox = proximitySensor ?: return false
        var near = false
        val latch = CountDownLatch(1)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event != null && event.values.isNotEmpty()) {
                    val threshold = min(prox.maximumRange, 5f)
                    near = event.values[0] < threshold
                }
                sensorManager.unregisterListener(this)
                latch.countDown()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        return try {
            val registered = sensorManager.registerListener(
                listener,
                prox,
                SensorManager.SENSOR_DELAY_FASTEST,
                proximityHandler
            )
            if (!registered) return false
            latch.await(PROXIMITY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            near
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { sensorManager.unregisterListener(listener) }
        }
    }

    private fun ensureReceiverRegistered() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                stateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    private fun unregisterReceiverIfNeeded() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(stateReceiver) }
        receiverRegistered = false
    }

    private fun publishStatus(status: BackTapStatus) {
        val prev = _runtimeState.value
        if (prev.status != status) {
            _runtimeState.value = prev.copy(status = status)
        }
    }

    companion object {
        private const val TARGET_SAMPLING_PERIOD_US = 2500
        private const val FALLBACK_SAMPLING_PERIOD_US = 5000
        private const val FIFO_BATCH_LATENCY_US = 20_000
        private const val TRIGGER_COOLDOWN_MS = 650L
        private const val PROXIMITY_TIMEOUT_MS = 30L

        private val _runtimeState = MutableStateFlow(BackTapRuntimeState())
        val runtimeState: StateFlow<BackTapRuntimeState> = _runtimeState.asStateFlow()
    }
}
