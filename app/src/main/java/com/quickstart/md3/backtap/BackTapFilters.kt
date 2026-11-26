package com.quickstart.md3.backtap

/**
 * 3-axis mutable vector reused across the 400Hz sensor pipeline to avoid allocations.
 */
class Point3f(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f) {
    fun set(nx: Float, ny: Float, nz: Float) {
        x = nx
        y = ny
        z = nz
    }
}

class Highpass1C {
    private var para = 1.0f
    private var xLast = 0.0f
    private var yLast = 0.0f

    fun init(value: Float) {
        xLast = value
        yLast = value
    }

    fun setPara(para: Float) {
        this.para = para
    }

    fun update(value: Float): Float {
        val newYLast = (value - xLast) * para + yLast * para
        yLast = newYLast
        xLast = value
        return newYLast
    }
}

class Highpass3C {
    private val xFilter = Highpass1C()
    private val yFilter = Highpass1C()
    private val zFilter = Highpass1C()

    fun init(point: Point3f) {
        xFilter.init(point.x)
        yFilter.init(point.y)
        zFilter.init(point.z)
    }

    fun setPara(para: Float) {
        xFilter.setPara(para)
        yFilter.setPara(para)
        zFilter.setPara(para)
    }

    fun update(input: Point3f, out: Point3f) {
        out.set(
            xFilter.update(input.x),
            yFilter.update(input.y),
            zFilter.update(input.z)
        )
    }
}

class Lowpass1C {
    private var para = 1.0f
    private var xLast = 0.0f

    fun init(value: Float) {
        xLast = value
    }

    fun setPara(para: Float) {
        this.para = para
    }

    fun update(value: Float): Float {
        val newXLast = value * para + (1.0f - para) * xLast
        xLast = newXLast
        return newXLast
    }
}

class Lowpass3C {
    private val xFilter = Lowpass1C()
    private val yFilter = Lowpass1C()
    private val zFilter = Lowpass1C()

    fun init(point: Point3f) {
        xFilter.init(point.x)
        yFilter.init(point.y)
        zFilter.init(point.z)
    }

    fun setPara(para: Float) {
        xFilter.setPara(para)
        yFilter.setPara(para)
        zFilter.setPara(para)
    }

    fun update(input: Point3f, out: Point3f) {
        out.set(
            xFilter.update(input.x),
            yFilter.update(input.y),
            zFilter.update(input.z)
        )
    }
}

class Slope1C {
    private var xRawLast = 0.0f

    fun init(x: Float) {
        xRawLast = x
    }

    fun update(value: Float, scale: Float): Float {
        val scaled = value * scale
        val delta = scaled - xRawLast
        xRawLast = scaled
        return delta
    }
}

class Slope3C {
    private val slopeX = Slope1C()
    private val slopeY = Slope1C()
    private val slopeZ = Slope1C()

    fun init(point: Point3f) {
        slopeX.init(point.x)
        slopeY.init(point.y)
        slopeZ.init(point.z)
    }

    fun update(input: Point3f, scale: Float, out: Point3f) {
        out.set(
            slopeX.update(input.x, scale),
            slopeY.update(input.y, scale),
            slopeZ.update(input.z, scale)
        )
    }
}

open class Resample1C {
    var interval: Long = 0L
        protected set
    protected var tRawLast: Long = 0L
    var tResampledLast: Long = 0L
        protected set
    protected var xRawLast: Float = 0f
    protected var xResampledThis: Float = 0f

    fun init(x: Float, t: Long, intervalNs: Long) {
        xRawLast = x
        tRawLast = t
        xResampledThis = x
        tResampledLast = t
        interval = intervalNs
    }

    fun setSyncTime(syncTimeNs: Long) {
        tResampledLast = syncTimeNs
    }
}

class Resample3C : Resample1C() {
    private var yRawLast: Float = 0f
    private var yResampledThis: Float = 0f
    private var zRawLast: Float = 0f
    private var zResampledThis: Float = 0f

    val point = Point3f()

    fun init(x: Float, y: Float, z: Float, t: Long, intervalNs: Long) {
        super.init(x, t, intervalNs)
        yRawLast = y
        zRawLast = z
        yResampledThis = y
        zResampledThis = z
        point.set(x, y, z)
    }

    fun update(x: Float, y: Float, z: Float, t: Long): Boolean {
        val tLast = tRawLast
        if (t == tLast) return false

        val targetT = (if (interval > 0L) interval else t - tLast) + tResampledLast
        if (t < targetT) {
            tRawLast = t
            xRawLast = x
            yRawLast = y
            zRawLast = z
            return false
        }

        val tLastSecond = tRawLast
        val denom = (t - tLastSecond).toFloat()
        if (denom == 0f) return false

        val ratio = (targetT - tLastSecond).toFloat() / denom
        xResampledThis = xRawLast + (x - xRawLast) * ratio
        yResampledThis = yRawLast + (y - yRawLast) * ratio
        zResampledThis = zRawLast + (z - zRawLast) * ratio
        tResampledLast = targetT
        point.set(xResampledThis, yResampledThis, zResampledThis)

        if (tLastSecond < targetT) {
            tRawLast = t
            xRawLast = x
            yRawLast = y
            zRawLast = z
        }
        return true
    }
}

class PeakDetector {
    private var amplitudeMajorPeak = 0.0f
    private var amplitudeReference = 0.0f
    var idMajorPeak: Int = -1
        private set
    private var minNoiseTolerate = 0.0f
    private var windowSize = 0

    fun setMinNoiseTolerate(noise: Float) {
        minNoiseTolerate = noise
    }

    fun setWindowSize(size: Int) {
        windowSize = size
    }

    fun reset() {
        amplitudeMajorPeak = 0.0f
        amplitudeReference = 0.0f
        idMajorPeak = -1
    }

    fun update(value: Float) {
        val adjustedPeak = idMajorPeak - 1
        idMajorPeak = adjustedPeak
        if (adjustedPeak < 0) {
            amplitudeMajorPeak = 0.0f
        }

        var noiseTolerate = minNoiseTolerate
        val scaledMajorPeak = amplitudeMajorPeak / 5.0f
        if (scaledMajorPeak > minNoiseTolerate) {
            noiseTolerate = scaledMajorPeak
        }

        val adjustedReference = amplitudeReference - value
        if (adjustedReference >= noiseTolerate) {
            amplitudeReference = value
        } else if (adjustedReference < 0.0f && value > noiseTolerate) {
            amplitudeReference = value
            if (value > amplitudeMajorPeak) {
                idMajorPeak = windowSize - 1
                amplitudeMajorPeak = value
            }
        }
    }
}

/**
 * Fixed-capacity primitive float ring buffer that avoids ArrayDeque<Float> boxing at 400Hz.
 */
class FloatRingBuffer(private val capacity: Int) {
    private val data = FloatArray(capacity)
    private var head = 0
    var size: Int = 0
        private set

    fun clear() {
        head = 0
        size = 0
    }

    fun addLast(value: Float) {
        if (size == capacity) {
            data[head] = value
            head = (head + 1) % capacity
        } else {
            val tail = (head + size) % capacity
            data[tail] = value
            size++
        }
    }

    fun trimTo(maxSize: Int) {
        val target = maxSize.coerceAtLeast(0)
        if (size > target) {
            val drop = size - target
            head = (head + drop) % capacity
            size = target
        }
    }

    fun copyWindow(offset: Int, count: Int, dest: FloatArray, destOffset: Int) {
        if (offset < 0 || count < 0 || offset + count > size) return
        var idx = (head + offset) % capacity
        for (i in 0 until count) {
            dest[destOffset + i] = data[idx]
            idx++
            if (idx == capacity) idx = 0
        }
    }
}
