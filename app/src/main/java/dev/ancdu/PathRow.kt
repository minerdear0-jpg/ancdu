package dev.ancdu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Строка пути над заголовком: одна строка при любом шрифте, не ниже 44dp, mono 12sp — предки
 * приглушённо, последний сегмент ярко; не влезает — начало отбрасывается целыми сегментами
 * ([PathText.fit]). В конце — шеврон 12dp цвета FRAME. Вся строка — одна цель (нажатие — PANEL2).
 */
class PathRow(ctx: Context) : View(ctx) {
    /** Показываемый путь. */
    var path = ""
        set(v) { if (field != v) { field = v; fitW = -1f; invalidate() } }

    /** TalkBack: подпись долгого нажатия; null — без подписи. */
    var longClickLabel: CharSequence? = null

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private val muted = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.get(ctx, mono = true, bold = false); textSize = sp(12f); color = C.MUTED
    }
    private val bright = TextPaint(muted).apply { color = C.TEXT }
    private val chevron = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.FRAME; style = Paint.Style.STROKE; strokeWidth = ctx.dp(1).toFloat() * 1.5f
        strokeCap = Paint.Cap.SQUARE
    }
    private val chevW = ctx.dp(12)
    private val gap = ctx.dp(8)
    private var fitW = -1f
    private var fit = PathText.Fit("", "")

    /** Для тестов: показанный текст при текущей ширине. */
    val shownText: String get() { refit(); return fit.text }

    init {
        background = ctx.pressable(Color.TRANSPARENT)
        isClickable = true; isLongClickable = true; isFocusable = true
        // Звук и вибрацию дают Feedback (feedbackClick и долгое нажатие экрана).
        isSoundEffectsEnabled = false
        isHapticFeedbackEnabled = false
    }

    override fun onMeasure(ws: Int, hs: Int) {
        val fm = muted.fontMetricsInt
        val h = maxOf(context.dp(44), fm.descent - fm.ascent + context.dp(8))
        val w = if (MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED)
            (muted.measureText(path) + chevW + gap).toInt() else MeasureSpec.getSize(ws)
        setMeasuredDimension(w, resolveSize(h, hs))
    }

    private fun refit() {
        val avail = (width - paddingLeft - paddingRight - chevW - gap).toFloat().coerceAtLeast(0f)
        if (avail != fitW) { fit = PathText.fit(path, avail, muted::measureText); fitW = avail }
    }

    override fun onDraw(c: Canvas) {
        refit()
        val fm = muted.fontMetrics
        val base = height / 2f - (fm.ascent + fm.descent) / 2f
        val x = paddingLeft.toFloat()
        c.drawText(fit.head, x, base, muted)
        c.drawText(fit.last, x + muted.measureText(fit.head), base, bright)
        // Шеврон вниз 12×6dp у правого края, по центру строки.
        val r = (width - paddingRight).toFloat()
        val l = r - chevW
        val cy = height / 2f
        val half = chevW / 4f
        c.drawLine(l + 1, cy - half, l + chevW / 2f, cy + half, chevron)
        c.drawLine(l + chevW / 2f, cy + half, r - 1, cy - half, chevron)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        longClickLabel?.let {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, it))
        }
    }
}
