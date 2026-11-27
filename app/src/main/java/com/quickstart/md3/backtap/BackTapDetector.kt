package com.quickstart.md3.backtap

import android.hardware.Sensor
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.roundToInt
import kotlin.math.sqrt

interface TapDetectorStrategy {
    val requiresGyroscope: Boolean
    val isFlatOnTable: Boolean
    fun setSensitivity(sensitivityLevel: Int)
    fun reset()
    fun onSensorSample(
        sensorType: Int,
        x: Float,
        y: Float,
        z: Float,
        timestampNs: Long
    ): Boolean
}

/**
 * Pure Columbus ML TapRT double-tap pipeline adapted from KieronQuinn/TapTap.
 * Keeps zero Android framework allocations in the 400Hz hot path and computes
 * flat-on-table inclination inline from accelerometer samples.
 */
class BackTapDetector(
    private var classifier: TapClassifier,
    private val onDiagLog: ((String) -> Unit)? = null
) : TapDetectorStrategy {
    override val requiresGyroscope: Boolean = true
    private val resampleAcc = Resample3C()
    private val resampleGyro = Resample3C()
    private val slopeAcc = Slope3C()
    private val slopeGyro = Slope3C()
    private val lowpassAcc = Lowpass3C()
    private val lowpassGyro = Lowpass3C()
    private val highpassAcc = Highpass3C()
    private val highpassGyro = Highpass3C()
    private val lowpassKey = Lowpass1C()
    private val highpassKey = Highpass1C()
    private val peakDetectorPositive = PeakDetector()

    private val xsAcc = FloatRingBuffer(RING_CAPACITY)
    private val ysAcc = FloatRingBuffer(RING_CAPACITY)
    private val zsAcc = FloatRingBuffer(RING_CAPACITY)
    private val xsGyro = FloatRingBuffer(RING_CAPACITY)
    private val ysGyro = FloatRingBuffer(RING_CAPACITY)
    private val zsGyro = FloatRingBuffer(RING_CAPACITY)

    private val featureVector = FloatArray(NUMBER_FEATURE)
    private val backTapTimestamps = ArrayDeque<Long>(8)

    private val tmpSlope = Point3f()
    private val tmpLowpass = Point3f()
    private val tmpHighpass = Point3f()

    private var gotAcc = false
    private var gotGyro = false
    private var syncTime = 0L
    private var lastEventTimeNs = 0L
    private var wasPeakApproaching = true

    private var lastFlatCheckNs = 0L
    @Volatile
    override var isFlatOnTable: Boolean = false
        private set

    init {
        configure(DEFAULT_SENSITIVITY_LEVEL)
    }

    fun configure(sensitivityLevel: Int) {
        val idx = sensitivityLevel.coerceIn(0, SENSITIVITY_VALUES.lastIndex)
        lowpassAcc.setPara(1.0f)
        lowpassGyro.setPara(1.0f)
        highpassAcc.setPara(0.05f)
        highpassGyro.setPara(0.05f)
        lowpassKey.setPara(0.2f)
        highpassKey.setPara(0.2f)
        peakDetectorPositive.setWindowSize(PEAK_WINDOW_SIZE)
        peakDetectorPositive.setMinNoiseTolerate(SENSITIVITY_VALUES[idx])
        reset()
    }

    override fun setSensitivity(sensitivityLevel: Int) {
        val idx = sensitivityLevel.coerceIn(0, SENSITIVITY_VALUES.lastIndex)
        peakDetectorPositive.setMinNoiseTolerate(SENSITIVITY_VALUES[idx])
    }

    fun replaceClassifier(newClassifier: TapClassifier) {
        classifier = newClassifier
        reset()
    }

    override fun reset() {
        xsAcc.clear()
        ysAcc.clear()
        zsAcc.clear()
        xsGyro.clear()
        ysGyro.clear()
        zsGyro.clear()
        gotAcc = false
        gotGyro = false
        syncTime = 0L
        lastEventTimeNs = 0L
        wasPeakApproaching = true
        peakDetectorPositive.reset()
        backTapTimestamps.clear()
    }

    override fun onSensorSample(
        sensorType: Int,
        x: Float,
        y: Float,
        z: Float,
        timestampNs: Long
    ): Boolean {
        if (lastEventTimeNs != 0L && timestampNs - lastEventTimeNs > RESUME_GAP_RESET_NS) {
            reset()
        }
        lastEventTimeNs = timestampNs

        when (sensorType) {
            Sensor.TYPE_ACCELEROMETER -> {
                updateFlatOnTable(x, y, z, timestampNs)
                gotAcc = true
                if (syncTime == 0L) {
                    resampleAcc.init(x, y, z, timestampNs, SAMPLING_INTERVAL_NS)
                }
                if (!gotGyro) return false
            }
            Sensor.TYPE_GYROSCOPE -> {
                gotGyro = true
                if (syncTime == 0L) {
                    resampleGyro.init(x, y, z, timestampNs, SAMPLING_INTERVAL_NS)
                }
                if (!gotAcc) return false
            }
            else -> return false
        }

        if (syncTime == 0L) {
            syncTime = timestampNs
            resampleAcc.setSyncTime(syncTime)
            resampleGyro.setSyncTime(syncTime)
            slopeAcc.init(resampleAcc.point)
            slopeGyro.init(resampleGyro.point)
            val zero = Point3f(0f, 0f, 0f)
            lowpassAcc.init(zero)
            lowpassGyro.init(zero)
            highpassAcc.init(zero)
            highpassGyro.init(zero)
            lowpassKey.init(0f)
            highpassKey.init(0f)
            return false
        }

        if (sensorType == Sensor.TYPE_ACCELEROMETER) {
            while (resampleAcc.update(x, y, z, timestampNs)) {
                processAccAndKeySignal()
                val pred = recognizeTapML()
                if (pred.classIndex == TAP_CLASS_BACK) {
                    backTapTimestamps.addLast(timestampNs)
                    onDiagLog?.invoke("BackTap recorded (acc) queue=${backTapTimestamps.size}")
                }
            }
        } else {
            while (resampleGyro.update(x, y, z, timestampNs)) {
                processGyro()
            }
            val pred = recognizeTapML()
            if (pred.classIndex == TAP_CLASS_BACK) {
                backTapTimestamps.addLast(timestampNs)
                onDiagLog?.invoke("BackTap recorded (gyro) queue=${backTapTimestamps.size}")
            }
        }

        if (abs(resampleAcc.tResampledLast - resampleGyro.tResampledLast) > DRIFT_RESET_NS) {
            onDiagLog?.invoke(
                "Reset due to acc/gyro drift=${(resampleAcc.tResampledLast - resampleGyro.tResampledLast) / 1_000_000L}ms"
            )
            reset()
            return false
        }

        return checkDoubleTapTiming(timestampNs) == 2
    }

    private fun updateFlatOnTable(x: Float, y: Float, z: Float, timestampNs: Long) {
        if (timestampNs - lastFlatCheckNs < FLAT_CHECK_INTERVAL_NS) return
        lastFlatCheckNs = timestampNs
        val norm = sqrt(x * x + y * y + z * z)
        if (norm < 1e-3f) return
        val cosZ = (z / norm).coerceIn(-1f, 1f)
        val inclination = Math.toDegrees(acos(cosZ.toDouble())).roundToInt()
        isFlatOnTable = inclination < 22 || inclination > 158
    }

    private fun processAccAndKeySignal() {
        val interval = resampleAcc.interval.coerceAtLeast(1L)
        val scale = SAMPLING_INTERVAL_NS.toFloat() / interval.toFloat()
        slopeAcc.update(resampleAcc.point, scale, tmpSlope)
        lowpassAcc.update(tmpSlope, tmpLowpass)
        highpassAcc.update(tmpLowpass, tmpHighpass)

        xsAcc.addLast(tmpHighpass.x)
        ysAcc.addLast(tmpHighpass.y)
        zsAcc.addLast(tmpHighpass.z)

        val maxWindow = (SIZE_WINDOW_NS / interval).toInt()
        xsAcc.trimTo(maxWindow)
        ysAcc.trimTo(maxWindow)
        zsAcc.trimTo(maxWindow)

        val lKey = lowpassKey.update(tmpSlope.z)
        val hKey = highpassKey.update(lKey)
        peakDetectorPositive.update(hKey)
    }

    private fun processGyro() {
        val interval = resampleGyro.interval.coerceAtLeast(1L)
        val scale = SAMPLING_INTERVAL_NS.toFloat() / interval.toFloat()
        slopeGyro.update(resampleGyro.point, scale, tmpSlope)
        lowpassGyro.update(tmpSlope, tmpLowpass)
        highpassGyro.update(tmpLowpass, tmpHighpass)

        xsGyro.addLast(tmpHighpass.x)
        ysGyro.addLast(tmpHighpass.y)
        zsGyro.addLast(tmpHighpass.z)

        val maxWindow = (SIZE_WINDOW_NS / interval).toInt()
        xsGyro.trimTo(maxWindow)
        ysGyro.trimTo(maxWindow)
        zsGyro.trimTo(maxWindow)
    }

    private fun recognizeTapML(): TapPrediction {
        val resampleInterval = resampleAcc.interval.coerceAtLeast(1L)
        val resampleOffset = ((resampleAcc.tResampledLast - resampleGyro.tResampledLast) / resampleInterval).toInt()
        val majorPeakId = peakDetectorPositive.idMajorPeak
        if (majorPeakId > FRAME_ALIGN_PEAK) {
            wasPeakApproaching = true
        }

        val adjustedPeakId = majorPeakId - 6
        val adjustedGyroStart = adjustedPeakId - resampleOffset
        val accSize = zsAcc.size
        val gyroSize = xsGyro.size

        if (wasPeakApproaching && majorPeakId in 0..FRAME_ALIGN_PEAK) {
            if (adjustedPeakId < 0 ||
                adjustedGyroStart < 0 ||
                adjustedPeakId + SIZE_FEATURE_WINDOW >= accSize ||
                adjustedGyroStart + SIZE_FEATURE_WINDOW > gyroSize
            ) {
                onDiagLog?.invoke(
                    "Peak skipped align: major=$majorPeakId adj=$adjustedPeakId gyroStart=$adjustedGyroStart offset=$resampleOffset accSize=$accSize gyroSize=$gyroSize"
                )
                return TapPrediction(TAP_CLASS_OTHERS)
            }

            wasPeakApproaching = false
            xsAcc.copyWindow(adjustedPeakId, SIZE_FEATURE_WINDOW, featureVector, 0)
            ysAcc.copyWindow(adjustedPeakId, SIZE_FEATURE_WINDOW, featureVector, SIZE_FEATURE_WINDOW)
            zsAcc.copyWindow(adjustedPeakId, SIZE_FEATURE_WINDOW, featureVector, SIZE_FEATURE_WINDOW * 2)
            xsGyro.copyWindow(adjustedGyroStart, SIZE_FEATURE_WINDOW, featureVector, SIZE_FEATURE_WINDOW * 3)
            ysGyro.copyWindow(adjustedGyroStart, SIZE_FEATURE_WINDOW, featureVector, SIZE_FEATURE_WINDOW * 4)
            zsGyro.copyWindow(adjustedGyroStart, SIZE_FEATURE_WINDOW, featureVector, SIZE_FEATURE_WINDOW * 5)

            for (i in (NUMBER_FEATURE / 2) until NUMBER_FEATURE) {
                featureVector[i] *= GYRO_SCALE
            }

            val pred = classifier.predict(featureVector)
            onDiagLog?.invoke(
                "Tap ML: cls=${pred.classIndex} backScore=${"%.3f".format(pred.backScore)} topScore=${"%.3f".format(pred.topScore)} offset=$resampleOffset"
            )
            return pred
        }
        return TapPrediction(TAP_CLASS_OTHERS)
    }

    fun checkDoubleTapTiming(timestampNs: Long): Int {
        while (backTapTimestamps.isNotEmpty()) {
            val first = backTapTimestamps.peekFirst() ?: break
            val ageMs = (timestampNs - first) / 1_000_000L
            if (timestampNs - first > MAX_TIME_GAP_NS) {
                backTapTimestamps.removeFirst()
                onDiagLog?.invoke("BackTap expired age=${ageMs}ms")
            } else {
                break
            }
        }
        if (backTapTimestamps.isEmpty()) return 0

        val newest = backTapTimestamps.peekLast() ?: return 0
        for (past in backTapTimestamps) {
            val gapMs = (newest - past) / 1_000_000L
            if (newest - past > MIN_TIME_GAP_NS) {
                onDiagLog?.invoke("DoubleTap HIT gap=${gapMs}ms")
                backTapTimestamps.clear()
                return 2
            }
        }
        return 1
    }

    companion object {
        const val DEFAULT_SENSITIVITY_LEVEL = 6
        const val SIZE_WINDOW_NS = 160_000_000L
        const val SAMPLING_INTERVAL_NS = 2_500_000L
        const val SIZE_FEATURE_WINDOW = 50
        const val NUMBER_FEATURE = 300
        const val FRAME_ALIGN_PEAK = 12
        const val MIN_TIME_GAP_NS = 100_000_000L
        const val MAX_TIME_GAP_NS = 500_000_000L
        const val GYRO_SCALE = 10.0f
        const val TAP_CLASS_BACK = 1
        const val TAP_CLASS_OTHERS = 6

        private const val RING_CAPACITY = 96
        private const val PEAK_WINDOW_SIZE = 64
        private const val RESUME_GAP_RESET_NS = 500_000_000L
        private const val DRIFT_RESET_NS = 100_000_000L
        private const val FLAT_CHECK_INTERVAL_NS = 500_000_000L

        val SENSITIVITY_VALUES = floatArrayOf(
            0.75f, 0.53f, 0.40f, 0.25f, 0.10f, 0.05f, 0.04f, 0.03f, 0.02f, 0.01f, 0.0f
        )
    }
}
