package dev.ancdu

import android.app.Activity
import android.app.Dialog
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Лист точки отсчёта (из плашки «Δ с …» браузера), в стиле панели пути: «ТОЧКА ОТСЧЁТА», дата её
 * скана, «N дней назад · размер файла», как она обновляется, «ОТМЕТИТЬ СЕЙЧАС» (сразу, без
 * подтверждения: точка отсчёта := дерево на экране, Δ становится ±0) и «ЗАКРЫТЬ».
 * [time] — время скана точки отсчёта, [bytes] — размер её файла; [onClose] — лист закрыт любым путём.
 */
class BaselineSheet(private val act: Activity, time: Long, bytes: Long, onMark: () -> Unit,
                    private val onClose: () -> Unit = {}) {
    private val t: Txt = act.tx
    lateinit var dateText: TextView
        private set
    lateinit var infoText: TextView
        private set
    lateinit var markButton: View
        private set
    lateinit var closeButton: View
        private set

    val dialog: Dialog = act.sheetDialog(t.s(R.string.baseline_caps), act.vbox(10).apply {
        addView(act.caps(t.s(R.string.baseline_caps)))
        dateText = act.label(Freshness.date(t, R.string.fmt_since_time, time), 20f, C.TEXT, mono = true, bold = true)
        addView(dateText)
        infoText = act.label("${GrowthText.daysAgo(t, System.currentTimeMillis(), time)} · ${Fmt.size(bytes, t)}",
            13f, C.MUTED, mono = true)
        addView(infoText)
        addView(act.label(t.s(R.string.baseline_explain), 14f, C.MUTED))
        markButton = act.action(t.s(R.string.baseline_mark), t.s(R.string.baseline_mark_sub), primary = true) { onMark() }
        addView(markButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        closeButton = act.action(t.s(R.string.close), null, primary = false, cue = Cue.BACK) { dismiss() }
        addView(closeButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    })

    init { dialog.setOnDismissListener { onClose() } }

    fun show() = dialog.show()
    fun dismiss() = dialog.dismiss()
}
