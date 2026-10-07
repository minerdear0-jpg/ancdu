package dev.ancdu

/** Цель скана: корень и режим su. */
data class ScanTarget(val root: String, val su: Boolean) {
    companion object {
        /** Общее хранилище без root — цель автоскана главного экрана. */
        val STORAGE = ScanTarget(Scans.STORAGE, false)
    }
}

/** Фоновый скан цели: идёт, ждёт в очереди или его нет. */
enum class ScanState { RUNNING, QUEUED, NONE }

/** Итог скана цели: дерево готово, не удался (или не запустился), выброшен («грязный» после удаления). */
enum class ScanEnd { OK, FAILED, DISCARDED }

/**
 * Чистый Kotlin: последние итоги сканов по целям. Экран берёт [mark], когда видит начало скана
 * своей цели, и по [since] узнаёт итог именно этого скана: прежний итог той же цели и итог чужой
 * цели, записанный следом, его не подменяют.
 */
class ScanEnds {
    private var seq = 0L
    private val ends = HashMap<ScanTarget, Pair<Long, ScanEnd>>()

    /** Метка «сейчас»: итоги, записанные позже, — новее её. */
    val mark: Long get() = seq

    fun record(t: ScanTarget, e: ScanEnd) { ends[t] = ++seq to e }

    /** Итог цели [t], записанный после метки [mark], или null. */
    fun since(t: ScanTarget, mark: Long): ScanEnd? = ends[t]?.takeIf { it.first > mark }?.second
}

/**
 * Чистый Kotlin: очередь сканов BgScan — FIFO без повторов. Постановка не вытесняет ждущие цели
 * (обновление после удаления не теряется из-за второго удаления или скана главного экрана);
 * повтор той же цели остаётся одной записью на прежнем месте.
 */
class ScanQueue {
    private val q = LinkedHashSet<ScanTarget>()

    val isEmpty: Boolean get() = q.isEmpty()
    operator fun contains(t: ScanTarget): Boolean = t in q
    operator fun plusAssign(t: ScanTarget) { q += t }
    fun remove(t: ScanTarget) { q -= t }
    fun pop(): ScanTarget? = q.firstOrNull()?.also { q -= it }

    /**
     * Следующая цель к запуску. [rootDenied] — root отклонён с момента постановки: su-цели
     * выбрасываются, чтобы запрос Magisk не всплыл сам (ждущий их запрос закончится провалом).
     */
    fun next(rootDenied: Boolean): ScanTarget? {
        while (true) {
            val t = pop() ?: return null
            if (!(t.su && rootDenied)) return t
        }
    }

    /** Скан цели [t] идёт ([running] — цель идущего скана или null) или ждёт в очереди. */
    fun active(t: ScanTarget, running: ScanTarget?): Boolean = running == t || t in q

    /** То же, что [active], с различением «идёт» и «ждёт». */
    fun state(t: ScanTarget, running: ScanTarget?): ScanState = when {
        running == t -> ScanState.RUNNING
        t in q -> ScanState.QUEUED
        else -> ScanState.NONE
    }
}
