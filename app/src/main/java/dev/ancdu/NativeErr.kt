package dev.ancdu

import android.util.Log

/**
 * Ошибка скана: [code] — код запуска (err[0] scanStart/rootStart, 0 — запуск прошёл), [raw] —
 * текст ядра (sess_error) после неудачи. Пользователю — только [NativeErr.text]; [raw] — в Log.
 */
data class ScanFail(val code: Int, val raw: String = "")

/** Тексты ядра (C, английские) → ресурсы по виду сообщения. Чистый Kotlin, кроме [log]. */
object NativeErr {
    /** Код удаления: вершина не тот объект, что видел скан (rm_tree_expect, хелпер — выход 9). */
    const val ESTALE = 116

    /** openCache: кэш другой версии формата (прошлой версии приложения) — не повреждён, устарел. */
    const val ENOEXEC = 8

    fun cacheOutdated(err: Int): Boolean = err == -ENOEXEC

    /** Удаление отказано «изменилось после скана»: ничего не удалено, узел в дереве — ⚠ (F_ERR). */
    fun changedSinceScan(r: Int): Boolean = r == -ESTALE

    private val CANNOT_OPEN = Regex("cannot open (.+)")
    private val HELPER_EXIT = Regex("helper failed \\(exit (-?[0-9]+)\\)")

    fun text(t: Txt, f: ScanFail): String {
        if (f.code != 0) return t.s(R.string.err_code, f.code.toString())
        val raw = f.raw.trim()
        CANNOT_OPEN.matchEntire(raw)?.let { return t.s(R.string.err_cannot_open, it.groupValues[1]) }
        if (raw.startsWith("helper failed to start")) return t.s(R.string.err_helper_start)
        HELPER_EXIT.matchEntire(raw)?.let { return t.s(R.string.err_helper_exit, it.groupValues[1]) }
        return t.s(R.string.unknown_error)
    }

    /** Сырой текст ядра — только в журнал. */
    fun log(where: String, f: ScanFail) {
        Log.w("ancdu", "$where failed: code=${f.code} native=\"${f.raw}\"")
    }
}
