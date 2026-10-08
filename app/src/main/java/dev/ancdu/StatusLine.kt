package dev.ancdu

import java.util.TimeZone

/**
 * Удаление, прерванное гибелью процесса (запись start без end в журнале удалений, DeleteLog).
 * [root]/[su] — дерево, в котором оно шло; [names] — сырые байты имён от корня; [disk] — размер на
 * момент подтверждения; [time] — начало; [freed] — сколько освобождено, если известно (null — нет).
 */
class InterruptedDelete(val id: Long, val root: String, val su: Boolean, val names: List<ByteArray>, val dir: Boolean,
                        val disk: Long, val time: Long, val freed: Long? = null) {
    /** «Download/» — путь от корня для показа: невалидный UTF-8 — U+FFFD, у каталога — «/» в конце. */
    val path: String get() = names.joinToString("/") { String(it, Charsets.UTF_8) } + if (dir && names.isNotEmpty()) "/" else ""

    /** Папка, которую открывает тап: каталог — он сам, файл — его папка. */
    val folder: List<ByteArray> get() = if (dir) names else names.dropLast(1)

    fun withFreed(f: Long?): InterruptedDelete = InterruptedDelete(id, root, su, names, dir, disk, time, f)
}

/** Одно состояние строки статуса главного экрана. */
sealed class Status {
    class Interrupted(val d: InterruptedDelete) : Status()
    /** Дерево старше суток ([ageMs]) или его нет (null); [approx] — показан индекс. */
    class Stale(val ageMs: Long?, val approx: Boolean) : Status()
    class Grew(val g: HomeGrowth) : Status()
    object None : Status()
}

/**
 * Чистый Kotlin: единственная строка статуса под карточкой — один факт по приоритету
 * (прервано > устарело ≥ 24 ч > рост > ничего). По умолчанию — тишина.
 */
object StatusLine {
    const val STALE_MS = 86_400_000L

    /**
     * [interrupted] — непросмотренное прерванное удаление; [running] — скан общего хранилища идёт
     * или ждёт; [treeTime] — время показанного дерева или кэша (null — нет ни того, ни другого,
     * или это индекс); [approx] — индекс; [growth] — строка «что выросло».
     */
    fun pick(interrupted: InterruptedDelete?, running: Boolean, treeTime: Long?, approx: Boolean,
             growth: HomeGrowth?, now: Long): Status = when {
        interrupted != null -> Status.Interrupted(interrupted)
        !running && treeTime == null -> Status.Stale(null, approx)
        !running && treeTime != null && now - treeTime >= STALE_MS -> Status.Stale(now - treeTime, approx)
        growth != null -> Status.Grew(growth)
        else -> Status.None
    }

    /**
     * Текст состояния [s] или null (строки нет). [wide] — полная форма строки роста помещается в
     * одну строку; иначе без «больше всего».
     */
    fun text(t: Txt, s: Status, now: Long, wide: Boolean, tz: TimeZone = TimeZone.getDefault()): String? = when (s) {
        is Status.Interrupted -> t.s(R.string.status_interrupted, Bidi.visible(s.d.path),
            s.d.freed?.let { Fmt.sizeOf(it, s.d.disk, t) } ?: Fmt.size(s.d.disk, t))
        is Status.Stale -> when {
            s.approx -> t.s(R.string.status_approx)
            s.ageMs == null -> Freshness.refresh(t)
            else -> t.s(R.string.status_stale, GrowthText.daysAgo(t, now, now - s.ageMs))
        }
        is Status.Grew -> {
            val mostly = s.g.path.ifEmpty { null }
            if (wide || mostly == null) GrowthText.home(t, s.g.delta, s.g.baseTime, mostly, tz)
            else t.s(R.string.growth_home_short, GrowthText.signed(s.g.delta, t), GrowthText.since(t, s.g.baseTime, tz), mostly)
        }
        Status.None -> null
    }
}

/** Чистый Kotlin: арифметика карточки «Внутренняя память». */
object HomeMath {
    private const val GIB = 1L shl 30

    /** «Приложения и система»: занято на /data минус общее хранилище; null — что-то неизвестно. */
    fun appsBytes(dataUsed: Long, shared: Long?): Long? =
        if (shared == null || dataUsed < 0 || shared < 0) null else maxOf(0L, dataUsed - shared)

    /** Места нет: свободно меньше 1 ГиБ или меньше 5% раздела. */
    fun storageFull(free: Long, total: Long): Boolean =
        total > 0 && (free < GIB || free * 20 < total)
}
