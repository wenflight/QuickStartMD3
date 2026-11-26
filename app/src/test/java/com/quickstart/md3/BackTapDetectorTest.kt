package com.quickstart.md3

import android.hardware.Sensor
import com.quickstart.md3.backtap.BackTapDetector
import com.quickstart.md3.backtap.FloatRingBuffer
import com.quickstart.md3.backtap.TapClassifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackTapDetectorTest {

    @Test
    fun ringBufferWrapsTrimsAndCopiesWindow() {
        val buf = FloatRingBuffer(5)
        for (i in 1..7) {
            buf.addLast(i.toFloat())
        }
        assertEquals(5, buf.size)

        val all = FloatArray(5)
        buf.copyWindow(0, 5, all, 0)
        assertArrayEquals(floatArrayOf(3f, 4f, 5f, 6f, 7f), all, 1e-6f)

        buf.trimTo(3)
        assertEquals(3, buf.size)
        val trimmed = FloatArray(3)
        buf.copyWindow(0, 3, trimmed, 0)
        assertArrayEquals(floatArrayOf(5f, 6f, 7f), trimmed, 1e-6f)
    }

    @Test
    fun doubleTapTimingWindowRejectsTooCloseOrTooFarAndAcceptsValidWindow() {
        var predictBack = false
        var predictCount = 0
        val fakeClassifier = object : TapClassifier {
            override fun predictClass(features: FloatArray): Int {
                predictCount++
                return if (predictBack) {
                    predictBack = false
                    BackTapDetector.TAP_CLASS_BACK
                } else {
                    BackTapDetector.TAP_CLASS_OTHERS
                }
            }

            override fun close() = Unit
        }

        val detector = BackTapDetector(fakeClassifier)
        detector.configure(sensitivityLevel = 10)

        var ts = 1_000_000_000L
        val stepNs = 2_500_000L

        fun feedIdle(samples: Int): Boolean {
            var triggered = false
            repeat(samples) {
                if (detector.onSensorSample(Sensor.TYPE_ACCELEROMETER, 0f, 0f, 9.8f, ts)) {
                    triggered = true
                }
                if (detector.onSensorSample(Sensor.TYPE_GYROSCOPE, 0f, 0f, 0f, ts)) {
                    triggered = true
                }
                ts += stepNs
            }
            return triggered
        }

        // Feed baseline samples so resamplers & 64-sample ring buffers warm up.
        feedIdle(80)

        fun feedTapAndDrain(zSpike: Float, tailIdleSamples: Int): Boolean {
            var triggered = false
            predictBack = true
            val wave = floatArrayOf(0f, zSpike, zSpike * 1.5f, zSpike * 0.4f, 0f, 0f)
            for (v in wave) {
                if (detector.onSensorSample(Sensor.TYPE_ACCELEROMETER, 0f, 0f, 9.8f + v, ts)) {
                    triggered = true
                }
                if (detector.onSensorSample(Sensor.TYPE_GYROSCOPE, 0.2f, 0.2f, 0.2f, ts)) {
                    triggered = true
                }
                ts += stepNs
            }
            if (feedIdle(tailIdleSamples)) triggered = true
            return triggered
        }

        // First tap registers timestamp, should not trigger double tap yet.
        assertFalse(feedTapAndDrain(8.0f, tailIdleSamples = 60))
        assertEquals(1, predictCount)

        // Second tap 220ms later (66 samples * 2.5ms = 165ms + 55ms idle) -> triggers double tap.
        feedIdle(22)
        assertTrue(feedTapAndDrain(8.0f, tailIdleSamples = 60))
        assertEquals(2, predictCount)

        // Wait 600ms (>500ms max gap) so window expires, single tap again should NOT trigger.
        ts += 600_000_000L
        feedIdle(80)
        assertFalse(feedTapAndDrain(8.0f, tailIdleSamples = 60))
        assertEquals(3, predictCount)
    }

    @Test
    fun flatOnTableDetectionTracksInclination() {
        val dummyClassifier = object : TapClassifier {
            override fun predictClass(features: FloatArray): Int = BackTapDetector.TAP_CLASS_OTHERS
            override fun close() = Unit
        }
        val detector = BackTapDetector(dummyClassifier)

        // Flat face up: z ~= 9.8
        detector.onSensorSample(Sensor.TYPE_ACCELEROMETER, 0f, 0f, 9.8f, 1_000_000_000L)
        assertTrue(detector.isFlatOnTable)

        // Upright held in hand 600ms later: y ~= 9.8, z ~= 0
        detector.onSensorSample(Sensor.TYPE_ACCELEROMETER, 0f, 9.8f, 0.2f, 1_600_000_000L)
        assertFalse(detector.isFlatOnTable)
    }
}
