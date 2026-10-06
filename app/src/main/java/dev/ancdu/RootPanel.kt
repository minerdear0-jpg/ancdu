package dev.ancdu

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Root на главном экране: «пилюля» su в шапке (ручной запрос root) и приглушённый блок внизу —
 * чипы корней, подсказка после отказа, строки кэшей root-сканов. Всё — главный поток.
 * [su] — есть исполняемый su; без него пилюли нет, блок — только ради оставшихся root-кэшей.
 */
class RootPanel(private val a: MainActivity, private val su: Boolean, hasRootCaches: Boolean) {
    /** Кнопка-«пилюля» su; null — su нет. */
    val pill: TextView? = if (su) buildPill() else null
    /** Блок Root внизу; null — su нет и root-кэшей нет. */
    val block: LinearLayout? = if (su || hasRootCaches) buildBlock() else null
    /** Сюда главный экран кладёт строки кэшей root-сканов. */
    var caches: LinearLayout? = null
        private set
    private var hint: TextView? = null
    private val ui = Handler(Looper.getMainLooper())
    private var dots = 0

    /** «su…»: бегущие точки, пока идёт запрос root. */
    private val asking = object : Runnable {
        override fun run() {
            if (Root.state != RootState.ASKING) return
            dots = (dots + 1) % 3
            pill?.text = "su" + ".".repeat(dots + 1)
            ui.postDelayed(this, 400)
        }
    }

    private fun buildPill(): TextView = a.label("su", 12f, C.MUTED, mono = true).apply {
        gravity = Gravity.CENTER
        minHeight = a.dp(44); minWidth = a.dp(44)
        setPadding(a.dp(12), 0, a.dp(12), 0)
        isClickable = true; isFocusable = true
        setOnClickListener { Root.request(a) }
    }

    private class Look(val text: String, val fg: Int, val bg: Int, val desc: String, val outline: Boolean = false)

    /** Пилюля и подсказка — по Root.state. */
    fun render() {
        hint?.visibility = if (su && Root.state == RootState.DENIED) View.VISIBLE else View.GONE
        val p = pill ?: return
        val look = when (Root.state) {
            RootState.UNKNOWN -> Look("su", C.MUTED, C.SURFACE, "su: запросить root", outline = true)
            RootState.ASKING -> Look("su…", C.TEXT, C.SURFACE, "su: идёт запрос root", outline = true)
            RootState.GRANTED -> Look("root ✓", C.OK_TXT, C.OK_BG, "root выдан; нажмите, чтобы проверить снова")
            RootState.DENIED -> Look("root ✗", C.WARN, C.SURFACE, "root отклонён; нажмите, чтобы запросить снова")
        }
        p.text = look.text
        p.setTextColor(look.fg)
        p.contentDescription = look.desc
        // Касание 44dp, видимая пилюля ниже.
        val shape = rounded(look.bg, a.dp(10).toFloat()) // тот же радиус, что у chip()
        if (look.outline) shape.setStroke(a.dp(1), C.LINE)
        p.background = InsetDrawable(shape, 0, a.dp(7), 0, a.dp(7))
        // setBackground подменяет padding на padding drawable (0): вернуть отступы текста.
        p.setPadding(a.dp(12), a.dp(7), a.dp(12), a.dp(7))
        ui.removeCallbacks(asking)
        if (Root.state == RootState.ASKING) { dots = 2; ui.postDelayed(asking, 400) }
    }

    fun destroy() = ui.removeCallbacks(asking)

    private fun buildBlock(): LinearLayout = a.vbox(10).apply {
        setPadding(a.dp(18), a.dp(14), a.dp(18), a.dp(14))
        background = GradientDrawable().apply {
            setColor(C.BG); cornerRadius = a.dp(14).toFloat(); setStroke(a.dp(1), C.LINE)
        }
        val head = SpannableString("Root · напрямую, без FUSE, видно /data/data")
        head.setSpan(StyleSpan(Typeface.BOLD), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        head.setSpan(ForegroundColorSpan(C.TEXT), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        addView(a.label(head, 13f, C.MUTED))
        if (su) addView(a.hbox(8).apply {
            for (path in listOf("/data", "/", "/data/media")) {
                addView(a.chip(path, false) { scan(path) }.apply {
                    setTextColor(C.MUTED)
                    contentDescription = "Сканировать как root: $path"
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
        })
        hint = a.label("root отклонён — нажмите su вверху, чтобы запросить снова", 12f, C.MUTED).apply {
            visibility = View.GONE
        }
        addView(hint)
        caches = a.vbox(8)
        addView(caches)
    }

    private fun scan(root: String) =
        a.startActivity(Intent(a, ScanActivity::class.java).putExtra(EXTRA_ROOT, root).putExtra(EXTRA_SU, true))
}
