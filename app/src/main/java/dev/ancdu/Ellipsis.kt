package dev.ancdu

/** Обрезка посередине: «com.goo…messaging». Режет только по границам кодовых точек. */
object Ellipsis {
    const val MARK = "…"

    /** Самая длинная строка вида начало + «…» + конец шириной ≤ [max]; [s], если влезает целиком. */
    fun middle(s: String, max: Float, measure: (String) -> Float): String {
        if (measure(s) <= max) return s
        var best = if (measure(MARK) <= max) MARK else ""
        val cps = s.codePointCount(0, s.length)
        var lo = 1
        var hi = cps - 1
        while (lo <= hi) {
            val k = (lo + hi) ushr 1
            val c = cut(s, k)
            if (measure(c) <= max) { best = c; lo = k + 1 } else hi = k - 1
        }
        return best
    }

    /** [k] кодовых точек: половина (с округлением вверх) от начала, остальное от конца. */
    private fun cut(s: String, k: Int): String {
        val head = s.offsetByCodePoints(0, (k + 1) / 2)
        val tail = s.offsetByCodePoints(s.length, -(k / 2))
        return s.substring(0, head) + MARK + s.substring(tail)
    }
}
