package app.lofi.render

import kotlin.math.PI
import kotlin.math.tanh

/** How "worn" the recorded sound is. Chosen by the clarity level. */
enum class AudioProfile(val id: String, val highpassHz: Float, val lowpassHz: Float, val hiss: Float, val drive: Float) {
    /** Small tape camcorder microphone: no bass or treble, soft clipping, tape hiss. */
    LOFI("lofi", 220f, 6000f, 0.010f, 1.2f),
    LIGHT("light", 100f, 12000f, 0.003f, 0f),
    CLEAN("clean", 0f, 0f, 0f, 0f);

    companion object {
        fun fromId(id: String): AudioProfile = values().firstOrNull { it.id == id } ?: CLEAN
    }
}

/** Streaming mono 16-bit PCM filter: one-pole high-pass, two-pole low-pass, soft clip, hiss. Not thread-safe. */
class AudioChain(sampleRate: Int, private val profile: AudioProfile, seed: Int = 12345) {
    private val hpA: Float
    private val lpA: Float
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f
    private var lp1 = 0f
    private var lp2 = 0f
    private var rng = seed

    init {
        val dt = 1f / sampleRate
        hpA = if (profile.highpassHz > 0f) {
            val rc = 1f / (2f * PI.toFloat() * profile.highpassHz)
            rc / (rc + dt)
        } else 0f
        lpA = if (profile.lowpassHz > 0f) {
            val rc = 1f / (2f * PI.toFloat() * profile.lowpassHz)
            dt / (rc + dt)
        } else 1f
    }

    val isBypass: Boolean get() = profile == AudioProfile.CLEAN

    fun process(buf: ShortArray, n: Int) {
        if (isBypass) return
        val driveNorm = if (profile.drive > 0f) tanh(profile.drive) else 1f
        for (i in 0 until n) {
            var x = buf[i] / 32768f
            if (profile.highpassHz > 0f) {
                val y = hpA * (hpPrevOut + x - hpPrevIn)
                hpPrevIn = x
                hpPrevOut = y
                x = y
            }
            if (profile.lowpassHz > 0f) {
                lp1 += lpA * (x - lp1)
                lp2 += lpA * (lp1 - lp2)
                x = lp2
            }
            if (profile.drive > 0f) x = tanh(profile.drive * x) / driveNorm
            if (profile.hiss > 0f) x += noise() * profile.hiss
            buf[i] = (x.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
    }

    /** Uniform noise in [-1, 1) from a small LCG (deterministic per seed). */
    private fun noise(): Float {
        rng = rng * 1664525 + 1013904223
        return (rng ushr 8) / 8388608f - 1f
    }
}
