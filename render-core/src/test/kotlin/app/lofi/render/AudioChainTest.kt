package app.lofi.render

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioChainTest {
    private val rate = 44100

    private fun sine(hz: Double, amp: Double = 0.5, seconds: Int = 1): ShortArray =
        ShortArray(rate * seconds) { (sin(2 * PI * hz * it / rate) * amp * 32767).toInt().toShort() }

    /** RMS of the last half second (filters and AGC settled). */
    private fun rmsTail(b: ShortArray): Double {
        var sum = 0.0
        val from = b.size - rate / 2
        for (i in from until b.size) sum += (b[i] / 32768.0) * (b[i] / 32768.0)
        return sqrt(sum / (b.size - from))
    }

    private fun run(profile: AudioProfile, input: ShortArray, seed: Int = 1): ShortArray {
        val out = input.copyOf()
        val chain = AudioChain(rate, profile, seed)
        // feed in realistic 2048-sample chunks
        var i = 0
        val chunk = ShortArray(2048)
        while (i < out.size) {
            val n = minOf(2048, out.size - i)
            System.arraycopy(out, i, chunk, 0, n)
            chain.process(chunk, n)
            System.arraycopy(chunk, 0, out, i, n)
            i += n
        }
        return out
    }

    /** Amplitude of one frequency in the last second (Goertzel). */
    private fun amplitudeAt(b: ShortArray, hz: Double): Double {
        val n = rate
        val from = b.size - n
        val w = 2 * PI * hz / rate
        val coeff = 2 * Math.cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until n) {
            val s0 = b[from + i] / 32768.0 + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        return 2 * sqrt(power) / n
    }

    /**
     * Level of [hz] relative to a 1 kHz tone played at the same time. Measuring two tones together keeps the AGC out
     * of it: both get the same gain, so the ratio is the filter's own response.
     */
    private fun relativeToMid(profile: AudioProfile, hz: Double): Double {
        val a = sine(1000.0, amp = 0.15, seconds = 3)
        val b = sine(hz, amp = 0.15, seconds = 3)
        val mix = ShortArray(a.size) { (a[it] + b[it]).toShort() }
        val out = run(profile, mix)
        return amplitudeAt(out, hz) / amplitudeAt(out, 1000.0)
    }

    @Test fun cleanIsABitExactBypass() {
        val input = sine(440.0)
        val out = input.copyOf()
        val chain = AudioChain(rate, AudioProfile.CLEAN)
        chain.process(out, out.size)
        assertTrue(chain.isBypass)
        assertArrayEquals(input, out)
    }

    @Test fun lofiRemovesRumbleAndSparkleButKeepsTheMidrange() {
        assertTrue("40 Hz ${relativeToMid(AudioProfile.LOFI, 40.0)}", relativeToMid(AudioProfile.LOFI, 40.0) < 0.45)
        assertTrue("16 kHz ${relativeToMid(AudioProfile.LOFI, 16000.0)}", relativeToMid(AudioProfile.LOFI, 16000.0) < 0.45)
        assertTrue("400 Hz ${relativeToMid(AudioProfile.LOFI, 400.0)}", relativeToMid(AudioProfile.LOFI, 400.0) in 0.7..1.3)
    }

    @Test fun lightKeepsMoreOfTheSpectrumThanLofi() {
        assertTrue(relativeToMid(AudioProfile.LIGHT, 10000.0) > relativeToMid(AudioProfile.LOFI, 10000.0))
        assertTrue(relativeToMid(AudioProfile.LIGHT, 60.0) > relativeToMid(AudioProfile.LOFI, 60.0))
    }

    @Test fun quietSpeechLevelSignalIsLiftedToAHealthyLevel() {
        val input = sine(500.0, amp = 0.02, seconds = 5)   // about -34 dBFS peak: a quiet phone microphone
        for (p in listOf(AudioProfile.LOFI, AudioProfile.LIGHT)) {
            val ratio = rmsTail(run(p, input)) / rmsTail(input)
            assertTrue("${p.id} gain $ratio", ratio > 4.0)
        }
    }

    @Test fun loudSignalIsNeverBoostedAndNeverClips() {
        val input = sine(500.0, amp = 0.9, seconds = 3)
        for (p in listOf(AudioProfile.LOFI, AudioProfile.LIGHT)) {
            val out = run(p, input)
            val ratio = rmsTail(out) / rmsTail(input)
            assertTrue("${p.id} ratio $ratio", ratio in 0.4..1.05)
            assertTrue("${p.id} peak", out.all { abs(it / 32768.0) <= 0.995 })
        }
    }

    @Test fun theNoiseFloorIsNotPumpedUp() {
        // a very quiet room: noise at about -66 dBFS must stay quiet (gate holds the AGC)
        val rnd = java.util.Random(3)
        val room = ShortArray(rate * 4) { ((rnd.nextDouble() * 2 - 1) * 0.0005 * 32767).toInt().toShort() }
        for (p in listOf(AudioProfile.LOFI, AudioProfile.LIGHT)) {
            assertTrue("${p.id} floor ${rmsTail(run(p, room))}", rmsTail(run(p, room)) < 0.003)
        }
    }

    @Test fun lofiHissIsOnlyATintOnSilence() {
        val r = rmsTail(run(AudioProfile.LOFI, ShortArray(rate * 2), seed = 7))
        assertTrue("hiss rms $r", r in 0.0003..0.0025)
        assertEquals(0.0, rmsTail(run(AudioProfile.LIGHT, ShortArray(rate * 2))), 1e-9)
    }

    @Test fun speechStaysFarAboveTheHiss() {
        val input = sine(500.0, amp = 0.02, seconds = 5)
        val out = run(AudioProfile.LOFI, input)
        val signal = rmsTail(out)
        val hiss = rmsTail(run(AudioProfile.LOFI, ShortArray(rate * 2), seed = 7))
        val snr = 20 * Math.log10(signal / hiss)
        assertTrue("SNR $snr dB", snr > 30.0)
    }

    @Test fun deterministicPerSeed() {
        val a = run(AudioProfile.LOFI, ShortArray(rate), seed = 7)
        val b = run(AudioProfile.LOFI, ShortArray(rate), seed = 7)
        val c = run(AudioProfile.LOFI, ShortArray(rate), seed = 8)
        assertArrayEquals(a, b)
        assertTrue(!a.contentEquals(c))
    }

    @Test fun statsReportLevels() {
        val chain = AudioChain(rate, AudioProfile.LOFI)
        val buf = sine(440.0, amp = 0.1)
        chain.process(buf, buf.size)
        val s = chain.takeStats()
        assertTrue(s, s.startsWith("inPeak=0.1"))
        assertTrue(chain.takeStats().contains("inPeak=0.000"))
    }

    @Test fun profileIdsRoundTrip() {
        for (p in AudioProfile.values()) assertEquals(p, AudioProfile.fromId(p.id))
        assertEquals(AudioProfile.CLEAN, AudioProfile.fromId("garbage"))
    }
}
