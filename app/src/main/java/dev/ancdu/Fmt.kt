package dev.ancdu

import java.util.Locale

/**
 * Все числа интерфейса — здесь, в языке [Txt.locale]: десятичный разделитель и группы разрядов
 * по локали (группы-пробелы — всегда NBSP), между числом и единицей — NBSP, единицы — из ресурсов
 * (KiB/КиБ, s/с). Размер — не больше 5 знаков числа: от 1000 единиц — следующая единица с двумя
 * знаками после запятой («0.98 GiB»). Проценты — целые, без пробела («48%»).
 */
object Fmt {
    const val NBSP = ' '
    private val UNITS = intArrayOf(R.string.unit_kib, R.string.unit_mib, R.string.unit_gib,
        R.string.unit_tib, R.string.unit_pib)

    fun size(b: Long, t: Txt): String {
        if (b < 0) return "—"
        if (b < 1024) return count(b, t.locale) + NBSP + t.s(R.string.unit_b)
        var v = b / 1024.0
        var u = 0
        while (v >= 1024 && u < UNITS.size - 1) { v /= 1024; u++ }
        // Не больше 5 знаков: «1009.9 MiB» не влезает в колонку размера — «0.99 GiB».
        if (v >= 999.95 && u < UNITS.size - 1) return two(v / 1024, t.locale) + NBSP + t.s(UNITS[u + 1])
        return one(v, t.locale) + NBSP + t.s(UNITS[u])
    }

    fun pct(part: Long, whole: Long): String {
        if (whole <= 0) return ""
        if (part <= 0) return "0%"
        val p = part * 100.0 / whole
        return if (p < 1) "<1%" else "${p.toInt()}%"
    }

    /** Целое с группами разрядов локали: en «1,284,113», ru «1 284 113» (NBSP). */
    fun count(n: Long, loc: Locale): String = nbsp(String.format(loc, "%,d", n))

    /** Длительность в секундах с одной цифрой после запятой: «0.3 s» / «0,3 с». */
    fun secs(ms: Long, t: Txt): String = one(maxOf(ms, 0L) / 1000.0, t.locale) + NBSP + t.s(R.string.unit_s)

    /** Таймер скана «мм:сс.с»: «01:05.3» / «01:05,3». */
    fun timer(ms: Long, loc: Locale): String {
        val m = maxOf(ms, 0L)
        return nbsp(String.format(loc, "%02d:%04.1f", m / 60000, (m % 60000) / 1000.0))
    }

    private fun one(v: Double, loc: Locale): String = nbsp(String.format(loc, "%.1f", v))

    private fun two(v: Double, loc: Locale): String = String.format(loc, "%.2f", v)

    /** Пробел, NBSP и узкий NBSP (U+202F, новые CLDR) в группах разрядов — один NBSP. */
    private fun nbsp(s: String): String = s.replace(' ', NBSP).replace(' ', NBSP)
}
