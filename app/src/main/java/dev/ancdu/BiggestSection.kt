package dev.ancdu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * «САМЫЕ КРУПНЫЕ ФАЙЛЫ» под фокусной панелью главного экрана: до [Biggest.K] файлов всего
 * дерева общего хранилища — того, что показывает карточка (дерево в Holder или кэш). Считается на
 * Holder.io (FIFO с delete/free: дерево не читается параллельно с удалением), показывается на
 * главном потоке. Нет дерева или доступа — секции нет совсем.
 */
class BiggestSection(private val a: MainActivity) {
    private val t: Txt = a.tx
    /** Вся секция (GONE — нечего показать). */
    val box: LinearLayout = a.vbox().apply { visibility = View.GONE }
    private val fresh: TextView = a.label("", 12f, C.MUTED, mono = true)
    private val list: LinearLayout = a.vbox()
    /** Для тестов: показанные строки по порядку. */
    var rows: List<BigFile> = emptyList()
        private set
    /** Для тестов: их View (касание — переход в браузер). */
    val rowViews = ArrayList<View>()
    /** Номер запроса: ответ старого (экран ушёл, дерево сменилось) отбрасывается. */
    private var seq = 0
    /** Для тестов: сколько ответов показано. */
    var shown = 0
        private set
    /**
     * Ключ источника последнего запроса: дерево Holder — его поколение и счётчик удалений; кэш — время
     * записи «caches», длина и mtime файла. Тот же ключ — строки не пересчитываются (кэш не
     * открывается и не проверяется заново на каждом onResume и тике скана). null — пересчитать.
     */
    private var key: String? = null
    /** Для тестов: сколько раз строки считались на io. */
    var loads = 0
        private set

    init {
        box.addView(a.hbox(8).apply {
            minimumHeight = a.dp(32)
            addView(a.caps(t.s(R.string.big_title)), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(fresh)
        })
        box.hairline()
        box.addView(list)
    }

    /**
     * Главный поток. Пересчитать по текущему дереву карточки: дерево общего хранилища в Holder
     * (как есть, его время — в строке свежести), иначе кэш общего хранилища (открывается на io и
     * сразу освобождается). Во время удаления — ничего (дерево меняется на io). Источник тот же
     * ([key]) — ничего, кроме [force] (новое дерево, конец удаления).
     */
    fun refresh(force: Boolean = false) {
        if (!Perms.files()) { hide(); return }
        if (Holder.deleting) return
        val app = a.applicationContext
        val self = a.packageName
        val txt = t
        if (a.storage.storageShown()) {
            val k = "tree:${Holder.gen}:${Holder.deletes}"
            if (!force && k == key) return
            key = k
            loads++
            val my = ++seq
            val h = Holder.h
            val kind = Holder.kind
            val time = Holder.time
            Holder.io.execute {
                val r = runCatching { load(app, txt, h, self) }.getOrDefault(emptyList())
                a.runOnUiThread { show(my, r, kind, time) }
            }
            return
        }
        val meta = Scans.meta(a, Scans.STORAGE, false) ?: run { hide(); return }
        val file = Holder.cacheFile(a, Scans.STORAGE, false)
        val k = "cache:${meta.time}:${file.length()}:${file.lastModified()}"
        if (!force && k == key) return
        key = k
        loads++
        val my = ++seq
        Holder.io.execute {
            val r = runCatching {
                val h = Native.openCache(file.path, IntArray(1))
                if (h == 0L) emptyList() else try { load(app, txt, h, self) } finally { Native.free(h) }
            }.getOrDefault(emptyList())
            a.runOnUiThread { show(my, r, Kind.CACHE, meta.time) }
        }
    }

    /** Нечего показать (нет доступа, нет дерева): секции нет, следующий [refresh] считает заново. */
    private fun hide() {
        key = null
        clear()
    }

    /** Секция пуста (ответ io: файлов нет); ключ остаётся — тот же источник не перечитывается. */
    private fun clear() {
        seq++
        rows = emptyList()
        list.removeAllViews(); rowViews.clear()
        box.visibility = View.GONE
    }

    private fun show(my: Int, r: List<BigFile>, kind: Kind, time: Long) {
        if (my != seq || a.isDestroyed) return
        shown++
        if (r.isEmpty()) { clear(); return }
        rows = r
        list.removeAllViews(); rowViews.clear()
        fresh.text = when (kind) {
            Kind.CACHE -> t.s(R.string.badge_cache, Freshness.date(t, R.string.fmt_day_time, time))
            Kind.INDEX -> t.s(R.string.badge_index)
            else -> ""
        }
        fresh.visibility = if (fresh.text.isEmpty()) View.GONE else View.VISIBLE
        for (f in r) {
            val v = BigRow(a, f, t).apply { feedbackClick { a.openFocused(f.names) } }
            rowViews += v
            // Волосяные линии — только между строками.
            if (rowViews.size > 1) list.hairline()
            list.addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        box.visibility = View.VISIBLE
    }

    companion object {
        /**
         * На Holder.io: крупнейшие файлы дерева [h] (только чтения: name, path, parent, nodeInfo).
         * Метки — как в браузере ([Tag.of]; корень дерева — «хранилище»).
         */
        fun load(ctx: Context, t: Txt, h: Long, self: String): List<BigFile> {
            val ids = IntArray(Biggest.K)
            val k = Native.topFiles(h, ids)
            if (k <= 0) return emptyList()
            val inf = LongArray(4 * k).also { Native.nodeInfo(h, ids, k, it) }
            val root = Native.str(Native.path(h, 0))
            return (0 until k).map { i ->
                val nd = ids[i]
                val flags = inf[4 * i + 3].toInt()
                val parent = Native.parent(h, nd)
                val path = Native.str(Native.path(h, nd))
                val names = ArrayList<ByteArray>()
                var c = nd
                while (c > 0) { names += Native.name(h, c); c = Native.parent(h, c) }
                names.reverse()
                val block = DeletePolicy.blockReason(path, false, parent == 0, root, flags, Kind.SCAN)
                val tag = Tag.of(path, flags, Owner.packageOf(path), block, root, self)
                BigFile(Native.str(Native.name(h, nd)), inf[4 * i], Biggest.parentText(t, root, Native.str(Native.path(h, parent))),
                    names, tag, tag?.pkg?.let { AppLabels.get(ctx, it) })
            }
        }
    }
}

/**
 * Строка 48dp (растёт под крупный шрифт): размер (моно 700, колонка справа 76dp), имя
 * ([Ellipsis.stemKeepExt], [Bidi.visible]) с меткой справа ([Tag.fit]) и под ним папка приглушённо.
 * Одна касаемая View: описание для TalkBack — «<имя>, <размер>, в <папка>, <метка>».
 */
class BigRow(ctx: Context, val file: BigFile, t: Txt) : View(ctx) {
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private val mono = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.get(ctx, mono = true, bold = false); textSize = sp(14f); color = C.TEXT
    }
    private val sizePaint = TextPaint(mono).apply { typeface = Fonts.get(ctx, mono = true, bold = true) }
    private val fitPaint = TextPaint(sizePaint)
    private val small = TextPaint(mono).apply { textSize = sp(12f); color = C.MUTED }
    private val tagPaint = TextPaint(small)
    private val gap = ctx.dp(10)
    private val sizeW = maxOf(ctx.dp(76), sizePaint.measureText("1023.9 MiB").toInt())
    private val compact = resources.configuration.fontScale > 1.3f
    private val mainH = mono.fontMetricsInt.let { it.descent - it.ascent }
    private val subH = small.fontMetricsInt.let { it.descent - it.ascent }
    private val subGap = ctx.dp(2)
    private val rowH = ListMath.rowHeight(ctx.dp(48), mainH + subGap + subH, ctx.dp(8))
    val size = Fmt.size(file.disk, t)
    private val name = Bidi.visible(file.name)
    private val parent = Bidi.visible(file.parent)
    private val tag = file.tag?.resolve(t, file.tagLabel)
    /** Для тестов: показанная метка (после [Tag.fit]) или null. */
    var tagShown: String? = null
        private set

    init {
        background = ctx.pressable(C.BG)
        isClickable = true; isFocusable = true
        contentDescription = Biggest.desc(t, file.name, size, file.parent, file.tag, file.tagLabel)
    }

    override fun onMeasure(ws: Int, hs: Int) =
        setMeasuredDimension(MeasureSpec.getSize(ws), resolveSize(rowH, hs))

    /** Колонка размера: при крупном шрифте сужается — имени остаётся не меньше 40% строки. */
    private fun sizeCol(w: Int): Int = if (!compact) sizeW else minOf(sizeW, maxOf(context.dp(48), (w * 0.6f).toInt() - gap))

    override fun onDraw(c: Canvas) {
        val w = width
        val blockH = mainH + subGap + subH
        val top = (height - blockH) / 2
        val base = (top - mono.fontMetricsInt.ascent).toFloat()
        val col = sizeCol(w)
        val sw = sizePaint.measureText(size)
        if (sw <= col) c.drawText(size, col - sw, base, sizePaint)
        else { fitPaint.textSize = sizePaint.textSize * col / sw; c.drawText(size, 0f, base, fitPaint) }
        val x = (col + gap).toFloat()
        var avail = maxOf(w - x, 0f)
        tagShown = tag?.let { Tag.fit(name, it.text, avail, gap.toFloat(), mono::measureText, tagPaint::measureText) }
        tagShown?.let {
            val tw = tagPaint.measureText(it)
            tagPaint.color = tag!!.color
            c.drawText(it, w - tw, base, tagPaint)
            avail = maxOf(avail - tw - gap, 0f)
        }
        c.drawText(Ellipsis.stemKeepExt(name, avail, mono::measureText), x, base, mono)
        val sb = (top + mainH + subGap - small.fontMetricsInt.ascent).toFloat()
        c.drawText(Ellipsis.middle(parent, maxOf(w - x, 0f), small::measureText), x, sb, small)
    }
}
