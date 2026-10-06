package dev.ancdu

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.util.TypedValue
import android.widget.TextView
import kotlin.math.max

/**
 * Фон фокусной панели: заливка [fill] и угловые скобки (плечи 12dp, 1dp, FRAME). Одна такая
 * панель на экран. [bottom] = false — скобки только у верхних углов (лист удаления).
 */
class Brackets(ctx: Context, private val fill: Int, private val bottom: Boolean = true,
               private val pressedFill: Int? = null) : Drawable() {
    private val arm = ctx.dp(12).toFloat()
    private val w = ctx.dp(1).toFloat()
    private val paint = Paint()
    private var down = false

    /** С [pressedFill] — нажатие и фокус панели перекрашивают заливку. */
    override fun isStateful() = pressedFill != null
    override fun onStateChange(state: IntArray): Boolean {
        val v = pressedFill != null && (android.R.attr.state_pressed in state || android.R.attr.state_focused in state)
        if (v == down) return false
        down = v; invalidateSelf(); return true
    }

    override fun draw(c: Canvas) {
        val b = bounds
        paint.color = if (down) pressedFill!! else fill
        c.drawRect(b, paint)
        paint.color = C.FRAME
        val l = b.left.toFloat(); val t = b.top.toFloat(); val r = b.right.toFloat(); val bt = b.bottom.toFloat()
        c.drawRect(l, t, l + arm, t + w, paint); c.drawRect(l, t, l + w, t + arm, paint)
        c.drawRect(r - arm, t, r, t + w, paint); c.drawRect(r - w, t, r, t + arm, paint)
        if (bottom) {
            c.drawRect(l, bt - w, l + arm, bt, paint); c.drawRect(l, bt - arm, l + w, bt, paint)
            c.drawRect(r - arm, bt - w, r, bt, paint); c.drawRect(r - w, bt - arm, r, bt, paint)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Drawable API")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * Развёртка скана: амберная линия 1dp бежит сверху вниз по панели (1200 мс, по кругу) со
 * шлейфом ≤8dp и альфой ≤0.35 над ней. Ставится как foreground панели; только пока идёт скан.
 * Анимации выключены ([Motion]) — [start] ничего не делает, линии нет.
 */
class Sweep(ctx: Context) : Drawable() {
    /** Идущая анимация; null — линии нет. */
    var animator: ValueAnimator? = null
        private set
    private var pos = 0f
    private val one = ctx.dp(1).toFloat()
    private val trail = ctx.dp(8).toFloat()
    private val line = Paint().apply { color = C.AMBER }
    private val glow = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, trail, C.AMBER and 0x00FFFFFF, (C.AMBER and 0x00FFFFFF) or 0x59000000,
            Shader.TileMode.CLAMP)
    }

    fun start() {
        if (animator != null || !Motion.on()) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { pos = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        invalidateSelf()
    }

    override fun draw(c: Canvas) {
        if (animator == null) return
        val b = bounds
        val y = b.top + pos * b.height()
        c.save()
        c.clipRect(b)
        c.translate(b.left.toFloat(), y - trail)
        c.drawRect(0f, 0f, b.width().toFloat(), trail, glow)
        c.restore()
        c.save()
        c.clipRect(b)
        c.drawRect(b.left.toFloat(), y, b.right.toFloat(), y + one, line)
        c.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Drawable API")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Галочка 20dp: контур амбером; отмечена — амберная заливка и тёмная «✓». */
class Check(ctx: Context) : Drawable() {
    private val size = ctx.dp(20)
    private val one = ctx.dp(1).toFloat()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = ctx.dp(2).toFloat(); strokeCap = Paint.Cap.SQUARE }
    private var on = false
    private var focused = false

    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val v = android.R.attr.state_checked in state
        val f = android.R.attr.state_focused in state
        if (v == on && f == focused) return false
        on = v; focused = f; invalidateSelf(); return true
    }
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size

    override fun draw(c: Canvas) {
        val b = bounds
        val l = b.left.toFloat(); val t = b.top.toFloat(); val r = l + size; val bt = t + size
        paint.style = Paint.Style.FILL
        paint.color = C.AMBER
        if (on) c.drawRect(l, t, r, bt, paint)
        else { c.drawRect(l, t, r, t + one, paint); c.drawRect(l, bt - one, r, bt, paint)
               c.drawRect(l, t, l + one, bt, paint); c.drawRect(r - one, t, r, bt, paint) }
        if (focused) {
            // Фокус (клавиатура, переключатели): контур цвета текста.
            paint.color = C.TEXT
            c.drawRect(l, t, r, t + one, paint); c.drawRect(l, bt - one, r, bt, paint)
            c.drawRect(l, t, l + one, bt, paint); c.drawRect(r - one, t, r, bt, paint)
        }
        if (on) {
            paint.color = C.INK
            paint.style = Paint.Style.STROKE
            val s = size.toFloat()
            c.drawLine(l + s * 0.25f, t + s * 0.52f, l + s * 0.43f, t + s * 0.70f, paint)
            c.drawLine(l + s * 0.43f, t + s * 0.70f, l + s * 0.76f, t + s * 0.32f, paint)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Drawable API")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Полоса ProgressBar: контур 1dp FRAME, заполнение амбером. */
fun Context.framedProgress(): Drawable {
    val inset = dp(1)
    val layers = LayerDrawable(arrayOf(box(C.BG, C.FRAME), ClipDrawable(ColorDrawable(C.AMBER), Gravity.START, ClipDrawable.HORIZONTAL)))
    layers.setId(0, android.R.id.background)
    layers.setId(1, android.R.id.progress)
    layers.setLayerInset(1, inset, inset, inset, inset)
    return layers
}

/**
 * Ряды по ширине: дети (wrap_content) переносятся на следующую строку, ничего не обрезается.
 * [endLast] — последний ребёнок прижат к правому краю своей строки.
 */
class Flow(ctx: Context, private val hGap: Int, private val vGap: Int, private val endLast: Boolean = false) : ViewGroup(ctx) {
    private var xs = IntArray(0)
    private var ys = IntArray(0)
    private var lineOf = IntArray(0)
    private var lineH = IntArray(0)

    override fun onMeasure(ws: Int, hs: Int) {
        val wMode = MeasureSpec.getMode(ws)
        val maxW = if (wMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(ws) - paddingLeft - paddingRight
        val n = childCount
        xs = IntArray(n); ys = IntArray(n); lineOf = IntArray(n)
        val heights = ArrayList<Int>()
        var x = 0; var y = 0; var h = 0; var widest = 0; var started = false
        for (i in 0 until n) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(maxW.coerceAtMost(1 shl 24), if (wMode == MeasureSpec.UNSPECIFIED) MeasureSpec.UNSPECIFIED else MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val cw = c.measuredWidth
            if (started && x + hGap + cw > maxW) { heights += h; y += h + vGap; x = 0; h = 0; started = false }
            if (started) x += hGap
            xs[i] = x; ys[i] = y; lineOf[i] = heights.size
            x += cw
            h = max(h, c.measuredHeight)
            widest = max(widest, x)
            started = true
        }
        heights += h
        lineH = heights.toIntArray()
        val w = if (wMode == MeasureSpec.EXACTLY) MeasureSpec.getSize(ws) else widest + paddingLeft + paddingRight
        setMeasuredDimension(resolveSize(w, ws), resolveSize(y + h + paddingTop + paddingBottom, hs))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var last = -1
        for (i in 0 until childCount) if (getChildAt(i).visibility != View.GONE) last = i
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            val left = if (endLast && i == last) width - paddingRight - c.measuredWidth else paddingLeft + xs[i]
            val top = paddingTop + ys[i] + (lineH[lineOf[i]] - c.measuredHeight) / 2
            c.layout(left, top, left + c.measuredWidth, top + c.measuredHeight)
        }
    }
}

/**
 * Крупное число в одну строку: не влезает в ширину — шрифт уменьшается до [minSp]; не влезает
 * и так — переносится на вторую строку (никогда не режется). [maxSp] — размер при достаточной
 * ширине; оба — с учётом масштаба шрифта.
 */
class FitText(ctx: Context, private val maxSp: Float, private val minSp: Float = 14f) : TextView(ctx) {
    init { maxLines = 1; textSize = maxSp }

    /** Новое значение (тик скана) — пересчитать размер, даже если ширина view не меняется. */
    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, after: Int) {
        super.onTextChanged(text, start, before, after)
        requestLayout()
    }

    override fun onMeasure(ws: Int, hs: Int) {
        if (MeasureSpec.getMode(ws) != MeasureSpec.UNSPECIFIED) {
            val avail = MeasureSpec.getSize(ws) - totalPaddingLeft - totalPaddingRight
            val dm = resources.displayMetrics
            val max = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, maxSp, dm)
            val min = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, minSp, dm)
            val cur = textSize
            val need = paint.measureText(text.toString()) * max / cur
            val fit = if (need <= avail || need <= 0f) max else max * avail / need * 0.98f
            val px = maxOf(fit, min)
            if (px != cur) setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
            val lines = if (fit < min) 2 else 1
            if (maxLines != lines) maxLines = lines
        }
        super.onMeasure(ws, hs)
    }
}
