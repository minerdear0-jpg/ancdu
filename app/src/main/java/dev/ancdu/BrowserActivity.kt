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
    private var loadedH = 0L
    /** Идёт удаление: никаких вызовов Native на дескрипторе, ввод игнорируется. */
    private var busy = false
    private val scrollAt = HashMap<Int, Int>()
    private lateinit var crumbs: TextView
    private lateinit var summary: TextView
    private lateinit var badge: TextView
    private lateinit var footer: TextView
    private lateinit var chips: LinearLayout
    private val h get() = Holder.h

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
        val full = Holder.progress()[0] == ST_FULL.toLong()
        badge = label(Holder.label + if (full) " · неполный" else "", 12f, if (full) C.WARN else C.ACCENT, mono = true)
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
        list.source = src
        load(0, 0)
    }

    override fun onResume() {
        super.onResume()
        // Сессию сменили, пока экран был скрыт: прежнее дерево уже освобождается.
        if (!busy && loadedH != 0L && Holder.h != loadedH) recreate()
    }

    private fun renderChips() {
        chips.removeAllViews()
        chips.addView(chip("размер", sort == SORT_SIZE) { setSort(SORT_SIZE) })
        chips.addView(chip("имя", sort == SORT_NAME) { setSort(SORT_NAME) })
        chips.addView(chip(if (apparent) "apparent" else "disk", false) { setApparent(!apparent) })
    }

    fun setSort(k: Int) { if (busy) return; sort = k; load(node, 0) }
    fun setApparent(v: Boolean) { if (busy) return; apparent = v; load(node, 0) }

    private fun load(target: Int, restore: Int) {
        node = target
        loadedH = h
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
        val errs = Holder.progress()[3]
        footer.text = "тап — открыть · долгий — удалить" + if (errs > 0) "   ⚠ ${Fmt.count(errs)} ошибок" else ""
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
        val path = Native.str(Native.path(h, kids[i]))
        val approx = if (Holder.kind == Kind.INDEX) "\n(размер по индексу, приблизительно)" else ""
        alert("Удалить «${nameAt(i)}»?",
            "$path\n\nОсвободится: ${Fmt.size(info[o])}$approx\nЭлементов: ${Fmt.count(info[o + 2])}\n\n" +
                "Без корзины. Действие необратимо.",
            ok = "Удалить", cancel = "Отмена") {
            startDelete(i) { r ->
                if (r != 0 && !isDestroyed) alert("Не удалось удалить полностью",
                    "Часть файлов осталась (код $r). Удалено частично — пересканируйте.")
            }
        }
    }

    /**
     * Главный поток. Удаление строки i на [Holder.io]; пока оно идёт, список отцеплен,
     * поверх — неотменяемый модальный диалог, Native на дескрипторе не вызывается.
     * [done] вызывается на главном потоке после перезагрузки уровня.
     */
    private fun startDelete(i: Int, done: (Int) -> Unit) {
        val handle = h
        val target = kids[i]
        val helper = if (Holder.viaRoot) Root.helper(this) else null
        val keep = list.scroll
        busy = true
        list.source = null
        footer.text = "Удаление…"
        val wait = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage("Удаление…").setCancelable(false).show()
        Holder.io.execute {
            val r = Native.delete(handle, target, helper)
            runOnUiThread {
                busy = false
                if (!isDestroyed) {
                    wait.dismiss()
                    list.source = src
                    load(node, keep)
                }
                done(r)
            }
        }
    }

    /** Для тестов: синхронное удаление строки i. Вызывать с тестового потока, не с главного. */
    fun deleteBlocking(i: Int): Int {
        check(Looper.myLooper() != Looper.getMainLooper()) { "deleteBlocking на главном потоке" }
        var r = Int.MIN_VALUE
        val latch = CountDownLatch(1)
        runOnUiThread { startDelete(i) { r = it; latch.countDown() } }
        check(latch.await(60, TimeUnit.SECONDS)) { "удаление не завершилось за 60 с" }
        return r
    }
}
