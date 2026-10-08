package dev.ancdu

import android.view.Gravity
import android.view.View
import android.widget.TextView

/**
 * Ошибки скана в подвале браузера [a]: число узлов с ошибкой (на io, раз на дерево и после каждого
 * удаления), ссылка «⚠ 2 ошибки ›» и лист ошибок. Переход к узлу и root-скан — у экрана.
 * Главный поток; io — Holder.io.
 */
class ErrorsFooter(private val a: BrowserActivity) {
    /** «⚠ 2 ошибки ›» — открывает лист ошибок; видна, только если ошибок больше 0. */
    lateinit var errLink: TextView
        private set
    /** Открытый лист ошибок скана. */
    var errorsSheet: ErrorsSheet? = null
        private set
    /** Узлов с ошибкой в дереве (Native.errorNodes, считается на io) — число в [errLink]. */
    var errCount = 0
        private set
    /** Для чего посчитан [errCount]: поколение сессии и счётчик удалений (null — пересчитать). */
    private var errKey: String? = null
    /** Номер запроса числа ошибок: ответ для старого дерева отбрасывается. */
    private var errSeq = 0
    /** Лист ошибок собирается на io (второй тап не открывает второй лист). */
    private var errLoading = false

    /** Ссылка ошибок для подвала (onCreate экрана). */
    fun link(): TextView {
        errLink = a.label("", 12f, C.AMBER_TEXT, mono = true).apply {
            gravity = Gravity.CENTER
            minHeight = a.dp(44); minWidth = a.dp(44)
            setPadding(a.dp(12), 0, a.dp(16), 0)
            background = a.pressable(android.graphics.Color.TRANSPARENT)
            isClickable = true; isFocusable = true
            feedbackClick { openErrors() }
            visibility = View.GONE
        }
        return errLink
    }

    /**
     * Число узлов с ошибкой — на io (Native.errorNodes, O(n)), один раз на дерево и после каждого
     * удаления; иначе только перерисовка ссылки. Во время удаления дерево не читается.
     */
    fun refreshErrors() {
        val k = "${Holder.gen}:${Holder.deletes}"
        if (k == errKey || a.h == 0L || a.h != Holder.h || Holder.deleting) { renderErrLink(); return }
        errKey = k
        // Новое дерево: число прежнего не рисуется, ссылка скрыта до ответа io.
        errCount = 0
        renderErrLink()
        val handle = a.h
        val my = ++errSeq
        Holder.io.execute {
            val total = runCatching { Native.errorNodes(handle, IntArray(0)) }.getOrDefault(0)
            a.ui.post {
                if (my != errSeq || a.h != handle || a.isDestroyed) return@post
                errCount = total
                renderErrLink()
            }
        }
    }

    private fun renderErrLink() {
        errLink.visibility = if (errCount > 0) View.VISIBLE else View.GONE
        // Ошибки — амбер (ссылка и ⚠ строк): чип «новее» тогда в контуре.
        a.head.styleNewer(errCount > 0)
        if (errCount <= 0) return
        errLink.text = ScanErrors.link(a.txt, errCount)
        errLink.contentDescription = ScanErrors.linkDesc(a.txt, errCount)
    }

    /**
     * Лист ошибок: узлы, пути и повторная попытка — на io; лист — на главном. Ошибок уже нет (удалены)
     * — листа нет, ссылка прячется.
     */
    fun openErrors() {
        if (a.busy || a.h == 0L || a.h != Holder.h || Holder.deleting || errLoading || a.sheetOpen() || a.selection.active) return
        val handle = a.h
        val viaRoot = Holder.viaRoot
        val root = a.rootPath()
        val rootTitle = PathText.rootTitle(root, a.txt.s(R.string.internal_storage))
        errLoading = true
        Holder.io.execute {
            val r = runCatching { ErrorsSheet.load(handle, viaRoot, root, rootTitle) }.getOrNull()
            a.ui.post {
                errLoading = false
                if (r == null || a.isFinishing || a.isDestroyed || a.h != handle || a.busy) return@post
                errCount = r.total
                renderErrLink()
                // За время io открылся другой лист или режим выбора — лист ошибок не открывается поверх.
                if (r.rows.isEmpty() || a.sheetOpen() || a.selection.active) return@post
                errorsSheet?.dismiss()
                errorsSheet = ErrorsSheet(a, r, Root.state == RootState.GRANTED,
                    onPick = { nd -> errorsSheet?.dismiss(); a.revealNode(handle, nd) },
                    onRoot = { errorsSheet?.dismiss(); a.scanAsRoot() },
                    onClose = { a.refreshPending() }).also { it.show() }
            }
        }
    }

    /** Закрыть лист ошибок (его строки — узлы старого дерева). */
    fun dismissSheet() { errorsSheet?.dismiss(); errorsSheet = null }

    /** onDestroy экрана. */
    fun dispose() = dismissSheet()
}
