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
               val prevDisk: Long?) {
        /** Хвост плашки браузера: « · 69 312 эл. · 0,2 с». */
        val suffix: String get() = " · ${Fmt.count(items)} эл. · " +
            String.format(java.util.Locale.forLanguageTag("ru"), "%.1f с", ms / 1000.0)
    }

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
        val meta = CacheMeta(root, su, p[1], p[4], done.time, done.disk, done.items).format()
        Holder.io.execute {
            if (Native.saveCache(h, file.path) == 0)
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(file.name, meta).apply()
        }
        return done
    }
}
