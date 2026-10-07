package dev.ancdu

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView

/** Всё, что показывает лист удаления. Собирается из дерева в памяти; файлы не читаются. */
class DeletePreview(
    val name: String,
    val path: String,
    val dir: Boolean,
    val disk: Long,
    val apparent: Long,
    val items: Long,
    val flags: Int,
    /** Крупнейшие дети по диску: имя (с «/» у каталогов) и размер. Пусто у файлов. */
    val top: List<Pair<String, Long>>,
    /** Сколько детей не вошло в [top]. */
    val more: Int,
    val owner: String?,
    val viaRoot: Boolean,
    /** Причина запрета (DeletePolicy) или null. */
    val block: Block?,
    val kind: Kind,
    /** Время скана для Kind.CACHE (уже в языке экрана), иначе null. */
    val cacheTime: String?,
    /** Доступен быстрый путь root в обход FUSE (/storage/emulated/<n>/X через /data/media). */
    val fast: Boolean = false,
    /** Известное о root (Root.state): галочка быстрого пути по умолчанию — только при GRANTED. */
    val root: RootState = RootState.UNKNOWN,
)

/** Лист подтверждения удаления: framework Dialog у нижнего края, без AndroidX. */
/** [onDelete] получает выбор «быстро через root» (false, если быстрый путь недоступен); [onClose] — лист закрыт любым путём. */
class DeleteSheet(private val act: Activity, val p: DeletePreview, private val onClose: () -> Unit = {},
                  private val onDelete: (Boolean) -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private val t: Txt = act.tx
    val dialog = Dialog(act, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
    /** null — удаление запрещено ([blockText] вместо кнопки). */
    var deleteButton: TextView? = null
        private set
    var blockText: TextView? = null
        private set
    var ownerText: TextView? = null
        private set
    /** Имена показанных детей (для тестов). */
    val childNames = ArrayList<String>()
    lateinit var cancelButton: TextView
        private set
    /** Для тестов: строка размера (главное число и видимый размер). */
    var sizeText: TextView? = null
        private set
    /** Галочка «быстро через root» (null — быстрый путь недоступен). */
    var fastBox: CheckBox? = null
        private set
    /** Строка «Удаление от root · без корзины» (null — удаление не от root или запрещено). */
    var rootText: TextView? = null
        private set
    /** Для тестов: показанные числа обратного отсчёта по порядку. */
    val countdownShown = ArrayList<Long>()

    private val hardlink = !p.dir && p.flags and F_HLDUP != 0
    /** Пауза до «Удалить» (мс): своё приложение (тест, свой кэш) «чужим» не считается. */
    private val pauseMs = if (p.block != null) 0L
        else DeletePolicy.pauseMs(p.viaRoot, p.owner != null && p.owner != act.packageName, p.disk)
    private val pause = pauseMs > 0
    private val readyLabel = if (hardlink) t.s(R.string.delete_btn) else t.s(R.string.delete_btn_size, Fmt.size(p.disk, t))
    private var enableAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            val b = deleteButton ?: return
            val left = enableAt - SystemClock.uptimeMillis()
            if (left > 0) {
                val s = (left + 999) / 1000
                // Новое число — count; первое совпадает с arm и молчит.
                if (countdownShown.lastOrNull() != s) {
                    if (countdownShown.isNotEmpty()) Feedback.cue(b, Cue.COUNT)
                    countdownShown += s
                }
                val n = Fmt.count(s, t.locale)
                b.text = t.s(R.string.delete_in, n)
                b.contentDescription = t.s(R.string.delete_in_desc, n + Fmt.NBSP + t.s(R.string.unit_s))
                ui.postDelayed(this, minOf(left, 100))
            } else {
                b.isEnabled = true
                b.alpha = 1f
                b.text = readyLabel
                b.contentDescription = t.s(R.string.delete_btn_desc, readyLabel, p.name)
                Feedback.cue(b, Cue.READY)
                if (b.a11yOn()) b.announceForAccessibility(t.s(R.string.delete_ready))
            }
        }
    }

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(build())
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH_PARENT, WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
            // Въезд снизу 160 мс, уход 120 мс; затемнение — вместе с окном. Без анимаций — 0.
            setWindowAnimations(Motion.sheet())
        }
        dialog.setOnDismissListener { ui.removeCallbacks(tick); onClose() }
    }

    fun show() {
        dialog.show()
        cancelButton.requestFocus()
        Feedback.cue(cancelButton, when {
            p.block != null -> Cue.REFUSE
            p.viaRoot -> Cue.ARM_ROOT
            else -> Cue.ARM
        })
        if (pause) {
            enableAt = SystemClock.uptimeMillis() + pauseMs
            tick.run()
        }
    }

    fun dismiss() = dialog.dismiss()

    /**
     * Тело листа (заголовок … галочка) прокручивается; предупреждение и кнопки — под ним, всегда
     * видны (и при крупном шрифте).
     */
    private fun build(): View = act.vbox(10).apply {
        background = Brackets(act, C.PANEL, bottom = false)
        setPadding(act.dp(20), act.dp(20), act.dp(20), act.dp(16))
        addView(ScrollView(act).apply {
            isFillViewport = false
            addView(body())
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1f))
        // Предупреждение — сразу над кнопками; от root — строка об этом прямо над ним.
        if (p.block == null && p.viaRoot) addView(act.label(t.s(R.string.root_no_trash), 14f, C.DANGER_TEXT, bold = true)
            .also { rootText = it })
        if (p.block == null) addView(act.label("⚠ " + t.s(R.string.no_trash), 14f, C.DANGER_TEXT, bold = true))
        addView(buttons())
    }

    private fun body(): View = act.vbox(10).apply {
        val title = t.s(if (p.block == null) R.string.sheet_title else R.string.sheet_title_blocked, p.name)
        addView(act.hbox(8).apply {
            addView(act.label(title, 22f, C.TEXT, bold = true).apply {
                setSingleLine(true); ellipsize = TextUtils.TruncateAt.MIDDLE
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (p.viaRoot) addView(act.caps(t.s(R.string.as_root), C.TEXT).apply {
                setPadding(act.dp(8), act.dp(4), act.dp(8), act.dp(4))
                background = act.box(Color.TRANSPARENT, C.FRAME)
                contentDescription = t.s(R.string.as_root_desc)
            })
        })
        // Путь целиком, с переносами.
        addView(act.label(p.path, 12f, C.MUTED, mono = true).apply {
            contentDescription = t.s(R.string.path_desc, p.path)
        })
        p.owner?.let { addView(ownerRow(it)) }
        addView(sizeLine())
        if (p.dir && p.top.isNotEmpty()) addView(children())
        if (hardlink) addView(act.label(t.s(R.string.hardlink), 13f, C.AMBER))
        if (p.kind == Kind.INDEX) addView(act.label(t.s(R.string.index_approx), 13f, C.MUTED))
        if (p.cacheTime != null) addView(act.label(t.s(R.string.cache_sizes, p.cacheTime), 13f, C.MUTED))
        if (p.block == null && p.fast) addView(fastRow())
    }

    /** «быстро через root»: удаление через /data/media/<n>/ под su, затем очистка галереи в фоне (MediaClean). */
    private fun fastRow(): View = act.vbox(2).apply {
        val note = act.label(t.s(R.string.fast_note), 12f, C.MUTED)
        val box = CheckBox(act).apply {
            text = t.s(R.string.fast_box)
            textSize = 14f
            setTextColor(C.TEXT)
            typeface = Fonts.get(act, mono = false, bold = false)
            buttonDrawable = Check(act)
            setPadding(act.dp(12), 0, 0, 0)
            minHeight = act.dp(48)
            isChecked = DeletePolicy.fastByDefault(p.items, p.root)
            setOnCheckedChangeListener { _, on -> note.visibility = if (on) View.VISIBLE else View.GONE }
        }
        note.visibility = if (box.isChecked) View.VISIBLE else View.GONE
        fastBox = box
        addView(box)
        addView(note)
    }

    private fun ownerRow(pkg: String): View = act.hbox(8).apply {
        val pm = act.packageManager
        var name = pkg
        try {
            val ai = pm.getApplicationInfo(pkg, 0)
            name = ai.loadLabel(pm).toString()
            addView(ImageView(act).apply {
                setImageDrawable(ai.loadIcon(pm))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(act.dp(20), act.dp(20)))
        } catch (e: PackageManager.NameNotFoundException) {
            // Пакета нет (удалён, другой профиль): только имя пакета, без значка.
        }
        ownerText = act.label(t.s(R.string.owner, name), 14f, C.TEXT).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        addView(ownerText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        contentDescription = t.s(R.string.owner_desc, name)
    }

    /**
     * Главное число листа и видимый размер приглушённо после него — один TextView: внутри частей
     * пробелы неразрывные, перенос возможен только между ними. Не влезает в строку — видимый
     * размер уходит на вторую, ничего не обрезается.
     */
    private fun sizeLine(): View {
        val main = Fmt.size(p.disk, t) + if (p.dir) " · " + t.items(p.items) else ""
        val apparent = t.s(R.string.apparent_size, Fmt.size(p.apparent, t))
        val text = SpannableStringBuilder(main.replace(' ', Fmt.NBSP))
        text.setSpan(TypefaceSpan(Fonts.get(act, mono = true, bold = true)), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (p.apparent != p.disk) {
            text.append("   ")
            val at = text.length
            text.append(apparent.replace(' ', Fmt.NBSP))
            text.setSpan(AbsoluteSizeSpan(13, true), at, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ForegroundColorSpan(C.MUTED), at, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return act.label(text, 18f, C.TEXT, mono = true).apply {
            sizeText = this
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = t.s(R.string.disk_size_desc, Fmt.size(p.disk, t)) +
                (if (p.dir) ", " + t.q(R.plurals.items_long, p.items, Fmt.count(p.items, t.locale)) else "") +
                (if (p.apparent != p.disk) ", $apparent" else "")
        }
    }

    private fun children(): View = act.vbox(4).apply {
        val barMax = act.dp(56)
        for ((nm, size) in p.top) {
            childNames += nm
            addView(act.hbox(8).apply {
                // Мини-полоса в контуре 1dp FRAME, заполнение > 0 — не меньше 1px.
                val one = act.dp(1)
                val frame = FrameLayout(act).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    foreground = act.box(Color.TRANSPARENT, C.FRAME)
                    val w = ((barMax - 2 * one) * ListMath.bar(size, p.disk)).toInt().coerceAtLeast(if (size > 0) 1 else 0)
                    addView(View(act).apply { setBackgroundColor(if (nm.endsWith("/")) C.AMBER else C.BLUE) },
                        FrameLayout.LayoutParams(w, act.dp(8) - 2 * one).apply { setMargins(one, one, 0, 0) })
                }
                addView(frame, LinearLayout.LayoutParams(barMax, act.dp(8)))
                addView(act.label(nm, 13f, C.TEXT, mono = true).apply {
                    setSingleLine(true); ellipsize = TextUtils.TruncateAt.MIDDLE
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(act.label(Fmt.size(size, t), 13f, C.MUTED, mono = true))
                contentDescription = "$nm, ${Fmt.size(size, t)}"
            })
        }
        if (p.more > 0) addView(act.label(t.s(R.string.more_children, Fmt.count(p.more.toLong(), t.locale)), 12f, C.MUTED, mono = true).apply {
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    /**
     * Запрет: причина и «Закрыть» во всю ширину. Иначе «Отмена» (контур) и «Удалить» (красная
     * заливка) — две равные колонки 56dp; подпись не влезает в половину — друг под другом,
     * «Удалить» сверху.
     */
    private fun buttons(): View = act.vbox(10).apply {
        setPadding(0, act.dp(6), 0, 0)
        val b = p.block?.let { t.s(it.res) }
        if (b != null) {
            blockText = act.label(b, 14f, C.MUTED).apply { contentDescription = b }
            addView(blockText)
        }
        cancelButton = button(t.s(if (b == null) R.string.cancel else R.string.close), null, C.TEXT, Cue.BACK) { dialog.dismiss() }
        if (b != null) { addView(cancelButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)); return@apply }
        val del = button(readyLabel, C.DANGER_FILL, Color.WHITE, cue = null) {
            val b = deleteButton
            if (b?.isEnabled == true) {
                Feedback.cue(b, if (p.viaRoot) Cue.COMMIT_ROOT else Cue.COMMIT)
                dialog.dismiss(); onDelete(fastBox?.isChecked == true)
            }
        }.apply {
            contentDescription = t.s(R.string.delete_btn_desc, readyLabel, p.name)
            if (pause) { isEnabled = false; alpha = 0.5f }
        }
        deleteButton = del
        addView(ButtonPair(act, del, cancelButton, listOf(readyLabel, t.s(R.string.delete_in, Fmt.count(9, t.locale)))),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    /** Кнопка 56dp: [bg] — заливка, null — контур FRAME. [cue] — звук касания (null — его даёт [onClick]). */
    private fun button(text: String, bg: Int?, fg: Int, cue: Cue?, onClick: () -> Unit): TextView =
        act.label(text, 15f, fg, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = act.dp(56)
            setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8))
            background = if (bg == null) act.pressable(Color.TRANSPARENT, C.FRAME) else StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), act.box(DANGER_PRESSED))
                addState(intArrayOf(android.R.attr.state_focused), act.box(bg, Color.WHITE))
                addState(intArrayOf(), act.box(bg))
            }
            isClickable = true; isFocusable = true
            isSoundEffectsEnabled = false
            setOnClickListener { if (cue != null) Feedback.cue(this, cue); onClick() }
        }

    companion object {
        /** Нажатая «Удалить»: темнее DANGER_FILL (белый текст на нём контрастнее). */
        private const val DANGER_PRESSED = 0xFF8C1D17.toInt()
    }
}

/**
 * Две кнопки равными колонками; если подпись [first] (с учётом [labels] — всех её вариантов)
 * или [second] не влезает в половину ширины — друг под другом, [first] сверху.
 * В строку: [second] слева, [first] справа.
 */
private class ButtonPair(ctx: Context, private val first: TextView, private val second: TextView,
                         private val labels: List<String>) : ViewGroup(ctx) {
    private val gap = ctx.dp(10)
    private var stacked = false

    init { addView(second); addView(first) }

    private fun need(v: TextView, texts: List<CharSequence>): Float =
        texts.maxOf { v.paint.measureText(it.toString()) } + v.totalPaddingLeft + v.totalPaddingRight

    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws)
        val half = (w - gap) / 2
        stacked = need(first, labels + first.text) > half || need(second, listOf(second.text)) > half
        val cw = if (stacked) w else half
        val spec = MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY)
        val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        first.measure(spec, free); second.measure(spec, free)
        val h = if (stacked) first.measuredHeight + gap + second.measuredHeight
                else maxOf(first.measuredHeight, second.measuredHeight)
        if (!stacked && first.measuredHeight != second.measuredHeight) {
            val eq = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            first.measure(spec, eq); second.measure(spec, eq)
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (stacked) {
            first.layout(0, 0, first.measuredWidth, first.measuredHeight)
            val y = first.measuredHeight + gap
            second.layout(0, y, second.measuredWidth, y + second.measuredHeight)
        } else {
            second.layout(0, 0, second.measuredWidth, second.measuredHeight)
            val x = width - first.measuredWidth
            first.layout(x, 0, x + first.measuredWidth, first.measuredHeight)
        }
    }
}
