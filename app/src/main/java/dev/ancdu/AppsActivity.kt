package dev.ancdu

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

class AppsActivity : Activity() {
    private var apps: List<AppStat> = emptyList()
    /** Строки форматируются один раз при загрузке; bind только копирует поля. */
    private var rows: List<Row> = emptyList()
    private var gen = 0
    private lateinit var list: NcduListView
    private lateinit var title: TextView

    private val src = object : RowSource {
        override val count get() = rows.size
        override fun bind(index: Int, row: Row) {
            val r = rows[index]
            row.name = r.name; row.sub = r.sub; row.size = r.size; row.pct = r.pct
            row.bar = r.bar; row.segs = r.segs; row.segColors = r.segColors; row.desc = r.desc
        }
        override fun click(index: Int) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + apps[index].pkg)))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkBars()
    }

    override fun onResume() {
        super.onResume()
        val my = ++gen
        if (!Perms.usage(this)) { showAsk(); return }
        showList()
        Thread {
            val t = try { Quotas.load(applicationContext) } catch (e: Exception) {
                runOnUiThread {
                    if (isDestroyed || my != gen) return@runOnUiThread
                    showError(e.message ?: e.toString())
                }
                return@Thread
            }
            val loaded = t.apps.orEmpty()
            val max = loaded.firstOrNull()?.total ?: 0
            val sum = loaded.sumOf { it.total }
            val built = loaded.map { a -> Row().also { AppRows.fill(a, max, sum, it) } }
            runOnUiThread {
                if (isDestroyed || my != gen) return@runOnUiThread
                apps = loaded
                rows = built
                title.text = "${loaded.size} · ${Fmt.size(sum)}"
                list.refresh()
            }
        }.start()
    }

    override fun onPause() {
        gen++
        super.onPause()
    }

    private fun header(): LinearLayout = hbox(8).apply {
        setPadding(dp(8), dp(12), dp(16), dp(8))
        addView(backButton { finish() })
        addView(label("Приложения", 20f, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        title = label("…", 13f, C.MUTED, mono = true)
        addView(title)
    }

    private fun legend(): LinearLayout = hbox(16).apply {
        setPadding(dp(16), 0, dp(16), dp(10))
        for ((c, t) in listOf(C.FILE to "APK", C.ACCENT to "данные", C.CACHE to "кэш")) {
            val s = android.text.SpannableString("■ $t")
            s.setSpan(android.text.style.ForegroundColorSpan(c), 0, 1, 0)
            addView(label(s, 12f, C.MUTED))
        }
    }

    private fun showList() {
        list = NcduListView(this).apply { withSub = true; source = src }
        setContentView(vbox().apply {
            setBackgroundColor(C.BG)
            addView(header())
            addView(legend())
            addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(label("Данные StorageStatsManager · работает без root · тап — настройки приложения",
                12f, C.MUTED).apply { setPadding(dp(16), dp(10), dp(16), dp(10)) })
        })
    }

    private fun showError(msg: String) {
        setContentView(vbox(16).apply {
            setBackgroundColor(C.BG)
            setPadding(dp(16), dp(24), dp(16), dp(24))
            addView(header())
            addView(label("Не удалось загрузить размеры приложений: $msg", 15f, C.FREE_TXT))
        })
    }

    private fun showAsk() {
        setContentView(vbox(16).apply {
            setBackgroundColor(C.BG)
            setPadding(dp(16), dp(24), dp(16), dp(24))
            addView(header())
            addView(label("Чтобы показать, сколько места занимает каждое приложение, нужен " +
                "«Доступ к истории использования». ancdu читает только размеры, без истории.", 15f))
            addView(action("Открыть настройки", "Доступ к истории использования → ancdu", true) {
                Perms.askUsage(this@AppsActivity)
            })
        })
    }
}
