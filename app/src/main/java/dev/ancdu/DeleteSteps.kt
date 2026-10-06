package dev.ancdu

/**
 * Порядок шагов одного удаления на Holder.io. Чистый Kotlin (JVM-тесты), состояние — у вызывающего.
 * 1. [bulk] (необязательный массовый шаг MediaStore). Исключение из него не прерывает удаление.
 * 2. [arm] под замком вызывающего: false — «Стоп» уже нажат, ядро не зовётся, итог -EINTR
 *    (удалённое массовым шагом остаётся удалённым); true — дескриптор открыт для deleteStop.
 * 3. [native] — Native.delete/deleteMedia: всегда, если не было «Стопа», — источник истины дерева.
 * «Стоп» после того, как массовый шаг что-то удалил ([bulkRows] > 0): ядро не вызывается, но
 * дерево уже не совпадает с диском — [markPartial] помечает узел F_ERR (как частичное удаление).
 */
object DeleteSteps {
    fun run(bulk: (() -> Unit)?, arm: () -> Boolean, native: () -> Int,
            onBulkError: (Throwable) -> Unit = {}, bulkRows: () -> Long = { 0L },
            markPartial: () -> Unit = {}): Int {
        if (bulk != null) {
            try { bulk() } catch (e: Exception) { onBulkError(e) }
        }
        if (!arm()) {
            if (bulkRows() > 0) markPartial()
            return -DeleteProgress.EINTR
        }
        return native()
    }

    /** Прогресс для диалога: строки массового шага плюс счётчик ядра, без переполнения и < 0. */
    fun done(rows: Long, native: Long): Long {
        val a = maxOf(rows, 0L)
        val b = maxOf(native, 0L)
        return if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
    }
}
