package dev.ancdu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
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
    var barColor = C.ACCENT
    var nameColor = C.TEXT
    var mark = ""
    var segs: FloatArray? = null
    var segColors: IntArray? = null
    var sub: String? = null
    var desc = ""

    fun reset() {
        name = ""; size = ""; pct = ""; bar = 0f; barColor = C.ACCENT; nameColor = C.TEXT
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

    val rowHeight: Int get() = context.dp(if (withSub) 64 else 56)

    var scroll = 0
        set(v) {
            val n = ListMath.clampScroll(v, source?.count ?: 0, rowHeight, height)
            val changed = n != field
            field = n
            invalidate()
            if (changed) {
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
    private val sizeW = ctx.dp(76)
    private val barW = ctx.dp(64)
    private val pctW = ctx.dp(40)
    private val gap = ctx.dp(10)
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE; textSize = ctx.dp(14).toFloat(); color = C.TEXT
    }
    private val small = Paint(mono).apply { textSize = ctx.dp(12).toFloat(); color = C.MUTED }
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
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
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
        val mid = top + (if (row.sub != null) rh / 2 - context.dp(4) else rh / 2)
        val base = mid + (mono.textSize / 3)
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
                fill.color = row.segColors?.getOrNull(k) ?: C.ACCENT
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
        // имя (обрезка по ширине)
        mono.color = row.nameColor
        val label = if (row.mark.isEmpty()) row.name else "${row.mark} ${row.name}"
        val avail = (w - pad - x).toFloat()
        val n = mono.breakText(label, true, avail, null)
        c.drawText(if (n < label.length) label.substring(0, maxOf(0, n - 1)) + "…" else label, x.toFloat(), base, mono)
        row.sub?.let { c.drawText(it, x.toFloat(), base + context.dp(18), small) }
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
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
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
