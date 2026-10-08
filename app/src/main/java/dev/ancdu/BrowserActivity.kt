package dev.ancdu

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.AnimationUtils
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BrowserActivity : LangActivity() {
    /** Тексты в языке экрана (смена языка пересоздаёт экран). */
    internal val txt: Txt by lazy { tx }
    lateinit var list: NcduListView
    var node = 0
        private set
    /** Идёт удаление (Holder.deleting): Native не вызывается, ввод игнорируется. */
    val busy get() = Holder.deleting
    /** Для тестов: сколько раз уровень читался из дерева. */
    var loads = 0
        private set
    internal var sort = SORT_SIZE
        private set
    internal var apparent = false
        private set
    internal var kids = IntArray(0)
    internal var n = 0
        private set
    internal var info = LongArray(0)
    // Строки строк считаются лениво при первом bind и живут до следующего load().
    private var names = arrayOfNulls<String>(0)
    private var shown = arrayOfNulls<String>(0)
    internal var sizes = arrayOfNulls<String>(0)
        private set
    internal var pcts = arrayOfNulls<String>(0)
        private set
    private var descs = arrayOfNulls<String>(0)
    /** Метки безопасности строк текущей папки; пришла метка приложения — строки перерисовываются. */
    private val rowTags by lazy { RowTags(this, txt) { descs = arrayOfNulls(n); list.invalidate() } }
    private var maxV = 0L
    private var parentV = 0L
    /** Δ уровня: сортировка Δ, «ушло», лист точки отсчёта. */
    internal val deltaLevel = DeltaLevel(this)
    /** На этом уровне показана сортировка Δ (выбрана и Δ есть). */
    val deltaShown get() = deltaLevel.shown
    /** Строка «ушло: …» внизу папки в сортировке Δ; null — её нет. */
    val goneText get() = deltaLevel.goneText
    /** Для тестов: открытый лист точки отсчёта. */
    val baselineSheet get() = deltaLevel.sheet
    /** Δ посчитана заново (новое дерево, удаление, «Отметить сейчас») или пропала. */
    private val onGrowth: () -> Unit = { growthChanged() }
    /** Единственный дескриптор, с которым экран вызывает Native; id узлов относятся к нему. */
    internal var h = 0L
        private set
    /** Holder.gen дескриптора [h]: ключ сохранённого пути вместе с h. */
    internal var gen = 0L
        private set
    internal var keepScroll = 0
    private var wait: AlertDialog? = null
    /** Для тестов: полоса, счётчик и кнопка «Стоп» диалога удаления (null — диалога нет). */
    var waitBar: ProgressBar? = null
        private set
    var waitText: TextView? = null
        private set
    var waitStop: Button? = null
        private set
    /** Для тестов: последнее сообщение по итогам удаления (заголовок и текст). */
    var lastAlert: Pair<String, String>? = null
        private set
    internal val ui = Handler(Looper.getMainLooper())
    private var lastDecile = -1
    /** Опрос прогресса удаления каждые 100 мс, только Holder.deleteProgress (атомики ядра). */
    private val poll = object : Runnable {
        override fun run() {
            if (!Holder.deleting || wait == null) return
            renderWait()
            ui.postDelayed(this, 100)
        }
    }
    /** Для тестов: открытый лист удаления. */
    var sheet: DeleteSheet? = null
        internal set
    /** Карточка быстрого просмотра и превью листа удаления. */
    internal val previews = BrowserPreviews(this)
    /** Для тестов: открытая карточка быстрого просмотра. */
    val quickLook get() = previews.quickLook
    private val scrollAt = HashMap<Int, Int>()
    /** Шапка: путь и заголовок, сводка, плашка и чип «новее», переключатели, полоса скана. */
    internal val head = BrowserHeader(this)
    /** Заголовок: имя текущей папки (на корне — PathText.rootTitle). */
    val title get() = head.title
    /** Строка пути над заголовком (всегда, и на корне): тап — панель пути, долгое — копировать. */
    val pathRow get() = head.pathRow
    /** Полный путь текущей папки (его копирует «Копировать путь»). */
    val currentPath get() = head.currentPath
    /** Для тестов: открытая панель пути. */
    val pathPanel get() = head.pathPanel
    /** Узлы пути от корня до текущей папки (строки панели пути). */
    val crumbNodes get() = head.crumbNodes
    /** Пустая папка: сообщение по центру вместо списка. */
    lateinit var empty: TextView
        private set
    /** Плашка вида дерева («скан · 69 312 эл. · 0,2 с»). */
    val badge get() = head.badge
    /** Подсказка или сообщение подвала: одна строка с «…» в конце (ссылка ошибок не переносит её). */
    lateinit var footer: TextView
        private set
    /** Подвал: подсказка или сообщение ([footer]) и справа ссылка ошибок ([errLink]); в режиме выбора — GONE. */
    lateinit var footerBar: LinearLayout
        private set
    /** Ссылка ошибок скана в подвале и лист ошибок. */
    private val errors = ErrorsFooter(this)
    /** «⚠ 2 ошибки ›» — открывает лист ошибок; видна, только если ошибок больше 0. */
    val errLink get() = errors.errLink
    /** Для тестов: открытый лист ошибок скана. */
    val errorsSheet get() = errors.errorsSheet
    /** Узлов с ошибкой в дереве (Native.errorNodes, считается на io) — число в [errLink]. */
    val errCount get() = errors.errCount
    /** «галерея: очистка N…», пока идёт MediaClean; иначе скрыта. */
    lateinit var gallery: TextView
        private set
    private val onClean: () -> Unit = { renderGallery() }
    val chips get() = head.chips
    /** Шапка целиком (высота одна при любой сортировке, режиме размера, чипе «новее», на корне и вглубь). */
    val header get() = head.header
    /** Амберный чип «новее · обновить»: в Holder ждёт более новое дерево того же корня. */
    val newer get() = head.newer
    /** Экземпляр закрепил дескриптор в Holder.browsers (снимается в onDestroy). */
    private var pinned = false
    /** Идёт [promotePending]: смену сессии экран обрабатывает сам, без recreate. */
    private var promoting = false
    /** Фоновый скан мог положить дерево в Holder.offer (тот слушателей не зовёт). */
    private val onBg: () -> Unit = {
        val end = head.renderProgress()
        refreshPending()
        // Этот скан удался и обновлённое дерево ждёт тапа по чипу «новее» — единственное
        // объявление конца (автоподстановку объявляет landed(), провал — молча).
        if (end != null && ScanProgress.announceNewer(end, newer.visibility == View.VISIBLE) && newer.a11yOn())
            newer.announceForAccessibility(txt.s(R.string.newer_desc))
    }
    /** Полоса 2dp под линией шапки: фоновое обновление показанного дерева. */
    val scanLine get() = head.scanLine
    /** «Обновить сам, сохранив путь»: ждёт обновлённое дерево после удаления или перед листом. */
    internal val auto = AutoPromote()
    /** Обычная подсказка подвала текущего уровня (load); без жестов после первых сессий. */
    private var hint = ""
    /** Показывать ли подсказку жестов (первые HINT_SESSIONS открытий браузера). */
    private var showHint = true

    /** Режим выбора в одной папке, панель выбора и удаление группы. */
    internal val sel = BrowserSelection(this)
    val selection get() = sel.selection
    /** Панель выбора внизу (вместо подвала): ✕, итог и число, «ВСЕ»/«НИЧЕГО», «УДАЛИТЬ…». */
    val selBar get() = sel.selBar
    val selTotal get() = sel.selTotal
    val selCount get() = sel.selCount
    val selAll get() = sel.selAll
    val selDelete get() = sel.selDelete
    val selExit get() = sel.selExit
    /** Выйти из выбора; [cleared] — подвал «Выбор снят: N» на 4 с. */
    fun leaveSelection(cleared: Boolean = false) = sel.leaveSelection(cleared)
    /** «УДАЛИТЬ…» панели выбора: один — обычный лист, больше — лист группы. */
    fun deleteSelected() = sel.deleteSelected()
    /** Сообщение подвала в режиме выбора — поверх низа списка (подвала нет, список не прыгает). */
    internal lateinit var notice: TextView
        private set

    /**
     * Holder.set сменил сессию — вызывается синхронно внутри set, до free(старой). Экран тут же
     * перестаёт трогать старый дескриптор: отцепляет список и забывает h (JNI игнорирует 0).
     * Если идёт удаление, пересоздание сделает onDeleted (delete стоит на io раньше free).
     */
    private val onSession: () -> Unit = {
        if (Holder.h != h) {
            list.source = null
            h = 0L
            // Уходящий экран («назад», уже isFinishing) не пересоздаётся.
            if (!busy && !promoting && !isFinishing) { if (Holder.h == 0L) finish() else recreate() }
        }
    }

    private val onDeleted: (Int) -> Unit = { r ->
        dismissWait()
        // В любом случае — выход из выбора (не удалённые остаются с ⚠).
        selection.leave()
        if (Holder.delCount > 1) sel.groupDeleted() else singleDeleted(r)
    }

    private fun singleDeleted(r: Int) {
        // su отказал: «root ✓» из прошлого больше не правда (и быстрый путь по умолчанию — выкл.).
        // Только prefs и Root.state — и для уходящего экрана.
        if (DeletePolicy.nothingDeleted(r, Holder.delRoot)) Root.denied(this)
        if (h == 0L || Holder.h != h) {
            list.source = null
            recreate()
        } else {
            list.source = src
            load(node, keepScroll)
            // Готово — done; отказ и ошибка — refuse; «Стоп» пользователя (-EINTR) — тишина, tock уже был.
            if (r == 0 || !isFinishing) FeedbackPolicy.afterDelete(r)?.let { Feedback.cue(list, it) }
            if (r == 0) note(DeleteProgress.freed(txt, Holder.delDisk))
            if (r != 0 && !isFinishing) {
                val doneN = Holder.deleteProgress()
                when {
                    // «Стоп» до начала: пользователь сам остановил — без диалога.
                    DeleteProgress.isCancelled(r, doneN) -> note(DeleteProgress.cancelled(txt))
                    r == -DeleteProgress.ELOOP -> report(txt.s(R.string.delete_failed), txt.s(R.string.delete_symlink))
                    NativeErr.changedSinceScan(r) -> report(txt.s(R.string.delete_failed), txt.s(R.string.delete_changed))
                    DeletePolicy.rootPathRefused(r, Holder.delRoot) -> report(txt.s(R.string.delete_failed),
                        txt.s(R.string.delete_root_path))
                    DeletePolicy.nothingDeleted(r, Holder.delRoot) -> report(txt.s(R.string.delete_failed),
                        txt.s(R.string.delete_no_root))
                    // Файл не удалён: сканировать нечего, ничего не освобождено.
                    !Holder.delDir -> note(DeleteProgress.freed(txt, 0))
                    // Удалено не всё (остановлено, частично): дерево обновляется само
                    // (BgScan.deleteFinished — до слушателей), путь сохраняется. Обновление не
                    // запустилось — сразу итог по прежнему дереву (refreshFailed).
                    else -> {
                        auto.afterDelete(Holder.delNames, Holder.delName, Holder.delDisk)
                        // Ход обновления — в шапке (renderProgress).
                        if (!BgScan.active) refreshFailed(auto.take()!!)
                    }
                }
                Log.i("ancdu", "delete r=$r done=$doneN refresh=${auto.request != null}")
            }
        }
    }

    /** Текст подвала; пустой — подвал невидим, но место держит (список не прыгает). В режиме выбора подвала нет. */
    private fun setFooter(text: CharSequence) {
        footer.text = text
        footer.visibility = if (text.isEmpty()) View.INVISIBLE else View.VISIBLE
        footerBar.visibility = if (selection.active) View.GONE else View.VISIBLE
    }

    /** Подвал: [text] на 4 с, затем обычная подсказка. В режиме выбора — поверх низа списка. */
    internal fun note(text: String) {
        if (selection.active) { notice.text = text; notice.visibility = View.VISIBLE } else setFooter(text)
        ui.removeCallbacks(restoreFooter)
        restoreFooter = Runnable {
            if (notice.visibility == View.VISIBLE && notice.text.toString() == text) notice.visibility = View.GONE
            else if (footer.text.toString() == text) setFooter(idleFooter())
        }
        ui.postDelayed(restoreFooter, 4000)
    }

    private fun idleFooter(): String = hint

    private var restoreFooter = Runnable {}

    /** Для тестов: текст подвала (в режиме выбора — сообщение поверх списка, если оно видно). */
    val footerText: CharSequence get() = if (notice.visibility == View.VISIBLE) notice.text else footer.text

    internal fun report(title: String, msg: String) {
        lastAlert = title to msg
        alert(title, msg)
    }

    internal fun value(index: Int): Long = info[4 * index + if (apparent) 1 else 0]

    /** Метка пути [path] узла с флагами [flags] и запретом [block] дерева [h], уже для показа. */
    internal fun tagOf(path: String, flags: Int, block: Block?): TagText? = rowTags.of(path, flags, block)

    private fun nameAt(index: Int): String =
        names[index] ?: Native.str(Native.name(h, kids[index])).also { names[index] = it }

    internal val src = object : RowSource {
        override val count get() = n + if (goneText != null) 1 else 0
        override fun bind(index: Int, row: Row) {
            // Последняя строка в сортировке Δ — сводка «ушло» (не узел, без касаний).
            if (index >= n) { row.note = goneText; return }
            val v = value(index)
            val flags = info[4 * index + 3].toInt()
            val dir = flags and F_DIR != 0
            val nm = nameAt(index)
            // Управляющие направления текста — видимыми («⟨U+202E⟩»): имя не переставляется.
            row.name = shown[index] ?: Bidi.visible(if (dir) "$nm/" else nm).also { shown[index] = it }
            if (deltaShown) {
                deltaLevel.bind(index, row, v)
            } else {
                row.size = sizes[index] ?: (if (flags and F_OTHERFS != 0) "—" else Fmt.size(v, txt)).also { sizes[index] = it }
                row.bar = ListMath.bar(v, maxV)
                row.pct = pcts[index] ?: Fmt.pct(v, parentV).also { pcts[index] = it }
                row.barColor = if (dir) C.AMBER else C.BLUE
            }
            row.nameColor = if (dir) C.TEXT else C.BLUE_HI
            val tag = rowTags.at(index, nm, flags)
            row.tag = tag?.text
            if (tag != null) row.tagColor = tag.color
            when {
                flags and F_ERR != 0 -> { row.mark = "⚠"; row.nameColor = C.AMBER_TEXT }
                flags and F_OTHERFS != 0 -> row.mark = "↪"
                flags and F_HLDUP != 0 -> row.mark = "≡"
            }
            row.desc = descs[index] ?: buildString {
                val dd = deltaLevel.rowDesc(index, nm, v, dir)
                if (dd != null) append(dd)
                else {
                    append(nm); append(", "); append(row.size)
                    if (row.pct.isNotEmpty()) { append(", "); append(row.pct) }
                    if (dir) { append(", "); append(txt.s(R.string.desc_dir)) }
                }
                // F_ERR — и нет доступа, и незаконченное удаление: данные узла неполные.
                if (flags and F_ERR != 0) { append(", "); append(txt.s(R.string.desc_incomplete)) }
                if (tag != null) append(tag.desc)
            }.also { descs[index] = it }
            if (selection.active) {
                sel.bind(index, row, dir)
            } else if (!dir) {
                row.clickLabel = txt.s(R.string.click_label_quick)
            }
        }
        override fun click(index: Int) {
            if (busy || index >= n) return
            // В режиме выбора тап выбирает (и каталог: вглубь нельзя).
            if (selection.active) { sel.toggleRow(index); return }
            // Файл — карточка быстрого просмотра; долгое нажатие — режим выбора.
            if (info[4 * index + 3].toInt() and F_DIR == 0) { previews.openQuickLook(kids[index], value(index), info[4 * index + 3].toInt()); return }
            scrollAt[node] = list.scroll
            load(kids[index], 0, dir = 1)
        }
        override fun longClick(index: Int) {
            if (busy || index >= n) return
            if (!selection.active) { sel.enterSelection(index); return }
            val flags = info[4 * index + 3].toInt()
            if (flags and F_DIR == 0) previews.openQuickLook(kids[index], value(index), flags) else sel.toggleRow(index)
        }
        // В режиме выбора звук даёт сам выбор: tick — выбран, tock — снят, refuse — запрет.
        override fun clickCue(index: Int): Cue? = if (selection.active || index >= n) null else Cue.TAP
        override fun interactive(index: Int): Boolean = index < n
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        systemBars()
        h = Holder.h
        gen = Holder.gen
        if (h == 0L) { finish(); return }
        Holder.pinBrowser(); pinned = true
        Root.load(this)
        BgScan.bind(this)
        val top = head.build()
        list = NcduListView(this).apply { longClickLabel = txt.s(R.string.long_click_label); keepExt = true }
        empty = label("", 15f, C.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), 0)
            visibility = View.GONE
        }
        head.makeScanLine()
        // Всегда одна строка (и при 200%): появление ссылки ошибок не переносит текст и не меняет
        // высоту подвала. Полный текст — у TalkBack (text не меняется, обрезка только при отрисовке).
        footer = label("", 12f, C.MUTED, mono = true).apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            visibility = View.INVISIBLE
        }
        errors.link()
        // Высота подвала — всегда не меньше касания ссылки: её появление не двигает список.
        footerBar = hbox().apply {
            minimumHeight = dp(44)
            addView(footer, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(errLink, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        gallery = label("", 12f, C.MUTED, mono = true).apply {
            setPadding(dp(16), 0, dp(16), dp(10))
            visibility = View.GONE
        }
        notice = label("", 12f, C.TEXT, mono = true).apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(C.PANEL2)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        sel.selectionBar()
        setContentView(vbox().apply {
            setBackgroundColor(C.BG)
            addView(top)
            hairline()
            // Вне шапки: её высота и верх списка от полосы не зависят (место держится всегда).
            addView(scanLine, LinearLayout.LayoutParams(MATCH_PARENT, dp(2)))
            addView(FrameLayout(this@BrowserActivity).apply {
                addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(notice, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
            }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(footerBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            // Режим выбора: панель вместо подвала — сдвигается низ списка, шапка и верх — нет.
            addView(selBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(gallery)
        })
        // Подсказка подвала — только первые HINT_SESSIONS открытий браузера (не пересозданий).
        val uiPrefs = getSharedPreferences(LangPrefs.PREFS, MODE_PRIVATE)
        val seen = uiPrefs.getInt(K_SESSIONS, 0)
        val sessions = seen + if (savedInstanceState == null) 1 else 0
        // После HINT_SESSIONS счётчик больше не пишется.
        if (savedInstanceState == null && seen <= HINT_SESSIONS) uiPrefs.edit().putInt(K_SESSIONS, sessions).apply()
        showHint = sessions <= HINT_SESSIONS
        Holder.addDeleteListener(onDeleted)
        MediaClean.addListener(onClean)
        renderGallery()
        Holder.addSessionListener(onSession)
        BgScan.addListener(onBg)
        Growth.addListener(onGrowth)
        // Пересоздание (смена языка, системой) того же дерева: та же папка, сортировка и режим размера.
        val st = savedInstanceState?.takeIf { it.getLong(S_H) == h && it.getLong(S_GEN, -1) == gen }
        if (st != null) {
            sort = st.getInt(S_SORT, SORT_SIZE)
            apparent = st.getBoolean(S_APPARENT, false)
            node = st.getInt(S_NODE, 0)
            keepScroll = st.getInt(S_SCROLL, 0)   // и для onDeleted, если удаление ещё идёт
        } else if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_DELTA, false)) {
            // Строка «что выросло» главного экрана: сразу в сортировке Δ (Δ может ещё считаться на io).
            sort = SORT_DELTA
        }
        if (busy) {
            // Удаление начато прежним экземпляром: дерево не читаем до onDeleted.
            showWait()
        } else {
            list.source = src
            load(node, keepScroll)
            if (st != null) sel.restoreSelection(st)
            // Только при первом создании: пересоздание держит свою папку и прокрутку.
            else if (savedInstanceState == null) Focus.parse(intent.getByteArrayExtra(EXTRA_FOCUS))?.let { focus(it) }
            // «⚠ N» на карточке главного экрана: сразу лист ошибок (после первого кадра).
            if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_ERRORS, false)) ui.post { if (!isFinishing) errors.openErrors() }
        }
        head.renderProgress()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putLong(S_H, h)
        out.putLong(S_GEN, gen)
        out.putInt(S_NODE, node)
        out.putInt(S_SORT, sort)
        out.putBoolean(S_APPARENT, apparent)
        out.putInt(S_SCROLL, if (busy) keepScroll else list.scroll)
        sel.save(out)
    }

    override fun onResume() {
        super.onResume()
        if (relaunching) return   // LangActivity уже пересоздаёт экран
        // Сессию сменили, пока экран был скрыт: старые id узлов к новому дереву не относятся.
        if (!busy && Holder.h != h) { list.source = null; recreate(); return }
        refreshPending()
    }

    /** Есть ли в Holder более новое дерево того же корня и того же режима su. Главный поток. */
    internal fun hasNewer(): Boolean =
        Swap.newer(Holder.pending, Holder.pendingRoot, Holder.pendingViaRoot, Holder.root, Holder.viaRoot)

    /** Открыт лист удаления, карточка или панель пути: дерево не подставляется под ними (их id узлов устарели бы). */
    internal fun sheetOpen(): Boolean = sheet?.dialog?.isShowing == true || quickLook?.dialog?.isShowing == true ||
        pathPanel?.dialog?.isShowing == true || errorsSheet?.dialog?.isShowing == true ||
        baselineSheet?.dialog?.isShowing == true

    /**
     * Показать/скрыть «новее · обновить». Главный поток; Holder.offer слушателей не зовёт.
     * Взведён [auto] — ждущее дерево подставляется само (никогда при удалении или открытом листе);
     * обновить не вышло — флаг снимается.
     */
    fun refreshPending() {
        if (!head.built) return
        if (h != 0L && !isFinishing && !isDestroyed) {
            val nw = hasNewer()
            // Никогда во время удаления (Holder.deleting) и при открытом листе.
            if (auto.ready(nw, busy = Holder.deleting, sheetOpen = sheetOpen())) { landed(auto.take()!!); return }
            if (!Holder.deleting && auto.failed(nw, BgScan.active)) refreshFailed(auto.take()!!)
        }
        newer.visibility = if (hasNewer() && h != 0L) View.VISIBLE else View.GONE
    }

    /** Обновлённое дерево готово: подставить (путь сохраняется) и показать итог запроса [r]. */
    private fun landed(r: AutoPromote.Request) {
        promotePending()
        if (h == 0L) return
        if (r.group) { sel.groupLanded(); return }
        val hit = resolveNode(r.names)
        val disk = if (hit.exact) LongArray(4).also { Native.nodeInfo(h, intArrayOf(hit.node), 1, it) }[0] else 0L
        when (val o = AutoPromote.outcome(txt, r, hit.exact, disk)) {
            is AutoPromote.Outcome.Footer -> note(o.text)
            AutoPromote.Outcome.Sheet -> openSheet(hit.node)
        }
    }

    /**
     * Обновить не вышло (скан не запустился или не удался, su отказал): дерево остаётся прежним.
     * После удаления — итог по нему (нижняя граница освобождённого); ждущий лист — с REFRESH_FAILED.
     */
    internal fun refreshFailed(r: AutoPromote.Request) {
        Log.i("ancdu", "tree refresh failed: ${BgScan.failure}")
        setFooter(hint)
        if (r.group) { if (selection.active) sel.openGroupSheet(gone = 0) else note(GroupSheet.gone(txt, sel.groupAsked)); return }
        val hit = resolveNode(r.names)
        if (r.delDisk != null) {
            val disk = if (hit.exact) LongArray(4).also { Native.nodeInfo(h, intArrayOf(hit.node), 1, it) }[0] else 0L
            note(AutoPromote.unrefreshed(txt, r, hit.exact, disk))
        } else if (hit.exact) {
            openSheet(hit.node)
        }
    }

    /** Отменить ждущий лист; его su-обновление, ещё не начатое, снимается с очереди BgScan. */
    internal fun cancelAsk() {
        auto.cancelSheet()?.target?.let { BgScan.unqueue(it) }
    }

    /**
     * [EXTRA_FOCUS]: файл [names] (байты имён от корня) — его папка, строка видна, затем карточка
     * быстрого просмотра. Файла уже нет — ближайшая существующая папка и «… уже нет на диске».
     */
    private fun focus(names: List<ByteArray>) {
        val hit = resolveNode(names)
        val flags = LongArray(4).also { Native.nodeInfo(h, intArrayOf(hit.node), 1, it) }[3].toInt()
        val dir = flags and F_DIR != 0
        if (!hit.exact || dir) {
            val folder = if (dir) hit.node else maxOf(Native.parent(h, hit.node), 0)
            if (folder != node) load(folder, 0)
            if (!hit.exact) note(DeleteProgress.gone(txt, Native.str(names.last())))
            return
        }
        val target = hit.node
        val parent = maxOf(Native.parent(h, target), 0)
        if (parent != node) load(parent, 0)
        val i = kids.indexOf(target)
        if (i !in 0 until n) return
        list.reveal(i)
        val size = value(i)
        val handle = h
        // Карточка — после первого кадра экрана (окно Activity уже на месте).
        ui.post { if (!isFinishing && !busy && h == handle && h != 0L) previews.openQuickLook(target, size, flags) }
    }

    /** Узел по байтам имён от корня в дереве [h] (файл или каталог). */
    private fun resolveNode(names: List<ByteArray>): PathWalk.Hit =
        PathWalk.resolve(names) { nd, nm -> child(nd, nm, dirOnly = false) }

    /** Байты имён пути узла [nd] дерева [handle] от корня (без самого корня). */
    internal fun pathNames(handle: Long, nd: Int): List<ByteArray> {
        val chain = ArrayList<ByteArray>()
        var c = nd
        while (c > 0) { chain += Native.name(handle, c); c = Native.parent(handle, c) }
        chain.reverse()
        return chain
    }

    private companion object {
        const val S_H = "h"
        const val S_GEN = "gen"
        const val S_NODE = "node"
        const val S_SORT = "sort"
        const val S_APPARENT = "apparent"
        const val S_SCROLL = "scroll"
        /** v2: подсказка жестов сменилась (долгое — выбрать) — показать её снова. */
        const val K_SESSIONS = "browser_sessions_v2"
        const val HINT_SESSIONS = 3
        /** Root-скан общего хранилища напрямую (чип «/data/media» главного экрана). */
        const val ROOT_MEDIA = "/data/media"
    }

    /**
     * «новее · обновить»: подставляет ждущее дерево и открывает ТОТ ЖЕ путь по именам — от корня
     * нового дерева; не нашлось — ближайший существующий предок. Имена читаются из старого
     * дескриптора ДО promote (set освобождает его на io после слушателей). Прокрутка — по
     * возможности: та же, если путь найден целиком.
     */
    fun promotePending() {
        if (busy || h == 0L || !hasNewer()) return
        // Байты имён, не строки: невалидный UTF-8 декодируется неоднозначно.
        val names = pathNames(h, node)
        val keep = list.scroll
        // Строки панели пути и листа ошибок — узлы старого дерева.
        head.dismissPanel()
        errors.dismissSheet()
        deltaLevel.dismissSheet()
        list.source = null
        promoting = true
        try { Holder.promote() } finally { promoting = false }
        h = Holder.h
        gen = Holder.gen
        scrollAt.clear()
        if (h == 0L) { finish(); return }
        list.source = src
        val hit = PathWalk.resolve(names) { nd, nm -> child(nd, nm, dirOnly = true) }
        // Выбор — по именам в новом дереве; пропавшие выбрасываются. Папки нет — load снимет выбор.
        if (selection.active && hit.exact) {
            val map = childMap(hit.node)
            selection.rebind { map[it] }
        }
        load(hit.node, if (hit.exact) keep else 0)
        refreshPending()
    }

    /** Живые дети [nd] дерева [h] по байтам имени (один проход). */
    internal fun childMap(nd: Int): HashMap<NameKey, Int> {
        val c = IntArray(Native.childCount(h, nd))
        val k = maxOf(0, Native.children(h, nd, SORT_NAME, false, c))
        val m = HashMap<NameKey, Int>(k * 2)
        for (i in 0 until k) m[NameKey(Native.name(h, c[i]))] = c[i]
        return m
    }

    /** Ребёнок [nd] с именем ровно [nm] (байты) в дереве [h] — каталог, если [dirOnly], — или null. */
    private fun child(nd: Int, nm: ByteArray, dirOnly: Boolean): Int? {
        val c = IntArray(Native.childCount(h, nd))
        val k = maxOf(0, Native.children(h, nd, SORT_NAME, false, c))
        if (k == 0) return null
        val inf = LongArray(4 * k).also { Native.nodeInfo(h, c, k, it) }
        for (i in 0 until k)
            if ((!dirOnly || inf[4 * i + 3].toInt() and F_DIR != 0) && Native.name(h, c[i]).contentEquals(nm)) return c[i]
        return null
    }

    override fun onPause() {
        super.onPause()
        // Плеер карточки не играет в фоне.
        previews.pause()
        sheet?.pause()
        // «Назад»: Main.onResume идёт раньше нашего onDestroy — закрепление снимается здесь.
        if (isFinishing) unpin()
    }

    private fun unpin() { if (pinned) { pinned = false; Holder.unpinBrowser() } }

    override fun onDestroy() {
        Holder.removeDeleteListener(onDeleted)
        Holder.removeSessionListener(onSession)
        BgScan.removeListener(onBg)
        Growth.removeListener(onGrowth)
        MediaClean.removeListener(onClean)
        unpin()
        cancelAsk()
        dismissWait()
        ui.removeCallbacks(restoreFooter)
        sheet?.dismiss(); sheet = null
        head.dispose()
        errors.dispose()
        deltaLevel.dispose()
        sel.dispose()
        previews.dispose()
        if (::list.isInitialized) list.animate().cancel()
        super.onDestroy()
    }

    private fun progress(): LongArray = LongArray(6).also { Native.progress(h, it) }

    private fun renderGallery() {
        if (!::gallery.isInitialized) return
        gallery.visibility = if (MediaClean.running) View.VISIBLE else View.GONE
        gallery.text = txt.s(R.string.gallery_cleaning, Fmt.count(MediaClean.cleaned, txt.locale))
    }

    /** Для тестов: тексты сегментов сортировки, затем режима размера (как в ресурсах). */
    fun segmentTexts(): List<String> = head.segmentTexts()

    /** Для тестов: все сегменты (касания 44dp). */
    fun segments(): List<View> = head.segments()

    fun setSort(k: Int) { if (busy) return; sort = k; load(node, 0) }
    fun setApparent(v: Boolean) { if (busy) return; apparent = v; load(node, 0) }

    /** [dir] — переход по дереву: +1 вглубь, −1 назад или к предку из панели пути, 0 — тот же уровень (без анимации). */
    /** [keepAsk] — перечитать тот же уровень, не отменяя ждущий лист (пришла новая Δ). */
    internal fun load(target: Int, restore: Int, dir: Int = 0, keepAsk: Boolean = false) {
        // Любая навигация отменяет ждущий лист (подстановка дерева при этом всё равно будет).
        if (!keepAsk) cancelAsk()
        // Выбор живёт в ОДНОЙ папке: ушли из неё (панель пути, папки нет в новом дереве) — снят.
        val dropped = sel.leaveOutside(target)
        loads++
        node = target
        // Δ посчитана заранее на io (Growth): здесь только чтения массивов, без работы с базой.
        sort = deltaLevel.begin(sort)
        // Массив — по childCount (с удалёнными детьми); показываем столько, сколько вернул children().
        kids = IntArray(Native.childCount(h, node))
        // Δ — сортировка Kotlin поверх готового порядка по размеру (ядро SORT_DELTA не знает).
        n = maxOf(0, Native.children(h, node, if (sort == SORT_DELTA) SORT_SIZE else sort, apparent, kids))
        names = arrayOfNulls(n); shown = arrayOfNulls(n); sizes = arrayOfNulls(n)
        pcts = arrayOfNulls(n); descs = arrayOfNulls(n)
        sel.reset(n)
        info = LongArray(4 * maxOf(n, 1))
        if (n > 0) Native.nodeInfo(h, kids, n, info)
        deltaLevel.order()
        val self = LongArray(4).also { Native.nodeInfo(h, intArrayOf(node), 1, it) }
        parentV = self[if (apparent) 1 else 0]
        maxV = (0 until n).maxOfOrNull { value(it) } ?: 0L
        head.renderHeader()
        rowTags.reset(n, currentPath, rootPath(), node == 0, Holder.root, Holder.kind)
        empty.visibility = if (n == 0) View.VISIBLE else View.GONE
        empty.text = txt.s(if (self[3].toInt() and F_ERR == 0) R.string.folder_empty else R.string.folder_no_access)
        head.summary.text = deltaLevel.summary(parentV) ?: "${Fmt.size(parentV, txt)} · ${txt.items(self[2])}"
        val p = progress()
        val full = p[0] == ST_FULL.toLong()
        head.showSource(deltaLevel.badge() ?: Badge.text(txt, Holder.kind, Holder.time, Holder.ms, full))
        hint = if (showHint) txt.s(R.string.browser_hint) else ""
        setFooter(idleFooter())
        errors.refreshErrors()
        head.renderChips()
        refreshPending()
        slide(dir)
        sel.renderSelection()
        list.scroll = restore
        if (dropped > 0) note(GroupSheet.cleared(txt, dropped))
    }

    /**
     * Δ дерева сменилась (Growth): в сортировке Δ — тот же уровень заново (прокрутка и ждущий лист
     * остаются); иначе — только сегмент Δ появляется или пропадает.
     */
    private fun growthChanged() {
        if (h == 0L || busy || isFinishing || isDestroyed || !::list.isInitialized || list.source == null) return
        // Holder уже держит другое дерево (Holder.set зовёт Growth раньше слушателей сессии): экран пересоздаётся.
        if (Holder.h != h || Holder.gen != gen) return
        if (sort == SORT_DELTA || deltaShown) load(node, list.scroll, keepAsk = true) else head.renderChips()
    }

    /**
     * Переход по дереву: анимируется только список — въезжает с ±16dp ([dir] +1 справа, −1 слева)
     * и проявляется за 120 мс; шапка меняется сразу. Следующий load отменяет идущую анимацию.
     */
    private fun slide(dir: Int) {
        list.animate().cancel()
        list.translationX = 0f; list.alpha = 1f
        if (dir == 0 || !Motion.on()) return
        list.translationX = dir * dp(16).toFloat(); list.alpha = 0f
        list.animate().translationX(0f).alpha(1f).setDuration(120)
            .setInterpolator(enterCurve).withLayer().start()
    }

    private val enterCurve by lazy { AnimationUtils.loadInterpolator(this, R.interpolator.motion_enter) }

    internal fun rootPath(): String = Native.str(Native.path(h, 0)).ifEmpty { Holder.root }

    internal fun nameOf(nd: Int): String = Native.str(Native.name(h, nd))

    /**
     * Узел [target] из листа ошибок: его папка, строка видна. Узла уже нет (удалён он или предок,
     * дерево сменилось) — сообщение в подвале.
     */
    internal fun revealNode(handle: Long, target: Int) {
        if (busy || h == 0L) return
        val hit = if (h == handle) resolveNode(pathNames(h, target)) else null
        if (hit == null || !hit.exact || hit.node != target) { note(txt.s(R.string.err_node_gone)); return }
        if (target == 0) { if (node != 0) load(0, 0, dir = -1); return }
        val parent = maxOf(Native.parent(h, target), 0)
        if (parent != node) load(parent, 0)
        val i = kids.indexOf(target)
        if (i in 0 until n) list.reveal(i) else note(txt.s(R.string.err_node_gone))
    }

    /** «СКАНИРОВАТЬ ОТ ROOT»: тот же root-скан /data/media, что чип главного экрана; браузер уходит. */
    internal fun scanAsRoot() {
        if (busy) return
        finish()
        startActivity(Intent(this, ScanActivity::class.java).putExtra(EXTRA_ROOT, ROOT_MEDIA).putExtra(EXTRA_SU, true))
    }

    /** Переход к предку [target] из панели пути: его прокрутка восстанавливается, более глубоких — забываются. */
    fun jumpTo(target: Int) {
        if (busy || target == node || target !in crumbNodes) return
        for (nd in crumbNodes.dropWhile { it != target }.drop(1)) scrollAt.remove(nd)
        load(target, scrollAt.remove(target) ?: 0, dir = -1)
    }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        if (busy) return
        // В режиме выбора «назад» только снимает выбор — без перехода.
        if (selection.active) { sel.leaveSelection(cleared = true); return }
        cancelAsk()
        if (node != 0) {
            val p = maxOf(Native.parent(h, node), 0)
            load(p, scrollAt.remove(p) ?: 0, dir = -1)
        } else {
            super.onBackPressed()
        }
    }

    /** Лист удаления строки [i] — выбор из одного (тот же лист, что «УДАЛИТЬ…» с одним выбранным). */
    fun askDelete(i: Int) { if (!busy && i in 0 until n) ask(kids[i]) }

    /**
     * Лист удаления узла [target]. Главный поток. Удаляется только из свежего дерева: ждёт более
     * новое — подставляется сразу (без скана), узел ищется в нём по байтам имён; каталог кэша или
     * индекса — экран сам пересканирует корень в том же режиме su, лист откроет [refreshPending].
     */
    internal fun ask(target: Int, fromCard: Boolean = false) {
        sheet?.dismiss()
        cancelAsk()
        var t = target
        if (hasNewer()) {
            val names = pathNames(h, t)
            val name = nameOf(t)
            promotePending()
            if (h == 0L) return
            val hit = resolveNode(names)
            if (!hit.exact) { note(DeleteProgress.gone(txt, name)); return }
            t = hit.node
        }
        if (blockReason(h, t, Native.str(Native.path(h, t))) == Block.REFRESH_FAILED) {
            auto.beforeDelete(pathNames(h, t), nameOf(t), ScanTarget(Holder.root, Holder.viaRoot))
            // Встать в очередь за сканом другого корня BgScan молча: «ждёт» показывает renderProgress.
            if (BgScan.refresh(this, Holder.root, Holder.viaRoot)) { head.renderProgress(); return }
            auto.take()
            Log.i("ancdu", "tree refresh not started: ${BgScan.failure}")
        }
        openSheet(t, fromCard)
    }

    /** Лист узла [target] дерева [h]; пара (дескриптор, узел) фиксируется здесь, подтверждение удаляет ровно её. */
    private fun openSheet(target: Int, fromCard: Boolean = false) {
        val handle = h
        sheet?.dismiss()
        // Закрыт лист — подставить дерево, если оно пришло, пока лист был открыт.
        sheet = DeleteSheet(this, previews.preview(handle, target, nameOf(target)), onClose = { refreshPending() },
            fromCard = fromCard) { fast ->
            startDelete(handle, target, fast)
        }.also { it.show() }
    }

    /** Главный поток, [handle] — живой дескриптор экрана. null — узел можно удалять. */
    internal fun blockReason(handle: Long, target: Int, path: String): Block? {
        val inf = LongArray(4).also { Native.nodeInfo(handle, intArrayOf(target), 1, it) }
        return DeletePolicy.blockReason(path, target == 0, Native.parent(handle, target) == 0,
            Holder.root, inf[3].toInt(), Holder.kind)
    }

    /**
     * Неотменяемый диалог удаления: полоса (max 1000 — промилле от items узла на момент
     * подтверждения), «N / M эл. · м:сс» и «Стоп». Данные — из Holder, поэтому новый экземпляр
     * после пересоздания показывает тот же диалог и продолжает опрос.
     */
    /** Быстрый путь root в обход FUSE: путь сопоставляется с /data/media, оба разрешены, есть su. */
    internal fun fastAllowed(path: String): Boolean =
        DeletePolicy.fastBlockReason(path) == null && Root.suExists()

    internal fun showWait() {
        list.source = null
        setFooter(txt.s(R.string.deleting))
        dismissWait()
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 1000
            progressDrawable = framedProgress()
            contentDescription = txt.s(R.string.delete_progress_desc)
        }
        val text = label("", 13f, C.MUTED, mono = true)
        val stop = Button(this).apply {
            minHeight = dp(48); minimumHeight = dp(48)
            setPadding(dp(20), 0, dp(20), 0)
            stateListAnimator = null
            isAllCaps = false
            textSize = 15f
            typeface = Fonts.get(this@BrowserActivity, mono = false, bold = true)
            setTextColor(android.content.res.ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(C.MUTED, C.TEXT)))
            background = pressable(android.graphics.Color.TRANSPARENT, C.FRAME)
            feedbackClick(Cue.BACK) { stopDelete() }
        }
        val body = vbox(12).apply {
            setPadding(dp(20), dp(20), dp(20), dp(16))
            background = Brackets(this@BrowserActivity, C.PANEL)
            addView(label(DeleteProgress.titleFor(txt, Holder.delName, Holder.delCount), 18f, C.TEXT, bold = true))
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, dp(8)))
            addView(text)
            addView(stop, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.END
            })
        }
        waitBar = bar; waitText = text; waitStop = stop
        lastDecile = -1
        wait = AlertDialog.Builder(this, R.style.Theme_Ancdu_Alert)
            .setView(body).setCancelable(false).create().apply {
                window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                // Заголовок окна — для TalkBack (видимый заголовок — в теле).
                window?.setTitle(DeleteProgress.titleFor(txt, Holder.delName, Holder.delCount))
                show()
            }
        renderWait()
        ui.postDelayed(poll, 100)
    }

    private fun renderWait() {
        val bar = waitBar ?: return
        val done = Holder.deleteProgress()
        val total = Holder.delTotal
        bar.progress = DeleteProgress.permille(done, total)
        waitText?.text = DeleteProgress.line(txt, done, total, SystemClock.elapsedRealtime() - Holder.delStartMs)
        waitStop?.apply {
            text = txt.s(if (Holder.delStopping) R.string.stopping else R.string.stop)
            isEnabled = !Holder.delStopping
        }
        val dec = DeleteProgress.decile(done, total)
        if (dec != lastDecile) {
            if (lastDecile >= 0 && bar.a11yOn()) bar.announceForAccessibility(DeleteProgress.announce(txt, done, total))
            lastDecile = dec
        }
    }

    /** «Стоп» диалога: ядро прекращает обход, удалённое остаётся удалённым. */
    fun stopDelete() {
        if (!busy) return
        Holder.deleteStop()
        renderWait()
    }

    private fun dismissWait() {
        ui.removeCallbacks(poll)
        wait?.dismiss(); wait = null
        waitBar = null; waitText = null; waitStop = null
    }

    /**
     * Главный поток. Удаляет узел [target] сессии [handle] — ровно ту пару, что показал диалог.
     * Завершение получает живой экземпляр через Holder (onDeleted), затем [done].
     */
    private fun startDelete(handle: Long, target: Int, fast: Boolean = false, done: (Int) -> Unit = {},
                            testBulk: ((stopped: () -> Boolean, add: (Long) -> Unit) -> Unit)? = null): Boolean {
        if (busy || isDestroyed) return false
        if (handle != Holder.h || handle != h) {
            alert(txt.s(R.string.delete_cancelled_title), txt.s(R.string.tree_changed)) {
                list.source = null; recreate()
            }
            return false
        }
        // Повторная проверка запретов: путь мимо диалога (тесты) тоже не удалит системное.
        val path = Native.str(Native.path(handle, target))
        if (blockReason(handle, target, path) != null) return false
        // Быстрый путь: и исходный, и сопоставленный /data/media-путь проверены политикой.
        if (fast && !fastAllowed(path)) return false
        val items = LongArray(4).also { Native.nodeInfo(handle, intArrayOf(target), 1, it) }[2]
        keepScroll = list.scroll
        val item = deleteItem(applicationContext, handle, target, fast, Holder.kind, Holder.viaRoot, testBulk)
        Holder.delete(handle, item, done, total = items, names = pathNames(handle, target))
        showWait()
        return true
    }

    /**
     * Для тестов: синхронное удаление строки i. Вызывать с тестового потока, не с главного.
     * [bulk] — шаг вместо массового (на io до ядра), как у Holder.delete.
     */
    fun deleteBlocking(i: Int, bulk: ((stopped: () -> Boolean, add: (Long) -> Unit) -> Unit)? = null): Int {
        check(Looper.myLooper() != Looper.getMainLooper()) { "deleteBlocking on the main thread" }
        var r = Int.MIN_VALUE
        val latch = CountDownLatch(1)
        runOnUiThread {
            if (!startDelete(h, kids[i], done = { r = it; latch.countDown() }, testBulk = bulk)) latch.countDown()
        }
        check(latch.await(60, TimeUnit.SECONDS)) { "delete did not finish in 60 s" }
        return r
    }
}

/** Живые дети [nd] дерева [handle] и их флаги. Вызывается на io из массового шага удаления
 * (вне экрана: лямбда удаления не держит Activity). Ошибка children() — исключение (отказ
 * закрытый: массовый шаг прерывается, удаляет rm_tree). SORT_SIZE по disk — готовый порядок без сортировки. */
private fun kidsWithFlags(handle: Long, nd: Int): Pair<IntArray, IntArray> {
    val c = IntArray(Native.childCount(handle, nd))
    val k = MediaBulk.checkedCount(Native.children(handle, nd, SORT_SIZE, false, c))
    if (k == 0) return IntArray(0) to IntArray(0)
    val inf = LongArray(4 * k).also { Native.nodeInfo(handle, c, k, it) }
    return c.copyOf(k) to IntArray(k) { inf[4 * it + 3].toInt() }
}

/**
 * Один объект удаления — тот же путь для одного и для группы: root (root-сессия или «быстро через
 * root»), быстрый /data/media, массовый шаг MediaStore до ядра. Чтения дерева [handle]: с
 * главного потока (один) или на io под правилами удаления (группа, экран дерево не читает).
 * Лямбды держат только [app], не Activity.
 */
internal fun deleteItem(app: Context, handle: Long, target: Int, fast: Boolean, kind: Kind, viaRoot: Boolean,
                       testBulk: ((stopped: () -> Boolean, add: (Long) -> Unit) -> Unit)? = null): DeleteItem {
    val pathBytes = Native.path(handle, target)
    val path = Native.str(pathBytes)
    val helper = if (viaRoot || fast) Root.helper(app) else null
    val inf = LongArray(4).also { Native.nodeInfo(handle, intArrayOf(target), 1, it) }
    val items = inf[2]
    val disk = inf[0]
    val dir = inf[3].toInt() and F_DIR != 0
    val name = Native.str(Native.name(handle, target))
    // Без root в общем хранилище: сначала пачками через MediaProvider, затем ядро — как всегда.
    // Индекс флагов ссылок не знает — для него массового шага нет.
    val bulkPath = if (kind == Kind.INDEX) null else MediaBulk.target(pathBytes, viaRoot = viaRoot, fast = fast)
    if (bulkPath == null && !fast && !viaRoot && MediaBulk.exactPath(pathBytes) == null)
        Log.i("ancdu", "bulk delete skipped: path is not valid UTF-8")
    Log.i("ancdu", "delete kind=$kind viaRoot=$viaRoot fast=$fast bulk=${bulkPath != null} items=$items")
    val cr = app.contentResolver
    val cleanPath = if (fast) MediaBulk.cleanable(pathBytes) else null
    val rootFlags = inf[3].toInt()
    return DeleteItem(target, helper, media = fast, name = name, dir = dir, disk = disk,
        bulk = testBulk ?: bulkPath?.let { p -> { stopped, add ->
            // На io, под правилами delete: чтение дерева [handle] (экран его сейчас не читает).
            // MediaProvider канонизирует путь перед unlink — ссылка в поддереве увела бы
            // удаление за пределы узла.
            if (MediaBulk.subtreeHas(target, rootFlags, F_SYMLINK, stopped) { nd -> kidsWithFlags(handle, nd) } ||
                Files.isSymbolicLink(Paths.get(p))) {
                Log.i("ancdu", "bulk delete skipped: symlink in subtree, or stopped")
            } else {
                val out = MediaBulk.run(ResolverRows(cr), p, dir, stopped = stopped, onDeleted = add)
                out.error?.let { Log.w("ancdu", "bulk delete fell back to rm_tree after ${out.deleted} rows", it) }
                Log.i("ancdu", "bulk delete rows=${out.deleted} matched=${out.matched} stopped=${out.stopped}")
            }
        } },
        afterIo = if (!fast) null else { r ->
            // MediaProvider не видел удаления в обход FUSE — убираем устаревшие строки:
            // путь исчез — пачками в фоне (MediaClean); частично — scanFile, как раньше
            // (удаление строк через MediaProvider удалило бы и оставшиеся файлы).
            if (r == 0 && cleanPath != null) MediaClean.enqueue(app, cleanPath, dir)
            else {
                // Путь не точный UTF-8: Native.str дал U+FFFD — scanFile этого пути ничего не найдёт.
                if (cleanPath == null) Log.w("ancdu", "gallery cleanup: path is not valid UTF-8, scanFile is a no-op")
                MediaScannerConnection.scanFile(app, arrayOf(path), null, null)
            }
        })
}
