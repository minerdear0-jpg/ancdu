package dev.ancdu

import android.content.Context
import android.content.res.Resources
import java.util.Locale

/**
 * Источник текстов интерфейса: строки и plurals из res/values(-ru). На устройстве — [ResTxt]
 * (ресурсы контекста Activity, с выбранным языком); в JVM-тестах — разбор тех же strings.xml.
 * Чистые объекты (DeleteProgress, Freshness, Fmt…) получают его параметром.
 */
interface Txt {
    /** Язык чисел и дат: тот же, что у ресурсов. */
    val locale: Locale
    /** Строка [id]; с [args] — String.format в [locale], без них — как есть. */
    fun s(id: Int, vararg args: Any): String
    /** Plurals [id]: форма по [n], затем format с [args]. */
    fun q(id: Int, n: Long, vararg args: Any): String
}

class ResTxt(private val r: Resources) : Txt {
    override val locale: Locale = r.configuration.locales[0] ?: Locale.ROOT
    override fun s(id: Int, vararg args: Any): String =
        if (args.isEmpty()) r.getString(id) else r.getString(id, *args)
    override fun q(id: Int, n: Long, vararg args: Any): String =
        r.getQuantityString(id, quantity(n), *args)

    companion object {
        /**
         * getQuantityString берёт Int. Форма зависит от последних цифр (ru: n % 10, n % 100) и
         * от n == 1 (en): больше Int.MAX_VALUE — последние шесть цифр плюс миллион.
         */
        fun quantity(n: Long): Int = if (n in 0..Int.MAX_VALUE) n.toInt() else (Math.floorMod(n, 1_000_000L) + 1_000_000).toInt()
    }
}

/** Тексты в языке этого контекста (Activity — с выбранным языком, см. [Lang]). */
val Context.tx: Txt get() = ResTxt(resources)

/** «N эл.» / «N items»: число в формате языка, форма — plurals. */
fun Txt.items(n: Long): String = q(R.plurals.items, n, Fmt.count(n, locale))
