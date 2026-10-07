package dev.ancdu

/** Строка пути шапки и заголовок корня. Чистый Kotlin. */
object PathText {
    /** Начало пути, отброшенное целыми сегментами. */
    const val LEAD = "…/"
    private val INTERNAL = Regex("/storage/emulated/[0-9]+")

    fun segments(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

    /** Показ пути: предки [head] (приглушённо) и последний сегмент [last] (ярко). */
    class Fit(val head: String, val last: String) {
        val text: String get() = head + last
    }

    /**
     * [path] шириной ≤ [max]: целиком; иначе «…/» и столько последних сегментов, сколько влезает
     * (сегменты отбрасываются только целиком); не влез и последний — он режется посередине.
     */
    fun fit(path: String, max: Float, measure: (String) -> Float): Fit {
        val segs = segments(path)
        if (segs.isEmpty()) return Fit("", "/")
        val last = segs.last()
        val whole = Fit("/" + segs.dropLast(1).joinToString("") { "$it/" }, last)
        if (measure(whole.text) <= max) return whole
        for (k in 1 until segs.size) {
            val f = Fit(LEAD + segs.subList(k, segs.size - 1).joinToString("") { "$it/" }, last)
            if (measure(f.text) <= max) return f
        }
        val head = if (segs.size == 1) "/" else LEAD
        return Fit(head, Ellipsis.middle(last, max - measure(head), measure))
    }

    /**
     * Заголовок корня дерева: /storage/emulated/<n> — [internal] («Внутренняя память»), «/» — «/»,
     * иначе последний сегмент.
     */
    fun rootTitle(path: String, internal: String): String {
        val p = path.trimEnd('/')
        return when {
            p.isEmpty() -> "/"
            INTERNAL.matches(p) -> internal
            else -> segments(p).last()
        }
    }
}
