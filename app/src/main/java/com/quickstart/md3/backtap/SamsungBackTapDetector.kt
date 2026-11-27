package com.quickstart.md3.backtap

import android.hardware.Sensor
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Samsung RegiStar back-tap detection pipeline adapted from KieronQuinn/TapTap.
 * Requires only TYPE_ACCELEROMETER (halving sensor power draw vs. Acc+Gyro),
 * uses a 60ms post-peak window with adaptive mean+3*std peak triggering,
 * and runs a 6-class 288-feature TFLite model.
 */
class SamsungBackTapDetector(
    private val classifier: TapClassifier,
    sensitivityLevel: Int = BackTapDetector.DEFAULT_SENSITIVITY_LEVEL,
    private val onDiagLog: ((String) -> Unit)? = null
) : TapDetectorStrategy {

    override val requiresGyroscope: Boolean = false

    @Volatile
    override var isFlatOnTable: Boolean = false
        private set

    private var sensitivity: Float = resolveSensitivity(sensitivityLevel)
    private val processor = SamsungSensorProcessor(sensitivity)
    private val featureVector = FloatArray(FEATURE_COUNT)
    private val backTapTimestamps = ArrayDeque<Long>(8)

    private val flatRing = IntArray(QUEUE_CAPACITY)
    private var flatIndex = 0
    private var flatSum = 0
    private var lastEventTimeNs = 0L

    override fun setSensitivity(sensitivityLevel: Int) {
        sensitivity = resolveSensitivity(sensitivityLevel)
        processor.sensitivity = sensitivity
    }

    override fun reset() {
        processor.reset()
        backTapTimestamps.clear()
        flatRing.fill(0)
        flatIndex = 0
        flatSum = 0
        lastEventTimeNs = 0L
        isFlatOnTable = false
    }

    override fun onSensorSample(
        sensorType: Int,
        x: Float,
        y: Float,
        z: Float,
        timestampNs: Long
    ): Boolean {
        if (sensorType != Sensor.TYPE_ACCELEROMETER) return false
        if (z < -60.0f || z > 60.0f) return false

        if (lastEventTimeNs != 0L && timestampNs - lastEventTimeNs > RESUME_GAP_RESET_NS) {
            reset()
        }
        lastEventTimeNs = timestampNs

        processor.updateData(Sensor.TYPE_ACCELEROMETER, x, y, z, timestampNs, SAMPLING_INTERVAL_NS)

        val norm = sqrt(x * x + y * y + z * z)
        if (norm > 1e-3f) {
            processor.updateData(
                TYPE_SYNTH_GYRO,
                acos((x / norm).coerceIn(-1f, 1f)),
                acos((y / norm).coerceIn(-1f, 1f)),
                acos((z / norm).coerceIn(-1f, 1f)),
                timestampNs,
                SAMPLING_INTERVAL_NS
            )
            val deg = Math.toDegrees(acos((z / norm).coerceIn(-1f, 1f)).toDouble()).toInt()
            val flatBit = if (deg < 10 || deg > 170) 1 else 0
            flatSum += flatBit - flatRing[flatIndex]
            flatRing[flatIndex] = flatBit
            flatIndex = (flatIndex + 1) % QUEUE_CAPACITY
            isFlatOnTable = flatSum.toFloat() / QUEUE_CAPACITY.toFloat() > 0.5f
        }

        if (!processor.az.isFull) {
            return false
        }

        if (processor.rightCount >= RIGHT_COUNT_FRAMES) {
            val minorPeaks = processor.nMinorPeaks
            val peakTimestampNs = processor.peakTimestampNs
            processor.rightCount = 0
            processor.hasMajorPeak = false
            processor.nMinorPeaks = 0

            val sinceLastWindow = timestampNs - processor.lastWindowEndNs
            if (minorPeaks < 2 && sinceLastWindow > MIN_WINDOW_GAP_NS) {
                evaluateCandidate(peakTimestampNs)
            } else {
                onDiagLog?.invoke(
                    "Samsung skipped: minorPeaks=$minorPeaks gapMs=${sinceLastWindow / 1_000_000L}"
                )
            }
            processor.lastWindowEndNs = timestampNs
        }

        return checkDoubleTapTiming(timestampNs) == 2
    }

    private fun evaluateCandidate(peakTimestampNs: Long) {
        var maxAbsX = 0f
        var maxAbsY = 0f
        var maxAbsZ = 0f
        for (i in 0 until FEATURE_WINDOW) {
            val idx = i + FEATURE_START_OFFSET
            val vz = processor.az.getValueAt(idx)
            val vy = processor.ay.getValueAt(idx)
            val vx = processor.ax.getValueAt(idx)
            featureVector[i] = vz
            featureVector[i + FEATURE_WINDOW] = vy
            featureVector[i + FEATURE_WINDOW * 2] = vx
            featureVector[i + FEATURE_WINDOW * 3] = processor.gz.getValueAt(idx)
            featureVector[i + FEATURE_WINDOW * 4] = processor.gy.getValueAt(idx)
            featureVector[i + FEATURE_WINDOW * 5] = processor.gx.getValueAt(idx)

            val azAbs = abs(vz)
            val ayAbs = abs(vy)
            val axAbs = abs(vx)
            if (azAbs > maxAbsZ) maxAbsZ = azAbs
            if (ayAbs > maxAbsY) maxAbsY = ayAbs
            if (axAbs > maxAbsX) maxAbsX = axAbs
        }

        if (maxAbsX <= maxAbsZ && maxAbsY <= maxAbsZ) {
            val pred = classifier.predict(featureVector)
            onDiagLog?.invoke(
                "Samsung ML: cls=${pred.classIndex} tapScore=${"%.3f".format(pred.backScore)} topScore=${"%.3f".format(pred.topScore)}"
            )
            if (pred.classIndex == TAP_CLASS_SAMSUNG) {
                backTapTimestamps.addLast(peakTimestampNs)
                onDiagLog?.invoke("SamsungTap recorded queue=${backTapTimestamps.size}")
            }
        } else {
            onDiagLog?.invoke(
                "Samsung non-Z axis: ax=${"%.2f".format(maxAbsX)} ay=${"%.2f".format(maxAbsY)} az=${"%.2f".format(maxAbsZ)}"
            )
        }
    }

    private fun checkDoubleTapTiming(timestampNs: Long): Int {
        while (backTapTimestamps.isNotEmpty()) {
            val first = backTapTimestamps.peekFirst() ?: break
            val ageMs = (timestampNs - first) / 1_000_000L
            if (timestampNs - first > MAX_TIME_GAP_NS) {
                backTapTimestamps.removeFirst()
                onDiagLog?.invoke("SamsungTap expired age=${ageMs}ms")
            } else {
                break
            }
        }
        if (backTapTimestamps.isEmpty()) return 0

        val newest = backTapTimestamps.peekLast() ?: return 0
        for (past in backTapTimestamps) {
            val gapMs = (newest - past) / 1_000_000L
            if (newest - past > MIN_TIME_GAP_NS) {
                onDiagLog?.invoke("Samsung DoubleTap HIT gap=${gapMs}ms")
                backTapTimestamps.clear()
                return 2
            }
        }
        return 1
    }

    private class SamsungDataQueue {
        private val data = FloatArray(QUEUE_CAPACITY)
        private var head = 0
        private var tail = 0
        private var elements = 0
        private var meanSquare = 0.0f

        var mean = 0.0f
            private set
        var std = 0.0f
            private set

        val isFull: Boolean
            get() = elements == QUEUE_CAPACITY

        val newestElement: Float
            get() {
                if (elements == 0) return 0f
                val idx = if (tail == 0) QUEUE_CAPACITY - 1 else tail - 1
                return data[idx]
            }

        fun getValueAt(offset: Int): Float = data[(head + offset) % QUEUE_CAPACITY]

        fun dequeue(updateStats: Boolean = false) {
            if (elements == 0) return
            val value = data[head]
            head = (head + 1) % QUEUE_CAPACITY
            elements--
            if (updateStats) {
                if (elements == 0) {
                    mean = 0.0f
                    meanSquare = 0.0f
                    std = 0.0f
                } else {
                    val m = (mean * (elements + 1).toFloat() - value) / elements.toFloat()
                    val m2 = (meanSquare * (elements + 1).toFloat() - value * value) / elements.toFloat()
                    mean = m
                    meanSquare = m2
                    std = sqrt((m2 - m * m).coerceAtLeast(0.0f).toDouble()).toFloat()
                }
            }
        }

        fun enqueue(value: Float, updateStats: Boolean = false) {
            data[tail] = value
            tail = (tail + 1) % QUEUE_CAPACITY
            elements++
            if (updateStats) {
                val m = (mean * (elements - 1).toFloat() + value) / elements.toFloat()
                val m2 = (meanSquare * (elements - 1).toFloat() + value * value) / elements.toFloat()
                mean = m
                meanSquare = m2
                std = sqrt((m2 - m * m).coerceAtLeast(0.0f).toDouble()).toFloat()
            }
        }

        fun reset() {
            head = 0
            tail = 0
            elements = 0
            mean = 0.0f
            meanSquare = 0.0f
            std = 0.0f
        }
    }

    private class SamsungSensorProcessor(var sensitivity: Float) {
        val ax = SamsungDataQueue()
        val ay = SamsungDataQueue()
        val az = SamsungDataQueue()
        val gx = SamsungDataQueue()
        val gy = SamsungDataQueue()
        val gz = SamsungDataQueue()

        var hasMajorPeak = false
        var lastWindowEndNs = 0L
        var nMinorPeaks = 0
        var rightCount = 0
        var peakTimestampNs = 0L

        private var slope = 0.0
        private var gotAcc = false
        private var gotGyro = false
        private var syncTimeNs = 0L

        private val resampleAcc = Resample3C()
        private val resampleGyro = Resample3C()
        private val slopeAcc = Slope3C()
        private val slopeGyro = Slope3C()
        private val highpassAcc = Highpass3C().apply { setPara(0.05f) }
        private val highpassGyro = Highpass3C().apply { setPara(0.05f) }

        private val tmpSlope = Point3f()
        private val tmpHighpass = Point3f()

        fun reset() {
            syncTimeNs = 0L
            gotAcc = false
            gotGyro = false
            ax.reset()
            ay.reset()
            az.reset()
            gx.reset()
            gy.reset()
            gz.reset()
            hasMajorPeak = false
            nMinorPeaks = 0
            slope = 0.0
            rightCount = 0
            lastWindowEndNs = 0L
            peakTimestampNs = 0L
        }

        fun updateData(type: Int, x: Float, y: Float, z: Float, timestampNs: Long, intervalNs: Long) {
            when (type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    gotAcc = true
                    if (syncTimeNs == 0L) {
                        resampleAcc.init(x, y, z, timestampNs, intervalNs)
                    }
                    if (!gotGyro) return
                }
                TYPE_SYNTH_GYRO -> {
                    gotGyro = true
                    if (syncTimeNs == 0L) {
                        resampleGyro.init(x, y, z, timestampNs, intervalNs)
                    }
                    if (!gotAcc) return
                }
                else -> return
            }

            if (syncTimeNs == 0L) {
                syncTimeNs = timestampNs
                resampleAcc.setSyncTime(syncTimeNs)
                resampleGyro.setSyncTime(syncTimeNs)
                slopeAcc.init(resampleAcc.point)
                slopeGyro.init(resampleGyro.point)
                val zero = Point3f(0f, 0f, 0f)
                highpassAcc.init(zero)
                highpassGyro.init(zero)
                return
            }

            if (type == Sensor.TYPE_ACCELEROMETER) {
                while (resampleAcc.update(x, y, z, timestampNs)) {
                    processAccAndKeySignal()
                    if (rightCount >= RIGHT_COUNT_FRAMES) {
                        return
                    }
                }
            } else {
                while (resampleGyro.update(x, y, z, timestampNs)) {
                    processGyro()
                }
            }
        }

        private fun checkIfPeak(curr: Float, prev: Float): Boolean {
            val multiplied = prev * sensitivity
            val diff = (curr - multiplied).toDouble()
            if (slope * diff < 0.0) {
                slope = diff
                val threshold = az.mean + az.std * 3.0f
                return multiplied >= threshold && multiplied >= 0.05f
            }
            slope = diff
            return false
        }

        private fun processAccAndKeySignal() {
            val interval = resampleAcc.interval.coerceAtLeast(1L)
            val scale = SAMPLING_INTERVAL_NS.toFloat() / interval.toFloat()
            slopeAcc.update(resampleAcc.point, scale, tmpSlope)
            highpassAcc.update(tmpSlope, tmpHighpass)

            val prevZ = az.newestElement
            if (az.isFull) {
                ax.dequeue(false)
                ay.dequeue(false)
                az.dequeue(true)
            }
            ax.enqueue(tmpHighpass.x, false)
            ay.enqueue(tmpHighpass.y, false)
            az.enqueue(tmpHighpass.z, true)

            if (hasMajorPeak) {
                rightCount++
                if (checkIfPeak(tmpHighpass.z, prevZ)) {
                    nMinorPeaks++
                }
            } else {
                val isPeak = checkIfPeak(tmpHighpass.z, prevZ)
                hasMajorPeak = isPeak
                if (isPeak) {
                    peakTimestampNs = resampleAcc.tResampledLast
                }
            }
        }

        private fun processGyro() {
            val interval = resampleGyro.interval.coerceAtLeast(1L)
            val scale = SAMPLING_INTERVAL_NS.toFloat() / interval.toFloat()
            slopeGyro.update(resampleGyro.point, scale, tmpSlope)
            highpassGyro.update(tmpSlope, tmpHighpass)

            if (gz.isFull) {
                gx.dequeue(false)
                gy.dequeue(false)
                gz.dequeue(false)
            }
            gx.enqueue(tmpHighpass.x, false)
            gy.enqueue(tmpHighpass.y, false)
            gz.enqueue(tmpHighpass.z, false)
        }
    }

    companion object {
        const val TAP_CLASS_SAMSUNG = 0
        private const val TYPE_SYNTH_GYRO = 4
        private const val QUEUE_CAPACITY = 400
        private const val FEATURE_WINDOW = 48
        private const val FEATURE_START_OFFSET = 352
        private const val FEATURE_COUNT = 288
        private const val RIGHT_COUNT_FRAMES = 24
        private const val SAMPLING_INTERVAL_NS = 2_500_000L
        private const val MIN_WINDOW_GAP_NS = 120_000_000L
        private const val MIN_TIME_GAP_NS = 100_000_000L
        private const val MAX_TIME_GAP_NS = 600_000_000L
        private const val RESUME_GAP_RESET_NS = 500_000_000L

        val SAMSUNG_SENSITIVITY_VALUES = floatArrayOf(
            0.25f, 0.4f, 0.55f, 0.7f, 0.85f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f
        )

        private fun resolveSensitivity(level: Int): Float {
            val idx = level.coerceIn(0, SAMSUNG_SENSITIVITY_VALUES.lastIndex)
            return SAMSUNG_SENSITIVITY_VALUES[idx]
        }
    }
}
