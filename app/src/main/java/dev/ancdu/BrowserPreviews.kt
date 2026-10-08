package dev.ancdu

import android.webkit.MimeTypeMap

/**
 * Превью браузера [a]: карточка быстрого просмотра файла и то, что показывают превью листа
 * удаления — сведения о файлах ([peekInfo]) и «контактные листы» каталогов ([contactSheet]).
 * Главный поток, чтения дерева — с переданным дескриптором.
 */
class BrowserPreviews(private val a: BrowserActivity) {
    /** Открытая карточка быстрого просмотра. */
    var quickLook: QuickLook? = null
        private set

    /**
     * Карточка файла [target] дерева [h] ([size] — размер в показанном режиме). «Удалить…» открывает
     * обычный лист удаления того же узла, если дерево за это время не сменилось.
     */
    fun openQuickLook(target: Int, size: Long, flags: Int) {
        a.sheet?.dismiss()
        quickLook?.dismiss()
        a.cancelAsk()
        val handle = a.h
        val path = Native.str(Native.path(handle, target))
        val info = QuickLookInfo(name = a.nameOf(target), path = path,
            parent = Native.str(Native.path(handle, Native.parent(handle, target))), size = size,
            owner = Owner.packageOf(path), rootOnly = Peek.rootOnly(path, Holder.viaRoot, a.packageName), flags = flags)
        // «ВЫБРАТЬ»: вне выбора — войти в него с этим файлом, в выборе — переключить файл.
        val selected = a.selection.active && a.selection.contains(target)
        // Закрыта карточка — подставить дерево, если оно пришло, пока она была открыта.
        quickLook = QuickLook(a, info, onClose = { a.refreshPending() },
            selectLabel = a.txt.s(if (selected) R.string.sel_deselect else R.string.ql_select),
            onSelect = { if (!a.busy && a.h == handle && !a.isFinishing) a.sel.selectFromCard(target) }) {
            if (!a.busy && a.h == handle && !a.isFinishing) a.ask(target, fromCard = true)
        }.also { it.show() }
    }

    fun preview(handle: Long, target: Int, name: String): DeletePreview {
        val path = Native.str(Native.path(handle, target))
        val self = LongArray(4).also { Native.nodeInfo(handle, intArrayOf(target), 1, it) }
        val flags = self[3].toInt()
        val dir = flags and F_DIR != 0
        var top = emptyList<Pair<String, Long>>()
        var topTags = emptyList<TagText?>()
        var topPeek = emptyList<QuickLookInfo?>()
        var topContact = emptyList<List<QuickLookInfo>>()
        var more = 0
        if (dir) {
            val ch = IntArray(Native.childCount(handle, target))
            val cn = maxOf(0, Native.children(handle, target, SORT_SIZE, false, ch))
            val k = minOf(cn, 3)
            if (k > 0) {
                val ci = LongArray(4 * k).also { Native.nodeInfo(handle, ch, k, it) }
                top = (0 until k).map { j ->
                    val nm = Native.str(Native.name(handle, ch[j]))
                    (if (ci[4 * j + 3].toInt() and F_DIR != 0) "$nm/" else nm) to ci[4 * j]
                }
                val paths = (0 until k).map { j -> Native.str(Native.path(handle, ch[j])) }
                topTags = (0 until k).map { j ->
                    val cf = ci[4 * j + 3].toInt()
                    a.tagOf(paths[j], cf, a.blockReason(handle, ch[j], paths[j]))
                }
                topPeek = (0 until k).map { j ->
                    val cf = ci[4 * j + 3].toInt()
                    if (cf and F_DIR != 0) null
                    else peekInfo(Native.str(Native.name(handle, ch[j])), paths[j], path, ci[4 * j], cf)
                }
                topContact = (0 until k).map { j ->
                    if (ci[4 * j + 3].toInt() and F_DIR != 0) contactSheet(handle, ch[j], paths[j]) else emptyList()
                }
            }
            more = cn - k
        }
        val block = a.blockReason(handle, target, path)
        return DeletePreview(
            name = name, path = path, dir = dir, disk = self[0], apparent = self[1], items = self[2],
            flags = flags, top = top, more = more, owner = Owner.packageOf(path), viaRoot = Holder.viaRoot,
            block = block, kind = Holder.kind,
            cacheTime = if (Holder.kind == Kind.CACHE) Freshness.date(a.txt, R.string.fmt_day_time, Holder.time) else null,
            fast = a.fastAllowed(path), root = Root.state, tag = a.tagOf(path, flags, block), topTags = topTags,
            topPeek = topPeek, topContact = topContact,
            selfContact = if (dir) contactSheet(handle, target, path) else emptyList(),
            selfPeek = if (dir) null else peekInfo(name, path,
                Native.str(Native.path(handle, Native.parent(handle, target))), self[0], flags))
    }

    /** Файл [path] (папка [parent]) для превью листа и карточки поверх него. */
    fun peekInfo(name: String, path: String, parent: String, size: Long, flags: Int) =
        QuickLookInfo(name = name, path = path, parent = parent, size = size, owner = Owner.packageOf(path),
            rootOnly = Peek.rootOnly(path, Holder.viaRoot, a.packageName), flags = flags)

    /**
     * «Контактный лист» каталога [dir] ([path]): до 4 крупнейших картинок и видео поддерева
     * ([ContactSheet]); путь только для root — пусто (такие пути не смотрим).
     */
    fun contactSheet(handle: Long, dir: Int, path: String): List<QuickLookInfo> {
        if (dir < 0 || Peek.rootOnly(path, Holder.viaRoot, a.packageName)) return emptyList()
        val picked = ContactSheet.pick(dir, { nd ->
            // JNI требует массив на всех детей (меньший — отказ, -1); порядок SORT_SIZE по диску —
            // готовый, так что сведения читаем только о первых KIDS_CAP — крупнейших.
            val ch = IntArray(Native.childCount(handle, nd))
            val n = minOf(maxOf(0, Native.children(handle, nd, SORT_SIZE, false, ch)), ContactSheet.KIDS_CAP)
            val inf = LongArray(4 * maxOf(n, 1)).also { if (n > 0) Native.nodeInfo(handle, ch, n, it) }
            (0 until n).map { ContactSheet.Kid(ch[it], inf[4 * it], inf[4 * it + 3].toInt()) }
        }, { nd -> Native.str(Native.name(handle, nd)) }, { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) })
        return picked.map { kid ->
            val fp = Native.str(Native.path(handle, kid.id))
            peekInfo(Native.str(Native.name(handle, kid.id)), fp,
                Native.str(Native.path(handle, Native.parent(handle, kid.id))), kid.disk, kid.flags)
        }.filterNot { it.rootOnly }
    }

    /** onPause экрана: плеер карточки не играет в фоне. */
    fun pause() { quickLook?.pause() }

    /** onDestroy экрана. */
    fun dispose() { quickLook?.dismiss(); quickLook = null }
}
