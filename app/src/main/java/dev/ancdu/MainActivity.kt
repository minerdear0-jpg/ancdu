package dev.ancdu

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
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
    private val titles = ArrayList<String>()
    private val rootTitles = ArrayList<String>()
    /** Поколение загрузки яруса 0: устаревшие результаты (после onPause) отбрасываются. */
    private var gen = 0
    /** Идёт открытие кэша на Holder.io: повторные тапы игнорируются. */
    var opening = false
        private set
    private var dialog: AlertDialog? = null
    /** Кнопка меню «⋯» в шапке: язык, звук и вибрация, о приложении. */
    lateinit var menuButton: TextView
        private set
    /** Для тестов: открытое меню «⋯» и лист «О приложении». */
    var menu: MenuSheet? = null
        private set
    var about: AboutSheet? = null
        private set
    /** Для тестов: открытый диалог выбора языка. */
    var langDialog: AlertDialog? = null
        private set
    /** Для тестов: открытый диалог «Звук и вибрация». */
    var fxDialog: AlertDialog? = null
        private set

    /** Тик/итог фонового скана или снятие закрепления браузером: подставить ждущее, перерисовать. */
    private val onBg: () -> Unit = { BgScan.promoteOnMain(); storage.render(); biggest.refresh() }
    private val onRoot: () -> Unit = { rootPanel.render() }
    /** Новое дерево (фоновый скан, подстановка ждущего) или конец удаления: пересчитать крупнейшие. */
    private val onTree: () -> Unit = { biggest.refresh(force = true) }
    private val onDeleted: (Int) -> Unit = { biggest.refresh(force = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkBars()
        Root.load(this)
        val prefs = getSharedPreferences(Scans.PREFS, MODE_PRIVATE)
        val rootCaches = prefs.all.values.any { CacheMeta.parse(it as? String)?.su == true }
        rootPanel = RootPanel(this, Root.suExists(), rootCaches)
        storage = StorageCard(this)
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
        super.onPause()
    }

    override fun onDestroy() {
        rootPanel.destroy()
        menu?.dismiss(); about?.dismiss()
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
        menuButton = label("⋯", 18f, C.TEXT, mono = true, bold = true).apply {
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

    /** Меню «⋯»: каждый пункт закрывает его и открывает свой выбор (один диалог за раз). */
    fun openMenu() {
        dialog?.dismiss()
        menu?.dismiss(); about?.dismiss()
        menu = MenuSheet(this,
            onLang = { dialog = Lang.ask(this).also { langDialog = it } },
            onFx = { dialog = Lang.askFx(this).also { fxDialog = it } },
            onAbout = { about = AboutSheet(this).also { it.show() } }).also { it.show() }
    }

    private fun openApps() = startActivity(Intent(this, AppsActivity::class.java))

    private fun renderTier0(t: Tier0) {
        t.segs?.let { storage.showSegs(it) }
        appsBox.removeAllViews()
        val apps = t.apps
        val x = tx
        if (apps == null) {
            // Нет «Доступа к истории использования»: AppsActivity объясняет и ведёт в настройки.
            appsBox.addView(navRow(x.s(R.string.apps_title), x.s(R.string.apps_grant),
                "${x.s(R.string.apps_no_access)}, ${x.s(R.string.apps_no_access_sub)}", C.AMBER, mono = false) { openApps() })
            appsBox.hairline()
            return
        }
        appsBox.addView(hbox().apply {
            addView(caps(x.s(R.string.apps_title)), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(label(x.s(R.string.apps_all), 14f, C.AMBER, bold = true).apply {
                minHeight = dp(44); gravity = Gravity.CENTER_VERTICAL; isClickable = true; isFocusable = true
                setPadding(dp(8), 0, 0, 0)
                background = pressable(C.BG)
                setOnClickListener { openApps() }
            })
        })
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
    }

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
        for ((file, m) in rows) {
            val box = (if (m.su) rootPanel.caches else lastBox) ?: continue
            val title = t.s(R.string.last_scan, m.root)
            if (m.su) rootTitles += title else titles += title
            val sub = listOfNotNull(Freshness.date(t, R.string.fmt_day_time, m.time),
                t.q(R.plurals.files, m.files, Fmt.count(m.files, t.locale)), Fmt.secs(m.ms, t),
                if (m.su) "root" else null).joinToString(" · ")
            // Одна строка «ПОСЛЕДНИЙ /путь · N файлов · 6,1 с · дата ›»; описание — как прежде.
            val line = listOf(m.root, t.q(R.plurals.files, m.files, Fmt.count(m.files, t.locale)), Fmt.secs(m.ms, t),
                Freshness.date(t, R.string.fmt_day_time, m.time)).joinToString(" · ")
            box.addView(navRow(t.s(R.string.last_short), line, "$title, $sub") { openCache(file, m.root, m.su, m.time) })
            box.hairline()
        }
    }

    /**
     * Чтение кэша — на Holder.io (FIFO с saveCache/delete/free), Holder.set — на главном потоке.
     * Экран закрылся, пока кэш читался, — новая сессия освобождается на io, Holder не трогается.
     * [time] — время скана из записи «caches»: оно и становится временем дерева (Holder.time).
     * [focus] — [EXTRA_FOCUS] для браузера (файл в фокусе) или null.
     */
    fun openCache(name: String, root: String, su: Boolean, time: Long, focus: ByteArray? = null) {
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
                startActivity(Intent(this, BrowserActivity::class.java).apply { if (focus != null) putExtra(EXTRA_FOCUS, focus) })
            }
        }
    }

    /**
     * Строка «крупнейших файлов»: браузер общего хранилища у папки файла [names] (байты имён от
     * корня), строка видна, карточка открыта. Дерево — как у тапа по карточке: ждущее
     * подставляется, готовое живое — сразу, иначе кэш.
     */
    fun openFocused(names: List<ByteArray>) {
        if (Holder.deleting || opening) return
        if (BgScan.pendingStorage()) Holder.promote()
        val focus = Focus.encode(names)
        val shown = storage.storageShown()
        val browser = Intent(this, BrowserActivity::class.java).putExtra(EXTRA_FOCUS, focus)
        if (shown && Holder.kind != Kind.INDEX) { startActivity(browser); return }
        val meta = Scans.meta(this, Scans.STORAGE, false)
        if (meta != null) { openCache(Holder.cacheFile(this, Scans.STORAGE, false).name, Scans.STORAGE, false, meta.time, focus); return }
        if (shown) { startActivity(browser); return }
        // Ни дерева, ни записи кэша (её забыли, пока строки были на экране): строки — заново, и сказать.
        biggest.refresh(force = true)
        android.widget.Toast.makeText(this, tx.s(R.string.big_gone), android.widget.Toast.LENGTH_SHORT).show()
    }
}
