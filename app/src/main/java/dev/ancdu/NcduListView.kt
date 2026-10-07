package dev.ancdu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
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
    /** Режим выбора: флажок в колонке процента (true — выбрана, строка PANEL2); null — режима нет. */
    var checked: Boolean? = null
    /** false — строку нельзя выбрать (запрет удаления): для TalkBack — недоступна. */
    var enabled = true
    /** TalkBack: состояние флажка («выбрано / не выбрано»). */
    var stateDesc: String? = null
    /** TalkBack: подпись действия «нажатие»; null — без подписи. */
    var clickLabel: String? = null
    /** TalkBack: подпись «долгого нажатия» этой строки; null — общая [NcduListView.longClickLabel]. */
    var longLabel: String? = null
    /** Есть ли у строки «долгое нажатие» для TalkBack. */
    var long = true

    fun reset() {
        name = ""; size = ""; pct = ""; bar = 0f; barColor = C.AMBER; nameColor = C.TEXT
        mark = ""; segs = null; segColors = null; sub = null; desc = ""
        checked = null; enabled = true; stateDesc = null; clickLabel = null; longLabel = null; long = true
    }
}

interface RowSource {
    val count: Int
    fun bind(index: Int, row: Row)
    fun click(index: Int) {}
    fun longClick(index: Int) {}
    /** Звук касания строки до [click]; null — его даёт сам [click]. */
    fun clickCue(index: Int): Cue? = Cue.TAP
}

/** Список в стиле ncdu: рисуются только видимые строки, свой скролл, доступность без AndroidX. */
class NcduListView(ctx: Context) : View(ctx) {
    var source: RowSource? = null
        set(v) { field = v; scroll = 0; refresh() }

    var withSub = false
        set(v) { field = v; requestLayout(); invalidate() }

    /**
     * Имена — файлы и каталоги: не влезающее имя режется в конце основы, расширение и «/»
     * остаются ([Ellipsis.stemKeepExt]); иначе — обычное многоточие в конце.
     */
    var keepExt = false

    /** TalkBack: подпись действия «долгое нажатие» на строках; null — без подписи. */
    var longClickLabel: CharSequence? = null

    /** Высота строки: 48/64 dp, но растёт под крупный шрифт (sp), чтобы текст не обрезался. */
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
    private val one = ctx.dp(1)
    private val barW = ctx.dp(64)
    private val barH = ctx.dp(8)
    private val gap = ctx.dp(10)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, context.resources.displayMetrics)
    private val mono = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.get(ctx, mono = true, bold = false); textSize = sp(14f); color = C.TEXT
    }
    private val sizePaint = TextPaint(mono).apply { typeface = Fonts.get(ctx, mono = true, bold = true) }
    private val small = TextPaint(mono).apply { textSize = sp(12f); color = C.MUTED }
    private val pctPaint = TextPaint(small).apply { color = C.TEXT }
    // Колонки размера и процента — по ширине самого длинного значения при текущем шрифте.
    private val sizeW = maxOf(ctx.dp(76), sizePaint.measureText("1023.9 MiB").toInt())
    private val pctW = maxOf(ctx.dp(34), pctPaint.measureText("100%").toInt())
    /** Крупный шрифт (> 130%): без полосы, колонка размера сужается — имени остаётся ≥40% строки. */
    private val compact = ctx.resources.configuration.fontScale > 1.3f
    private val fitPaint = TextPaint(sizePaint)

    /** Ширина колонки размера при ширине строки [w]; не влезающий размер рисуется мельче. */
    fun sizeColFor(w: Int): Int =
        if (!compact) sizeW
        else minOf(sizeW, maxOf(context.dp(48), (w * 0.58f).toInt() - 2 * pad - 2 * gap - pctW))

    /** Ширина колонки имени при ширине строки [w]. */
    fun nameWidthFor(w: Int): Int =
        w - 2 * pad - sizeColFor(w) - gap - (if (compact) 0 else barW + gap) - pctW - gap
    private val mainH = mono.fontMetricsInt.let { it.descent - it.ascent }
    private val subH = small.fontMetricsInt.let { it.descent - it.ascent }
    private val subGap = ctx.dp(2)
    private val rowPlain = ListMath.rowHeight(ctx.dp(48), mainH, ctx.dp(8))
    private val rowSub = ListMath.rowHeight(ctx.dp(64), mainH + subGap + subH, ctx.dp(8))
    private val fill = Paint()
    private val tmp = Rect()
    /** Флажок режима выбора: 16dp, галочка 2dp INK. */
    private val checkBox = ctx.dp(16)
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dp(2).toFloat(); color = C.INK
        strokeCap = Paint.Cap.SQUARE; strokeJoin = Paint.Join.MITER
    }
    private val tickPath = Path()
    /** Строка, которую показать целиком после смены высоты (вход в режим выбора); -1 — нет. */
    private var revealRow = -1
    /** Нажатая строка (PANEL2 и амберная скобка слева); -1 — нет. */
    private var pressed = -1
        set(v) { if (field != v) { field = v; invalidate() } }
    private val unpress = Runnable { pressed = -1 }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
        override fun onShowPress(e: MotionEvent) {
            pressed = ListMath.indexAt(e.y, scroll, rowHeight, source?.count ?: 0)
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            pressed = -1
            scroll += dy.toInt(); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val n = source?.count ?: 0
            scroller.fling(0, scroll, 0, -vy.toInt(), 0, 0, 0, ListMath.maxScroll(n, rowHeight, height))
            postInvalidateOnAnimation(); return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val i = ListMath.indexAt(e.y, scroll, rowHeight, source?.count ?: 0)
            // Короткий тап: нажатие видно ещё 100 мс после отпускания (см. onTouchEvent).
            if (i >= 0) pressed = i
            if (i >= 0) { source?.clickCue(i)?.let { Feedback.cue(this@NcduListView, it) }; source?.click(i) }
            return i >= 0
        }
        override fun onLongPress(e: MotionEvent) {
            val i = ListMath.indexAt(e.y, scroll, rowHeight, source?.count ?: 0)
            // Через Feedback: «Звук и вибрация: Выкл» и правило TalkBack действуют и здесь.
            if (i >= 0) { Feedback.cue(this@NcduListView, Cue.LONG_PRESS); source?.longClick(i) }
        }
    })

    init {
        setBackgroundColor(C.BG)
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /**
     * Показать строку [i] целиком, если она под нижним краем (панель выбора снизу уменьшает
     * высоту списка): сразу и ещё раз после смены размера.
     */
    fun reveal(i: Int) {
        revealRow = i
        applyReveal()
    }

    private fun applyReveal() {
        val i = revealRow
        if (i < 0 || height <= 0) return
        val bottom = (i + 1) * rowHeight
        if (bottom - scroll > height) scroll = bottom - height
        if (i * rowHeight < scroll) scroll = i * rowHeight
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        applyReveal()
        revealRow = -1
    }

    fun refresh() {
        scroll = scroll // повторное ограничение под новое число строк
        invalidate()
        if (a11yOn()) sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) revealRow = -1
        val r = gestures.onTouchEvent(e) || super.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(unpress); postDelayed(unpress, 100)
        }
        return r
    }

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
            drawRow(c, i * rh - scroll, rh, i == pressed)
        }
    }

    private fun drawRow(c: Canvas, top: Int, rh: Int, down: Boolean) {
        val w = width
        if (down) {
            fill.color = C.PANEL2
            c.drawRect(0f, top.toFloat(), w.toFloat(), (top + rh).toFloat(), fill)
            // Маленькая амберная скобка «[» у левого края.
            val bx = context.dp(4).toFloat(); val arm = context.dp(4).toFloat(); val inset = context.dp(8).toFloat()
            fill.color = C.AMBER
            c.drawRect(bx, top + inset, bx + one, top + rh - inset, fill)
            c.drawRect(bx, top + inset, bx + arm, top + inset + one, fill)
            c.drawRect(bx, top + rh - inset - one, bx + arm, top + rh - inset, fill)
        }
        val checked = row.checked
        if (checked == true && !down) {
            fill.color = C.PANEL2
            c.drawRect(0f, top.toFloat(), w.toFloat(), (top + rh).toFloat(), fill)
        }
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
        val col = sizeColFor(w)
        val sw = sizePaint.measureText(row.size)
        if (sw <= col) c.drawText(row.size, x + col - sw, base, sizePaint)
        else {
            fitPaint.textSize = sizePaint.textSize * col / sw
            c.drawText(row.size, x.toFloat(), base, fitPaint)
        }
        x += col + gap
        if (!compact) drawBar(c, x, mid)
        if (!compact) x += barW + gap
        // процент (по правому краю колонки); в режиме выбора — флажок на его месте
        if (checked != null) drawCheck(c, x + pctW - checkBox, mid - checkBox / 2, checked)
        else c.drawText(row.pct, x + pctW - pctPaint.measureText(row.pct), base, pctPaint)
        x += pctW + gap
        // имя: метка не режется, имя — до конца строки с многоточием (у файлов — в конце основы)
        mono.color = row.nameColor
        val mark = if (row.mark.isEmpty()) "" else "${row.mark} "
        val avail = maxOf((w - pad - x).toFloat() - mono.measureText(mark), 0f)
        val name = if (keepExt) Ellipsis.stemKeepExt(row.name, avail, mono::measureText)
            else TextUtils.ellipsize(row.name, mono, avail, TextUtils.TruncateAt.END).toString()
        c.drawText(mark + name, x.toFloat(), base, mono)
        if (sub != null) {
            val sb = (blockTop + mainH + subGap - small.fontMetricsInt.ascent).toFloat()
            c.drawText(Ellipsis.middle(sub, (w - pad - x).toFloat(), small::measureText), x.toFloat(), sb, small)
        }
    }

    /** Флажок 16dp с левым верхним углом ([x], [y]): выбран — амберная заливка и галочка INK, иначе контур 1dp FRAME. */
    private fun drawCheck(c: Canvas, x: Int, y: Int, on: Boolean) {
        val l = x.toFloat(); val t = y.toFloat(); val s = checkBox.toFloat()
        if (on) {
            fill.color = C.AMBER
            c.drawRect(l, t, l + s, t + s, fill)
            val u = s / 16f
            tickPath.reset()
            tickPath.moveTo(l + 3.5f * u, t + 8.2f * u)
            tickPath.lineTo(l + 6.6f * u, t + 11.2f * u)
            tickPath.lineTo(l + 12.5f * u, t + 4.8f * u)
            c.drawPath(tickPath, tick)
        } else {
            fill.color = C.FRAME
            c.drawRect(l, t, l + s, t + one, fill)
            c.drawRect(l, t + s - one, l + s, t + s, fill)
            c.drawRect(l, t, l + one, t + s, fill)
            c.drawRect(l + s - one, t, l + s, t + s, fill)
        }
    }

    /** Полоса 64×8dp в контуре 1dp; заполнение > 0 — не меньше 1px. [x] — левый край, [mid] — центр по высоте. */
    private fun drawBar(c: Canvas, x: Int, mid: Int) {
        val bt = (mid - barH / 2).toFloat()
        val inL = (x + one).toFloat(); val inW = (barW - 2 * one).toFloat()
        val segs = row.segs
        if (segs != null) {
            var sx = inL
            val total = inW * row.bar
            for (k in segs.indices) {
                val ww = total * segs[k]
                fill.color = row.segColors?.getOrNull(k) ?: C.AMBER
                c.drawRect(sx, bt + one, sx + ww, bt + barH - one, fill)
                sx += ww
            }
        } else {
            fill.color = row.barColor
            val fw = if (row.bar > 0f) maxOf(inW * row.bar, 1f) else 0f
            c.drawRect(inL, bt + one, inL + fw, bt + barH - one, fill)
        }
        fill.color = C.FRAME
        c.drawRect(x.toFloat(), bt, (x + barW).toFloat(), bt + one, fill)
        c.drawRect(x.toFloat(), bt + barH - one, (x + barW).toFloat(), bt + barH, fill)
        c.drawRect(x.toFloat(), bt, (x + one).toFloat(), bt + barH, fill)
        c.drawRect((x + barW - one).toFloat(), bt, (x + barW).toFloat(), bt + barH, fill)
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
                val checked = row.checked
                // Режим выбора: флажок (состояние — «выбрано / не выбрано»).
                className = if (checked != null) "android.widget.CheckBox" else "android.widget.Button"
                if (checked != null) {
                    isCheckable = true
                    isChecked = checked
                    stateDescription = row.stateDesc
                }
                contentDescription = row.desc.ifEmpty { "${row.name}, ${row.size}" }
                isClickable = true
                isLongClickable = row.long
                isEnabled = row.enabled
                isVisibleToUser = true
                val cl = row.clickLabel
                addAction(if (cl == null) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK
                          else AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, cl))
                val lcl = row.longLabel ?: longClickLabel
                if (row.long) addAction(if (lcl == null) AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK
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
