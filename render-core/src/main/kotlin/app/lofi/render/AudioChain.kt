package app.lofi.render

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh

/**
 * How "worn" the recorded sound is. Chosen by the clarity level.
 *
 * Design rule learned the hard way: the sound must stay intelligible. An earlier version added tape hiss at about
 * -40 dBFS with no level control, which on a quiet phone microphone drowned the speech. Now the signal is levelled
 * first (AGC) and any hiss is added AFTER it at a level that is barely a tint.
 */
enum class AudioProfile(
    val id: String,
    val highpassHz: Float,
    val lowpassHz: Float,
    /** Peak amplitude (0..1) of the white noise added after levelling. */
    val hiss: Float,
    val drive: Float,
    /** Level the signal towards a healthy loudness before colouring it. */
    val agc: Boolean,
) {
    /** Small camcorder microphone: no rumble or sparkle, a hint of saturation, a whisper of hiss. */
    LOFI("lofi", 150f, 7500f, 0.0015f, 0.5f, true),
    /** Slightly band-limited, levelled, no added noise. */
    LIGHT("light", 90f, 12000f, 0f, 0f, true),
    /** Untouched microphone signal. */
    CLEAN("clean", 0f, 0f, 0f, 0f, false);

    companion object {
        fun fromId(id: String): AudioProfile = values().firstOrNull { it.id == id } ?: CLEAN
    }
}

/**
 * Streaming mono 16-bit PCM processor: one-pole high-pass, two-pole low-pass, AGC (with a noise gate so it never
 * pumps up silence), soft saturation, soft limiter, then a little hiss. Not thread-safe.
 */
class AudioChain(private val sampleRate: Int, private val profile: AudioProfile, seed: Int = 12345) {
    private val hpA: Float
    private val lpA: Float
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f
    private var lp1 = 0f
    private var lp2 = 0f
    private var rng = seed

    // AGC state
    private var env = 0f
    private var gain = 1f
    private val envAttack = coef(0.005f)
    private val envRelease = coef(0.400f)
    private val gainDown = coef(0.030f)   // reduce gain quickly when it gets loud
    private val gainUp = coef(0.600f)     // raise gain slowly when it is quiet

    /** Level statistics since the last [takeStats]; for the debug log. */
    private var peakIn = 0f
    private var peakOut = 0f
    private var sumSqOut = 0.0
    private var count = 0

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

    /** Current AGC gain (1 = unchanged). */
    val currentGain: Float get() = gain

    fun process(buf: ShortArray, n: Int) {
        if (isBypass) {
            for (i in 0 until n) trackStats(buf[i] / 32768f, buf[i] / 32768f)
            return
        }
        val driveNorm = if (profile.drive > 0f) tanh(profile.drive) else 1f
        for (i in 0 until n) {
            val input = buf[i] / 32768f
            var x = input
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
            if (profile.agc) x *= level(x)
            if (profile.drive > 0f) x = tanh(profile.drive * x) / driveNorm
            x = softLimit(x)
            if (profile.hiss > 0f) x += noise() * profile.hiss
            x = x.coerceIn(-1f, 1f)
            buf[i] = (x * 32767f).toInt().toShort()
            trackStats(input, x)
        }
    }

    /** Returns "inPeak outPeak outRms gain" for the log and resets the counters. */
    fun takeStats(): String {
        val rms = if (count > 0) Math.sqrt(sumSqOut / count) else 0.0
        val s = "inPeak=%.3f outPeak=%.3f outRms=%.3f gain=%.2f".format(java.util.Locale.US, peakIn, peakOut, rms, gain)
        peakIn = 0f; peakOut = 0f; sumSqOut = 0.0; count = 0
        return s
    }

    private fun trackStats(input: Float, out: Float) {
        if (abs(input) > peakIn) peakIn = abs(input)
        if (abs(out) > peakOut) peakOut = abs(out)
        sumSqOut += (out * out).toDouble()
        count++
    }

    /** Gain to apply to sample [x]; updates the envelope follower and the smoothed gain. */
    private fun level(x: Float): Float {
        val a = abs(x)
        env += (a - env) * (if (a > env) envAttack else envRelease)
        val wanted = when {
            env < GATE -> gain                                   // too quiet to be speech: hold, do not pump the noise floor
            else -> (TARGET_PEAK / env).coerceIn(MIN_GAIN, MAX_GAIN)
        }
        gain += (wanted - gain) * (if (wanted < gain) gainDown else gainUp)
        return gain
    }

    /** Smoothly bends anything above 0.8 towards 0.99 instead of hard-clipping. */
    private fun softLimit(x: Float): Float {
        val ax = abs(x)
        if (ax <= 0.8f) return x
        val limited = 0.8f + 0.19f * tanh((ax - 0.8f) / 0.19f)
        return if (x < 0f) -limited else limited
    }

    /** Uniform noise in [-1, 1) from a small LCG (deterministic per seed). */
    private fun noise(): Float {
        rng = rng * 1664525 + 1013904223
        return (rng ushr 8) / 8388608f - 1f
    }

    private fun coef(seconds: Float): Float = 1f - exp(-1f / (seconds * sampleRate))

    private companion object {
        const val TARGET_PEAK = 0.35f   // about -9 dBFS peaks, roughly -20 dBFS RMS for speech
        const val MAX_GAIN = 10f        // +20 dB
        const val MIN_GAIN = 0.7f
        const val GATE = 0.004f         // about -48 dBFS
    }
}
