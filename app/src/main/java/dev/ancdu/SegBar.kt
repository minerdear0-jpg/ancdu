package dev.ancdu

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Полоса раздела 12dp: контур FRAME, занято — амбер, свободно — синий 30%, риски на 25/50/75%.
 * Первое значение заполняется 0→[used] за 400 мс (ease-out), если анимации не выключены.
 */
class SegBar(ctx: Context) : View(ctx) {
    var used = 0f
        set(v) {
            field = v
            contentDescription = context.tx.s(R.string.bar_used, "${(v * 100).toInt()}%")
            if (!filledOnce) {
                filledOnce = true
                if (Motion.on()) {
                    fill = ValueAnimator.ofFloat(0f, v).apply {
                        duration = 400
                        interpolator = DecelerateInterpolator()
                        addUpdateListener { shown = it.animatedValue as Float; invalidate() }
                        start()
                    }
                    return
                }
            }
            fill?.cancel()
            shown = v
            invalidate()
        }
    /** Для тестов: анимация первого заполнения (null — её не было: анимации выключены). */
    var fill: ValueAnimator? = null
        private set
    private var shown = 0f
    private val paint = Paint()
    private val one = ctx.dp(1).toFloat()

    init { tag = "segbar" }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), context.dp(12))

    override fun onDetachedFromWindow() {
        fill?.cancel()
        shown = used
        super.onDetachedFromWindow()
    }

    companion object {
        /** Заполнение анимируется один раз на процесс (не при каждом пересоздании экрана). Для тестов — сбрасываемо. */
        @Volatile var filledOnce = false
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val l = one; val t = one; val r = w - one; val b = h - one
        val split = l + (r - l) * shown
        paint.color = (C.BLUE and 0x00FFFFFF) or 0x4D000000   // свободно: 30%
        c.drawRect(split, t, r, b, paint)
        paint.color = C.AMBER
        c.drawRect(l, t, split, b, paint)
        paint.color = C.BG
        for (k in 1..3) { val x = l + (r - l) * k / 4f; c.drawRect(x, t, x + one, b, paint) }
        paint.color = C.FRAME
        c.drawRect(0f, 0f, w, one, paint); c.drawRect(0f, h - one, w, h, paint)
        c.drawRect(0f, 0f, one, h, paint); c.drawRect(w - one, 0f, w, h, paint)
    }
}

/**
 * Вторичная полоса 6dp яруса 0: категории занятого (без «свободно») в долях от их суммы,
 * контур FRAME, как у прочих полос. Без анимации.
 */
class CatBar(ctx: Context) : View(ctx) {
    var segs: List<Seg> = emptyList()
        set(v) {
            field = v
            val t = context.tx
            contentDescription = v.joinToString { "${t.s(it.label)} ${Fmt.size(it.bytes, t)}" }
            invalidate()
        }
    private val paint = Paint()
    private val one = ctx.dp(1).toFloat()

    init { tag = "catbar" }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), context.dp(6))

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val total = segs.sumOf { it.bytes }.coerceAtLeast(1)
        var x = one
        val inner = w - 2 * one
        for (s in segs) {
            val sw = inner * (s.bytes.toFloat() / total)
            paint.color = s.color
            c.drawRect(x, one, x + sw, h - one, paint)
            x += sw
        }
        paint.color = C.FRAME
        c.drawRect(0f, 0f, w, one, paint); c.drawRect(0f, h - one, w, h, paint)
        c.drawRect(0f, 0f, one, h, paint); c.drawRect(w - one, 0f, w, h, paint)
    }
}
