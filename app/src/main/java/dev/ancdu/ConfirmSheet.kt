package dev.ancdu

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.StateListDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Подтверждение и сообщение приложения — вместо системного диалога: лист у нижнего края в стиле
 * листа удаления и панели пути (PANEL, скобки сверху, затемнение, SheetAnim; без движения — при
 * сокращённой анимации). Заголовок (Exo 600, 22sp), текст (прокручивается, если длинный: перечень
 * итога группы) и не больше двух кнопок ([ButtonPair]: при крупном шрифте — друг под другом).
 *
 * [ok] null — одна кнопка [cancel] во всю ширину. [danger] — «опасный» вариант: [ok] в красной заливке
 * DANGER_FILL и не принимает касаний первые [OPEN_GUARD_MS] после открытия (второй тап двойного
 * нажатия не подтверждает; performClick — намеренный, проходит). Фокус по умолчанию — [cancel].
 * Звуки: [openCue] при открытии (null — его уже дал вызвавший), BACK — отмена любым путём, TAP —
 * [ok]. [onDismiss] — после закрытия любым путём. Заголовок окна — [title] (TalkBack).
 */
class ConfirmSheet(
    private val act: Activity,
    val title: String,
    val body: String,
    ok: String?,
    cancel: String,
    val danger: Boolean = false,
    private val openCue: Cue? = Cue.TAP,
    private val onDismiss: (() -> Unit)? = null,
    /** Нажата сама кнопка [cancel] (не «назад» и не тап мимо): у сообщения с одной кнопкой — её действие. */
    private val onCancelButton: () -> Unit = {},
    private val onOk: () -> Unit = {},
) {
    val dialog = Dialog(act, R.style.Theme_Ancdu_Sheet)
    lateinit var titleText: TextView
        private set
    lateinit var bodyText: TextView
        private set
    /** Кнопка действия; null — сообщение с одной кнопкой. */
    var okButton: TextView? = null
        private set
    lateinit var cancelButton: TextView
        private set
    /** Пара кнопок (null — одна кнопка): [ButtonPair.stacked] — друг под другом. */
    internal var pair: ButtonPair? = null
        private set
    /** Для тестов: касания [okButton], отброшенные защитой открытия. */
    var guardedTaps = 0
        private set
    private var shownAt = 0L
    private var okd = false

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(MaxHeightBox(act, 0.85f).apply {
            background = Brackets(act, C.PANEL, bottom = false)
            setPadding(act.dp(20), act.dp(20), act.dp(20), act.dp(16))
            titleText = act.label(title, 22f, C.TEXT, bold = true)
            addView(titleText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = act.dp(10) })
            bodyText = act.label(body, 15f, C.MUTED)
            addView(ScrollView(act).apply { addView(bodyText) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1f))
            cancelButton = sheetButton(act, cancel, null, C.TEXT) { dialog.cancel(); onCancelButton() }
            val okv = ok?.let { text ->
                sheetButton(act, text, if (danger) C.DANGER_FILL else C.AMBER, if (danger) C.WHITE else C.INK) { confirm() }
            }
            okButton = okv
            val row: View = if (okv == null) cancelButton
                else ButtonPair(act, okv, cancelButton, listOf(okv.text.toString())).also { pair = it }
            addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = act.dp(16) })
        })
        dialog.bottomSheet()
        dialog.setTitle(title)
        // «Отмена», «назад», тап мимо листа — BACK.
        dialog.setOnCancelListener { Feedback.cue(dialog.window?.decorView, Cue.BACK) }
        dialog.setOnDismissListener { onDismiss?.invoke() }
        if (danger) okButton?.setOnTouchListener { _, ev ->
            val early = shownAt == 0L || SystemClock.uptimeMillis() - shownAt < OPEN_GUARD_MS
            if (early && ev.actionMasked == MotionEvent.ACTION_UP) guardedTaps++
            early
        }
    }

    private fun confirm() {
        if (okd) return
        okd = true
        Feedback.cue(okButton, Cue.TAP)
        dialog.dismiss()
        onOk()
    }

    val isShowing: Boolean get() = dialog.isShowing

    /** Как у системного диалога: BUTTON_POSITIVE — [okButton], BUTTON_NEGATIVE — [cancelButton]; иначе null. */
    fun getButton(which: Int): TextView? = when (which) {
        DialogInterface.BUTTON_POSITIVE -> okButton
        DialogInterface.BUTTON_NEGATIVE -> cancelButton
        else -> null
    }

    fun show(): ConfirmSheet {
        dialog.show()
        shownAt = SystemClock.uptimeMillis()
        // Фокус по умолчанию — «Отмена»/«Закрыть»: и сразу, и после первого кадра окна (окно само
        // ставит фокус на первый фокусируемый вне режима касания).
        cancelButton.requestFocus()
        dialog.window?.decorView?.post { if (dialog.isShowing) cancelButton.requestFocus() }
        openCue?.let { Feedback.cue(cancelButton, it) }
        return this
    }

    fun dismiss() = dialog.dismiss()
    fun cancel() = dialog.cancel()

    companion object {
        /** Касания «опасной» кнопки столько после открытия — мимо (как у листа удаления). */
        const val OPEN_GUARD_MS = DeleteSheet.OPEN_GUARD_MS
    }
}

/**
 * Выбор одного из [options] (язык, тема, звук) — тот же лист, что [ConfirmSheet]: заголовок, строки
 * 48dp через волосяные линии, у выбранной [checked] — амберный флажок справа (как флажок режима
 * выбора), внизу «Закрыть». Тап по строке — TAP, лист закрывается, затем [onPick]; «Закрыть»,
 * «назад» — BACK; звук открытия — у пункта меню, открывшего выбор. Фокус — на
 * выбранной строке. TalkBack: строки — RadioButton с состоянием.
 */
class ChoiceSheet(private val act: Activity, val title: String, val options: List<String>, val checked: Int,
                  private val onPick: (Int) -> Unit) {
    val dialog = Dialog(act, R.style.Theme_Ancdu_Sheet)
    /** Строки по порядку [options]. */
    val rows = ArrayList<View>()
    lateinit var closeButton: TextView
        private set

    init {
        val t = act.tx
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(MaxHeightBox(act, 0.85f).apply {
            background = Brackets(act, C.PANEL, bottom = false)
            setPadding(act.dp(20), act.dp(20), act.dp(20), act.dp(16))
            addView(act.label(title, 22f, C.TEXT, bold = true), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = act.dp(6) })
            val list = act.vbox()
            for ((i, o) in options.withIndex()) {
                list.hairline()
                val on = i == checked
                val row = act.hbox(8).apply {
                    minimumHeight = act.dp(48)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(act.dp(8), act.dp(6), act.dp(8), act.dp(6))
                    background = act.pressable(Color.TRANSPARENT)
                    isClickable = true; isFocusable = true
                    isSoundEffectsEnabled = false
                    addView(act.label(o, 15f, C.TEXT).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(CheckMark(act, on), LinearLayout.LayoutParams(act.dp(16), act.dp(16)))
                    contentDescription = o
                    stateDescription = t.s(if (on) R.string.sel_on else R.string.sel_off)
                    accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(host: View, n: AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host, n)
                            n.className = "android.widget.RadioButton"
                            n.isCheckable = true; n.isChecked = on
                        }
                    }
                    setOnClickListener { Feedback.cue(this, Cue.TAP); dialog.dismiss(); onPick(i) }
                }
                rows += row
                list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
            addView(ScrollView(act).apply { addView(list) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1f))
            closeButton = sheetButton(act, t.s(R.string.close), null, C.TEXT) { dialog.cancel() }
            addView(closeButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = act.dp(16) })
        })
        dialog.bottomSheet()
        dialog.setTitle(title)
        dialog.setOnCancelListener { Feedback.cue(dialog.window?.decorView, Cue.BACK) }
    }

    val isShowing: Boolean get() = dialog.isShowing

    fun show(): ChoiceSheet {
        dialog.show()
        val focus = rows.getOrNull(checked) ?: closeButton
        focus.requestFocus()
        dialog.window?.decorView?.post { if (dialog.isShowing) focus.requestFocus() }
        // TAP открытия дал пункт меню, открывший выбор.
        return this
    }

    fun dismiss() = dialog.dismiss()
}

/** Флажок 16dp выбранной строки [ChoiceSheet]: AMBER с краем AMBER_TEXT и галочкой INK; не выбрана — пусто. */
private class CheckMark(ctx: Context, private val on: Boolean) : View(ctx) {
    private val fill = Paint()
    private val one = ctx.dp(1).toFloat()
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dp(2).toFloat(); color = C.INK
        strokeCap = Paint.Cap.SQUARE; strokeJoin = Paint.Join.MITER
    }
    private val path = Path()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(c: Canvas) {
        if (!on) return
        val s = minOf(width, height).toFloat()
        fill.color = C.AMBER
        c.drawRect(0f, 0f, s, s, fill)
        fill.color = C.AMBER_TEXT
        c.drawRect(0f, 0f, s, one, fill); c.drawRect(0f, s - one, s, s, fill)
        c.drawRect(0f, 0f, one, s, fill); c.drawRect(s - one, 0f, s, s, fill)
        val u = s / 16f
        path.reset()
        path.moveTo(3.5f * u, 8.2f * u); path.lineTo(6.6f * u, 11.2f * u); path.lineTo(12.5f * u, 4.8f * u)
        c.drawPath(path, tick)
    }
}

/**
 * Кнопка листа 56dp: [bg] — заливка (нажатие и фокус — контур FOCUS), null — контур FRAME.
 * Звук — у вызывающего ([onClick]).
 */
internal fun sheetButton(act: Context, text: String, bg: Int?, fg: Int, onClick: () -> Unit): TextView =
    act.label(text, 15f, fg, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = act.dp(56)
        setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8))
        val danger = bg == C.DANGER_FILL
        // Амберная нажатая — PANEL2 с подписью и контуром AMBER_TEXT (как action()): INK на PANEL2 не читается.
        if (bg != null && !danger) setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(C.AMBER_TEXT, fg)))
        background = if (bg == null) act.pressable(Color.TRANSPARENT, C.FRAME) else StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), if (danger) act.box(C.DANGER_PRESSED, C.FOCUS) else act.box(C.PANEL2, C.AMBER_TEXT))
            addState(intArrayOf(android.R.attr.state_focused), act.box(bg, C.FOCUS))
            addState(intArrayOf(), act.box(bg))
        }
        isClickable = true; isFocusable = true
        isSoundEffectsEnabled = false
        setOnClickListener { onClick() }
    }
