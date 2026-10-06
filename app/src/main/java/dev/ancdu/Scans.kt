package dev.ancdu

import android.content.Context

/**
 * Запись «caches»: «root|su|files|ms|time[|disk|items]». [disk] и [items] — объём и число
 * элементов корня дерева (с Task 15; у старых записей их нет).
 */
data class CacheMeta(val root: String, val su: Boolean, val files: Long, val ms: Long, val time: Long,
                     val disk: Long? = null, val items: Long? = null) {
    fun format(): String = "$root|$su|$files|$ms|$time" + if (disk != null && items != null) "|$disk|$items" else ""

    companion object {
        fun parse(s: String?): CacheMeta? {
            val p = s?.split('|') ?: return null
            if (p.size < 5) return null
            return CacheMeta(p[0], p[1] == "true", p[2].toLongOrNull() ?: return null,
                p[3].toLongOrNull() ?: return null, p[4].toLongOrNull() ?: return null,
                p.getOrNull(5)?.toLongOrNull(), p.getOrNull(6)?.toLongOrNull())
        }
    }
}

/** Общее завершение скана: и ScanActivity, и фоновый скан ([BgScan]). */
object Scans {
    const val STORAGE = "/storage/emulated/0"
    const val PREFS = "caches"

    class Done(val disk: Long, val items: Long, val time: Long, val ms: Long,
               /** Объём корня по прежнему кэшу этого корня (null — не было или старая запись). */
               val prevDisk: Long?)

    /** Итог последнего скана общего хранилища без root в этом процессе (любым путём). Главный
     *  поток; тесты сбрасывают в null. */
    var lastStorage: Done? = null

    fun meta(ctx: Context, root: String, su: Boolean): CacheMeta? =
        CacheMeta.parse(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(Holder.cacheFile(ctx, root, su).name, null))

    /**
     * Главный поток, вызывает владелец [h]; скан уже в ST_DONE/ST_FULL ([p] — его последний
     * progress). Читает объём корня, ставит saveCache на Holder.io (FIFO с delete и free этого
     * дескриптора), а запись «caches» — после успешного сохранения, там же на io.
     */
    fun finish(ctx: Context, h: Long, root: String, su: Boolean, p: LongArray): Done {
        val app = ctx.applicationContext
        val file = Holder.cacheFile(app, root, su)
        val prev = meta(app, root, su)?.disk
        val inf = LongArray(4).also { Native.nodeInfo(h, intArrayOf(0), 1, it) }
        val done = Done(inf[0], inf[2], System.currentTimeMillis(), p[4], prev)
        if (root == STORAGE && !su) lastStorage = done
        val meta = CacheMeta(root, su, p[1], p[4], done.time, done.disk, done.items).format()
        Holder.io.execute {
            if (Native.saveCache(h, file.path) == 0)
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(file.name, meta).apply()
        }
        return done
    }
}

/** Плашка вида дерева в браузере: «скан · 0,2 с», «кэш от 05.10 21:33», «… · неполный». */
object Badge {
    /** [time] — время дерева (для кэша), [ms] — длительность скана (-1 — нет), [partial] — ST_FULL. */
    fun text(t: Txt, kind: Kind, time: Long, ms: Long, partial: Boolean,
             tz: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        val parts = ArrayList<String>(3)
        parts += when (kind) {
            Kind.SCAN -> t.s(R.string.badge_scan)
            Kind.ROOT -> t.s(R.string.badge_root)
            Kind.INDEX -> t.s(R.string.badge_index)
            Kind.CACHE -> t.s(R.string.badge_cache, Freshness.date(t, R.string.fmt_day_time, time, tz))
        }
        if (ms >= 0) parts += Fmt.secs(ms, t)
        if (partial) parts += t.s(R.string.badge_partial)
        return parts.joinToString(" · ")
    }
}
