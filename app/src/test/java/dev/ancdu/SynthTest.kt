package dev.ancdu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10

class SynthTest {
    private val sr = 48000

    private fun peakDb(b: FloatArray) = 20 * log10(b.maxOf { abs(it) }.toDouble())

    /** Длина (сэмплы при 48 кГц) и пик (dBFS) каждого рецепта. */
    private val expect = mapOf(
        "tick" to (720 to -24.0),                  // 15 мс
        "arm" to (1920 + 1440 + 1920 to -20.0),    // 40 + 30 + 40 мс
        "count" to (1440 to -24.0),                // 30 мс
        "commit" to (3360 to -16.0),               // 70 мс
        "done" to (2640 + 2880 - 240 to -24.0),    // 55 + 60 мс, перекрытие 5 мс
        "refuse" to (2880 + 1920 + 4320 to -20.0), // 60 + 40 + 90 мс
    )

    @Test fun lengthsAndPeaks() {
        assertEquals(expect.keys, Synth.NAMES.toSet())
        for ((name, e) in expect) {
            val b = Synth.render(name, sr)
            assertEquals(name, e.first, b.size)
            assertEquals(name, e.second, peakDb(b), 0.1)
        }
    }

    /** Начало (кроме commit: шумовой щелчок защёлки начинается сразу) и конец — у нуля. */
    @Test fun edgesNearZero() {
        for (name in Synth.NAMES) {
            val b = Synth.render(name, sr)
            if (name != "commit") assertEquals(name, 0f, b.first(), 1e-6f)
            assertEquals(name, 0f, b.last(), 1e-6f)
        }
        // Щелчок commit — не громче своей нормировки −22 dBFS (с запасом на сумму с телом).
        assertTrue(abs(Synth.render("commit", sr).first()) < 0.1f)
    }

    @Test fun deterministic() {
        for (name in Synth.NAMES) assertArrayEquals(name, Synth.render(name, sr), Synth.render(name, sr), 0f)
    }

    @Test fun otherRateScalesLength() {
        assertEquals(662, Synth.render("tick", 44100).size)   // round(661.5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownName() { Synth.render("nope", sr) }

    @Test fun wavHeader() {
        val samples = floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f, 2f)
        val w = Synth.wav(samples, sr)
        assertEquals(44 + 2 * samples.size, w.size)
        val b = ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(at: Int) = String(w, at, 4, Charsets.US_ASCII)
        assertEquals("RIFF", tag(0))
        assertEquals(w.size - 8, b.getInt(4))
        assertEquals("WAVE", tag(8))
        assertEquals("fmt ", tag(12))
        assertEquals(16, b.getInt(16))
        assertEquals(1, b.getShort(20).toInt())        // PCM
        assertEquals(1, b.getShort(22).toInt())        // моно
        assertEquals(sr, b.getInt(24))
        assertEquals(sr * 2, b.getInt(28))             // байт в секунду
        assertEquals(2, b.getShort(32).toInt())        // выравнивание блока
        assertEquals(16, b.getShort(34).toInt())       // бит на сэмпл
        assertEquals("data", tag(36))
        assertEquals(2 * samples.size, b.getInt(40))
        val pcm = (0 until samples.size).map { b.getShort(44 + 2 * it).toInt() }
        assertEquals(listOf(0, 16384, -16383, 32767, -32767, 32767), pcm)   // округление к +∞, срез ±1
    }
}
