package dev.ancdu

import java.util.Locale

/** Тексты и арифметика диалога прогресса удаления. Чистые функции (JVM-тесты). */
object DeleteProgress {
    /** Native.delete возвращает -EINTR, если удаление остановлено (удалено частично). */
    const val EINTR = 4
    /** Хелпер отклонил путь: родитель проходит через симлинк, ничего не удалено. */
    const val ELOOP = 40

    /** Знаменатель: items узла — уже вместе с самим узлом (arena: items = 1 + потомки). */
    fun total(items: Long): Long = maxOf(items, 1L)

    fun clamp(done: Long, total: Long): Long = done.coerceIn(0L, maxOf(total, 0L))

    /** Доля в промилле (0..1000) — для ProgressBar с max = 1000. */
    fun permille(done: Long, total: Long): Int =
        if (total <= 0) 0 else (clamp(done, total) * 1000 / total).toInt()

    /** Десятки процентов (0..10): объявляем доступности при каждом новом. */
    fun decile(done: Long, total: Long): Int = permille(done, total) / 100

    fun elapsed(ms: Long): String {
        val s = maxOf(ms, 0L) / 1000
        val h = s / 3600
        val m = s / 60 % 60
        val ss = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, ss)
            else String.format(Locale.ROOT, "%d:%02d", m, ss)
    }

    fun title(name: String): String = "Удаление «$name»"

    fun line(done: Long, total: Long, ms: Long): String =
        "${Fmt.count(clamp(done, total))} / ${Fmt.count(total)} эл. · ${elapsed(ms)}"

    fun announce(done: Long, total: Long): String = "Удалено ${decile(done, total) * 10}%"

    /** Остановлено до первого удаления (ждало в очереди): ничего не тронуто. */
    fun isCancelled(r: Int, done: Long): Boolean = r == -EINTR && done <= 0

    const val CANCELLED = "Удаление отменено — ничего не удалено."

    fun freed(disk: Long): String = "освобождено ${Fmt.size(disk)}"

    /** Подвал, пока экран сам обновляет дерево. */
    const val REFRESHING = "обновляю дерево…"
    /** Хвост «освобождено …»: узел после обновления ещё на диске (удалён не весь). */
    const val LEFT = " · остаток в списке"

    /** Узла запроса нет в обновлённом дереве. */
    fun gone(name: String): String = "“$name” уже нет на диске"

    /**
     * Итог удаления [r] требует обновить дерево сканом: ядро записало в дерево не всё (частично,
     * остановлено после начала, ошибка). 0 — дерево уже точное (csr_remove). Ничего не удалено —
     * обновлять нечего: su отказал через root (-EPERM), симлинк в пути (-ELOOP), «Стоп» до начала.
     */
    fun refreshAfter(r: Int, viaRoot: Boolean, done: Long): Boolean =
        r != 0 && r != -ELOOP && !DeletePolicy.nothingDeleted(r, viaRoot) && !isCancelled(r, done)
}
