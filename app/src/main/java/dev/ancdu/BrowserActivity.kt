package dev.ancdu

import android.app.Activity
import android.app.AlertDialog
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BrowserActivity : Activity() {
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
    private lateinit var chips: LinearLayout

    /**
     * Holder.set сменил сессию — вызывается синхронно внутри set, до free(старой). Экран тут же
     * перестаёт трогать старый дескриптор: отцепляет список и забывает h (JNI игнорирует 0).
     * Если идёт удаление, пересоздание сделает onDeleted (delete стоит на io раньше free).
     */
    private val onSession: () -> Unit = {
        if (Holder.h != h) {
            list.source = null
            h = 0L
            if (!busy) { if (Holder.h == 0L) finish() else recreate() }
        }
    }

    private val onDeleted: (Int) -> Unit = { r ->
        dismissWait()
        if (h == 0L || Holder.h != h) {
            list.source = null
            recreate()
        } else {
            list.source = src
            load(node, keepScroll)
            if (r == 0) showFreed(Holder.delDisk)
            if (r != 0 && !isFinishing) {
                val doneN = Holder.deleteProgress()
                when {
                    DeleteProgress.isCancelled(r, doneN) -> report("Удаление отменено", DeleteProgress.CANCELLED)
                    r == -DeleteProgress.EINTR -> report("Удаление остановлено",
                        DeleteProgress.stopped(doneN, Holder.delTotal))
                    r == -DeleteProgress.ELOOP -> report("Не удалось удалить",
                        "Путь проходит через символическую ссылку — ничего не удалено.")
                    DeletePolicy.nothingDeleted(r, Holder.delRoot) -> report("Не удалось удалить",
                        "Не удалось получить root — ничего не удалено (код $r).")
                    else -> report("Не удалось удалить полностью",
                        "Часть файлов осталась (код $r). Удалено частично — пересканируйте.")
                }
            }
        }
    }

    /** Полный успех: «освобождено …» в подвале на 4 с, затем обычная подсказка. */
    private fun showFreed(disk: Long) {
        val normal = footer.text
        val freed = DeleteProgress.freed(disk)
        footer.text = freed
        ui.removeCallbacks(restoreFooter)
        restoreFooter = Runnable { if (footer.text.toString() == freed) footer.text = normal }
        ui.postDelayed(restoreFooter, 4000)
    }

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
            row.size = sizes[index] ?: (if (flags and F_OTHERFS != 0) "—" else Fmt.size(v)).also { sizes[index] = it }
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
                if (dir) append(", каталог")
                if (flags and F_ERR != 0) append(", нет доступа")
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
        val top = vbox(12).apply { setPadding(dp(8), dp(12), dp(16), dp(12)); setBackgroundColor(C.BG) }
        title = label("", 20f, C.TEXT, bold = true).apply {
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        crumbs = hbox()
        crumbScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(crumbs)
        }
        top.addView(hbox(4).apply {
            addView(backButton { onBackPressed() })
            addView(vbox().apply {
                addView(title)
                addView(crumbScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
        summary = label("", 13f, C.MUTED, mono = true)
        badge = label(Holder.label, 12f, C.ACCENT, mono = true)
        chips = hbox(6)
        top.addView(hbox(8).apply {
            setPadding(dp(8), 0, 0, 0)
            addView(vbox().apply { addView(summary); addView(badge) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(chips)
        })
        list = NcduListView(this).apply { longClickLabel = "Удалить или подробнее" }
        empty = label("", 15f, C.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), 0)
            visibility = View.GONE
        }
        footer = label("", 12f, C.MUTED, mono = true).apply { setPadding(dp(16), dp(10), dp(16), dp(10)) }
        setContentView(vbox().apply {
            setBackgroundColor(C.BG)
            addView(top)
            addView(FrameLayout(this@BrowserActivity).apply {
                addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(footer)
        })
        Holder.addDeleteListener(onDeleted)
        Holder.addSessionListener(onSession)
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
        if (!busy && Holder.h != h) { list.source = null; recreate() }
    }

    override fun onDestroy() {
        Holder.removeDeleteListener(onDeleted)
        Holder.removeSessionListener(onSession)
        dismissWait()
        ui.removeCallbacks(restoreFooter)
        sheet?.dismiss(); sheet = null
        super.onDestroy()
    }

    private fun progress(): LongArray = LongArray(6).also { Native.progress(h, it) }

    private fun renderChips() {
        chips.removeAllViews()
        chips.addView(chip("размер", sort == SORT_SIZE) { setSort(SORT_SIZE) })
        chips.addView(chip("имя", sort == SORT_NAME) { setSort(SORT_NAME) })
        chips.addView(chip(if (apparent) "apparent" else "disk", false) { setApparent(!apparent) })
    }

    fun setSort(k: Int) { if (busy) return; sort = k; load(node, 0) }
    fun setApparent(v: Boolean) { if (busy) return; apparent = v; load(node, 0) }

    private fun load(target: Int, restore: Int) {
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
        empty.text = when {
            self[3].toInt() and F_ERR == 0 -> "пусто"
            Holder.viaRoot -> "⚠ нет доступа"
            else -> "⚠ нет доступа — сканируйте как root"
        }
        summary.text = "${Fmt.size(parentV)} · ${Fmt.count(self[2])} эл."
        val p = progress()
        val full = p[0] == ST_FULL.toLong()
        badge.text = Holder.label + if (full) " · неполный" else ""
        badge.setTextColor(if (full) C.WARN else C.ACCENT)
        footer.text = "тап — открыть · долгий — подробнее, удалить" + if (p[3] > 0) "   ⚠ ${Fmt.count(p[3])} ошибок" else ""
        renderChips()
        list.refresh()
        list.scroll = restore
    }

    /** Заголовок и крошки текущего узла. Главный поток, чтения дерева — с [h]. */
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
        for ((k, nd) in chain.withIndex()) {
            val last = k == chain.size - 1
            if (k > 0) crumbs.addView(label("›", 12f, C.MUTED, mono = true).apply {
                setPadding(dp(2), 0, dp(2), 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            val text = if (nd == 0) rootName else nameOf(nd)
            crumbs.addView(label(text, 12f, if (last) C.TEXT else C.MUTED, mono = true).apply {
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(44)
                setPadding(dp(4), 0, dp(4), 0)
                if (!last) {
                    isClickable = true; isFocusable = true
                    contentDescription = "перейти к $text"
                    setOnClickListener { jumpTo(nd) }
                } else {
                    contentDescription = "текущая папка $text"
                }
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
        if (node != 0) {
            val p = maxOf(Native.parent(h, node), 0)
            load(p, scrollAt.remove(p) ?: 0)
        } else {
            super.onBackPressed()
        }
    }

    /**
     * Лист удаления для строки [i]. Главный поток, чтения дерева — с закреплённым [h]; пара
     * (дескриптор, узел) фиксируется здесь, подтверждение удаляет ровно её.
     */
    private fun askDelete(i: Int) {
        val handle = h
        val target = kids[i]
        sheet?.dismiss()
        sheet = DeleteSheet(this, preview(handle, target, nameAt(i))) { fast -> startDelete(handle, target, fast) }
            .also { it.show() }
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
            cacheTime = if (Holder.kind == Kind.CACHE) Holder.label.removePrefix("кэш от ") else null,
            fast = fastAllowed(path))
    }

    /** Главный поток, [handle] — живой дескриптор экрана. null — узел можно удалять. */
    private fun blockReason(handle: Long, target: Int, path: String): String? {
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
        footer.text = "Удаление…"
        dismissWait()
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 1000
            contentDescription = "Прогресс удаления"
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
            .setTitle(DeleteProgress.title(Holder.delName)).setView(body).setCancelable(false).show()
        renderWait()
        ui.postDelayed(poll, 100)
    }

    private fun renderWait() {
        val bar = waitBar ?: return
        val done = Holder.deleteProgress()
        val total = Holder.delTotal
        bar.progress = DeleteProgress.permille(done, total)
        waitText?.text = DeleteProgress.line(done, total, SystemClock.elapsedRealtime() - Holder.delStartMs)
        waitStop?.apply {
            text = if (Holder.delStopping) "Останавливаю…" else "Стоп"
            isEnabled = !Holder.delStopping
        }
        val dec = DeleteProgress.decile(done, total)
        if (dec != lastDecile) {
            if (lastDecile >= 0) bar.announceForAccessibility(DeleteProgress.announce(done, total))
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
    private fun startDelete(handle: Long, target: Int, fast: Boolean = false, done: (Int) -> Unit = {}): Boolean {
        if (busy || isDestroyed) return false
        if (handle != Holder.h || handle != h) {
            alert("Удаление отменено", "Дерево сменилось, пока был открыт диалог. Ничего не удалено.") {
                list.source = null; recreate()
            }
            return false
        }
        // Повторная проверка запретов: путь мимо диалога (тесты) тоже не удалит системное.
        val path = Native.str(Native.path(handle, target))
        if (blockReason(handle, target, path) != null) return false
        // Быстрый путь: и исходный, и сопоставленный /data/media-путь проверены политикой.
        if (fast && !fastAllowed(path)) return false
        val helper = if (Holder.viaRoot || fast) Root.helper(this) else null
        val inf = LongArray(4).also { Native.nodeInfo(handle, intArrayOf(target), 1, it) }
        val items = inf[2]
        val disk = inf[0]
        val name = Native.str(Native.name(handle, target))
        val app = applicationContext
        keepScroll = list.scroll
        Holder.delete(handle, target, helper, done, name, items, disk, media = fast,
            afterIo = if (!fast) null else {
                // MediaProvider не видел удаления в обход FUSE — убираем устаревшие строки.
                { MediaScannerConnection.scanFile(app, arrayOf(path), null, null) }
            })
        showWait()
        return true
    }

    /** Для тестов: синхронное удаление строки i. Вызывать с тестового потока, не с главного. */
    fun deleteBlocking(i: Int): Int {
        check(Looper.myLooper() != Looper.getMainLooper()) { "deleteBlocking на главном потоке" }
        var r = Int.MIN_VALUE
        val latch = CountDownLatch(1)
        runOnUiThread {
            if (!startDelete(h, kids[i]) { r = it; latch.countDown() }) latch.countDown()
        }
        check(latch.await(60, TimeUnit.SECONDS)) { "удаление не завершилось за 60 с" }
        return r
    }
}
