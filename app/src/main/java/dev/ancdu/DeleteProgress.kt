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

    fun title(t: Txt, name: String): String = t.s(R.string.progress_title, name)

    /** Заголовок диалога: [count] > 1 — «Удаление 3 объектов», один — [title] его имени. */
    fun titleFor(t: Txt, name: String, count: Int): String =
        if (count > 1) t.q(R.plurals.progress_title_n, count.toLong(), Fmt.count(count.toLong(), t.locale)) else title(t, name)

    /** «12 340 / 69 370 эл. · 0:12»: форма plurals — по [total]. */
    fun line(t: Txt, done: Long, total: Long, ms: Long): String =
        t.q(R.plurals.items, total, "${Fmt.count(clamp(done, total), t.locale)} / ${Fmt.count(total, t.locale)}") +
            " · " + elapsed(ms)

    fun announce(t: Txt, done: Long, total: Long): String =
        t.s(R.string.progress_announce, "${decile(done, total) * 10}%")

    /** Остановлено до первого удаления (ждало в очереди): ничего не тронуто. */
    fun isCancelled(r: Int, done: Long): Boolean = r == -EINTR && done <= 0

    fun cancelled(t: Txt): String = t.s(R.string.progress_cancelled)

    fun freed(t: Txt, disk: Long): String = t.s(R.string.freed, Fmt.size(disk, t))

    /** «освобождено …» и хвост: узел после обновления ещё на диске (удалён не весь). */
    fun freedLeft(t: Txt, disk: Long): String = t.s(R.string.freed_left, freed(t, disk))

    /** Узла запроса нет в обновлённом дереве. */
    fun gone(t: Txt, name: String): String = t.s(R.string.gone, name)

    /**
     * Итог удаления [r] требует обновить дерево сканом: ядро записало в дерево не всё (частично,
     * остановлено после начала, ошибка). 0 — дерево уже точное (csr_remove). Ничего не удалено —
     * обновлять нечего: su отказал через root (-EPERM), симлинк в пути (-ELOOP), «Стоп» до начала,
     * «изменилось после скана» (-ESTALE: узел уже помечен ⚠, пользователь видит сообщение).
     * Файл ([dir] false) — не каталог: сканировать нечего, он удалён или нет целиком.
     */
    fun refreshAfter(r: Int, viaRoot: Boolean, done: Long, dir: Boolean): Boolean =
        dir && r != 0 && r != -ELOOP && !NativeErr.changedSinceScan(r) && !DeletePolicy.nothingDeleted(r, viaRoot) &&
            !DeletePolicy.rootPathRefused(r, viaRoot) && !isCancelled(r, done)
}
