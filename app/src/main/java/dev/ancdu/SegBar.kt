package dev.ancdu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Полоса заполнения раздела: сегменты яруса 0 или одна доля «занято». */
class SegBar(ctx: Context) : View(ctx) {
    var segs: List<Seg>? = null
        set(v) { field = v; contentDescription = describe(); invalidate() }
    var used = 0f
        set(v) { field = v; contentDescription = describe(); invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val r = RectF()

    init { tag = "segbar" }

    private fun describe(): String {
        val t = context.tx
        return segs?.joinToString { "${t.s(it.label)} ${Fmt.size(it.bytes, t)}" }
            ?: t.s(R.string.bar_used, "${(used * 100).toInt()}%")
    }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), context.dp(12))

    override fun onDraw(c: Canvas) {
        val rad = height / 2f
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        clip.reset(); clip.addRoundRect(r, rad, rad, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        paint.color = C.PANEL2
        c.drawRect(r, paint)
        val s = segs
        if (s != null) {
            val total = s.sumOf { it.bytes }.coerceAtLeast(1)
            var x = 0f
            for (seg in s) {
                val w = width * (seg.bytes.toFloat() / total)
                paint.color = seg.color
                c.drawRect(x, 0f, x + w, height.toFloat(), paint)
                x += w
            }
        } else {
            paint.color = C.AMBER
            c.drawRect(0f, 0f, width * used, height.toFloat(), paint)
        }
        c.restore()
    }
}
