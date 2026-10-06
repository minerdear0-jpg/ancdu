package dev.ancdu

import android.app.Activity
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
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

    private val hardlink = !p.dir && p.flags and F_HLDUP != 0
    private val pause = p.block == null && DeletePolicy.needsPause(p.viaRoot, p.owner != null, p.disk)
    private val readyLabel = if (hardlink) t.s(R.string.delete_btn) else t.s(R.string.delete_btn_size, Fmt.size(p.disk, t))
    private var enableAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            val b = deleteButton ?: return
            val left = enableAt - SystemClock.uptimeMillis()
            if (left > 0) {
                val s = (left + 999) / 1000
                val n = Fmt.count(s, t.locale)
                b.text = t.s(R.string.delete_in, n)
                b.contentDescription = t.s(R.string.delete_in_desc, n + Fmt.NBSP + t.s(R.string.unit_s))
                ui.postDelayed(this, minOf(left, 100))
            } else {
                b.isEnabled = true
                b.alpha = 1f
                b.text = readyLabel
                b.contentDescription = t.s(R.string.delete_btn_desc, readyLabel, p.name)
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
        }
        dialog.setOnDismissListener { ui.removeCallbacks(tick); onClose() }
    }

    fun show() {
        dialog.show()
        cancelButton.requestFocus()
        if (pause) {
            enableAt = SystemClock.uptimeMillis() + PAUSE_MS
            tick.run()
        }
    }

    fun dismiss() = dialog.dismiss()

    private fun build(): View = act.vbox(10).apply {
        val r = act.dp(16).toFloat()
        background = GradientDrawable().apply {
            setColor(C.PANEL); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        setPadding(act.dp(20), act.dp(20), act.dp(20), act.dp(16))

        val title = t.s(if (p.block == null) R.string.sheet_title else R.string.sheet_title_blocked, p.name)
        addView(act.hbox(8).apply {
            addView(act.label(title, 18f, C.TEXT, bold = true).apply {
                setSingleLine(true); ellipsize = TextUtils.TruncateAt.MIDDLE
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (p.viaRoot) addView(act.label(t.s(R.string.as_root), 11f, Color.WHITE, mono = true, bold = true).apply {
                setPadding(act.dp(8), act.dp(3), act.dp(8), act.dp(3))
                background = act.box(C.DANGER_FILL)
                contentDescription = t.s(R.string.as_root_desc)
            })
        })
        addView(act.label(p.path, 12f, C.MUTED, mono = true).apply {
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.MIDDLE
            contentDescription = t.s(R.string.path_desc, p.path)
        })
        p.owner?.let { addView(ownerRow(it)) }
        addView(sizeLine())
        if (p.dir && p.top.isNotEmpty()) addView(children())
        if (hardlink) addView(act.label(t.s(R.string.hardlink), 13f, C.AMBER))
        if (p.kind == Kind.INDEX) addView(act.label(t.s(R.string.index_approx), 13f, C.MUTED))
        if (p.cacheTime != null) addView(act.label(t.s(R.string.cache_sizes, p.cacheTime), 13f, C.MUTED))
        if (p.block == null && p.fast) addView(fastRow())
        if (p.block == null) addView(act.label(t.s(R.string.no_trash), 14f, C.DANGER_TEXT))
        addView(buttons())
    }

    /** «быстро через root»: удаление через /data/media/<n>/ под su, затем очистка галереи в фоне (MediaClean). */
    private fun fastRow(): View = act.vbox(2).apply {
        val note = act.label(t.s(R.string.fast_note), 12f, C.MUTED)
        val box = CheckBox(act).apply {
            text = t.s(R.string.fast_box)
            textSize = 14f
            setTextColor(C.TEXT)
            minHeight = act.dp(44)
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
        text.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
                val frame = FrameLayout(act).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    addView(View(act).apply { setBackgroundColor(C.LINE) }, FrameLayout.LayoutParams(barMax, act.dp(8)))
                    val w = (barMax * ListMath.bar(size, p.disk)).toInt().coerceAtLeast(if (size > 0) 1 else 0)
                    addView(View(act).apply { setBackgroundColor(if (nm.endsWith("/")) C.AMBER else C.BLUE) },
                        FrameLayout.LayoutParams(w, act.dp(8)))
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

    private fun buttons(): View = act.hbox(10).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        setPadding(0, act.dp(6), 0, 0)
        val b = p.block?.let { t.s(it.res) }
        if (b != null) {
            blockText = act.label(b, 13f, C.MUTED).apply { contentDescription = b }
            addView(blockText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        cancelButton = button(t.s(if (b == null) R.string.cancel else R.string.close), C.PANEL2, C.TEXT) { dialog.dismiss() }
        addView(cancelButton)
        if (b == null) {
            deleteButton = button(readyLabel, C.DANGER_FILL, Color.WHITE) {
                if (deleteButton?.isEnabled == true) { dialog.dismiss(); onDelete(fastBox?.isChecked == true) }
            }.apply {
                contentDescription = t.s(R.string.delete_btn_desc, readyLabel, p.name)
                if (pause) { isEnabled = false; alpha = 0.5f }
            }
            addView(deleteButton)
        }
    }

    private fun button(text: String, bg: Int, fg: Int, onClick: () -> Unit): TextView =
        act.label(text, 15f, fg, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = act.dp(44); minWidth = act.dp(88)
            setPadding(act.dp(16), 0, act.dp(16), 0)
            background = act.box(bg)
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }

    companion object {
        const val PAUSE_MS = 1500L
    }
}
