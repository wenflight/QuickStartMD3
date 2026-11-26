package com.quickstart.md3.backtap

import android.content.Context
import android.os.Build
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.channels.FileChannel

interface TapClassifier {
    fun predictClass(features: FloatArray): Int
    fun close()
}

/**
 * Loads an Android 12 Columbus 7-class TFLite model ([1, 300, 1, 1] -> [1, 7])
 * and reuses pre-allocated input/output buffers so inference allocates zero objects.
 */
class TfliteBackTapClassifier(
    context: Context,
    private val assetPath: String,
    useLowPowerDelegate: Boolean
) : TapClassifier {

    private var nnApiDelegate: NnApiDelegate? = null
    private var interpreter: Interpreter? = null

    private val inputBuffer = Array(1) { Array(300) { Array(1) { FloatArray(1) } } }
    private val outputBuffer = Array(1) { FloatArray(NUM_CLASSES) }

    init {
        interpreter = createInterpreter(context, useLowPowerDelegate)
    }

    private fun createInterpreter(context: Context, useLowPowerDelegate: Boolean): Interpreter? {
        val modelBuffer = runCatching {
            context.assets.openFd(assetPath).use { afd ->
                FileInputStream(afd.fileDescriptor).channel.use { channel ->
                    channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
                }
            }
        }.onFailure {
            Log.w(TAG, "Failed to mmap model $assetPath", it)
        }.getOrNull() ?: return null

        if (useLowPowerDelegate && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val delegateAttempt = runCatching {
                val delOpts = NnApiDelegate.Options().apply {
                    setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_LOW_POWER)
                    setUseNnapiCpu(true)
                }
                val del = NnApiDelegate(delOpts)
                val opts = Interpreter.Options().apply {
                    setNumThreads(1)
                    addDelegate(del)
                }
                nnApiDelegate = del
                Interpreter(modelBuffer, opts)
            }
            if (delegateAttempt.isSuccess) {
                return delegateAttempt.getOrNull()
            }
            Log.w(TAG, "NNAPI delegate unavailable for $assetPath, falling back to CPU", delegateAttempt.exceptionOrNull())
            runCatching { nnApiDelegate?.close() }
            nnApiDelegate = null
        }

        return runCatching {
            val cpuOpts = Interpreter.Options().apply {
                setNumThreads(1)
            }
            Interpreter(modelBuffer, cpuOpts)
        }.onFailure {
            Log.w(TAG, "Failed to create CPU interpreter for $assetPath", it)
        }.getOrNull()
    }

    override fun predictClass(features: FloatArray): Int {
        val interp = interpreter ?: return -1
        if (features.size < 300) return -1

        for (i in 0 until 300) {
            inputBuffer[0][i][0][0] = features[i]
        }

        return runCatching {
            interp.run(inputBuffer, outputBuffer)
            val probs = outputBuffer[0]
            var bestIdx = 0
            var bestVal = probs[0]
            for (i in 1 until NUM_CLASSES) {
                if (probs[i] > bestVal) {
                    bestVal = probs[i]
                    bestIdx = i
                }
            }
            bestIdx
        }.getOrDefault(-1)
    }

    override fun close() {
        runCatching { interpreter?.close() }
        interpreter = null
        runCatching { nnApiDelegate?.close() }
        nnApiDelegate = null
    }

    companion object {
        private const val TAG = "BackTapClassifier"
        private const val NUM_CLASSES = 7
    }
}
