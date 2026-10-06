package dev.ancdu

/** Цель скана: корень и режим su. */
data class ScanTarget(val root: String, val su: Boolean) {
    companion object {
        /** Общее хранилище без root — цель автоскана главного экрана. */
        val STORAGE = ScanTarget(Scans.STORAGE, false)
    }
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

    /** Скан цели [t] идёт ([running] — цель идущего скана или null) или ждёт в очереди. */
    fun active(t: ScanTarget, running: ScanTarget?): Boolean = running == t || t in q
}
