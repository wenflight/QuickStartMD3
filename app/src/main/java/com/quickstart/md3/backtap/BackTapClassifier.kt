package com.quickstart.md3.backtap

import android.content.Context
import android.os.Build
import android.util.Log
import com.quickstart.md3.BuildConfig
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.channels.FileChannel

data class TapPrediction(
    val classIndex: Int,
    val topScore: Float = 0f,
    val backScore: Float = 0f
)

interface TapClassifier {
    fun predict(features: FloatArray): TapPrediction =
        TapPrediction(classIndex = predictClass(features))

    fun predictClass(features: FloatArray): Int
    fun close()
}

/**
 * Loads either a Columbus 7-class model ([1, 300, 1, 1] -> [1, 7]) or a Samsung RegiStar
 * 6-class model ([288, 1] -> [1, 6]) and reuses pre-allocated buffers for zero-GC inference.
 */
class TfliteBackTapClassifier(
    context: Context,
    private val assetPath: String,
    useLowPowerDelegate: Boolean,
    private val isSamsung: Boolean = false
) : TapClassifier {

    private var nnApiDelegate: NnApiDelegate? = null
    private var interpreter: Interpreter? = null

    private val numClasses = if (isSamsung) SAMSUNG_NUM_CLASSES else COLUMBUS_NUM_CLASSES
    private val targetClassIdx = if (isSamsung) SamsungBackTapDetector.TAP_CLASS_SAMSUNG else BackTapDetector.TAP_CLASS_BACK
    private val columbusInput: Array<Array<Array<FloatArray>>>? =
        if (isSamsung) null else Array(1) { Array(COLUMBUS_FEATURES) { Array(1) { FloatArray(1) } } }
    private val samsungInput: Array<FloatArray>? =
        if (isSamsung) Array(SAMSUNG_FEATURES) { FloatArray(1) } else null
    private val outputBuffer = Array(1) { FloatArray(numClasses) }

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
                if (BuildConfig.DEBUG) {
                    Log.d(DIAG_TAG, "Model loaded: $assetPath delegate=NNAPI samsung=$isSamsung")
                }
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
        }.getOrNull().also { interp ->
            if (interp != null && BuildConfig.DEBUG) {
                Log.d(DIAG_TAG, "Model loaded: $assetPath delegate=CPU samsung=$isSamsung")
            }
        }
    }

    override fun predict(features: FloatArray): TapPrediction {
        val interp = interpreter ?: return TapPrediction(-1)
        val inputObj: Any = if (isSamsung) {
            val buf = samsungInput ?: return TapPrediction(-1)
            if (features.size < SAMSUNG_FEATURES) return TapPrediction(-1)
            for (i in 0 until SAMSUNG_FEATURES) {
                buf[i][0] = features[i]
            }
            buf
        } else {
            val buf = columbusInput ?: return TapPrediction(-1)
            if (features.size < COLUMBUS_FEATURES) return TapPrediction(-1)
            for (i in 0 until COLUMBUS_FEATURES) {
                buf[0][i][0][0] = features[i]
            }
            buf
        }

        return runCatching {
            interp.run(inputObj, outputBuffer)
            val probs = outputBuffer[0]
            var bestIdx = 0
            var bestVal = probs[0]
            for (i in 1 until numClasses) {
                if (probs[i] > bestVal) {
                    bestVal = probs[i]
                    bestIdx = i
                }
            }
            TapPrediction(
                classIndex = bestIdx,
                topScore = bestVal,
                backScore = probs[targetClassIdx]
            )
        }.onFailure {
            if (BuildConfig.DEBUG) Log.d(DIAG_TAG, "Inference failed for $assetPath", it)
        }.getOrDefault(TapPrediction(-1))
    }

    override fun predictClass(features: FloatArray): Int = predict(features).classIndex

    override fun close() {
        runCatching { interpreter?.close() }
        interpreter = null
        runCatching { nnApiDelegate?.close() }
        nnApiDelegate = null
    }

    companion object {
        private const val TAG = "BackTapClassifier"
        private const val DIAG_TAG = "BackTapDiag"
        private const val COLUMBUS_FEATURES = 300
        private const val SAMSUNG_FEATURES = 288
        private const val COLUMBUS_NUM_CLASSES = 7
        private const val SAMSUNG_NUM_CLASSES = 6
    }
}
