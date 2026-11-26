package com.quickstart.md3.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class KeySoundPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var soundPool: SoundPool? = null
    private val loadedSounds = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    // Pentatonic scale frequencies (C5..A6) for 0..14 keys
    private val noteFreqs = doubleArrayOf(
        523.25, 587.33, 659.25, 783.99, 880.00,
        1046.50, 1174.66, 1318.51, 1567.98, 1760.00,
        440.00, 493.88, 392.00, 349.23, 329.63
    )

    init {
        scope.launch { reloadCustomSounds() }
    }

    fun reloadCustomSounds() {
        soundPool?.release()
        loadedSounds.clear()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sp = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attrs).build()
        soundPool = sp

        val customDir = File(appContext.getExternalFilesDir(null), "KeyAudio")
        if (customDir.exists()) {
            val exts = listOf("mp3", "wav", "ogg", "m4a")
            for (i in 0..14) {
                val file = exts.firstNotNullOfOrNull { ext ->
                    File(customDir, "$i.$ext").takeIf { it.exists() }
                }
                if (file != null) {
                    val id = sp.load(file.absolutePath, 1)
                    if (id != 0) loadedSounds[i] = id
                }
            }
        }
    }

    fun customAudioDirPath(): String =
        File(appContext.getExternalFilesDir(null), "KeyAudio").apply { mkdirs() }.absolutePath

    fun playKey(keyIndex: Int) {
        val customId = loadedSounds[keyIndex]
        if (customId != null) {
            soundPool?.play(customId, 0.8f, 0.8f, 1, 0, 1.0f)
            return
        }
        val freq = noteFreqs[keyIndex.coerceIn(0, noteFreqs.lastIndex)]
        scope.launch { playSynthTone(freq) }
    }

    private fun playSynthTone(freqHz: Double) {
        val sampleRate = 22050
        val durationSec = 0.14
        val count = (sampleRate * durationSec).toInt()
        val samples = ShortArray(count)
        for (i in 0 until count) {
            val t = i.toDouble() / sampleRate
            val env = exp(-t * 22.0) * (1.0 - exp(-t * 300.0))
            val wave = sin(2.0 * PI * freqHz * t) + 0.35 * sin(4.0 * PI * freqHz * t)
            samples[i] = (wave * env * 9000.0).toInt().coerceIn(-32767, 32767).toShort()
        }
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(samples, 0, samples.size)
            track.play()
            Thread.sleep(160)
            track.stop()
            track.release()
        }
    }
}
