package dev.ancdu

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Панель пути (лист у нижнего края, как лист удаления): «ПУТЬ», полный путь (выделяется),
 * «КОПИРОВАТЬ ПУТЬ» 56dp и по строке 48dp на каждого предка от корня вниз — тап переходит к нему.
 * Текущая папка — PANEL2 с «· здесь», не касаемая. [crumbs] — (узел, подпись) от корня до текущей.
 */
class PathPanel(private val act: Activity, val path: String, private val crumbs: List<Pair<Int, String>>,
                private val current: Int, private val onCopy: () -> Unit, private val onJump: (Int) -> Unit,
                /** Панель закрыта любым путём: отложенная подстановка дерева перепроверяется. */
                private val onClose: () -> Unit = {}) {
    private val t: Txt = act.tx
    val dialog = Dialog(act, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
    lateinit var pathText: TextView
        private set
    lateinit var copyButton: View
        private set
    /** Для тестов: строки предков по порядку (последняя — текущая папка) и их узлы. */
    val rows = ArrayList<View>()
    val rowNodes = ArrayList<Int>()

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(build())
        dialog.bottomSheet()
        dialog.setTitle(t.s(R.string.path_caps))
        dialog.setOnDismissListener { onClose() }
    }

    fun show() = dialog.show()
    fun dismiss() = dialog.dismiss()

    private fun build(): View = MaxHeightBox(act, 0.85f).apply {
        background = Brackets(act, C.PANEL, bottom = false)
        setPadding(act.dp(20), act.dp(20), act.dp(20), act.dp(16))
        addView(ScrollView(act).apply { addView(body()) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1f))
    }

    private fun body(): View = act.vbox(10).apply {
        addView(act.caps(t.s(R.string.path_caps)))
        pathText = act.label(path, 14f, C.TEXT, mono = true).apply { setTextIsSelectable(true) }
        addView(pathText)
        copyButton = act.action(t.s(R.string.copy_path), null, primary = false) { onCopy() }
        addView(copyButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(act.vbox().apply {
            for ((k, c) in crumbs.withIndex()) addView(row(k, c.first, c.second), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
    }

    private fun row(depth: Int, nd: Int, text: String): View = act.hbox(6).apply {
        minimumHeight = act.dp(48)
        setPadding(act.dp(8) + depth * act.dp(12), 0, act.dp(8), 0)
        val here = nd == current
        addView(act.label(text, 13f, if (here) C.TEXT else C.MUTED, mono = true).apply {
            setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        if (here) {
            addView(act.label(t.s(R.string.path_here), 12f, C.MUTED, mono = true))
            setBackgroundColor(C.PANEL2)
            contentDescription = "$text ${t.s(R.string.path_here)}"
        } else {
            background = act.pressable(Color.TRANSPARENT)
            isClickable = true; isFocusable = true
            contentDescription = t.s(R.string.crumb_go, text)
            feedbackClick(Cue.BACK) { onJump(nd) }
        }
        rows += this; rowNodes += nd
    }
}
