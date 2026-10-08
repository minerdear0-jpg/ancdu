package dev.ancdu

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Режим выбора браузера [a] и удаление группы: ключи и запреты строк, панель выбора внизу, лист
 * группы и его запуск. Удаление группы — по ИМЕНАМ (байты) в папке, каждое к своему началу ищется
 * в живом дереве и снова проверяется политикой ([GroupResolver], [groupJob]). Главный поток.
 */
class BrowserSelection(private val a: BrowserActivity) {
    /**
     * Режим выбора — в ОДНОЙ папке: ключи — байты имён её детей, [selScope] — её путь (байты имён
     * от корня). Переживает сортировку, режим размера и подстановку дерева (заново по именам).
     */
    val selection = Selection()
    private var selScope: List<ByteArray> = emptyList()
    // Ключи строк и запрет выбора — лениво, до следующего load().
    private var keys = arrayOfNulls<NameKey>(0)
    /** 0 — не считано, 1 — можно выбрать, 2 — нельзя ([selBlocks]). */
    private var selState = ByteArray(0)
    private var selBlocks = arrayOfNulls<Block>(0)
    private var selectableRows: List<Pair<NameKey, Int>>? = null
    /** Панель выбора внизу (вместо подвала): ✕, итог и число, «ВСЕ»/«НИЧЕГО», «УДАЛИТЬ…». */
    lateinit var selBar: LinearLayout
        private set
    lateinit var selTotal: TextView
        private set
    lateinit var selCount: TextView
        private set
    lateinit var selAll: TextView
        private set
    lateinit var selDelete: TextView
        private set
    lateinit var selExit: TextView
        private set
    /** Итог и число — одна живая область (polite). */
    private lateinit var selText: LinearLayout
    /** Лист группы ждёт обновления дерева: сколько было выбрано (для «N уже нет на диске»). */
    var groupAsked = 0
        private set

    /** load(): новый уровень из [n] строк — ключи и запреты строк заново (лениво). */
    fun reset(n: Int) {
        keys = arrayOfNulls(n); selState = ByteArray(n); selBlocks = arrayOfNulls(n); selectableRows = null
    }

    /** load() папки [target]: выбор живёт в ОДНОЙ папке — ушли из неё, он снят; сколько было снято. */
    fun leaveOutside(target: Int): Int =
        if (selection.active && !samePath(a.pathNames(a.h, target), selScope)) selection.leave() else 0

    /** Строка [index] в режиме выбора ([dir] — каталог). */
    fun bind(index: Int, row: Row, dir: Boolean) {
        // Флажок: CheckBox для TalkBack; запрещённая строка — недоступна, с причиной.
        val on = selection.contains(a.kids[index])
        val b = blockAt(index)
        row.checked = on
        row.enabled = b == null
        row.stateDesc = a.txt.s(if (on) R.string.sel_on else R.string.sel_off)
        row.clickLabel = a.txt.s(if (on) R.string.sel_deselect else R.string.sel_select)
        if (b != null) row.desc += a.txt.s(R.string.sel_blocked_desc, a.txt.s(b.res))
        // Долгое у файла — карточка; у каталога — то же, что тап.
        if (dir) row.long = false else row.longLabel = a.txt.s(R.string.click_label_quick)
    }

    /** onSaveInstanceState: выбор — по именам (байты); очень большой не сохраняется (предел Binder). */
    fun save(out: Bundle) {
        if (selection.active && !a.busy) {
            val ks = selection.keys
            if (ks.size <= SAVE_MAX && ks.sumOf { it.bytes.size } <= SAVE_BYTES) {
                out.putInt(S_SEL_N, ks.size)
                for ((i, k) in ks.withIndex()) out.putByteArray(S_SEL + i, k.bytes)
            }
        }
    }

    /** onDestroy экрана: своих окон нет — лист группы — это [BrowserActivity.sheet], его закрывает экран. */
    fun dispose() {}

    /**
     * Удаление группы кончилось: всё — done и «освобождено X»; «Стоп» — тишина (tock уже был) и
     * «Остановлено: удалено 1 из 3 · …»; иначе — refuse и сообщение «Удалено 2 из 3» с причиной
     * по каждому. Дерево — как после одного: каталог удалён частично — оно обновится само.
     */
    fun groupDeleted() {
        val results = Holder.delResults
        if (GroupResult.rootRefused(results, Holder.delRoot)) Root.denied(a)
        if (a.h == 0L || Holder.h != a.h) {
            a.list.source = null
            a.recreate()
            return
        }
        a.list.source = a.src
        a.load(a.node, a.keepScroll)
        if (a.isFinishing) return
        val o = GroupResult.outcome(results)
        val text = when (o) {
            is GroupResult.Outcome.Done -> { Feedback.cue(a.list, Cue.DONE); GroupResult.footer(a.txt, o).also { a.note(it) } }
            is GroupResult.Outcome.Stopped -> GroupResult.footer(a.txt, o).also { a.note(it) }
            is GroupResult.Outcome.Partial -> {
                Feedback.cue(a.list, Cue.REFUSE)
                val (title, msg) = GroupResult.alert(a.txt, o)
                a.report(title, msg)
                GroupResult.footer(a.txt, o)
            }
        }
        // Holder.delDir у группы — есть частично удалённый каталог: BgScan уже обновляет дерево.
        if (Holder.delDir) {
            a.auto.afterGroup(Holder.delNames, Holder.delName, Holder.delDisk, text)
            if (!BgScan.active) a.refreshFailed(a.auto.take()!!)
        }
        Log.i("ancdu", "group delete n=${results.size} ok=${o.deleted} refresh=${a.auto.request != null}")
    }

    /** Пересоздание: тот же выбор в той же папке — по именам в дереве [h]. */
    fun restoreSelection(st: Bundle) {
        val k = st.getInt(S_SEL_N, 0)
        if (k <= 0) return
        val map = a.childMap(a.node)
        for (i in 0 until k) {
            val key = NameKey(st.getByteArray(S_SEL + i) ?: continue)
            val nd = map[key] ?: continue
            if (a.blockReason(a.h, nd, Native.str(Native.path(a.h, nd))).let { it != null && it != Block.REFRESH_FAILED }) continue
            if (!selection.active) selection.start(key, nd) else if (selection.nodeOf(key) == null) selection.toggle(key, nd)
        }
        if (selection.active) selScope = a.pathNames(a.h, a.node)
        renderSelection()
    }

    private fun samePath(a: List<ByteArray>, b: List<ByteArray>): Boolean =
        a.size == b.size && a.indices.all { a[it].contentEquals(b[it]) }

    // ---------- режим выбора ----------

    private fun keyAt(i: Int): NameKey = keys[i] ?: NameKey(Native.name(a.h, a.kids[i])).also { keys[i] = it }

    /** Запрет выбора строки [i] или null. Каталог устаревшего дерева выбрать можно: лист сам обновит дерево. */
    private fun blockAt(i: Int): Block? {
        when (selState[i].toInt()) { 1 -> return null; 2 -> return selBlocks[i] }
        val b = a.blockReason(a.h, a.kids[i], Native.str(Native.path(a.h, a.kids[i]))).takeIf { it != Block.REFRESH_FAILED }
        selState[i] = if (b == null) 1 else 2
        selBlocks[i] = b
        return b
    }

    /** Все выбираемые строки уровня (ключ, узел) — для «ВСЕ»; один проход на уровень. */
    private fun selectable(): List<Pair<NameKey, Int>> =
        selectableRows ?: (0 until a.n).filter { blockAt(it) == null }.map { keyAt(it) to a.kids[it] }.also { selectableRows = it }

    private fun refuse(b: Block) {
        Feedback.cue(a.list, Cue.REFUSE)
        a.note(a.txt.s(b.res))
    }

    /** Долгое нажатие: режим выбора с этой строкой (tick и вибрация долгого нажатия). Запрещённую — нельзя. */
    fun enterSelection(i: Int) {
        val b = blockAt(i)
        if (b != null) { refuse(b); return }
        a.cancelAsk()
        selection.start(keyAt(i), a.kids[i])
        selScope = a.pathNames(a.h, a.node)
        Feedback.cue(a.list, Cue.TAP)
        renderSelection()
        // Строка не должна уйти под панель.
        a.list.reveal(i)
        if (a.list.a11yOn()) a.list.announceForAccessibility(a.txt.s(R.string.sel_mode_announce, Fmt.count(1, a.txt.locale)))
    }

    /** Тап в режиме выбора: выбрать (tick) или снять (tock); снят последний — режим выходит. */
    fun toggleRow(i: Int) {
        val b = blockAt(i)
        if (b != null) { refuse(b); return }
        a.cancelAsk()
        val on = selection.toggle(keyAt(i), a.kids[i])
        Feedback.cue(a.list, if (on) Cue.TAP else Cue.BACK)
        renderSelection()
    }

    /** Выйти из выбора; [cleared] — подвал «Выбор снят: N» на 4 с. */
    fun leaveSelection(cleared: Boolean = false) {
        a.cancelAsk()
        val k = selection.leave()
        renderSelection()
        if (cleared && k > 0) a.note(GroupSheet.cleared(a.txt, k))
    }

    /** «ВСЕ» — каждый выбираемый (запрещённые пропускаются); «НИЧЕГО» — выход. */
    fun selectAllOrNone() {
        if (a.busy || !selection.active) return
        a.cancelAsk()
        if (selection.isAll(selectable()) { false }) {
            Feedback.cue(a.list, Cue.BACK)
            leaveSelection()
        } else {
            Feedback.cue(a.list, Cue.TAP)
            selection.selectAll(selectable()) { false }
            renderSelection()
        }
    }

    /**
     * Панель 64dp внизу: ✕ (44dp), итог (mono 15 жирный) над «3 ВЫБРАНО» (caps 12), «ВСЕ» в
     * контуре FRAME и «УДАЛИТЬ…» в контуре DANGER_TEXT. Не влезает (200%) — две строки.
     */
    fun selectionBar(): LinearLayout = a.vbox().apply {
        setBackgroundColor(C.PANEL)
        visibility = View.GONE
        hairline()
        selExit = a.label("✕", 16f, C.MUTED).apply {
            gravity = Gravity.CENTER
            minWidth = a.dp(44); minHeight = a.dp(44)
            background = a.pressable(android.graphics.Color.TRANSPARENT)
            contentDescription = a.txt.s(R.string.sel_exit)
            isClickable = true; isFocusable = true
            feedbackClick(Cue.BACK) { if (!a.busy) leaveSelection() }
        }
        selTotal = a.label("", 15f, C.TEXT, mono = true, bold = true).apply { maxLines = 1 }
        selCount = a.caps("", C.MUTED)
        selText = a.vbox().apply {
            addView(selTotal); addView(selCount)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        fun outlined(text: String, color: Int): TextView = a.caps(text, color).apply {
            gravity = Gravity.CENTER
            minHeight = a.dp(44); minWidth = a.dp(44)
            setPadding(a.dp(14), 0, a.dp(14), 0)
            background = a.pressable(android.graphics.Color.TRANSPARENT, color)
            isClickable = true; isFocusable = true
            isSoundEffectsEnabled = false
        }
        selAll = outlined(a.txt.s(R.string.sel_all), C.TEXT).apply {
            background = a.pressable(android.graphics.Color.TRANSPARENT, C.FRAME)
            // Звук — в selectAllOrNone (tick — все, tock — ничего).
            setOnClickListener { selectAllOrNone() }
        }
        // Звук даёт открывшийся лист (arm).
        selDelete = outlined(a.txt.s(R.string.sel_delete), C.DANGER_TEXT).apply { setOnClickListener { deleteSelected() } }
        val flow = Flow(a, a.dp(8), a.dp(8), endLast = true).apply {
            addView(a.hbox(8).apply { addView(selExit); addView(selText) })
            addView(a.hbox(8).apply { addView(selAll); addView(selDelete) })
        }
        addView(FrameLayout(a).apply {
            minimumHeight = a.dp(63)
            setPadding(a.dp(8), a.dp(6), a.dp(16), a.dp(6))
            addView(flow, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }.also { selBar = it }

    /** Панель и подвал по режиму; итог — из nodeInfo выбранных узлов (диск или видимый — как в списке). */
    fun renderSelection() {
        if (!::selBar.isInitialized) return
        val on = selection.active
        selBar.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            a.footerBar.visibility = View.GONE
            val nodes = selection.nodes
            val inf = LongArray(4 * maxOf(nodes.size, 1))
            if (nodes.isNotEmpty() && a.h != 0L) Native.nodeInfo(a.h, nodes, nodes.size, inf)
            val total = DeletePolicy.sum(nodes.indices.map { inf[4 * it + if (a.apparent) 1 else 0] })
            selTotal.text = Fmt.size(total, a.txt)
            selCount.text = GroupSheet.selected(a.txt, nodes.size)
            selText.contentDescription = "${selTotal.text}, ${selCount.text}"
            selAll.text = a.txt.s(if (selection.isAll(selectable()) { false }) R.string.sel_none else R.string.sel_all)
        } else {
            a.notice.visibility = View.GONE
            a.footer.visibility = if (a.footer.text.isEmpty()) View.INVISIBLE else View.VISIBLE
            a.footerBar.visibility = View.VISIBLE
        }
        a.list.refresh()
    }

    /**
     * «УДАЛИТЬ…» панели выбора. Один выбранный — ровно сегодняшний лист ([ask]); больше — лист
     * группы. Узлы — только дети текущей папки.
     */
    fun deleteSelected() {
        if (a.busy || a.h == 0L || !selection.active) return
        val nodes = selection.nodes
        if (nodes.size == 1) { a.ask(nodes[0]); return }
        askGroup()
    }

    /**
     * Лист группы. Ждёт более новое дерево — подставляется сразу (выбор — по именам в нём);
     * в кэше или индексе выбран каталог — ОДНО обновление на всю группу (как у [ask]), лист
     * откроет [refreshPending]; пропавшие с диска выбрасываются («1 уже нет на диске»).
     */
    private fun askGroup() {
        a.sheet?.dismiss()
        a.cancelAsk()
        val before = selection.count
        if (a.hasNewer()) {
            a.promotePending()
            if (a.h == 0L) return
            if (!selection.active) { a.note(GroupSheet.gone(a.txt, before)); return }
        }
        val stale = (Holder.kind == Kind.CACHE || Holder.kind == Kind.INDEX) &&
            LongArray(4 * selection.count).also { Native.nodeInfo(a.h, selection.nodes, selection.count, it) }
                .let { inf -> (0 until selection.count).any { inf[4 * it + 3].toInt() and F_DIR != 0 } }
        if (stale) {
            groupAsked = before
            a.auto.beforeGroup(a.pathNames(a.h, a.node), a.nameOf(a.node), ScanTarget(Holder.root, Holder.viaRoot))
            if (BgScan.refresh(a, Holder.root, Holder.viaRoot)) { a.renderProgress(); return }
            a.auto.take()
            Log.i("ancdu", "tree refresh not started: ${BgScan.failure}")
        }
        openGroupSheet(gone = before - selection.count)
    }

    /** Обновлённое дерево для листа группы подставлено: выбор уже заново по именам. */
    fun groupLanded() {
        if (!selection.active) { a.note(GroupSheet.gone(a.txt, groupAsked)); return }
        openGroupSheet(gone = maxOf(0, groupAsked - selection.count))
    }

    /**
     * Лист группы по выбору в папке [node] дерева [h]. Подтверждение удаляет ПО ИМЕНАМ (байты) в
     * этой папке — каждое ищется в живом дереве к своему началу.
     */
    fun openGroupSheet(gone: Int) {
        val handle = a.h
        val folder = a.node
        a.sheet?.dismiss()
        val nodes = selection.nodes
        val keys = selection.keys.map { it.bytes }
        val inf = LongArray(4 * nodes.size).also { Native.nodeInfo(handle, nodes, nodes.size, it) }
        val items = nodes.indices.map { j ->
            val nd = nodes[j]
            val path = Native.str(Native.path(handle, nd))
            val flags = inf[4 * j + 3].toInt()
            val block = a.blockReason(handle, nd, path)
            val dir = flags and F_DIR != 0
            GroupItem(name = a.nameOf(nd), dir = dir, disk = inf[4 * j], apparent = inf[4 * j + 1],
                items = inf[4 * j + 2], flags = flags, owner = Owner.packageOf(path),
                block = block, fast = a.fastAllowed(path), tag = a.tagOf(path, flags, block),
                peek = if (dir) null else a.previews.peekInfo(a.nameOf(nd), path, a.currentPath, inf[4 * j], flags), node = nd)
        }
        val (p, g) = GroupSheet.preview(a.txt, items, a.currentPath, a.packageName, Holder.viaRoot, Holder.kind,
            if (Holder.kind == Kind.CACHE) Freshness.date(a.txt, R.string.fmt_day_time, Holder.time) else null, Root.state, gone,
            contact = { a.previews.contactSheet(handle, it.node, Native.str(Native.path(handle, it.node))) })
        a.sheet = DeleteSheet(a, p, onClose = { a.refreshPending() }, group = g) { fast ->
            startGroup(handle, folder, keys, fast)
        }.also { it.show() }
    }

    /**
     * Главный поток. Удаление группы: объекты — дети [folder] сессии [handle] с именами [keys]
     * (байты). Каждый к своему началу ищется по имени в живом дереве и снова проверяется
     * политикой (на io, [groupJob]); запрещённые не отправляются, вне папки — ничего.
     */
    private fun startGroup(handle: Long, folder: Int, keys: List<ByteArray>, fast: Boolean): Boolean {
        if (a.busy || a.isDestroyed) return false
        if (handle != Holder.h || handle != a.h || folder != a.node) {
            a.alert(a.txt.s(R.string.delete_cancelled_title), a.txt.s(R.string.tree_changed)) {
                a.list.source = null; a.recreate()
            }
            return false
        }
        // Числа диалога — по именам сейчас (тот же поиск повторится к началу каждого).
        val map = a.childMap(folder)
        val found = keys.map { map[NameKey(it)] }
        val live = found.filterNotNull().toIntArray()
        val inf = LongArray(4 * maxOf(live.size, 1)).also { if (live.isNotEmpty()) Native.nodeInfo(handle, live, live.size, it) }
        val disks = HashMap<Int, Long>()
        val dirs = HashSet<Int>()
        for ((j, nd) in live.withIndex()) {
            disks[nd] = inf[4 * j]
            if (inf[4 * j + 3].toInt() and F_DIR != 0) dirs += nd
        }
        val total = DeletePolicy.sum(live.indices.map { inf[4 * it + 2] })
        val disk = DeletePolicy.sum(live.indices.map { inf[4 * it] })
        val app = a.applicationContext
        val kind = Holder.kind
        val viaRoot = Holder.viaRoot
        val sessionRoot = Holder.root
        val resolver = GroupResolver(handle, folder)
        val jobs = keys.mapIndexed { j, key ->
            val nd = found[j]
            val name = Native.str(key)
            GroupJob(name, nd != null && nd in dirs, nd?.let { disks[it] } ?: 0L) {
                groupJob(app, resolver, key, name, fast, kind, viaRoot, sessionRoot)
            }
        }
        a.keepScroll = a.list.scroll
        Log.i("ancdu", "group delete n=${keys.size} kind=$kind viaRoot=$viaRoot fast=$fast items=$total")
        Holder.deleteGroup(handle, jobs, root = viaRoot || fast, name = a.nameOf(folder), total = total, disk = disk,
            names = a.pathNames(handle, folder))
        a.showWait()
        return true
    }

    /** «ВЫБРАТЬ» карточки: файл [target] — строка текущего уровня. */
    fun selectFromCard(target: Int) {
        val i = a.kids.indexOf(target)
        if (i !in 0 until a.n) return
        if (selection.active) toggleRow(i) else enterSelection(i)
    }

    private companion object {
        const val S_SEL_N = "sel_n"
        const val S_SEL = "sel_"
        const val SAVE_MAX = 2000
        const val SAVE_BYTES = 256 * 1024
    }
}

/**
 * Дети папки [folder] дерева [handle] по байтам имени — только на io, внутри удаления группы.
 * Карта строится один раз к началу первого объекта (из живого дерева), каждый найденный узел
 * перед удалением проверяется заново: не удалён, родитель — [folder], имя — ровно то же.
 * Не сошлось — полный проход по живым детям.
 */
private class GroupResolver(val handle: Long, val folder: Int) {
    private var map: HashMap<NameKey, Int>? = null

    private fun live(): Pair<IntArray, Int> {
        val c = IntArray(Native.childCount(handle, folder))
        return c to maxOf(0, Native.children(handle, folder, SORT_NAME, false, c))
    }

    fun find(key: ByteArray): Int? {
        val m = map ?: HashMap<NameKey, Int>().also { m ->
            val (c, k) = live()
            for (i in 0 until k) m[NameKey(Native.name(handle, c[i]))] = c[i]
            map = m
        }
        m[NameKey(key)]?.let { if (verified(it, key)) return it }
        val (c, k) = live()
        for (i in 0 until k) if (verified(c[i], key)) return c[i]
        return null
    }

    private fun verified(nd: Int, key: ByteArray): Boolean {
        val f = LongArray(8).also { Native.nodeInfo(handle, intArrayOf(nd, folder), 2, it) }
        return f[3].toInt() and F_DELETED == 0 && f[7].toInt() and F_DELETED == 0 &&
            Native.parent(handle, nd) == folder && Native.name(handle, nd).contentEquals(key)
    }
}

/**
 * На io, к началу объекта группы: ребёнок папки с именем ровно [key] в ЖИВОМ дереве (не по
 * старому id узла), повторная проверка запрета ([kind], [sessionRoot] — сессии на момент
 * подтверждения), затем тот же путь удаления, что у одного ([deleteItem]). Нет в дереве —
 * ENOENT (уже удалён); запрещён — не отправляется.
 */
private fun groupJob(app: Context, res: GroupResolver, key: ByteArray, name: String, fast: Boolean, kind: Kind,
                     viaRoot: Boolean, sessionRoot: String): Planned = try {
    val handle = res.handle
    val node = res.find(key)
    if (node == null) Planned.Skip(ItemResult(name, false, 0L, -GroupResult.ENOENT, 0L))
    else {
        val inf = LongArray(4).also { Native.nodeInfo(handle, intArrayOf(node), 1, it) }
        val flags = inf[3].toInt()
        val dir = flags and F_DIR != 0
        val path = Native.str(Native.path(handle, node))
        val block = DeletePolicy.blockReason(path, false, res.folder == 0, sessionRoot, flags, kind)
            ?: if (fast && (DeletePolicy.fastBlockReason(path) != null || !Root.suExists())) Block.NO_FAST else null
        if (block != null) {
            Log.i("ancdu", "group item not sent: $block")
            Planned.Skip(ItemResult(name, dir, inf[0], -1, 0L, attempted = false, block = block))
        } else Planned.Go(deleteItem(app, handle, node, fast, kind, viaRoot))
    }
} catch (e: Exception) {
    Log.w("ancdu", "group item skipped", e)
    Planned.Skip(ItemResult(name, false, 0L, -5, 0L, attempted = false))
}
