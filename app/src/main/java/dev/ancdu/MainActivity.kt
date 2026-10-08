package dev.ancdu

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class MainActivity : LangActivity() {
    /** Карточка памяти — вход в дерево общего хранилища. */
    lateinit var storage: StorageCard
        private set
    /** «Самые крупные файлы» под карточкой. */
    lateinit var biggest: BiggestSection
        private set
    /** Пилюля su и блок Root. */
    lateinit var rootPanel: RootPanel
        private set
    private lateinit var appsBox: LinearLayout
    private lateinit var lastBox: LinearLayout
    /** Места нет: «ещё: приложения · root · сканы ›» вместо строк под карточкой (тап — раскрыть). */
    lateinit var moreRow: LinearLayout
        private set
    /** «ещё» раскрыто тапом (до пересоздания экрана). */
    private var moreOpen = false
    /** «47,0 ГиБ» в строке «Приложения и система» (null — строки ещё нет). */
    var appsTotal: TextView? = null
        private set
    private val titles = ArrayList<String>()
    private val rootTitles = ArrayList<String>()
    /** Поколение загрузки яруса 0: устаревшие результаты (после onPause) отбрасываются. */
    private var gen = 0
    /** Идёт открытие кэша на Holder.io: повторные тапы игнорируются. */
    var opening = false
        private set
    private var dialog: AlertDialog? = null
    /** Кнопка меню «···» в шапке: язык, звук и вибрация, тема, о приложении. */
    lateinit var menuButton: TextView
        private set
    /** Для тестов: открытое меню «···» и лист «О приложении». */
    var menu: MenuSheet? = null
        private set
    var about: AboutSheet? = null
        private set
    /** Для тестов: открытый лист журнала удалений (из меню «···»). */
    var logSheet: DeleteLogSheet? = null
        private set
    /** Для тестов: открытый диалог выбора языка. */
    var langDialog: AlertDialog? = null
        private set
    /** Для тестов: открытый диалог «Звук и вибрация». */
    var fxDialog: AlertDialog? = null
        private set
    /** Для тестов: открытый диалог «Тема». */
    var themeDialog: AlertDialog? = null
        private set

    /** Тик/итог фонового скана или снятие закрепления браузером: подставить ждущее, перерисовать. */
    private val onBg: () -> Unit = { BgScan.promoteOnMain(); storage.render(); biggest.refresh() }
    private val onRoot: () -> Unit = { rootPanel.render() }
    /** Новое дерево (фоновый скан, подстановка ждущего) или конец удаления: пересчитать крупнейшие. */
    private val onTree: () -> Unit = { biggest.refresh(force = true) }
    private val onDeleted: (Int) -> Unit = { biggest.refresh(force = true) }
    /** Δ показанного дерева посчитана заново: строка «что выросло» и значки NEW (ключ — сама Δ). */
    private val onGrowth: () -> Unit = { biggest.refresh() }
    /** Уведомление о прерванном удалении появилось или просмотрено. */
    private val onNotice: () -> Unit = { storage.interrupted = DeleteLog.notice; storage.render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.reconcile(this)
        systemBars()
        Root.load(this)
        val prefs = getSharedPreferences(Scans.PREFS, MODE_PRIVATE)
        val rootCaches = prefs.all.values.any { CacheMeta.parse(it as? String)?.su == true }
        rootPanel = RootPanel(this, Root.suExists(), rootCaches)
        storage = StorageCard(this).apply { onInterrupted = { openInterrupted(it) } }
        // Журнал удалений: начало без итога — удаление прервано (строка статуса, приоритет 1).
        DeleteLog.init(this)
        biggest = BiggestSection(this)
        val body = vbox(16).apply { setPadding(dp(16), dp(12), dp(16), dp(24)) }
        body.addView(header())
        body.addView(storage.panel)
        body.addView(biggest.box)
        // Ниже панели — строки без карточек, разделённые волосяными линиями.
        appsBox = vbox()
        body.addView(appsBox)
        lastBox = vbox()
        body.addView(lastBox)
        rootPanel.block?.let { body.addView(it) }
        moreRow = vbox().apply { visibility = View.GONE }
        body.addView(moreRow)
        setContentView(ScrollView(this).apply { setBackgroundColor(C.BG); addView(body) })
        storage.showStatfs()
    }

    override fun onResume() {
        super.onResume()
        BgScan.mainResumed = true
        BgScan.addListener(onBg)
        Root.addListener(onRoot)
        Holder.addSessionListener(onTree)
        Holder.addDeleteListener(onDeleted)
        Growth.addListener(onGrowth)
        DeleteLog.listener = onNotice
        storage.interrupted = DeleteLog.notice
        storage.showStatfs()
        // Скан закончился, пока был открыт браузер: здесь его уже никто не держит — подставляем.
        BgScan.promoteOnMain()
        storage.gate = BgScan.maybeStart(this)
        renderLast()
        storage.render()
        rootPanel.render()
        biggest.refresh()
        val my = ++gen
        val app = applicationContext
        Thread {
            val t = try { Quotas.load(app) } catch (e: Exception) { null }
            runOnUiThread { if (t != null && !isDestroyed && my == gen) renderTier0(t) }
        }.start()
    }

    override fun onPause() {
        gen++
        // Фоновый скан не отменяется: он допишет кэш и подставит или предложит дерево сам.
        BgScan.mainResumed = false
        BgScan.removeListener(onBg)
        Root.removeListener(onRoot)
        Holder.removeSessionListener(onTree)
        Holder.removeDeleteListener(onDeleted)
        Growth.removeListener(onGrowth)
        if (DeleteLog.listener === onNotice) DeleteLog.listener = null
        super.onPause()
    }

    override fun onDestroy() {
        rootPanel.destroy()
        menu?.dismiss(); about?.dismiss()
        logSheet?.dismiss(); logSheet = null
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    fun showAlert(title: String, msg: String) { dialog = alert(title, msg) }

    /** versionName из манифеста (не зашитая строка); пусто, если прочитать нельзя. */
    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

    private fun header() = hbox(8).apply {
        addView(label("ANCDU", 20f, mono = true, bold = true).apply { letterSpacing = 0.18f })
        addView(label("v" + versionName(), 12f, C.MUTED, mono = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        // «···» (U+00B7 ×3) — есть в шрифте; U+22EF «⋯» в подмножестве шрифта нет.
        menuButton = label("···", 18f, C.TEXT, mono = true, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(44); minWidth = dp(44)
            background = pressable(C.BG, C.FRAME)
            isClickable = true; isFocusable = true
            contentDescription = tx.s(R.string.menu)
            feedbackClick { openMenu() }
        }
        addView(menuButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        // Только когда su есть: без него пилюли нет совсем.
        rootPanel.pill?.let { addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)) }
    }

    /** Меню «···»: каждый пункт закрывает его и открывает свой выбор (один диалог за раз). */
    fun openMenu() {
        dialog?.dismiss()
        menu?.dismiss(); about?.dismiss()
        menu = MenuSheet(this,
            onLang = { dialog = Lang.ask(this).also { langDialog = it } },
            onFx = { dialog = Lang.askFx(this).also { fxDialog = it } },
            onTheme = { dialog = Theme.ask(this).also { themeDialog = it } },
            onAbout = { about = AboutSheet(this).also { it.show() } },
            onLog = { DeleteLogSheet.open(this) { s -> logSheet?.dismiss(); logSheet = s } }).also { it.show() }
    }

    private fun openApps() = startActivity(Intent(this, AppsActivity::class.java))

    /**
     * Тап по «⚠ прервано: Download/ · …»: уведомление просмотрено (больше не показывается), папка
     * открывается — там остаток. Общее хранилище — как строка «крупнейших»; другое дерево — его кэш.
     */
    fun openInterrupted(d: InterruptedDelete) {
        if (Holder.deleting || opening) return
        DeleteLog.markSeen()
        if (!d.su && d.root == Scans.STORAGE) { openFocused(d.folder); return }
        val meta = Scans.meta(this, d.root, d.su) ?: return
        openCache(Holder.cacheFile(this, d.root, d.su).name, d.root, d.su, meta.time,
            focus = if (d.folder.isEmpty()) null else Focus.encode(d.folder))
    }

    /**
     * Карточка изменилась (statfs, объём общего хранилища): число «Приложения и система» и правило
     * «места нет» — строки под карточкой сворачиваются в одну «ещё: … ›».
     */
    fun layoutChanged() {
        if (!::moreRow.isInitialized) return
        appsTotal?.text = HomeMath.appsBytes(storage.dataUsed, storage.sharedDisk)?.let { Fmt.size(it, tx) }.orEmpty()
        val fold = storage.full && !moreOpen
        appsBox.visibility = if (fold) View.GONE else View.VISIBLE
        lastBox.visibility = if (fold) View.GONE else View.VISIBLE
        rootPanel.block?.visibility = if (fold) View.GONE else View.VISIBLE
        moreRow.visibility = if (fold) View.VISIBLE else View.GONE
        if (!fold) return
        val x = tx
        val parts = listOfNotNull(x.s(R.string.more_apps), rootPanel.block?.let { x.s(R.string.more_root) },
            if (scansCount > 0) x.s(R.string.more_scans) else null)
        val text = x.s(R.string.more_row, parts.joinToString(" · "))
        if ((moreRow.getChildAt(0) as? TextView)?.text?.toString() == text) return
        moreRow.removeAllViews()
        moreRow.addView(label(text, 13f, C.MUTED, mono = true).apply {
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            background = pressable(C.BG)
            isClickable = true; isFocusable = true
            contentDescription = x.s(R.string.more_desc)
            feedbackClick { moreOpen = true; layoutChanged() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        moreRow.hairline()
    }

    /** «ПРИЛОЖЕНИЯ И СИСТЕМА 47,0 ГиБ» и справа [action] (приглушённо: не акцент). */
    private fun appsHead(action: String, desc: String?): LinearLayout = hbox(8).apply {
        val x = tx
        minimumHeight = dp(48)
        addView(caps(x.s(R.string.apps_system)))
        appsTotal = label("", 13f, C.TEXT, mono = true)
        addView(appsTotal, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(label(action, 14f, C.TEXT, bold = true).apply {
            minHeight = dp(44); gravity = Gravity.CENTER_VERTICAL; isClickable = true; isFocusable = true
            setPadding(dp(8), 0, 0, 0)
            background = pressable(C.BG)
            if (desc != null) contentDescription = desc
            feedbackClick { openApps() }
        })
    }

    private fun renderTier0(t: Tier0) {
        t.segs?.let { storage.showSegs(it) }
        appsBox.removeAllViews()
        val apps = t.apps
        val x = tx
        if (apps == null) {
            // Нет «Доступа к истории использования»: AppsActivity объясняет и ведёт в настройки.
            appsBox.addView(appsHead(x.s(R.string.apps_access), "${x.s(R.string.apps_no_access)}, ${x.s(R.string.apps_no_access_sub)}"))
            appsBox.hairline()
            layoutChanged()
            return
        }
        appsBox.addView(appsHead(x.s(R.string.apps_all), null))
        appsBox.hairline()
        for (a in apps.take(3)) {
            appsBox.addView(hbox(12).apply {
                minimumHeight = dp(48)
                background = pressable(C.BG)
                isClickable = true; isFocusable = true
                contentDescription = "${a.label}, ${Fmt.size(a.total, x)}"
                setOnClickListener { openApps() }
                addView(label(a.label, 14f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(label(Fmt.size(a.total, x), 14f, mono = true))
            })
            appsBox.hairline()
        }
        layoutChanged()
    }

    /** Строк прошлых сканов (видна самая свежая; остальные — после «ещё N ›»). */
    private var scansCount = 0
    /** «ещё N ›» раскрыто (до пересоздания экрана). */
    private var scansOpen = false
    /** Для тестов: строка «ещё N ›» (null — прошлых сканов не больше одного или раскрыто). */
    var scansMore: TextView? = null
        private set

    /** «ещё N ›»: показать все строки прошлых сканов. */
    fun expandScans() { scansOpen = true; renderLast() }

    /** Заголовки строк «Последний скан» без root (для тестов); общее хранилище — на карточке. */
    fun lastScans(): List<String> = titles.toList()
    /** Заголовки строк кэшей root-сканов в блоке Root (для тестов). */
    fun rootScans(): List<String> = rootTitles.toList()

    private fun renderLast() {
        lastBox.removeAllViews()
        rootPanel.caches?.removeAllViews()
        titles.clear(); rootTitles.clear()
        val prefs = getSharedPreferences(Scans.PREFS, MODE_PRIVATE)
        val storageName = Holder.cacheFile(this, Scans.STORAGE, false).name
        val rows = prefs.all.mapNotNull { (file, v) ->
            val m = CacheMeta.parse(v as? String) ?: return@mapNotNull null
            if (file == storageName) null else file to m
        }.sortedByDescending { it.second.time }
        val t = tx
        // Один раздел под карточкой: строки прошлых сканов (и root, и без) — в блоке Root, если он
        // есть, иначе отдельно; видна самая свежая и «ещё N ›», остальные — по тапу.
        val box = rootPanel.caches ?: lastBox
        scansCount = 0
        for ((file, m) in rows) {
            val title = t.s(R.string.last_scan, m.root)
            if (m.su) rootTitles += title else titles += title
            val sub = listOfNotNull(Freshness.date(t, R.string.fmt_day_time, m.time),
                t.q(R.plurals.files, m.files, Fmt.count(m.files, t.locale)), Fmt.secs(m.ms, t),
                if (m.su) "root" else null).joinToString(" · ")
            // Одна строка «ПОСЛЕДНИЙ /путь · N файлов · 6,1 с · дата ›»; описание — как прежде.
            val line = listOf(m.root, t.q(R.plurals.files, m.files, Fmt.count(m.files, t.locale)), Fmt.secs(m.ms, t),
                Freshness.date(t, R.string.fmt_day_time, m.time)).joinToString(" · ")
            val first = scansCount == 0
            box.addView(navRow(t.s(R.string.last_short), line, "$title, $sub") { openCache(file, m.root, m.su, m.time) }.apply {
                if (!first && !scansOpen) visibility = View.GONE
            })
            box.addView(View(this).apply {
                setBackgroundColor(C.LINE)
                if (!first && !scansOpen) visibility = View.GONE
            }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))
            scansCount++
        }
        val more = scansCount - 1
        if (more > 0 && !scansOpen) {
            val text = t.s(R.string.scans_more, Fmt.count(more.toLong(), t.locale))
            box.addView(label(text, 13f, C.MUTED, mono = true).apply {
                minHeight = dp(48)
                gravity = Gravity.CENTER_VERTICAL
                background = pressable(C.BG)
                isClickable = true; isFocusable = true
                contentDescription = t.q(R.plurals.scans_more_desc, more.toLong(), Fmt.count(more.toLong(), t.locale))
                feedbackClick { expandScans() }
            }.also { scansMore = it }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            box.hairline()
        } else scansMore = null
        layoutChanged()
    }

    /**
     * Чтение кэша — на Holder.io (FIFO с saveCache/delete/free), Holder.set — на главном потоке.
     * Экран закрылся, пока кэш читался, — новая сессия освобождается на io, Holder не трогается.
     * [time] — время скана из записи «caches»: оно и становится временем дерева (Holder.time).
     * [focus] — [EXTRA_FOCUS] для браузера (файл в фокусе) или null.
     */
    fun openCache(name: String, root: String, su: Boolean, time: Long, focus: ByteArray? = null, delta: Boolean = false,
                  errors: Boolean = false) {
        if (opening) return
        opening = true
        val app = applicationContext
        val file = File(filesDir, name)
        Holder.io.execute {
            val err = IntArray(1)
            val h = try { Native.openCache(file.path, err) } catch (e: Exception) { 0L }
            if (h == 0L) {
                app.getSharedPreferences(Scans.PREFS, MODE_PRIVATE).edit().remove(name).commit()
                file.delete()
                // Кэш другой версии формата или негодный — забыть и его точку отсчёта; временный сбой
                // (нет памяти, дескрипторов) её не трогает.
                if (Baseline.dropOnCacheError(err[0])) Baseline.files(app, root, su).forget()
            }
            runOnUiThread {
                opening = false
                if (isDestroyed || isFinishing) {
                    if (h != 0L) Holder.io.execute { Native.free(h) }
                    return@runOnUiThread
                }
                if (h == 0L && NativeErr.cacheOutdated(err[0])) {
                    // Кэш прошлой версии формата: молча забыт, вместо него — тот же скан заново.
                    renderLast()
                    storage.render()
                    biggest.refresh(force = true)
                    startActivity(Intent(this, ScanActivity::class.java).putExtra(EXTRA_ROOT, root).putExtra(EXTRA_SU, su))
                    return@runOnUiThread
                }
                if (h == 0L) {
                    showAlert(tx.s(R.string.cache_corrupt_title), tx.s(R.string.cache_corrupt_msg))
                    renderLast()
                    storage.render()
                    biggest.refresh(force = true)
                    return@runOnUiThread
                }
                Holder.set(h, Kind.CACHE, root, su, time)
                startActivity(Intent(this, BrowserActivity::class.java).apply {
                    if (focus != null) putExtra(EXTRA_FOCUS, focus)
                    if (delta) putExtra(EXTRA_DELTA, true)
                    if (errors) putExtra(EXTRA_ERRORS, true)
                })
            }
        }
    }

    /**
     * Строка «крупнейших файлов»: браузер общего хранилища у папки файла [names] (байты имён от
     * корня), строка видна, карточка открыта. Дерево — как у тапа по карточке: ждущее
     * подставляется, готовое живое — сразу, иначе кэш.
     */
    fun openFocused(names: List<ByteArray>, delta: Boolean = false) {
        if (Holder.deleting || opening) return
        if (BgScan.pendingStorage()) Holder.promote()
        // [delta] — строка «что выросло»: папка «больше всего» (пусто — корень) в сортировке Δ.
        val focus = if (names.isEmpty()) null else Focus.encode(names)
        val shown = storage.storageShown()
        val browser = Intent(this, BrowserActivity::class.java).apply {
            if (focus != null) putExtra(EXTRA_FOCUS, focus)
            if (delta) putExtra(EXTRA_DELTA, true)
        }
        if (shown && Holder.kind != Kind.INDEX) { startActivity(browser); return }
        val meta = Scans.meta(this, Scans.STORAGE, false)
        if (meta != null) { openCache(Holder.cacheFile(this, Scans.STORAGE, false).name, Scans.STORAGE, false, meta.time, focus, delta); return }
        if (shown) { startActivity(browser); return }
        // Ни дерева, ни записи кэша (её забыли, пока строки были на экране): строки — заново, и сказать.
        biggest.refresh(force = true)
        android.widget.Toast.makeText(this, tx.s(R.string.big_gone), android.widget.Toast.LENGTH_SHORT).show()
    }
}
