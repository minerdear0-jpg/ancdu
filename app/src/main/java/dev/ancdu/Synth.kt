package dev.ancdu

import java.io.ByteArrayOutputStream
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Звуки «консоли» (рецепты, утверждённые пользователем 2026-10-07). Чистый Kotlin: синтез в
 * память и WAV 16 бит моно; файлы пишет [Feedback]. Производные (tock, armRoot, ready) — те же
 * сэмплы с другой скоростью SoundPool ([Sound.rate]).
 */
object Synth {
    val NAMES = listOf("tick", "arm", "count", "commit", "done", "refuse")

    /** Зерно шума щелчка commit: звук одинаков при каждой генерации. */
    private const val SEED = 0x616E6364L

    fun render(name: String, sr: Int): FloatArray {
        val s = Gen(sr)
        return when (name) {
            "tick" -> norm(s.tone(15.0, 2400.0, 0.5, 3.0, H1), -24.0)
            "arm" -> norm(concat(s.tone(40.0, 880.0, 3.0, 12.0, H1), s.silence(30.0), s.tone(40.0, 660.0, 3.0, 12.0, H1)), -20.0)
            "count" -> norm(s.tone(30.0, 1000.0, 2.0, 8.0, listOf(1.0 to 1.0, 2.0 to 0.3)), -24.0)
            "commit" -> s.latch()
            "done" -> s.done()
            "refuse" -> {
                val h = listOf(1.0 to 1.0, 3.0 to 0.2)
                norm(concat(s.tone(60.0, 660.0, 2.0, 30.0, h), s.silence(40.0), s.tone(90.0, 440.0, 2.0, 40.0, h)), -20.0)
            }
            else -> throw IllegalArgumentException("unknown sound: $name")
        }
    }

    /** WAV RIFF: PCM 16 бит, моно, [sr] Гц. Сэмплы вне ±1 срезаются. */
    fun wav(samples: FloatArray, sr: Int): ByteArray {
        val data = 2 * samples.size
        val out = ByteArrayOutputStream(44 + data)
        fun i32(v: Int) { for (k in 0 until 4) out.write(v ushr (8 * k) and 0xFF) }
        fun i16(v: Int) { out.write(v and 0xFF); out.write(v ushr 8 and 0xFF) }
        fun tag(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        tag("RIFF"); i32(36 + data); tag("WAVE")
        tag("fmt "); i32(16); i16(1); i16(1); i32(sr); i32(sr * 2); i16(2); i16(16)
        tag("data"); i32(data)
        for (x in samples) i16((x.coerceIn(-1f, 1f) * 32767f).roundToInt())
        return out.toByteArray()
    }

    private val H1 = listOf(1.0 to 1.0)

    private fun dbToLin(db: Double) = 10.0.pow(db / 20)

    /** Пик — ровно [dbfs]. */
    private fun norm(b: FloatArray, dbfs: Double): FloatArray {
        var p = 0f
        for (v in b) p = max(p, abs(v))
        val g = if (p > 0) (dbToLin(dbfs) / p).toFloat() else 0f
        for (i in b.indices) b[i] *= g
        return b
    }

    private fun concat(vararg parts: FloatArray): FloatArray {
        val o = FloatArray(parts.sumOf { it.size })
        var k = 0
        for (p in parts) { p.copyInto(o, k); k += p.size }
        return o
    }

    private class Gen(val sr: Int) {
        fun n(ms: Double) = (ms * sr / 1000).roundToInt()

        fun silence(ms: Double) = FloatArray(n(ms))

        /**
         * Огибающая: атака — приподнятый косинус 0.5−0.5·cos(π·i/a), дальше спад exp(−(t−a)/τ),
         * последняя 1 мс — линейное затухание к нулю.
         */
        fun env(n: Int, attackMs: Double, tauMs: Double): DoubleArray {
            val a = max(1, (attackMs * sr / 1000).roundToInt())
            val fade = (0.001 * sr).roundToInt()
            return DoubleArray(n) { i ->
                val t = i.toDouble() / sr
                var g = if (i < a) 0.5 - 0.5 * cos(PI * i / a) else exp(-(t - a.toDouble() / sr) / (tauMs / 1000))
                if (i >= n - fade) g *= (n - 1 - i).toDouble() / fade
                g
            }
        }

        /** Сумма гармоник (множитель частоты, амплитуда) основной [f] под огибающей. */
        fun tone(ms: Double, f: Double, attack: Double, tau: Double, harmonics: List<Pair<Double, Double>>): FloatArray {
            val n = n(ms)
            val e = env(n, attack, tau)
            return FloatArray(n) { i ->
                val t = i.toDouble() / sr
                var s = 0.0
                for ((k, amp) in harmonics) s += amp * sin(2 * PI * f * k * t)
                (s * e[i]).toFloat()
            }
        }

        /** done: 880 Гц 55 мс, затем 1320 Гц 60 мс с линейным перекрытием 5 мс. */
        fun done(): FloatArray {
            val a = tone(55.0, 880.0, 2.0, 25.0, H1)
            val c = tone(60.0, 1320.0, 2.0, 25.0, H1)
            val x = n(5.0)
            val o = FloatArray(a.size + c.size - x)
            a.copyInto(o)
            for (i in c.indices) {
                val k = a.size - x + i
                val w = if (i < x) i.toFloat() / x else 1f
                o[k] = (if (i < x) o[k] * (1 - w) else 0f) + c[i] * w
            }
            return norm(o, -24.0)
        }

        /**
         * commit, вариант A «защёлка»: тело sin 220 + 0.35·sin 440 Гц (70 мс, −17 dBFS) и в
         * начале щелчок — 3 мс белого шума через однополюсный ФВЧ 2 кГц с линейным затуханием
         * (−22 dBFS); сумма — −16 dBFS, самый громкий звук.
         */
        fun latch(): FloatArray {
            val n = n(70.0)
            val e = env(n, 1.0, 18.0)
            val b = FloatArray(n) { i ->
                val t = i.toDouble() / sr
                ((sin(2 * PI * 220 * t) + 0.35 * sin(2 * PI * 440 * t)) * e[i]).toFloat()
            }
            norm(b, -17.0)
            val m = n(3.0)
            val rc = 1 / (2 * PI * 2000)
            val al = rc / (rc + 1.0 / sr)
            val rnd = Random(SEED)
            var x0 = 0.0
            var y = 0.0
            val r = FloatArray(m) { i ->
                val x = rnd.nextDouble() * 2 - 1
                y = al * (y + x - x0); x0 = x
                (y * (1 - i.toDouble() / m)).toFloat()
            }
            norm(r, -22.0)
            for (i in 0 until m) b[i] += r[i]
            return norm(b, -16.0)
        }
    }
}
