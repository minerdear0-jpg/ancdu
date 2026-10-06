package dev.ancdu

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.TimeUnit

/** Известное о root: [UNKNOWN] — не спрашивали, [ASKING] — запрос su идёт. */
enum class RootState { UNKNOWN, ASKING, GRANTED, DENIED }

/** Чистый Kotlin: итог `su -c id` и его запись в prefs (`root_last`). */
object RootProbe {
    private val UID0 = Regex("(^|[^A-Za-z0-9_])uid=0([^0-9]|$)")

    /** Выдан ли root: процесс вышел сам ([timedOut] — нет) с кодом 0, и `id` напечатал uid=0. */
    fun granted(exit: Int?, out: String, timedOut: Boolean): Boolean =
        !timedOut && exit == 0 && UID0.containsMatchIn(out)

    fun format(s: RootState, time: Long): String =
        (if (s == RootState.GRANTED) "granted" else "denied") + "|$time"

    fun parse(s: String?): RootState = when (s?.substringBefore('|')) {
        "granted" -> RootState.GRANTED
        "denied" -> RootState.DENIED
        else -> RootState.UNKNOWN
    }
}

object Root {
    fun helper(ctx: Context): String = ctx.applicationInfo.nativeLibraryDir + "/libancdu_scan.so"

    /** Только наличие su; сам доступ запрашивает Magisk при первом root-скане или по тапу «su». */
    fun suExists(): Boolean = suCheck()

    /** Для тестов: подмена проверки наличия su. */
    @Volatile var suCheck: () -> Boolean = ::suOnPath

    private fun suOnPath(): Boolean =
        (System.getenv("PATH") ?: "/system/bin:/system/xbin")
            .split(':').any { File(it, "su").let { f -> f.exists() && f.canExecute() } }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("root", Context.MODE_PRIVATE)
    fun memfdAllowed(ctx: Context): Boolean = prefs(ctx).getBoolean("memfd", true)
    fun rememberMemfd(ctx: Context, worked: Boolean) {
        prefs(ctx).edit().putBoolean("memfd", worked).apply()
    }

    /** Итог одного запуска su: [exit] null — процесс не запустился или не вышел сам. */
    class Probe(val exit: Int?, val out: String, val timedOut: Boolean)

    const val TIMEOUT_MS = 60_000L

    /**
     * Последнее известное состояние, общее для процесса; пишет только главный поток.
     * «root ✓» из prefs — лишь подсказка: каждая root-операция всё равно обрабатывает отказ.
     */
    @Volatile var state = RootState.UNKNOWN; private set
    private var loaded = false
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()

    /** Для тестов: подмена запуска su (вызывается на потоке запроса, аргумент — таймаут, мс). */
    @Volatile var probe: (Long) -> Probe = ::runSu

    /** Главный поток. Состояние из prefs — один раз за процесс. */
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        state = RootProbe.parse(prefs(ctx).getString("root_last", null))
    }

    /** Для тестов: следующий [load] снова прочтёт prefs. Главный поток. */
    fun reset() { loaded = false; state = RootState.UNKNOWN }

    /** Только главный поток. Слушатели вызываются при каждой смене [state]. */
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun publish(ctx: Context, s: RootState) {
        state = s
        if (s == RootState.GRANTED || s == RootState.DENIED)
            prefs(ctx).edit().putString("root_last", RootProbe.format(s, System.currentTimeMillis())).apply()
        for (l in listeners.toList()) l()
    }

    /** Главный поток. Root-операция прошла (например, root-скан) — root выдан. */
    fun granted(ctx: Context) {
        load(ctx)
        if (state != RootState.GRANTED) publish(ctx.applicationContext, RootState.GRANTED)
    }

    /** Главный поток. Root-операция получила отказ (-EPERM до удаления) — root отклонён. */
    fun denied(ctx: Context) {
        load(ctx)
        if (state != RootState.DENIED) publish(ctx.applicationContext, RootState.DENIED)
    }

    /**
     * Главный поток. Ручной запрос root: `su -c id` на ОТДЕЛЬНОМ потоке — не Holder.io: запрос
     * Magisk/KernelSU/APatch может висеть до [TIMEOUT_MS], и io не должен вставать за ним.
     * Повторный вызов, пока запрос идёт, ничего не делает.
     */
    fun request(ctx: Context) {
        load(ctx)
        if (state == RootState.ASKING) return
        val app = ctx.applicationContext
        publish(app, RootState.ASKING)
        val run = probe
        Thread({
            val r = try { run(TIMEOUT_MS) } catch (e: Exception) { Probe(null, "", false) }
            val s = if (RootProbe.granted(r.exit, r.out, r.timedOut)) RootState.GRANTED else RootState.DENIED
            main.post { publish(app, s) }
        }, "ancdu-su").apply { isDaemon = true }.start()
    }

    /** Сколько вывода su сохраняется для разбора (остальное читается и выбрасывается). */
    private const val OUT_CAP = 64 * 1024

    /**
     * Поток запроса. Вывод (stderr слит в stdout) вычитывается ПАРАЛЛЕЛЬНО с waitFor отдельным
     * потоком: болтливая обёртка su не встанет на полном канале до таймаута. Потоки процесса
     * закрываются всегда; по таймауту процесс уничтожается (и канал закрывается — чтение выходит).
     */
    private fun runSu(timeoutMs: Long): Probe {
        val p = try {
            ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        } catch (e: Exception) {
            return Probe(null, "", false)
        }
        val buf = java.io.ByteArrayOutputStream()
        val drain = Thread({
            runCatching {
                val chunk = ByteArray(4096)
                val input = p.inputStream
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    synchronized(buf) { if (buf.size() < OUT_CAP) buf.write(chunk, 0, minOf(n, OUT_CAP - buf.size())) }
                }
            }
        }, "ancdu-su-out").apply { isDaemon = true }
        try {
            p.outputStream.close()
            drain.start()
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) return Probe(null, "", true)
            drain.join(2_000)   // вывод дочитывается после выхода процесса
            val out = synchronized(buf) { buf.toString("UTF-8") }
            return Probe(p.exitValue(), out, false)
        } finally {
            runCatching { p.outputStream.close() }
            runCatching { p.inputStream.close() }
            runCatching { p.errorStream.close() }
            p.destroyForcibly()
        }
    }
}
