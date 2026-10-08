package dev.ancdu

/**
 * Чистый Kotlin: единственный флаг браузера «обновить сам, сохранив путь» (autoPromote).
 * Взводится после удаления с итогом r ≠ 0 ([afterDelete]) и перед удалением каталога из
 * устаревшего дерева — кэша или индекса ([beforeDelete]). Экран сам пересканирует корень
 * (BgScan.refresh), подставляет новое дерево, как только можно ([ready]), и показывает
 * [outcome]. Не вышло ([failed]) — флаг снимается.
 */
class AutoPromote {
    /**
     * [names] — путь узла: байты имён от корня (без корня), [name] — его имя для подвала;
     * [delDisk] — размер удалённого узла на момент подтверждения; null — ждёт лист удаления;
     * [target] — дерево, обновление которого ждёт запрос листа (его снять с очереди при отмене).
     */
    class Request(val names: List<ByteArray>, val name: String, val delDisk: Long?, val target: ScanTarget? = null,
                  /** Итог группы уже показан: после подстановки (или провала обновления) — этот же текст. */
                  val note: String? = null,
                  /** Ждёт лист ГРУППЫ: [names] — путь папки, выбранное в ней — у экрана. */
                  val group: Boolean = false,
                  /** «Обновить» сообщения: только подставить дерево на том же пути — ни листа, ни подвала. */
                  val quiet: Boolean = false)

    var request: Request? = null
        private set

    fun afterDelete(names: List<ByteArray>, name: String, delDisk: Long) { request = Request(names, name, delDisk) }
    fun beforeDelete(names: List<ByteArray>, name: String, target: ScanTarget) {
        request = Request(names, name, null, target)
    }

    /** «Обновить» сообщения об ошибке: [names] — путь текущей папки. Навигация его снимает (как лист). */
    fun afterRefresh(names: List<ByteArray>, name: String) { request = Request(names, name, null, quiet = true) }

    /** Удаление группы с частично удалённым каталогом: [note] — уже показанный итог. */
    fun afterGroup(names: List<ByteArray>, name: String, delDisk: Long, note: String) {
        request = Request(names, name, delDisk, note = note)
    }

    /** Лист группы из устаревшего дерева: одно обновление на всю группу ([names] — путь папки). */
    fun beforeGroup(names: List<ByteArray>, name: String, target: ScanTarget) {
        request = Request(names, name, null, target, group = true)
    }

    /**
     * Навигация, «назад» или новый долгий тап: ждущий лист больше не нужен; итог удаления остаётся.
     * Возвращает отменённый запрос листа (его обновление, если оно ещё в очереди, тоже не нужно) или null.
     */
    fun cancelSheet(): Request? {
        val r = request ?: return null
        if (r.delDisk != null) return null
        request = null
        return r
    }

    /** Подставить сейчас: флаг взведён, новое дерево ждёт, удаление не идёт, лист не открыт. */
    fun ready(newer: Boolean, busy: Boolean, sheetOpen: Boolean): Boolean =
        request != null && newer && !busy && !sheetOpen

    /** Обновить не вышло: флаг взведён, нового дерева нет, а скан не идёт и не ждёт ([refreshing]). */
    fun failed(newer: Boolean, refreshing: Boolean): Boolean = request != null && !newer && !refreshing

    /** Снять флаг, вернув запрос. */
    fun take(): Request? = request.also { request = null }

    sealed class Outcome {
        /** Текст в подвал на несколько секунд. */
        class Footer(val text: String) : Outcome()
        /** Открыть лист удаления найденного узла — с числами нового дерева. */
        object Sheet : Outcome()
    }

    companion object {
        /** После подстановки: [exact] — узел запроса найден в новом дереве, [disk] — его размер там. */
        fun outcome(t: Txt, r: Request, exact: Boolean, disk: Long): Outcome {
            r.note?.let { return Outcome.Footer(it) }
            val del = r.delDisk ?: return if (exact) Outcome.Sheet else Outcome.Footer(DeleteProgress.gone(t, r.name))
            return Outcome.Footer(
                if (exact) DeleteProgress.freedLeft(t, maxOf(0L, del - disk))
                else DeleteProgress.freed(t, del))
        }

        /**
         * Удаление [r], обновить дерево не вышло: подвал — нижняя граница освобождённого по прежнему
         * дереву ([exact] — узел в нём найден, [disk] — его размер сейчас), без «остатка».
         */
        fun unrefreshed(t: Txt, r: Request, exact: Boolean, disk: Long): String =
            r.note ?: DeleteProgress.freed(t, if (exact) maxOf(0L, (r.delDisk ?: 0L) - disk) else 0L)
    }
}
