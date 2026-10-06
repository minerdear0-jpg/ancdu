package dev.ancdu

import java.util.Locale

object Fmt {
    private val units = arrayOf("KiB", "MiB", "GiB", "TiB", "PiB")

    fun size(b: Long): String {
        if (b < 0) return "—"
        if (b < 1024) return "$b B"
        var v = b / 1024.0
        var u = 0
        while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
        return String.format(Locale.ROOT, "%.1f %s", v, units[u])
    }

    fun pct(part: Long, whole: Long): String {
        if (whole <= 0) return ""
        if (part <= 0) return "0%"
        val p = part * 100.0 / whole
        return if (p < 1) "<1%" else "${p.toInt()}%"
    }

    fun count(n: Long): String {
        val s = n.toString()
        val sb = StringBuilder()
        for (i in s.indices) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(' ')
            sb.append(s[i])
        }
        return sb.toString()
    }
}
