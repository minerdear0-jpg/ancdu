package dev.ancdu

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Looper
import android.text.TextUtils
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
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
    private val scrollAt = HashMap<Int, Int>()
    private lateinit var crumbs: TextView
    private lateinit var summary: TextView
    private lateinit var badge: TextView
    private lateinit var footer: TextView
    private lateinit var chips: LinearLayout

    private val onDeleted: (Int) -> Unit = { r ->
        wait?.dismiss(); wait = null
        if (Holder.h != h) {
            list.source = null
            recreate()
        } else {
            list.source = src
            load(node, keepScroll)
            if (r != 0 && !isFinishing) alert("Не удалось удалить полностью",
                "Часть файлов осталась (код $r). Удалено частично — пересканируйте.")
        }
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
            if (busy || info[4 * index + 3].toInt() and F_DIR == 0) return
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
        crumbs = label("", 14f, C.MUTED, mono = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.START
        }
        top.addView(hbox(4).apply {
            addView(backButton { onBackPressed() })
            addView(crumbs, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
        summary = label("", 13f, C.MUTED, mono = true)
        badge = label(Holder.label, 12f, C.ACCENT, mono = true)
        chips = hbox(6)
        top.addView(hbox(8).apply {
            setPadding(dp(8), 0, 0, 0)
            addView(vbox().apply { addView(summary); addView(badge) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(chips)
        })
        list = NcduListView(this)
        footer = label("", 12f, C.MUTED, mono = true).apply { setPadding(dp(16), dp(10), dp(16), dp(10)) }
        setContentView(vbox().apply {
            setBackgroundColor(C.BG)
            addView(top)
            addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(footer)
        })
        Holder.addDeleteListener(onDeleted)
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
        wait?.dismiss(); wait = null
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
        crumbs.text = Native.str(Native.path(h, node))
        summary.text = "${Fmt.size(parentV)} · ${Fmt.count(self[2])} эл."
        val p = progress()
        val full = p[0] == ST_FULL.toLong()
        badge.text = Holder.label + if (full) " · неполный" else ""
        badge.setTextColor(if (full) C.WARN else C.ACCENT)
        footer.text = "тап — открыть · долгий — удалить" + if (p[3] > 0) "   ⚠ ${Fmt.count(p[3])} ошибок" else ""
        renderChips()
        list.refresh()
        list.scroll = restore
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

    private fun askDelete(i: Int) {
        val o = 4 * i
        val handle = h
        val target = kids[i]
        val path = Native.str(Native.path(handle, target))
        val approx = if (Holder.kind == Kind.INDEX) "\n(размер по индексу, приблизительно)" else ""
        alert("Удалить «${nameAt(i)}»?",
            "$path\n\nОсвободится: ${Fmt.size(info[o])}$approx\nЭлементов: ${Fmt.count(info[o + 2])}\n\n" +
                "Без корзины. Действие необратимо.",
            ok = "Удалить", cancel = "Отмена") { startDelete(handle, target) }
    }

    private fun showWait() {
        list.source = null
        footer.text = "Удаление…"
        wait = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage("Удаление…").setCancelable(false).show()
    }

    /**
     * Главный поток. Удаляет узел [target] сессии [handle] — ровно ту пару, что показал диалог.
     * Завершение получает живой экземпляр через Holder (onDeleted), затем [done].
     */
    private fun startDelete(handle: Long, target: Int, done: (Int) -> Unit = {}): Boolean {
        if (busy || isDestroyed) return false
        if (handle != Holder.h || handle != h) {
            alert("Удаление отменено", "Дерево сменилось, пока был открыт диалог. Ничего не удалено.") {
                list.source = null; recreate()
            }
            return false
        }
        val helper = if (Holder.viaRoot) Root.helper(this) else null
        keepScroll = list.scroll
        showWait()
        Holder.delete(handle, target, helper, done)
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
