package dev.ancdu

import android.app.Activity
import android.app.AlertDialog
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BrowserActivity : Activity() {
    /** Тексты в языке экрана (смена языка пересоздаёт экран). */
    private val txt: Txt by lazy { tx }
    lateinit var list: NcduListView
    var node = 0
        private set
    /** Идёт удаление (Holder.deleting): Native не вызывается, ввод игнорируется. */
    val busy get() = Holder.deleting
    /** Для тестов: сколько раз уровень читался из дерева. */
    var loads = 0
        private set
    private var sort = SORT_SIZE
    private var apparent = false
    private var kids = IntArray(0)
    private var n = 0
    private var info = LongArray(0)
    // Строки строк считаются лениво при первом bind и живут до следующего load().
    private var names = arrayOfNulls<String>(0)
    private var shown = arrayOfNulls<String>(0)
    private var sizes = arrayOfNulls<String>(0)
    private var pcts = arrayOfNulls<String>(0)
    private var descs = arrayOfNulls<String>(0)
    private var maxV = 0L
    private var parentV = 0L
    /** Единственный дескриптор, с которым экран вызывает Native; id узлов относятся к нему. */
    private var h = 0L
    private var keepScroll = 0
    private var wait: AlertDialog? = null
    /** Для тестов: полоса, счётчик и кнопка «Стоп» диалога удаления (null — диалога нет). */
    var waitBar: ProgressBar? = null
        private set
    var waitText: TextView? = null
        private set
    var waitStop: Button? = null
        private set
    /** Для тестов: последнее сообщение по итогам удаления (заголовок и текст). */
    var lastAlert: Pair<String, String>? = null
        private set
    private val ui = Handler(Looper.getMainLooper())
    private var lastDecile = -1
    /** Опрос прогресса удаления каждые 100 мс, только Holder.deleteProgress (атомики ядра). */
    private val poll = object : Runnable {
        override fun run() {
            if (!Holder.deleting || wait == null) return
            renderWait()
            ui.postDelayed(this, 100)
        }
    }
    /** Для тестов: открытый лист удаления. */
    var sheet: DeleteSheet? = null
        private set
    private val scrollAt = HashMap<Int, Int>()
    /** Заголовок: имя текущей папки (на верхнем уровне — путь корня). */
    lateinit var title: TextView
        private set
    /** Крошки пути: сегмент i ведёт к узлу crumbNodes[i]. */
    private lateinit var crumbs: LinearLayout
    private lateinit var crumbScroll: HorizontalScrollView
    /** Для тестов: узлы сегментов крошек от корня до текущей папки. */
    var crumbNodes = IntArray(0)
        private set
    /** Пустая папка: сообщение по центру вместо списка. */
    lateinit var empty: TextView
        private set
    private lateinit var summary: TextView
    /** Плашка вида дерева («скан · 69 312 эл. · 0,2 с»). */
    lateinit var badge: TextView
        private set
    private lateinit var footer: TextView
    /** «галерея: очистка N…», пока идёт MediaClean; иначе скрыта. */
    lateinit var gallery: TextView
        private set
    private val onClean: () -> Unit = { renderGallery() }
    lateinit var chips: LinearLayout
        private set
    /** Шапка целиком: её высота не зависит от папки, сортировки, режима размера и чипа «новее». */
    lateinit var header: LinearLayout
        private set
    /** Амберный чип «новее · обновить»: в Holder ждёт более новое дерево того же корня. */
    lateinit var newer: TextView
        private set
    /** Экземпляр закрепил дескриптор в Holder.browsers (снимается в onDestroy). */
    private var pinned = false
    /** Идёт [promotePending]: смену сессии экран обрабатывает сам, без recreate. */
    private var promoting = false
    /** Фоновый скан мог положить дерево в Holder.offer (тот слушателей не зовёт). */
    private val onBg: () -> Unit = { refreshPending() }
    /** «Обновить сам, сохранив путь»: ждёт обновлённое дерево после удаления или перед листом. */
    private val auto = AutoPromote()
    /** Обычная подсказка подвала текущего уровня (load). */
    private var hint = ""

    /**
     * Holder.set сменил сессию — вызывается синхронно внутри set, до free(старой). Экран тут же
     * перестаёт трогать старый дескриптор: отцепляет список и забывает h (JNI игнорирует 0).
     * Если идёт удаление, пересоздание сделает onDeleted (delete стоит на io раньше free).
     */
    private val onSession: () -> Unit = {
        if (Holder.h != h) {
            list.source = null
            h = 0L
            // Уходящий экран («назад», уже isFinishing) не пересоздаётся.
            if (!busy && !promoting && !isFinishing) { if (Holder.h == 0L) finish() else recreate() }
        }
    }

    private val onDeleted: (Int) -> Unit = { r ->
        dismissWait()
        // su отказал: «root ✓» из прошлого больше не правда (и быстрый путь по умолчанию — выкл.).
        // Только prefs и Root.state — и для уходящего экрана.
        if (DeletePolicy.nothingDeleted(r, Holder.delRoot)) Root.denied(this)
        if (h == 0L || Holder.h != h) {
            list.source = null
            recreate()
        } else {
            list.source = src
            load(node, keepScroll)
            if (r == 0) note(DeleteProgress.freed(txt, Holder.delDisk))
            if (r != 0 && !isFinishing) {
                val doneN = Holder.deleteProgress()
                when {
                    // «Стоп» до начала: пользователь сам остановил — без диалога.
                    DeleteProgress.isCancelled(r, doneN) -> note(DeleteProgress.cancelled(txt))
                    r == -DeleteProgress.ELOOP -> report(txt.s(R.string.delete_failed), txt.s(R.string.delete_symlink))
                    DeletePolicy.nothingDeleted(r, Holder.delRoot) -> report(txt.s(R.string.delete_failed),
                        txt.s(R.string.delete_no_root))
                    // Файл не удалён: сканировать нечего, ничего не освобождено.
                    !Holder.delDir -> note(DeleteProgress.freed(txt, 0))
                    // Удалено не всё (остановлено, частично): дерево обновляется само
                    // (BgScan.deleteFinished — до слушателей), путь сохраняется. Обновление не
                    // запустилось — сразу итог по прежнему дереву (refreshFailed).
                    else -> {
                        auto.afterDelete(Holder.delNames, Holder.delName, Holder.delDisk)
                        if (BgScan.active) footer.text = DeleteProgress.refreshing(txt)
                        else refreshFailed(auto.take()!!)
                    }
                }
                Log.i("ancdu", "delete r=$r done=$doneN refresh=${auto.request != null}")
            }
        }
    }

    /** Подвал: [text] на 4 с, затем обычная подсказка. */
    private fun note(text: String) {
        footer.text = text
        ui.removeCallbacks(restoreFooter)
        restoreFooter = Runnable { if (footer.text.toString() == text) footer.text = idleFooter() }
        ui.postDelayed(restoreFooter, 4000)
    }

    private fun idleFooter(): String = if (auto.request != null) DeleteProgress.refreshing(txt) else hint

    private var restoreFooter = Runnable {}

    /** Для тестов: текст подвала. */
    val footerText: CharSequence get() = footer.text

    private fun report(title: String, msg: String) {
        lastAlert = title to msg
        alert(title, msg)
    }

    private fun value(index: Int): Long = info[4 * index + if (apparent) 1 else 0]

    private fun nameAt(index: Int): String =
        names[index] ?: Native.str(Native.name(h, kids[index])).also { names[index] = it }

    private val src = object : RowSource {
        override val count get() = n
        override fun bind(index: Int, row: Row) {
            val v = value(index)
            val flags = info[4 * index + 3].toInt()
            val dir = flags and F_DIR != 0
            val nm = nameAt(index)
            row.name = shown[index] ?: (if (dir) "$nm/" else nm).also { shown[index] = it }
            row.size = sizes[index] ?: (if (flags and F_OTHERFS != 0) "—" else Fmt.size(v, txt)).also { sizes[index] = it }
            row.bar = ListMath.bar(v, maxV)
            row.pct = pcts[index] ?: Fmt.pct(v, parentV).also { pcts[index] = it }
            row.barColor = if (dir) C.ACCENT else C.FILE
            row.nameColor = if (dir) C.TEXT else 0xFFB8C7D9.toInt()
            when {
                flags and F_ERR != 0 -> { row.mark = "⚠"; row.nameColor = C.WARN }
                flags and F_OTHERFS != 0 -> row.mark = "↪"
                flags and F_HLDUP != 0 -> row.mark = "≡"
            }
            row.desc = descs[index] ?: buildString {
                append(nm); append(", "); append(row.size)
                if (row.pct.isNotEmpty()) { append(", "); append(row.pct) }
                if (dir) { append(", "); append(txt.s(R.string.desc_dir)) }
                // F_ERR — и нет доступа, и незаконченное удаление: данные узла неполные.
                if (flags and F_ERR != 0) { append(", "); append(txt.s(R.string.desc_incomplete)) }
            }.also { descs[index] = it }
        }
        override fun click(index: Int) {
            if (busy) return
            if (info[4 * index + 3].toInt() and F_DIR == 0) { askDelete(index); return }
            scrollAt[node] = list.scroll
            load(kids[index], 0)
        }
        override fun longClick(index: Int) { if (!busy) askDelete(index) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkBars()
        h = Holder.h
        if (h == 0L) { finish(); return }
        Holder.pinBrowser(); pinned = true
        Root.load(this)
        BgScan.bind(this)
        val top = vbox(12).also { header = it }.apply { setPadding(dp(8), dp(12), dp(16), dp(12)); setBackgroundColor(C.BG) }
        title = label("", 20f, C.TEXT, bold = true).apply {
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        crumbs = hbox()
        crumbScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            // На корне крошек нет (путь — в заголовке): строка не схлопывается, шапка не прыгает.
            minimumHeight = dp(44)
            addView(crumbs)
        }
        top.addView(hbox(4).apply {
            addView(backButton { onBackPressed() })
            addView(vbox().apply {
                addView(title)
                addView(crumbScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
        summary = label("", 13f, C.MUTED, mono = true).apply {
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
        }
        badge = label("", 12f, C.ACCENT, mono = true).apply {
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
        }
        newer = label(txt.s(R.string.newer_chip), 12f, ON_ACCENT, mono = true, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(10), 0, dp(10), 0)
            background = rounded(C.ACCENT, dp(10).toFloat())
            isClickable = true; isFocusable = true
            contentDescription = txt.s(R.string.newer_desc)
            setOnClickListener { promotePending() }
            visibility = View.GONE
        }
        chips = hbox(6)
        top.addView(vbox().apply {
            setPadding(dp(8), 0, 0, 0)
            addView(summary, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            // Строка плашки всегда высотой с чип «новее» (44dp): его появление не двигает список.
            addView(hbox(8).apply {
                minimumHeight = dp(44)
                addView(badge, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(newer)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
        top.addView(chips, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        list = NcduListView(this).apply { longClickLabel = txt.s(R.string.long_click_label) }
        empty = label("", 15f, C.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), 0)
            visibility = View.GONE
        }
        footer = label("", 12f, C.MUTED, mono = true).apply { setPadding(dp(16), dp(10), dp(16), dp(10)) }
        gallery = label("", 12f, C.MUTED, mono = true).apply {
            setPadding(dp(16), 0, dp(16), dp(10))
            visibility = View.GONE
        }
        setContentView(vbox().apply {
            setBackgroundColor(C.BG)
            addView(top)
            addView(FrameLayout(this@BrowserActivity).apply {
                addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(footer)
            addView(gallery)
        })
        Holder.addDeleteListener(onDeleted)
        MediaClean.addListener(onClean)
        renderGallery()
        Holder.addSessionListener(onSession)
        BgScan.addListener(onBg)
        if (busy) {
            // Удаление начато прежним экземпляром: дерево не читаем до onDeleted.
            showWait()
        } else {
            list.source = src
            load(0, 0)
        }
    }

    override fun onResume() {
        super.onResume()
        // Сессию сменили, пока экран был скрыт: старые id узлов к новому дереву не относятся.
        if (!busy && Holder.h != h) { list.source = null; recreate(); return }
        refreshPending()
    }

    /** Есть ли в Holder более новое дерево того же корня и того же режима su. Главный поток. */
    private fun hasNewer(): Boolean =
        Swap.newer(Holder.pending, Holder.pendingRoot, Holder.pendingViaRoot, Holder.root, Holder.viaRoot)

    private fun sheetOpen(): Boolean = sheet?.dialog?.isShowing == true

    /**
     * Показать/скрыть «новее · обновить». Главный поток; Holder.offer слушателей не зовёт.
     * Взведён [auto] — ждущее дерево подставляется само (никогда при удалении или открытом листе);
     * обновить не вышло — флаг снимается.
     */
    fun refreshPending() {
        if (!::newer.isInitialized) return
        if (h != 0L && !isFinishing && !isDestroyed) {
            val nw = hasNewer()
            // Никогда во время удаления (Holder.deleting) и при открытом листе.
            if (auto.ready(nw, busy = Holder.deleting, sheetOpen = sheetOpen())) { landed(auto.take()!!); return }
            if (!Holder.deleting && auto.failed(nw, BgScan.active)) refreshFailed(auto.take()!!)
        }
        newer.visibility = if (hasNewer() && h != 0L) View.VISIBLE else View.GONE
    }

    /** Обновлённое дерево готово: подставить (путь сохраняется) и показать итог запроса [r]. */
    private fun landed(r: AutoPromote.Request) {
        promotePending()
        if (h == 0L) return
        val hit = resolveNode(r.names)
        val disk = if (hit.exact) LongArray(4).also { Native.nodeInfo(h, intArrayOf(hit.node), 1, it) }[0] else 0L
        when (val o = AutoPromote.outcome(txt, r, hit.exact, disk)) {
            is AutoPromote.Outcome.Footer -> note(o.text)
            AutoPromote.Outcome.Sheet -> openSheet(hit.node)
        }
    }

    /**
     * Обновить не вышло (скан не запустился или не удался, su отказал): дерево остаётся прежним.
     * После удаления — итог по нему (нижняя граница освобождённого); ждущий лист — с REFRESH_FAILED.
     */
    private fun refreshFailed(r: AutoPromote.Request) {
        Log.i("ancdu", "tree refresh failed: ${BgScan.failure}")
        footer.text = hint
        val hit = resolveNode(r.names)
        if (r.delDisk != null) {
            val disk = if (hit.exact) LongArray(4).also { Native.nodeInfo(h, intArrayOf(hit.node), 1, it) }[0] else 0L
            note(AutoPromote.unrefreshed(txt, r, hit.exact, disk))
        } else if (hit.exact) {
            openSheet(hit.node)
        }
    }

    /** Отменить ждущий лист; его su-обновление, ещё не начатое, снимается с очереди BgScan. */
    private fun cancelAsk() {
        auto.cancelSheet()?.target?.let { BgScan.unqueue(it) }
    }

    /** Узел по байтам имён от корня в дереве [h] (файл или каталог). */
    private fun resolveNode(names: List<ByteArray>): PathWalk.Hit =
        PathWalk.resolve(names) { nd, nm -> child(nd, nm, dirOnly = false) }

    /** Байты имён пути узла [nd] дерева [handle] от корня (без самого корня). */
    private fun pathNames(handle: Long, nd: Int): List<ByteArray> {
        val chain = ArrayList<ByteArray>()
        var c = nd
        while (c > 0) { chain += Native.name(handle, c); c = Native.parent(handle, c) }
        chain.reverse()
        return chain
    }

    /**
     * «новее · обновить»: подставляет ждущее дерево и открывает ТОТ ЖЕ путь по именам — от корня
     * нового дерева; не нашлось — ближайший существующий предок. Имена читаются из старого
     * дескриптора ДО promote (set освобождает его на io после слушателей). Прокрутка — по
     * возможности: та же, если путь найден целиком.
     */
    fun promotePending() {
        if (busy || h == 0L || !hasNewer()) return
        // Байты имён, не строки: невалидный UTF-8 декодируется неоднозначно.
        val names = pathNames(h, node)
        val keep = list.scroll
        list.source = null
        promoting = true
        try { Holder.promote() } finally { promoting = false }
        h = Holder.h
        scrollAt.clear()
        if (h == 0L) { finish(); return }
        list.source = src
        val hit = PathWalk.resolve(names) { nd, nm -> child(nd, nm, dirOnly = true) }
        load(hit.node, if (hit.exact) keep else 0)
        refreshPending()
    }

    /** Ребёнок [nd] с именем ровно [nm] (байты) в дереве [h] — каталог, если [dirOnly], — или null. */
    private fun child(nd: Int, nm: ByteArray, dirOnly: Boolean): Int? {
        val c = IntArray(Native.childCount(h, nd))
        val k = maxOf(0, Native.children(h, nd, SORT_NAME, false, c))
        if (k == 0) return null
        val inf = LongArray(4 * k).also { Native.nodeInfo(h, c, k, it) }
        for (i in 0 until k)
            if ((!dirOnly || inf[4 * i + 3].toInt() and F_DIR != 0) && Native.name(h, c[i]).contentEquals(nm)) return c[i]
        return null
    }

    override fun onPause() {
        super.onPause()
        // «Назад»: Main.onResume идёт раньше нашего onDestroy — закрепление снимается здесь.
        if (isFinishing) unpin()
    }

    private fun unpin() { if (pinned) { pinned = false; Holder.unpinBrowser() } }

    override fun onDestroy() {
        Holder.removeDeleteListener(onDeleted)
        Holder.removeSessionListener(onSession)
        BgScan.removeListener(onBg)
        MediaClean.removeListener(onClean)
        unpin()
        cancelAsk()
        dismissWait()
        ui.removeCallbacks(restoreFooter)
        sheet?.dismiss(); sheet = null
        super.onDestroy()
    }

    private fun progress(): LongArray = LongArray(6).also { Native.progress(h, it) }

    private fun renderGallery() {
        if (!::gallery.isInitialized) return
        gallery.visibility = if (MediaClean.running) View.VISIBLE else View.GONE
        gallery.text = txt.s(R.string.gallery_cleaning, Fmt.count(MediaClean.cleaned, txt.locale))
    }

    /** Не setSingleLine: он делает setLines(1) и затирает minHeight (общее поле mMinimum) — чип стал бы плоским. */
    private fun TextView.oneLine() { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }

    private fun renderChips() {
        chips.removeAllViews()
        // Равные веса: ширина чипов не зависит от текста. Зазор — SHOW_DIVIDER_MIDDLE у hbox(6).
        val lp = { LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
        chips.addView(chip(txt.s(R.string.sort_size), sort == SORT_SIZE) { setSort(SORT_SIZE) }.apply { oneLine() }, lp())
        chips.addView(chip(txt.s(R.string.sort_name), sort == SORT_NAME) { setSort(SORT_NAME) }.apply { oneLine() }, lp())
        chips.addView(chip(txt.s(if (apparent) R.string.size_apparent else R.string.size_disk), false) { setApparent(!apparent) }.apply {
            oneLine()
            contentDescription = txt.s(if (apparent) R.string.size_desc_apparent else R.string.size_desc_disk)
        }, lp())
    }

    fun setSort(k: Int) { if (busy) return; sort = k; load(node, 0) }
    fun setApparent(v: Boolean) { if (busy) return; apparent = v; load(node, 0) }

    private fun load(target: Int, restore: Int) {
        // Любая навигация отменяет ждущий лист (подстановка дерева при этом всё равно будет).
        cancelAsk()
        loads++
        node = target
        // Массив — по childCount (с удалёнными детьми); показываем столько, сколько вернул children().
        kids = IntArray(Native.childCount(h, node))
        n = maxOf(0, Native.children(h, node, sort, apparent, kids))
        names = arrayOfNulls(n); shown = arrayOfNulls(n); sizes = arrayOfNulls(n)
        pcts = arrayOfNulls(n); descs = arrayOfNulls(n)
        info = LongArray(4 * maxOf(n, 1))
        if (n > 0) Native.nodeInfo(h, kids, n, info)
        val self = LongArray(4).also { Native.nodeInfo(h, intArrayOf(node), 1, it) }
        parentV = self[if (apparent) 1 else 0]
        maxV = (0 until n).maxOfOrNull { value(it) } ?: 0L
        renderHeader()
        empty.visibility = if (n == 0) View.VISIBLE else View.GONE
        empty.text = txt.s(if (self[3].toInt() and F_ERR == 0) R.string.folder_empty else R.string.folder_no_access)
        summary.text = "${Fmt.size(parentV, txt)} · ${txt.items(self[2])}"
        val p = progress()
        val full = p[0] == ST_FULL.toLong()
        badge.text = Badge.text(txt, Holder.kind, Holder.time, Holder.ms, full)
        badge.setTextColor(if (full) C.WARN else C.ACCENT)
        hint = txt.s(R.string.browser_hint) +
            if (p[3] > 0) "   ⚠ " + txt.q(R.plurals.errors, p[3], Fmt.count(p[3], txt.locale)) else ""
        footer.text = idleFooter()
        renderChips()
        refreshPending()
        list.refresh()
        list.scroll = restore
    }

    /**
     * Заголовок — текущая папка (на корне — путь корня), крошки — её предки: путь виден один раз.
     * Главный поток, чтения дерева — с [h].
     */
    private fun renderHeader() {
        val chain = ArrayList<Int>()
        var c = node
        while (c > 0) { chain += c; c = Native.parent(h, c) }
        chain += 0
        chain.reverse()
        crumbNodes = chain.toIntArray()
        val rootName = Native.str(Native.path(h, 0)).ifEmpty { Holder.root }
        title.text = if (node == 0) rootName else nameOf(node)
        crumbs.removeAllViews()
        for ((k, nd) in chain.dropLast(1).withIndex()) {
            if (k > 0) crumbs.addView(label("›", 12f, C.MUTED, mono = true).apply {
                setPadding(dp(2), 0, dp(2), 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            val text = if (nd == 0) rootName else nameOf(nd)
            crumbs.addView(label(text, 12f, C.MUTED, mono = true).apply {
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(44)
                setPadding(dp(4), 0, dp(4), 0)
                isClickable = true; isFocusable = true
                contentDescription = txt.s(R.string.crumb_go, text)
                setOnClickListener { jumpTo(nd) }
            })
        }
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun nameOf(nd: Int): String = Native.str(Native.name(h, nd))

    /** Переход к предку [target] из крошек: его прокрутка восстанавливается, более глубоких — забываются. */
    fun jumpTo(target: Int) {
        if (busy || target == node || target !in crumbNodes) return
        for (nd in crumbNodes.dropWhile { it != target }.drop(1)) scrollAt.remove(nd)
        load(target, scrollAt.remove(target) ?: 0)
    }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        if (busy) return
        cancelAsk()
        if (node != 0) {
            val p = maxOf(Native.parent(h, node), 0)
            load(p, scrollAt.remove(p) ?: 0)
        } else {
            super.onBackPressed()
        }
    }

    private fun askDelete(i: Int) = ask(kids[i])

    /**
     * Лист удаления узла [target]. Главный поток. Удаляется только из свежего дерева: ждёт более
     * новое — подставляется сразу (без скана), узел ищется в нём по байтам имён; каталог кэша или
     * индекса — экран сам пересканирует корень в том же режиме su, лист откроет [refreshPending].
     */
    private fun ask(target: Int) {
        sheet?.dismiss()
        cancelAsk()
        var t = target
        if (hasNewer()) {
            val names = pathNames(h, t)
            val name = nameOf(t)
            promotePending()
            if (h == 0L) return
            val hit = resolveNode(names)
            if (!hit.exact) { note(DeleteProgress.gone(txt, name)); return }
            t = hit.node
        }
        if (blockReason(h, t, Native.str(Native.path(h, t))) == Block.REFRESH_FAILED) {
            auto.beforeDelete(pathNames(h, t), nameOf(t), ScanTarget(Holder.root, Holder.viaRoot))
            if (BgScan.refresh(this, Holder.root, Holder.viaRoot)) { footer.text = DeleteProgress.refreshing(txt); return }
            auto.take()
            Log.i("ancdu", "tree refresh not started: ${BgScan.failure}")
        }
        openSheet(t)
    }

    /** Лист узла [target] дерева [h]; пара (дескриптор, узел) фиксируется здесь, подтверждение удаляет ровно её. */
    private fun openSheet(target: Int) {
        val handle = h
        sheet?.dismiss()
        // Закрыт лист — подставить дерево, если оно пришло, пока лист был открыт.
        sheet = DeleteSheet(this, preview(handle, target, nameOf(target)), onClose = { refreshPending() }) { fast ->
            startDelete(handle, target, fast)
        }.also { it.show() }
    }

    private fun preview(handle: Long, target: Int, name: String): DeletePreview {
        val path = Native.str(Native.path(handle, target))
        val self = LongArray(4).also { Native.nodeInfo(handle, intArrayOf(target), 1, it) }
        val flags = self[3].toInt()
        val dir = flags and F_DIR != 0
        var top = emptyList<Pair<String, Long>>()
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
            }
            more = cn - k
        }
        return DeletePreview(
            name = name, path = path, dir = dir, disk = self[0], apparent = self[1], items = self[2],
            flags = flags, top = top, more = more, owner = Owner.packageOf(path), viaRoot = Holder.viaRoot,
            block = blockReason(handle, target, path), kind = Holder.kind,
            cacheTime = if (Holder.kind == Kind.CACHE) Freshness.date(txt, R.string.fmt_day_time, Holder.time) else null,
            fast = fastAllowed(path), root = Root.state)
    }

    /** Главный поток, [handle] — живой дескриптор экрана. null — узел можно удалять. */
    private fun blockReason(handle: Long, target: Int, path: String): Block? {
        val inf = LongArray(4).also { Native.nodeInfo(handle, intArrayOf(target), 1, it) }
        return DeletePolicy.blockReason(path, target == 0, Native.parent(handle, target) == 0,
            Holder.root, inf[3].toInt(), Holder.kind)
    }

    /**
     * Неотменяемый диалог удаления: полоса (max 1000 — промилле от items узла на момент
     * подтверждения), «N / M эл. · м:сс» и «Стоп». Данные — из Holder, поэтому новый экземпляр
     * после пересоздания показывает тот же диалог и продолжает опрос.
     */
    /** Быстрый путь root в обход FUSE: путь сопоставляется с /data/media, оба разрешены, есть su. */
    private fun fastAllowed(path: String): Boolean =
        DeletePolicy.fastBlockReason(path) == null && Root.suExists()

    private fun showWait() {
        list.source = null
        footer.text = txt.s(R.string.deleting)
        dismissWait()
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 1000
            contentDescription = txt.s(R.string.delete_progress_desc)
        }
        val text = label("", 13f, C.MUTED, mono = true)
        val stop = Button(this).apply {
            minHeight = dp(44)
            setOnClickListener { stopDelete() }
        }
        val body = vbox(10).apply {
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(text)
            addView(stop, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.END
            })
        }
        waitBar = bar; waitText = text; waitStop = stop
        lastDecile = -1
        wait = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(DeleteProgress.title(txt, Holder.delName)).setView(body).setCancelable(false).show()
        renderWait()
        ui.postDelayed(poll, 100)
    }

    private fun renderWait() {
        val bar = waitBar ?: return
        val done = Holder.deleteProgress()
        val total = Holder.delTotal
        bar.progress = DeleteProgress.permille(done, total)
        waitText?.text = DeleteProgress.line(txt, done, total, SystemClock.elapsedRealtime() - Holder.delStartMs)
        waitStop?.apply {
            text = txt.s(if (Holder.delStopping) R.string.stopping else R.string.stop)
            isEnabled = !Holder.delStopping
        }
        val dec = DeleteProgress.decile(done, total)
        if (dec != lastDecile) {
            if (lastDecile >= 0 && bar.a11yOn()) bar.announceForAccessibility(DeleteProgress.announce(txt, done, total))
            lastDecile = dec
        }
    }

    /** «Стоп» диалога: ядро прекращает обход, удалённое остаётся удалённым. */
    fun stopDelete() {
        if (!busy) return
        Holder.deleteStop()
        renderWait()
    }

    private fun dismissWait() {
        ui.removeCallbacks(poll)
        wait?.dismiss(); wait = null
        waitBar = null; waitText = null; waitStop = null
    }

    /**
     * Главный поток. Удаляет узел [target] сессии [handle] — ровно ту пару, что показал диалог.
     * Завершение получает живой экземпляр через Holder (onDeleted), затем [done].
     */
    private fun startDelete(handle: Long, target: Int, fast: Boolean = false, done: (Int) -> Unit = {},
                            testBulk: ((stopped: () -> Boolean, add: (Long) -> Unit) -> Unit)? = null): Boolean {
        if (busy || isDestroyed) return false
        if (handle != Holder.h || handle != h) {
            alert(txt.s(R.string.delete_cancelled_title), txt.s(R.string.tree_changed)) {
                list.source = null; recreate()
            }
            return false
        }
        // Повторная проверка запретов: путь мимо диалога (тесты) тоже не удалит системное.
        val pathBytes = Native.path(handle, target)
        val path = Native.str(pathBytes)
        if (blockReason(handle, target, path) != null) return false
        // Быстрый путь: и исходный, и сопоставленный /data/media-путь проверены политикой.
        if (fast && !fastAllowed(path)) return false
        val helper = if (Holder.viaRoot || fast) Root.helper(this) else null
        val inf = LongArray(4).also { Native.nodeInfo(handle, intArrayOf(target), 1, it) }
        val items = inf[2]
        val disk = inf[0]
        val dir = inf[3].toInt() and F_DIR != 0
        val name = Native.str(Native.name(handle, target))
        val app = applicationContext
        keepScroll = list.scroll
        // Без root в общем хранилище: сначала пачками через MediaProvider, затем ядро — как всегда.
        // Индекс флагов ссылок не знает — для него массового шага нет.
        val bulkPath = if (Holder.kind == Kind.INDEX) null
            else MediaBulk.target(pathBytes, viaRoot = Holder.viaRoot, fast = fast)
        if (bulkPath == null && !fast && !Holder.viaRoot && MediaBulk.exactPath(pathBytes) == null)
            Log.i("ancdu", "bulk delete skipped: path is not valid UTF-8")
        Log.i("ancdu", "delete kind=${Holder.kind} viaRoot=${Holder.viaRoot} fast=$fast bulk=${bulkPath != null} items=$items")
        val cr = app.contentResolver
        val cleanPath = if (fast) MediaBulk.cleanable(pathBytes) else null
        val rootFlags = inf[3].toInt()
        Holder.delete(handle, target, helper, done, name, items, disk, names = pathNames(handle, target), dir = dir, media = fast,
            bulk = testBulk ?: bulkPath?.let { p -> { stopped, add ->
                // На io, под правилами delete: чтение дерева [handle] (экран его сейчас не читает).
                // MediaProvider канонизирует путь перед unlink — ссылка в поддереве увела бы
                // удаление за пределы узла.
                if (MediaBulk.subtreeHas(target, rootFlags, F_SYMLINK, stopped) { nd -> kidsWithFlags(handle, nd) } ||
                    Files.isSymbolicLink(Paths.get(p))) {
                    Log.i("ancdu", "bulk delete skipped: symlink in subtree, or stopped")
                } else {
                    val out = MediaBulk.run(ResolverRows(cr), p, dir, stopped = stopped, onDeleted = add)
                    out.error?.let { Log.w("ancdu", "bulk delete fell back to rm_tree after ${out.deleted} rows", it) }
                    Log.i("ancdu", "bulk delete rows=${out.deleted} matched=${out.matched} stopped=${out.stopped}")
                }
            } },
            afterIo = if (!fast) null else { r ->
                // MediaProvider не видел удаления в обход FUSE — убираем устаревшие строки:
                // путь исчез — пачками в фоне (MediaClean); частично — scanFile, как раньше
                // (удаление строк через MediaProvider удалило бы и оставшиеся файлы).
                if (r == 0 && cleanPath != null) MediaClean.enqueue(app, cleanPath, dir)
                else {
                    // Путь не точный UTF-8: Native.str дал U+FFFD — scanFile этого пути ничего не найдёт.
                    if (cleanPath == null) Log.w("ancdu", "gallery cleanup: path is not valid UTF-8, scanFile is a no-op")
                    MediaScannerConnection.scanFile(app, arrayOf(path), null, null)
                }
            })
        showWait()
        return true
    }

    /**
     * Для тестов: синхронное удаление строки i. Вызывать с тестового потока, не с главного.
     * [bulk] — шаг вместо массового (на io до ядра), как у Holder.delete.
     */
    fun deleteBlocking(i: Int, bulk: ((stopped: () -> Boolean, add: (Long) -> Unit) -> Unit)? = null): Int {
        check(Looper.myLooper() != Looper.getMainLooper()) { "deleteBlocking on the main thread" }
        var r = Int.MIN_VALUE
        val latch = CountDownLatch(1)
        runOnUiThread {
            if (!startDelete(h, kids[i], done = { r = it; latch.countDown() }, testBulk = bulk)) latch.countDown()
        }
        check(latch.await(60, TimeUnit.SECONDS)) { "delete did not finish in 60 s" }
        return r
    }
}

/** Живые дети [nd] дерева [handle] и их флаги. Вызывается на io из массового шага удаления
 * (вне экрана: лямбда удаления не держит Activity). Ошибка children() — исключение (отказ
 * закрытый: массовый шаг прерывается, удаляет rm_tree). SORT_SIZE по disk — готовый порядок без сортировки. */
private fun kidsWithFlags(handle: Long, nd: Int): Pair<IntArray, IntArray> {
    val c = IntArray(Native.childCount(handle, nd))
    val k = MediaBulk.checkedCount(Native.children(handle, nd, SORT_SIZE, false, c))
    if (k == 0) return IntArray(0) to IntArray(0)
    val inf = LongArray(4 * k).also { Native.nodeInfo(handle, c, k, it) }
    return c.copyOf(k) to IntArray(k) { inf[4 * it + 3].toInt() }
}
