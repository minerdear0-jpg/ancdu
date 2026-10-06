package dev.ancdu

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

class AppsActivity : Activity() {
    private var apps: List<AppStat> = emptyList()
    /** Строки форматируются один раз при загрузке; bind только копирует поля. */
    private var rows: List<Row> = emptyList()
    private var gen = 0
    /** Для тестов: сколько раз данные применены к списку. */
    var loaded = 0
        private set
    /** Для тестов. */
    lateinit var list: NcduListView
        private set
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

    private lateinit var listBox: LinearLayout
    private lateinit var askBox: LinearLayout
    private lateinit var errorText: TextView

    /** Экран строится один раз; onResume только перечитывает данные и обновляет список на месте. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkBars()
        list = NcduListView(this).apply { withSub = true; source = src }
        listBox = vbox().apply {
            addView(legend())
            addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(label("Данные StorageStatsManager · работает без root · тап — настройки приложения",
                12f, C.MUTED).apply { setPadding(dp(16), dp(10), dp(16), dp(10)) })
        }
        askBox = vbox(16).apply {
            setPadding(dp(16), dp(8), dp(16), dp(24))
            addView(label("Чтобы показать, сколько места занимает каждое приложение, нужен " +
                "«Доступ к истории использования». ancdu читает только размеры, без истории.", 15f))
            addView(action("Открыть настройки", "Доступ к истории использования → ancdu", true) {
                Perms.askUsage(this@AppsActivity)
            })
        }
        errorText = label("", 15f, C.WARN).apply { setPadding(dp(16), dp(8), dp(16), dp(24)) }
        setContentView(vbox().apply {
            setBackgroundColor(C.BG)
            addView(header())
            addView(listBox, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(askBox)
            addView(errorText)
        })
    }

    override fun onResume() {
        super.onResume()
        val my = ++gen
        if (!Perms.usage(this)) { show(askBox); return }
        show(listBox)
        Thread {
            val t = try { Quotas.load(applicationContext) } catch (e: Exception) {
                runOnUiThread {
                    if (isDestroyed || my != gen) return@runOnUiThread
                    errorText.text = "Не удалось загрузить размеры приложений: ${e.message ?: e}"
                    show(errorText)
                }
                return@Thread
            }
            val got = t.apps.orEmpty()
            val max = got.firstOrNull()?.total ?: 0
            val sum = got.sumOf { it.total }
            val built = got.map { a -> Row().also { AppRows.fill(a, max, sum, it) } }
            runOnUiThread {
                if (isDestroyed || my != gen) return@runOnUiThread
                apps = got
                rows = built
                loaded++
                title.text = "${got.size} · ${Fmt.size(sum)}"
                list.refresh()   // прокрутка сохраняется (refresh только ограничивает её)
            }
        }.start()
    }

    override fun onPause() {
        gen++
        super.onPause()
    }

    private fun show(v: View) {
        for (x in listOf(listBox, askBox, errorText)) x.visibility = if (x === v) View.VISIBLE else View.GONE
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
}
