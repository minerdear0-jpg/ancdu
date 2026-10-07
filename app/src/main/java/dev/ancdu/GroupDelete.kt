package dev.ancdu

/** Один выбранный объект для листа группы (из дерева в памяти). */
class GroupItem(
    val name: String,
    val dir: Boolean,
    val disk: Long,
    val apparent: Long,
    val items: Long,
    val flags: Int,
    val owner: String?,
    val block: Block?,
    val fast: Boolean,
)

/**
 * Что лист группы показывает сверх сводного [DeletePreview]: число объектов, владельцы (по
 * порядку размера), чьи данные чужие, размеры (для яруса), жёсткие ссылки, сколько пропало
 * с диска, items каталогов в строках «крупнейших» (-1 — файл).
 */
class GroupInfo(
    val count: Int,
    val owners: List<String>,
    val owned: List<Boolean>,
    val disks: List<Long>,
    val hardlink: Boolean,
    val gone: Int,
    val topItems: List<Long>,
) {
    /** Ярус группы: строжайшее правило, сумма размеров. */
    fun tier(viaRoot: Boolean, fast: Boolean): DeleteTier = DeletePolicy.groupTier(viaRoot, fast, owned, disks)
}

/** Чистые тексты и сводка листа группы. */
object GroupSheet {
    /** Строк «крупнейших» в листе. */
    const val TOP = 5
    /** Значков владельцев в сводке. */
    const val ICONS = 3

    /** «3 объекта» / «3 items». */
    fun objects(t: Txt, n: Int): String = t.q(R.plurals.objects, n.toLong(), Fmt.count(n.toLong(), t.locale))

    /** «Удалить 3 объекта?» */
    fun title(t: Txt, n: Int): String = t.s(R.string.group_title, objects(t, n))

    /** «3 выбрано» (подпись прописными делает caps). */
    fun selected(t: Txt, n: Int): String = t.q(R.plurals.selected_n, n.toLong(), Fmt.count(n.toLong(), t.locale))

    /** «данные 2 приложений: WhatsApp, Telegram»; больше [ICONS] — «A, B, C +2». */
    fun owners(t: Txt, labels: List<String>): String {
        val shown = labels.take(ICONS).joinToString(", ") + if (labels.size > ICONS) " +${labels.size - ICONS}" else ""
        return t.q(R.plurals.owners_n, labels.size.toLong(), Fmt.count(labels.size.toLong(), t.locale), shown)
    }

    /** «1 уже нет на диске». */
    fun gone(t: Txt, n: Int): String = t.s(R.string.group_gone, Fmt.count(n.toLong(), t.locale))

    /** Подвал после ухода из папки: «Выбор снят: 3». */
    fun cleared(t: Txt, n: Int): String = t.s(R.string.selection_cleared, Fmt.count(n.toLong(), t.locale))

    /** Путь папки с «/» в конце. */
    fun parentPath(path: String): String = if (path.endsWith("/")) path else "$path/"

    /**
     * Сводный превью-объект листа и [GroupInfo]. Суммы disk/apparent/items, «· N эл.» — если есть
     * каталог, крупнейшие [TOP] по диску, быстрый путь — только если его проходят ВСЕ, запрет —
     * первый найденный, владелец — если он один. [self] — свой пакет (свои данные не «чужие»).
     */
    fun preview(t: Txt, items: List<GroupItem>, parent: String, self: String, viaRoot: Boolean, kind: Kind,
                cacheTime: String?, root: RootState, gone: Int): Pair<DeletePreview, GroupInfo> {
        val bySize = items.sortedByDescending { it.disk }
        val top = bySize.take(TOP)
        val owners = bySize.mapNotNull { it.owner }.distinct()
        val p = DeletePreview(
            name = objects(t, items.size), path = parentPath(parent), dir = items.any { it.dir },
            disk = DeletePolicy.sum(items.map { it.disk }), apparent = DeletePolicy.sum(items.map { it.apparent }),
            items = DeletePolicy.sum(items.map { it.items }), flags = 0,
            top = top.map { (if (it.dir) it.name + "/" else it.name) to it.disk }, more = items.size - top.size,
            owner = owners.singleOrNull(), viaRoot = viaRoot, block = items.firstNotNullOfOrNull { it.block },
            kind = kind, cacheTime = cacheTime, fast = items.isNotEmpty() && items.all { it.fast }, root = root)
        val info = GroupInfo(
            count = items.size, owners = owners, owned = items.map { it.owner != null && it.owner != self },
            disks = items.map { it.disk }, hardlink = items.any { !it.dir && it.flags and F_HLDUP != 0 },
            gone = gone, topItems = top.map { if (it.dir) it.items else -1L })
        return p to info
    }
}

/** Почему объект группы не удалён; текст — [res]. */
enum class Fail(val res: Int) {
    ACCESS(R.string.fail_access),
    BUSY(R.string.fail_busy),
    SYMLINK(R.string.fail_symlink),
    PARTIAL(R.string.fail_partial),
    BLOCKED(R.string.fail_blocked),
    ERROR(R.string.fail_error),
}

/**
 * Итог одного объекта группы. [r] — код Native.delete (0, -errno); [done] — сколько записей
 * удалено им; [attempted] — false: не отправлялся (запрещён к началу — [block], «Стоп» или
 * отказ su раньше него).
 */
class ItemResult(
    val name: String,
    val dir: Boolean,
    val disk: Long,
    val r: Int,
    val done: Long,
    val attempted: Boolean = true,
    val block: Block? = null,
)

/** Чистая классификация итогов группы и тексты итога. */
object GroupResult {
    private const val EPERM = 1
    const val ENOENT = 2
    private const val EBUSY = 16
    private const val EXDEV = 18
    private const val EACCES = 13

    /** Удалён: код 0 или ENOENT (его уже не было). */
    fun deleted(x: ItemResult): Boolean = x.attempted && x.block == null && (x.r == 0 || x.r == -ENOENT)

    /** Причина неудачи или null — удалён. */
    fun fail(x: ItemResult): Fail? = when {
        deleted(x) -> null
        x.block != null -> Fail.BLOCKED
        x.dir && x.done > 0 -> Fail.PARTIAL
        x.r == -EACCES || x.r == -EPERM -> Fail.ACCESS
        // rm_tree отдаёт EBUSY точки монтирования как EXDEV.
        x.r == -EBUSY || x.r == -EXDEV -> Fail.BUSY
        x.r == -DeleteProgress.ELOOP -> Fail.SYMLINK
        x.dir -> Fail.PARTIAL
        else -> Fail.ERROR
    }

    sealed class Outcome(val deleted: Int, val total: Int, val freed: Long) {
        class Done(total: Int, freed: Long) : Outcome(total, total, freed)
        /** «Стоп»: удалённое осталось удалённым, остальное не трогалось. */
        class Stopped(deleted: Int, total: Int, freed: Long) : Outcome(deleted, total, freed)
        class Partial(deleted: Int, total: Int, freed: Long, val fails: List<Pair<String, Fail>>) : Outcome(deleted, total, freed)
    }

    /** Освобождено — размеры удалённых целиком (нижняя граница: частичные не считаются). */
    fun outcome(results: List<ItemResult>): Outcome {
        val ok = results.filter { deleted(it) }
        // Пропавший к началу (ENOENT) места не освободил.
        val freed = DeletePolicy.sum(ok.filter { it.r == 0 }.map { it.disk })
        return when {
            ok.size == results.size -> Outcome.Done(results.size, freed)
            results.any { it.r == -DeleteProgress.EINTR } -> Outcome.Stopped(ok.size, results.size, freed)
            else -> Outcome.Partial(ok.size, results.size, freed,
                results.mapNotNull { x -> fail(x)?.let { (if (x.dir) x.name + "/" else x.name) to it } })
        }
    }

    /** Подвал: «освобождено X» или «Остановлено: удалено 1 из 3 · освобождено X». */
    fun footer(t: Txt, o: Outcome): String = when (o) {
        is Outcome.Stopped -> t.s(R.string.group_stopped, Fmt.count(o.deleted.toLong(), t.locale),
            Fmt.count(o.total.toLong(), t.locale), DeleteProgress.freed(t, o.freed))
        else -> DeleteProgress.freed(t, o.freed)
    }

    /** Сообщение частичного итога: заголовок «Удалено 2 из 3», текст — освобождено и «Не удалено:» по строке. */
    fun alert(t: Txt, o: Outcome.Partial): Pair<String, String> {
        val title = t.s(R.string.group_partial, Fmt.count(o.deleted.toLong(), t.locale), Fmt.count(o.total.toLong(), t.locale))
        val lines = o.fails.joinToString("\n") { (nm, f) -> t.s(R.string.fail_line, Bidi.visible(nm), t.s(f.res)) }
        return title to DeleteProgress.freed(t, o.freed) + "\n\n" + t.s(R.string.not_deleted) + "\n" + lines
    }

    /** Каталог удалён частично (как [DeleteProgress.refreshAfter] у одного): дерево надо обновить. */
    private fun partialDir(x: ItemResult, viaRoot: Boolean): Boolean =
        x.attempted && !deleted(x) && DeleteProgress.refreshAfter(x.r, viaRoot, x.done, x.dir)

    fun needsRefresh(results: List<ItemResult>, viaRoot: Boolean): Boolean = results.any { partialDir(it, viaRoot) }

    /**
     * Код группы для слушателей и BgScan: 0 — удалено всё; есть частично удалённый каталог — его
     * код (по нему дерево обновится); иначе первая ошибка.
     */
    fun code(results: List<ItemResult>, viaRoot: Boolean): Int {
        results.firstOrNull { partialDir(it, viaRoot) }?.let { return it.r }
        val bad = results.firstOrNull { !deleted(it) } ?: return 0
        return if (bad.r != 0) bad.r else -EPERM
    }

    /** su отказал хотя бы на одном объекте: «root ✓» больше не правда. */
    fun rootRefused(results: List<ItemResult>, viaRoot: Boolean): Boolean =
        results.any { it.attempted && DeletePolicy.nothingDeleted(it.r, viaRoot) }
}
