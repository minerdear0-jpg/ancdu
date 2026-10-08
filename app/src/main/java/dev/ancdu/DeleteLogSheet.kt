package dev.ancdu

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

/** Чистый Kotlin: колонки строк журнала удалений. */
object LogRows {
    /** Время строк (новые сверху): дата — только у первой строки дня, дальше — «12:02». */
    fun times(t: Txt, times: List<Long>, tz: TimeZone = TimeZone.getDefault()): List<String> {
        val day = SimpleDateFormat("yyyyMMdd", t.locale).apply { timeZone = tz }
        var prev: String? = null
        return times.map { ms ->
            val d = day.format(Date(ms))
            val s = Freshness.date(t, if (d == prev) R.string.fmt_time else R.string.fmt_since_time, ms, tz)
            prev = d
            s
        }
    }

    /** Колонка размера: удалено (по умолчанию) — просто размер; отклонения — «⚠ X из Y», «⚠ частично», «⚠ прервано». */
    fun size(t: Txt, e: LogEntry): String = when (e.outcome) {
        LogOutcome.DELETED -> Fmt.size(e.start.disk, t)
        LogOutcome.PARTIAL -> "⚠ " + (e.freed?.let { Fmt.sizeOf(it, e.start.disk, t) } ?: t.s(R.string.log_partial))
        LogOutcome.INTERRUPTED -> "⚠ " + t.s(R.string.log_interrupted)
    }

    /** Путь от корня дерева («Download/», «Download/a.bin»); сам корень — его путь. */
    fun path(e: LogEntry): String {
        val n = e.start.names
        if (n.isEmpty()) return e.start.root
        return n.joinToString("/") { String(it, Charsets.UTF_8) } + if (e.start.dir) "/" else ""
    }

    /**
     * Строка пути: объект — его путь; группа — папка и число: «DCIM/.thumbnails/ · 1 204 объекта».
     * Имена объектов группы (до 20) лист не показывает: они — для истории и будущего подробного вида.
     */
    fun title(t: Txt, e: LogEntry): String =
        if (e.start.group) folder(t, e) + " · " + GroupSheet.objects(t, e.start.count) else path(e)

    /** Папка группы; у группы из разных папок без общей папки ниже корня — «(разные папки)». */
    private fun folder(t: Txt, e: LogEntry): String =
        if (e.start.mixed && e.start.names.isEmpty()) t.s(R.string.log_several_folders) else path(e)
}

/**
 * Лист «Журнал удалений» (как панель пути): только чтение — время, путь, размер; ниже две кнопки:
 * «Очистить…» (контур, DANGER_TEXT; только если журнал не пуст) и «Закрыть». Очистка — после
 * подтверждения. Главный поток.
 */
class DeleteLogSheet(private val act: Activity, entries: List<LogEntry>) {
    private val t: Txt = act.tx
    val dialog = Dialog(act, R.style.Theme_Ancdu_Sheet)
    /** Для тестов: строки журнала (текст строки — время, путь, размер через « · »). */
    val rows = ArrayList<View>()
    lateinit var clearButton: TextView
        private set
    lateinit var closeButton: TextView
        private set
    /** Пустой журнал или «Журнал очищен» (null — есть строки). */
    var emptyText: TextView? = null
        private set
    /** Для тестов: открытое подтверждение очистки. */
    var confirm: AlertDialog? = null
        private set
    /** Для тестов: касаний «Очистить» подтверждения, отклонённых защитой от двойного тапа. */
    var guardedTaps = 0
        private set
    private var count = entries.size
    private lateinit var list: LinearLayout

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(build(entries))
        dialog.bottomSheet()
        dialog.setTitle(t.s(R.string.log_title))
        dialog.setOnCancelListener { Feedback.cue(dialog.window?.decorView, Cue.BACK) }
        dialog.setOnDismissListener { confirm?.dismiss() }
    }

    fun show() { dialog.show(); closeButton.requestFocus() }
    fun dismiss() = dialog.dismiss()

    private fun build(entries: List<LogEntry>): View = MaxHeightBox(act, 0.85f).apply {
        background = Brackets(act, C.PANEL, bottom = false)
        setPadding(act.dp(20), act.dp(20), act.dp(20), act.dp(16))
        addView(ScrollView(act).apply {
            addView(act.vbox(10).apply {
                addView(act.caps(t.s(R.string.log_title)))
                list = act.vbox()
                addView(list, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1f))
        fill(entries)
        addView(act.hbox(10).apply {
            setPadding(0, act.dp(12), 0, 0)
            clearButton = button(t.s(R.string.log_clear), C.DANGER_TEXT, Cue.TAP) { askClear() }
            closeButton = button(t.s(R.string.close), C.TEXT, Cue.BACK) { dialog.dismiss() }
            addView(clearButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(closeButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            clearButton.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun fill(entries: List<LogEntry>, empty: Int = R.string.log_empty) {
        list.removeAllViews(); rows.clear()
        emptyText = null
        if (entries.isEmpty()) {
            emptyText = act.label(t.s(empty), 14f, C.MUTED).apply {
                minHeight = act.dp(48); gravity = Gravity.CENTER_VERTICAL
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            list.addView(emptyText)
            return
        }
        val times = LogRows.times(t, entries.map { it.start.time })
        for ((k, e) in entries.withIndex()) list.addView(row(times[k], e), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun row(time: String, e: LogEntry): View = act.hbox(8).apply {
        minimumHeight = act.dp(44)
        val path = Bidi.visible(LogRows.title(t, e))
        val size = LogRows.size(t, e)
        addView(act.label(time, 12f, C.MUTED, mono = true).apply { setSingleLine(true) })
        addView(act.label(path, 13f, C.TEXT, mono = true).apply {
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (e.start.viaRoot) addView(act.label("root", 12f, C.MUTED, mono = true))
        addView(act.label(size, 13f, if (e.outcome == LogOutcome.DELETED) C.MUTED else C.AMBER_TEXT, mono = true).apply {
            setSingleLine(true)
        })
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
        contentDescription = listOfNotNull(time, path, if (e.start.viaRoot) "root" else null, size).joinToString(", ")
        rows += this
    }

    /** Кнопка 56dp в контуре FRAME; [fg] — цвет подписи. */
    private fun button(text: String, fg: Int, cue: Cue, onClick: () -> Unit): TextView =
        act.label(text, 15f, fg, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = act.dp(56)
            setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8))
            background = act.pressable(Color.TRANSPARENT, C.FRAME)
            isClickable = true; isFocusable = true
            feedbackClick(cue) { onClick() }
        }

    /**
     * «Очистить…»: подтверждение (фокус — «Отмена»). «Очистить» не принимает касаний первые
     * [OPEN_GUARD_MS]: второй тап двойного нажатия «Очистить…» не подтверждает.
     */
    private fun askClear() {
        if (count == 0 || confirm?.isShowing == true) return
        val n = Fmt.count(count.toLong(), t.locale)
        val body = t.q(if (DeleteLog.notice != null) R.plurals.log_clear_body_notice else R.plurals.log_clear_body, count.toLong(), n)
        val d = AlertDialog.Builder(act, R.style.Theme_Ancdu_Alert)
            .setTitle(t.s(R.string.log_clear_title)).setMessage(body)
            .setNegativeButton(t.s(R.string.cancel)) { dd, _ -> Feedback.cue((dd as AlertDialog).window?.decorView, Cue.BACK) }
            .setPositiveButton(t.s(R.string.log_clear_ok), null)
            .create()
        d.setOnCancelListener { Feedback.cue(d.window?.decorView, Cue.BACK) }
        d.show()
        val shownAt = SystemClock.uptimeMillis()
        val ok = d.getButton(DialogInterface.BUTTON_POSITIVE)
        ok.setOnTouchListener { _, ev ->
            val early = SystemClock.uptimeMillis() - shownAt < OPEN_GUARD_MS
            if (early && ev.actionMasked == MotionEvent.ACTION_UP) guardedTaps++
            early
        }
        ok.setOnClickListener {
            Feedback.cue(ok, Cue.TAP)
            d.dismiss()
            DeleteLog.clear { done -> if (done && dialog.isShowing) cleared() }
        }
        // Фокус по умолчанию — «Отмена». Окно диалога при первом проходе само ставит фокус на первую
        // кнопку (вне режима касания), поэтому — и сразу, и после первого кадра окна.
        val cancel = d.getButton(DialogInterface.BUTTON_NEGATIVE)
        cancel.requestFocus()
        d.window?.decorView?.post { if (d.isShowing) cancel.requestFocus() }
        confirm = d
    }

    /** Очищено: строки уходят, на их месте — «Журнал очищен»; «Очистить…» прячется. */
    private fun cleared() {
        count = 0
        fill(emptyList(), R.string.log_cleared)
        clearButton.visibility = View.GONE
        closeButton.requestFocus()
    }

    companion object {
        /** Касания «Очистить» в подтверждении столько после его открытия — мимо (как DeleteSheet.OPEN_GUARD_MS). */
        const val OPEN_GUARD_MS = DeleteSheet.OPEN_GUARD_MS

        /** Прочитать журнал на io и показать лист на [act]; [shown] — получает лист (экран держит ссылку). */
        fun open(act: Activity, shown: (DeleteLogSheet) -> Unit) {
            DeleteLog.init(act)
            DeleteLog.read { entries ->
                if (act.isFinishing || act.isDestroyed) return@read
                shown(DeleteLogSheet(act, entries).also { it.show() })
            }
        }
    }
}
