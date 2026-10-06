package dev.ancdu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.OverScroller

class Row {
    var name = ""
    var size = ""
    var pct = ""
    var bar = 0f
    var barColor = C.AMBER
    var nameColor = C.TEXT
    var mark = ""
    var segs: FloatArray? = null
    var segColors: IntArray? = null
    var sub: String? = null
    var desc = ""

    fun reset() {
        name = ""; size = ""; pct = ""; bar = 0f; barColor = C.AMBER; nameColor = C.TEXT
        mark = ""; segs = null; segColors = null; sub = null; desc = ""
    }
}

interface RowSource {
    val count: Int
    fun bind(index: Int, row: Row)
    fun click(index: Int) {}
    fun longClick(index: Int) {}
}

/** Список в стиле ncdu: рисуются только видимые строки, свой скролл, доступность без AndroidX. */
class NcduListView(ctx: Context) : View(ctx) {
    var source: RowSource? = null
        set(v) { field = v; scroll = 0; refresh() }

    var withSub = false
        set(v) { field = v; requestLayout(); invalidate() }

    /** TalkBack: подпись действия «долгое нажатие» на строках; null — без подписи. */
    var longClickLabel: CharSequence? = null

    /** Высота строки: 56/64 dp, но растёт под крупный шрифт (sp), чтобы текст не обрезался. */
    val rowHeight: Int get() = if (withSub) rowSub else rowPlain

    var scroll = 0
        set(v) {
            val n = ListMath.clampScroll(v, source?.count ?: 0, rowHeight, height)
            val changed = n != field
            field = n
            invalidate()
            if (changed && a11yOn()) {
                val ev = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED)
                onInitializeAccessibilityEvent(ev)
                ev.scrollY = n
                ev.maxScrollY = ListMath.maxScroll(source?.count ?: 0, rowHeight, height)
                parent?.requestSendAccessibilityEvent(this, ev)
            }
        }

    private val row = Row()
    private val scroller = OverScroller(ctx)
    private val pad = ctx.dp(16)
    private val barW = ctx.dp(40)
    private val gap = ctx.dp(10)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, context.resources.displayMetrics)
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE; textSize = sp(14f); color = C.TEXT
    }
    private val small = Paint(mono).apply { textSize = sp(12f); color = C.MUTED }
    // Колонки размера и процента — по ширине самого длинного значения при текущем шрифте.
    private val sizeW = maxOf(ctx.dp(76), Paint(mono).apply { isFakeBoldText = true }.measureText("1023.9 MiB").toInt())
    private val pctW = maxOf(ctx.dp(40), small.measureText("100% ").toInt())
    private val mainH = mono.fontMetricsInt.let { it.descent - it.ascent }
    private val subH = small.fontMetricsInt.let { it.descent - it.ascent }
    private val subGap = ctx.dp(2)
    private val rowPlain = ListMath.rowHeight(ctx.dp(56), mainH, ctx.dp(8))
    private val rowSub = ListMath.rowHeight(ctx.dp(64), mainH + subGap + subH, ctx.dp(8))
    private val fill = Paint()
    private val tmp = Rect()

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scroll += dy.toInt(); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val n = source?.count ?: 0
            scroller.fling(0, scroll, 0, -vy.toInt(), 0, 0, 0, ListMath.maxScroll(n, rowHeight, height))
            postInvalidateOnAnimation(); return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val i = ListMath.indexAt(e.y, scroll, rowHeight, source?.count ?: 0)
            if (i >= 0) { playSoundEffect(android.view.SoundEffectConstants.CLICK); source?.click(i) }
            return i >= 0
        }
        override fun onLongPress(e: MotionEvent) {
            val i = ListMath.indexAt(e.y, scroll, rowHeight, source?.count ?: 0)
            if (i >= 0) { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); source?.longClick(i) }
        }
    })

    init {
        setBackgroundColor(C.BG)
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun refresh() {
        scroll = scroll // повторное ограничение под новое число строк
        invalidate()
        if (a11yOn()) sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = gestures.onTouchEvent(e) || super.onTouchEvent(e)

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) { scroll = scroller.currY; postInvalidateOnAnimation() }
    }

    override fun onDraw(c: Canvas) {
        val src = source ?: return
        val rh = rowHeight
        val first = ListMath.firstVisible(scroll, rh)
        val last = ListMath.lastVisible(scroll, rh, height, src.count)
        for (i in first..last) {
            row.reset()
            src.bind(i, row)
            drawRow(c, i * rh - scroll, rh)
        }
    }

    private fun drawRow(c: Canvas, top: Int, rh: Int) {
        val w = width
        fill.color = C.LINE
        c.drawRect(0f, (top + rh - 1).toFloat(), w.toFloat(), (top + rh).toFloat(), fill)
        // Блок текста (имя и, если есть, подпись) центрируется по вертикали строки.
        val sub = row.sub
        val blockH = if (sub != null) mainH + subGap + subH else mainH
        val blockTop = top + (rh - blockH) / 2
        val base = (blockTop - mono.fontMetricsInt.ascent).toFloat()
        val mid = blockTop + mainH / 2
        var x = pad
        // размер (по правому краю колонки)
        mono.isFakeBoldText = true
        mono.color = C.TEXT
        val sw = mono.measureText(row.size)
        c.drawText(row.size, x + sizeW - sw, base, mono)
        mono.isFakeBoldText = false
        x += sizeW + gap
        // полоса
        val bh = context.dp(10)
        val bt = (mid - bh / 2).toFloat()
        fill.color = C.LINE
        c.drawRect(x.toFloat(), bt, (x + barW).toFloat(), bt + bh, fill)
        val segs = row.segs
        if (segs != null) {
            var sx = x.toFloat()
            val total = barW * row.bar
            for (k in segs.indices) {
                val ww = total * segs[k]
                fill.color = row.segColors?.getOrNull(k) ?: C.AMBER
                c.drawRect(sx, bt, sx + ww, bt + bh, fill)
                sx += ww
            }
        } else {
            fill.color = row.barColor
            c.drawRect(x.toFloat(), bt, x + barW * row.bar, bt + bh, fill)
        }
        x += barW + gap
        // процент
        c.drawText(row.pct, x.toFloat(), base, small)
        x += pctW
        // имя: метка не режется, имя — посередине (начало и конец видны)
        mono.color = row.nameColor
        val mark = if (row.mark.isEmpty()) "" else "${row.mark} "
        val avail = (w - pad - x).toFloat() - mono.measureText(mark)
        c.drawText(mark + Ellipsis.middle(row.name, avail, mono::measureText), x.toFloat(), base, mono)
        if (sub != null) {
            val sb = (blockTop + mainH + subGap - small.fontMetricsInt.ascent).toFloat()
            c.drawText(Ellipsis.middle(sub, (w - pad - x).toFloat(), small::measureText), x.toFloat(), sb, small)
        }
    }

    // ---------- доступность: каждая видимая строка — виртуальный узел ----------
    private val a11y = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(id: Int): AccessibilityNodeInfo? {
            val src = source ?: return null
            if (id == HOST_VIEW_ID) {
                val info = AccessibilityNodeInfo.obtain(this@NcduListView)
                onInitializeAccessibilityNodeInfo(info)
                val f = ListMath.firstVisible(scroll, rowHeight)
                val l = ListMath.lastVisible(scroll, rowHeight, height, src.count)
                for (i in f..l) info.addChild(this@NcduListView, i)
                info.isScrollable = src.count * rowHeight > height
                val max = ListMath.maxScroll(src.count, rowHeight, height)
                if (scroll < max) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                if (scroll > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
                return info
            }
            if (id !in 0 until src.count) return null
            row.reset()
            src.bind(id, row)
            return AccessibilityNodeInfo.obtain(this@NcduListView, id).apply {
                setParent(this@NcduListView)
                packageName = context.packageName
                className = "android.widget.Button"
                contentDescription = row.desc.ifEmpty { "${row.name}, ${row.size}" }
                isClickable = true
                isLongClickable = true
                isEnabled = true
                isVisibleToUser = true
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                val lcl = longClickLabel
                addAction(if (lcl == null) AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK
                          else AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, lcl))
                val top = id * rowHeight - scroll
                tmp.set(0, top, width, top + rowHeight)
                setBoundsInParent(tmp)
                val loc = IntArray(2).also { getLocationOnScreen(it) }
                tmp.offset(loc[0], loc[1])
                setBoundsInScreen(tmp)
            }
        }

        override fun performAction(id: Int, action: Int, args: Bundle?): Boolean {
            val src = source ?: return false
            if (id == HOST_VIEW_ID) return performAccessibilityAction(action, args)
            if (id !in 0 until src.count) return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> { src.click(id); true }
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> { src.longClick(id); true }
                else -> false
            }
        }
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = a11y

    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean {
        val n = source?.count ?: 0
        return when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { scroll += height - rowHeight; true }
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { scroll -= height - rowHeight; true }
            else -> n > 0 && super.performAccessibilityAction(action, args)
        }
    }
}
