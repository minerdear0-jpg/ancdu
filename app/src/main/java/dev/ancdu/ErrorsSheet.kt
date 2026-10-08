package dev.ancdu

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Строка листа ошибок: узел, полный путь, путь от корня, причина и заметка о закрытой папке Android. */
class ErrRow(val node: Int, val path: String, val rel: String, val reason: ErrReason, val errno: Int,
             val errnoName: String?, val note: Boolean, val dir: Boolean = true)

/** До [ScanErrors.CAP] строк по пути и сколько ошибок всего. */
class ErrList(val rows: List<ErrRow>, val total: Int)

/**
 * Лист «Ошибки сканирования» (как панель пути: PANEL, скобки сверху, затемнение 60%, SheetAnim, до
 * 85% экрана): заголовок и число, по строке 48dp+ на узел — путь от корня (mono 13, до двух строк,
 * «…» посередине) и причина (12sp MUTED). Закрытая папка Android/data|obb дерева не от root —
 * заметка под причиной; root выдан — одна кнопка «СКАНИРОВАТЬ ОТ ROOT». Тап по строке — [onPick].
 */
class ErrorsSheet(private val act: Activity, val list: ErrList, private val rootGranted: Boolean,
                  private val onPick: (Int) -> Unit, private val onRoot: () -> Unit,
                  /** Лист закрыт любым путём: отложенная подстановка дерева перепроверяется. */
                  private val onClose: () -> Unit = {}) {
    private val t: Txt = act.tx
    val dialog = Dialog(act, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
    /** Для тестов: строки по порядку ([list].rows), их причины, кнопка root (null — нет). */
    val rows = ArrayList<View>()
    val reasons = ArrayList<TextView>()
    var rootButton: View? = null
        private set
    var moreText: TextView? = null
        private set

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(build())
        dialog.bottomSheet()
        dialog.setTitle(t.s(R.string.scan_errors))
        dialog.setOnCancelListener { Feedback.cue(rows.firstOrNull() ?: dialog.window?.decorView, Cue.BACK) }
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
        val count = t.q(R.plurals.errors, list.total.toLong(), Fmt.count(list.total.toLong(), t.locale))
        addView(act.hbox(8).apply {
            addView(act.caps(t.s(R.string.scan_errors)), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(act.label(count, 12f, C.MUTED, mono = true))
        })
        // Root-скан /data/media покрывает только внутреннюю память: у съёмного тома — одна заметка.
        if (rootGranted && list.rows.any { it.note && ScanErrors.rootScanCovers(it.path) }) {
            rootButton = act.action(t.s(R.string.err_scan_root), null, primary = false) { onRoot() }.apply {
                minimumHeight = act.dp(48)
            }
            addView(rootButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        addView(act.vbox().apply {
            for (r in list.rows) addView(row(r), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
        ScanErrors.more(t, list.total, list.rows.size)?.let {
            moreText = act.label(it, 12f, C.MUTED, mono = true)
            addView(moreText)
        }
    }

    private fun row(r: ErrRow): View = act.vbox(2).apply {
        minimumHeight = act.dp(48)
        setPadding(act.dp(8), act.dp(6), act.dp(8), act.dp(6))
        gravity = android.view.Gravity.CENTER_VERTICAL
        val shown = Bidi.visible(r.rel)
        addView(MiddleLines(act, shown, 2).apply {
            textSize = 13f; setTextColor(C.TEXT); typeface = Fonts.get(act, mono = true, bold = false)
        })
        val why = ScanErrors.text(t, ScanErrors.shown(r.reason, r.note), r.errnoName, r.errno, r.dir)
        val reason = act.label(why, 12f, C.MUTED)
        reasons += reason
        addView(reason)
        val note = if (r.note) t.s(R.string.err_android_private) else null
        if (note != null) addView(act.label(note, 12f, C.MUTED))
        background = act.pressable(Color.TRANSPARENT)
        isClickable = true; isFocusable = true
        contentDescription = listOfNotNull(shown, why, note).joinToString(", ")
        feedbackClick { onPick(r.node) }
        rows += this
    }

    companion object {
        /**
         * На Holder.io (только чтения дерева [h]): узлы с ошибкой, их пути и повторная попытка открыть
         * каждый. Root-дерево ([viaRoot]) не повторяется — это было бы в пространстве имён su.
         */
        fun load(h: Long, viaRoot: Boolean, root: String, rootTitle: String): ErrList {
            val ids = IntArray(ScanErrors.CAP)
            val total = Native.errorNodes(h, ids)
            val k = Native.errorNodesWritten(total, ids.size)
            val inf = LongArray(4 * maxOf(k, 1))
            if (k > 0) Native.nodeInfo(h, ids, k, inf)
            val rows = (0 until k).map { i ->
                val nd = ids[i]
                val bytes = Native.path(h, nd)
                val path = Native.str(bytes)
                val dir = inf[4 * i + 3].toInt() and F_DIR != 0
                // Байты пути не UTF-8: строкой его не открыть — честно «не прочитано при скане».
                val errno = if (viaRoot || !path.toByteArray(Charsets.UTF_8).contentEquals(bytes)) -1 else retry(path, dir)
                val reason = if (errno < 0) ErrReason.NOT_READ else ScanErrors.reason(errno)
                val name = if (reason == ErrReason.OTHER) runCatching { OsConstants.errnoName(errno) }.getOrNull() else null
                ErrRow(nd, path, ScanErrors.relative(path, root, rootTitle), reason, maxOf(errno, 0), name,
                    ScanErrors.androidNote(path, viaRoot), dir)
            }.sortedBy { it.rel }
            return ErrList(rows, total)
        }

        /**
         * errno открытия [path] сейчас (0 — открылся). O_DIRECTORY в OsConstants нет (и значение у
         * arm64 и x86_64 разное), поэтому «это каталог» — по lstat до и fstat после open. Ссылка —
         * ELOOP; не каталог вместо каталога — ENOTDIR; FIFO и устройства не открываются (0).
         * open — O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK: не идёт по ссылке и не блокируется.
         */
        private fun retry(path: String, dir: Boolean): Int = try {
            val mode = Os.lstat(path).st_mode
            when {
                OsConstants.S_ISLNK(mode) -> ScanErrors.ELOOP
                dir && !OsConstants.S_ISDIR(mode) -> ScanErrors.ENOTDIR
                !dir && !OsConstants.S_ISREG(mode) -> 0
                else -> {
                    val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or
                        OsConstants.O_NONBLOCK, 0)
                    try { if (dir && !OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) ScanErrors.ENOTDIR else 0 } finally { Os.close(fd) }
                }
            }
        } catch (e: ErrnoException) {
            e.errno
        }
    }
}
