package dev.ancdu

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

class AppsActivity : LangActivity() {
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
            addView(label(tx.s(R.string.apps_footer), 12f, C.MUTED).apply { setPadding(dp(16), dp(10), dp(16), dp(10)) })
        }
        askBox = vbox(16).apply {
            setPadding(dp(16), dp(8), dp(16), dp(24))
            addView(label(tx.s(R.string.usage_explain), 14f))
            addView(action(tx.s(R.string.open_settings), tx.s(R.string.usage_settings_sub), true) {
                Perms.askUsage(this@AppsActivity)
            })
        }
        errorText = label("", 14f, C.AMBER).apply { setPadding(dp(16), dp(8), dp(16), dp(24)) }
        setContentView(vbox().apply {
            setBackgroundColor(C.BG)
            addView(header())
            hairline()
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
        val x = tx
        Thread {
            val t = try { Quotas.load(applicationContext) } catch (e: Exception) {
                runOnUiThread {
                    if (isDestroyed || my != gen) return@runOnUiThread
                    errorText.text = x.s(R.string.apps_load_failed, (e.message ?: e).toString())
                    show(errorText)
                }
                return@Thread
            }
            val got = t.apps.orEmpty()
            val max = got.firstOrNull()?.total ?: 0
            val sum = got.sumOf { it.total }
            val built = got.map { a -> Row().also { AppRows.fill(x, a, max, sum, it) } }
            runOnUiThread {
                if (isDestroyed || my != gen) return@runOnUiThread
                apps = got
                rows = built
                loaded++
                title.text = "${Fmt.count(got.size.toLong(), x.locale)} · ${Fmt.size(sum, x)}"
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
        addView(label(tx.s(R.string.apps_title), 22f, bold = true).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        title = label("…", 13f, C.MUTED, mono = true)
        addView(title)
    }

    private fun legend(): LinearLayout = hbox(16).apply {
        setPadding(dp(16), dp(10), dp(16), dp(10))
        for ((c, t) in listOf(C.BLUE to "APK", C.AMBER to tx.s(R.string.legend_data), C.MUTED to tx.s(R.string.legend_cache))) {
            val s = android.text.SpannableString("■ $t")
            s.setSpan(android.text.style.ForegroundColorSpan(c), 0, 1, 0)
            addView(label(s, 12f, C.MUTED))
        }
    }
}
