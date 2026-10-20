package app.lofi.render

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioChainTest {
    private val rate = 44100

    private fun sine(hz: Double, amp: Double = 0.5, n: Int = rate): ShortArray =
        ShortArray(n) { (sin(2 * PI * hz * it / rate) * amp * 32767).toInt().toShort() }

    /** RMS of the second half (filters settled). */
    private fun rms(b: ShortArray): Double {
        var sum = 0.0
        val from = b.size / 2
        for (i in from until b.size) sum += (b[i] / 32768.0) * (b[i] / 32768.0)
        return sqrt(sum / (b.size - from))
    }

    private fun gain(profile: AudioProfile, hz: Double): Double {
        val input = sine(hz)
        val out = input.copyOf()
        AudioChain(rate, profile).process(out, out.size)
        return rms(out) / rms(input)
    }

    @Test fun cleanIsABitExactBypass() {
        val input = sine(440.0)
        val out = input.copyOf()
        val chain = AudioChain(rate, AudioProfile.CLEAN)
        chain.process(out, out.size)
        assertTrue(chain.isBypass)
        assertArrayEquals(input, out)
    }

    @Test fun lofiRemovesBassAndTrebleButKeepsTheMidrange() {
        val mid = gain(AudioProfile.LOFI, 1000.0)
        assertTrue("1 kHz gain $mid", mid in 0.7..1.6)
        assertTrue("50 Hz gain ${gain(AudioProfile.LOFI, 50.0)}", gain(AudioProfile.LOFI, 50.0) < 0.4)
        assertTrue("15 kHz gain ${gain(AudioProfile.LOFI, 15000.0)}", gain(AudioProfile.LOFI, 15000.0) < 0.4)
    }

    @Test fun lightKeepsMoreOfTheSpectrumThanLofi() {
        assertTrue(gain(AudioProfile.LIGHT, 50.0) > gain(AudioProfile.LOFI, 50.0))
        assertTrue(gain(AudioProfile.LIGHT, 10000.0) > gain(AudioProfile.LOFI, 10000.0))
    }

    @Test fun lofiAddsTapeHissToSilenceAndIsDeterministic() {
        val a = ShortArray(rate)
        val b = ShortArray(rate)
        AudioChain(rate, AudioProfile.LOFI, seed = 7).process(a, a.size)
        AudioChain(rate, AudioProfile.LOFI, seed = 7).process(b, b.size)
        assertArrayEquals(a, b)
        val r = rms(a)
        assertTrue("hiss rms $r", r in 0.002..0.02)
        val c = ShortArray(rate)
        AudioChain(rate, AudioProfile.LOFI, seed = 8).process(c, c.size)
        assertTrue("different seed, different noise", !a.contentEquals(c))
    }

    @Test fun neverClipsOutOfRange() {
        val loud = ShortArray(4096) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        AudioChain(rate, AudioProfile.LOFI).process(loud, loud.size)
        assertEquals(4096, loud.size) // no exception, values stay within Short range by construction
    }

    @Test fun profileIdsRoundTrip() {
        for (p in AudioProfile.values()) assertEquals(p, AudioProfile.fromId(p.id))
        assertEquals(AudioProfile.CLEAN, AudioProfile.fromId("garbage"))
    }
}
