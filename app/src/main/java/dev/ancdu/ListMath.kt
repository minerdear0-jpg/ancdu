package dev.ancdu

object ListMath {
    fun maxScroll(count: Int, rowH: Int, viewH: Int): Int = (count * rowH - viewH).coerceAtLeast(0)
    fun clampScroll(scroll: Int, count: Int, rowH: Int, viewH: Int): Int =
        scroll.coerceIn(0, maxScroll(count, rowH, viewH))
    fun firstVisible(scroll: Int, rowH: Int): Int = scroll / rowH
    fun lastVisible(scroll: Int, rowH: Int, viewH: Int, count: Int): Int =
        if (count == 0) -1 else minOf(count - 1, (scroll + viewH - 1) / rowH)
    fun indexAt(y: Float, scroll: Int, rowH: Int, count: Int): Int {
        if (y < 0) return -1
        val i = ((y + scroll) / rowH).toInt()
        return if (i in 0 until count) i else -1
    }
    fun bar(value: Long, max: Long): Float =
        if (max <= 0) 0f else (value.toDouble() / max).toFloat().coerceIn(0f, 1f)
    /** Высота строки: не меньше [minH], но и не меньше текста [textH] с отступами [pad] сверху и снизу. */
    fun rowHeight(minH: Int, textH: Int, pad: Int): Int = maxOf(minH, textH + 2 * pad)
}
