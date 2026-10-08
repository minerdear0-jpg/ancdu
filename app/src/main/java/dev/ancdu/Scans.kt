package dev.ancdu

import android.content.Context
import android.util.Log

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
     * дескриптора), а запись «caches» и ротацию точки отсчёта ([BaselineFiles.onSaved]) — после
     * успешного сохранения, там же на io.
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
            if (Native.saveCache(h, file.path) == 0) {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(file.name, meta).apply()
                // Точка отсчёта «что выросло»: ссылка/копия только что записанного файла (не пересериализация).
                runCatching { Baseline.files(app, root, su).onSaved(file, System.currentTimeMillis()) }
                    .onFailure { Log.w("ancdu", "baseline rotation failed", it) }
            }
        }
        return done
    }
}

/** Плашка вида дерева в браузере: «скан 2 ч назад», «кэш · 10 ч назад», «… · неполный». */
object Badge {
    private const val HOUR = 3_600_000L
    private const val DAY = 86_400_000L

    /**
     * Плашка браузера — только отклонения: вид дерева (root, индекс, кэш), возраст от часа
     * («скан 2 ч назад», приглушённо) и «неполный». Свежий полный скан — пусто (тишина).
     * [time] — время дерева (0 — неизвестно), [partial] — ST_FULL.
     */
    fun text(t: Txt, kind: Kind, time: Long, partial: Boolean, now: Long = System.currentTimeMillis()): String {
        val age = if (time > 0 && kind != Kind.INDEX && now - time >= HOUR) ago(t, now - time, now) else null
        val parts = ArrayList<String>(3)
        when (kind) {
            Kind.SCAN -> if (age != null) parts += t.s(R.string.badge_scan_ago, age)
            Kind.ROOT -> { parts += t.s(R.string.badge_root); if (age != null) parts += t.s(R.string.badge_scan_ago, age) }
            Kind.INDEX -> parts += t.s(R.string.badge_index)
            Kind.CACHE -> { parts += t.s(R.string.badge_cache); if (age != null) parts += age }
        }
        if (partial) parts += t.s(R.string.badge_partial)
        return parts.joinToString(" · ")
    }

    /** «2 ч назад»; от суток — «3 дня назад». */
    private fun ago(t: Txt, d: Long, now: Long): String =
        if (d < DAY) t.s(R.string.h_ago, Fmt.count(d / HOUR, t.locale)) else GrowthText.daysAgo(t, now, now - d)
}
