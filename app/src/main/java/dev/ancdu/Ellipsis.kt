package dev.ancdu

/** Сокращение строк с «…» («com.goo…messaging»). Режет только по границам кодовых точек. Чистый Kotlin. */
object Ellipsis {
    const val MARK = "…"

    /** Самая длинная строка вида начало + «…» + конец шириной ≤ [max]; [s], если влезает целиком. */
    fun middle(s: String, max: Float, measure: (String) -> Float): String = middleFit(s) { measure(it) <= max }

    /**
     * Самая длинная строка вида начало + «…» + конец, для которой [fits]; [s], если подходит целиком;
     * «…» или «» — если не подходит ничего длиннее.
     */
    fun middleFit(s: String, fits: (String) -> Boolean): String {
        if (fits(s)) return s
        var best = if (fits(MARK)) MARK else ""
        val cps = s.codePointCount(0, s.length)
        var lo = 1
        var hi = cps - 1
        while (lo <= hi) {
            val k = (lo + hi) ushr 1
            val c = cut(s, k)
            if (fits(c)) { best = c; lo = k + 1 } else hi = k - 1
        }
        return best
    }

    /** [k] кодовых точек: половина (с округлением вверх) от начала, остальное от конца. */
    private fun cut(s: String, k: Int): String {
        val head = s.offsetByCodePoints(0, (k + 1) / 2)
        val tail = s.offsetByCodePoints(s.length, -(k / 2))
        return s.substring(0, head) + MARK + s.substring(tail)
    }

    /** Расширение имени файла: после последней «.» не в начале, 1–5 кодовых точек, без пробелов; иначе null. */
    fun ext(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return null
        val e = name.substring(dot + 1)
        if (e.codePointCount(0, e.length) !in 1..5 || e.any { it.isWhitespace() }) return null
        return e
    }

    /**
     * Имя строки списка шириной ≤ [max]: многоточие в конце основы, расширение ([ext]) или «/»
     * каталога остаются. Без расширения или уже, чем расширение + 4 знака (3 знака основы и
     * «…»), — обычное многоточие в конце ([end]).
     */
    fun stemKeepExt(s: String, max: Float, measure: (String) -> Float): String {
        if (measure(s) <= max) return s
        val suffix = if (s.endsWith('/')) "/" else ext(s)?.let { ".$it" } ?: return end(s, max, measure)
        val stem = s.substring(0, s.length - suffix.length)
        val n = stem.codePointCount(0, stem.length)
        val least = minOf(3, n)
        if (n == 0 || max < measure(prefix(stem, least) + MARK + suffix)) return end(s, max, measure)
        var best = least
        var lo = least + 1
        var hi = n - 1
        while (lo <= hi) {
            val k = (lo + hi) ushr 1
            if (measure(prefix(stem, k) + MARK + suffix) <= max) { best = k; lo = k + 1 } else hi = k - 1
        }
        return prefix(stem, best) + MARK + suffix
    }

    /** Самое длинное начало [s] + «…» шириной ≤ [max]; [s], если влезает; «…» или «», если не влезает ничего. */
    fun end(s: String, max: Float, measure: (String) -> Float): String {
        if (measure(s) <= max) return s
        var best = if (measure(MARK) <= max) MARK else ""
        var lo = 1
        var hi = s.codePointCount(0, s.length) - 1
        while (lo <= hi) {
            val k = (lo + hi) ushr 1
            val c = prefix(s, k) + MARK
            if (measure(c) <= max) { best = c; lo = k + 1 } else hi = k - 1
        }
        return best
    }

    private fun prefix(s: String, cps: Int): String = s.substring(0, s.offsetByCodePoints(0, cps))
}
