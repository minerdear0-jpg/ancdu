package dev.ancdu

/**
 * Управляющие символы направления текста (U+202A–202E, U+2066–2069): в имени файла они
 * переставляют видимые символы («photo‮gpj.exe» читается как «photoexe.jpg»). Чистый Kotlin.
 */
object Bidi {
    fun isControl(c: Char): Boolean = c in '‪'..'‮' || c in '⁦'..'⁩'

    /** Каждый управляющий — видимым «⟨U+202E⟩»; без них — та же строка. */
    fun visible(s: String): String {
        if (s.none(::isControl)) return s
        val sb = StringBuilder(s.length + 16)
        for (c in s) if (isControl(c)) sb.append("⟨U+").append("%04X".format(c.code)).append('⟩') else sb.append(c)
        return sb.toString()
    }

    /** Без управляющих; без них — та же строка. */
    fun strip(s: String): String = if (s.none(::isControl)) s else s.filterNot(::isControl)
}
