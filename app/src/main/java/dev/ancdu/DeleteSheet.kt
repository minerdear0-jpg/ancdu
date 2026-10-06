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
import android.text.TextUtils
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
    val block: String?,
    val kind: Kind,
    /** Время скана для Kind.CACHE («dd.MM HH:mm»), иначе null. */
    val cacheTime: String?,
    /** Доступен быстрый путь root в обход FUSE (/storage/emulated/<n>/X через /data/media). */
    val fast: Boolean = false,
)

/** Лист подтверждения удаления: framework Dialog у нижнего края, без AndroidX. */
/** [onDelete] получает выбор «быстро через root» (false, если быстрый путь недоступен). */
class DeleteSheet(private val act: Activity, val p: DeletePreview, private val onDelete: (Boolean) -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
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
    /** Галочка «быстро через root» (null — быстрый путь недоступен). */
    var fastBox: CheckBox? = null
        private set

    private val hardlink = !p.dir && p.flags and F_HLDUP != 0
    private val pause = p.block == null && DeletePolicy.needsPause(p.viaRoot, p.owner != null, p.disk)
    private val readyLabel = if (hardlink) "Удалить" else "Удалить ${Fmt.size(p.disk)}"
    private var enableAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            val b = deleteButton ?: return
            val left = enableAt - SystemClock.uptimeMillis()
            if (left > 0) {
                val s = (left + 999) / 1000
                b.text = "Удалить через $s…"
                b.contentDescription = "Удалить, станет доступно через $s с"
                ui.postDelayed(this, minOf(left, 100))
            } else {
                b.isEnabled = true
                b.alpha = 1f
                b.text = readyLabel
                b.contentDescription = "$readyLabel, «${p.name}»"
                b.announceForAccessibility("Кнопка «Удалить» доступна")
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
        dialog.setOnDismissListener { ui.removeCallbacks(tick) }
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
            setColor(C.SURFACE); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        setPadding(act.dp(20), act.dp(20), act.dp(20), act.dp(16))

        val title = if (p.block == null) "Удалить «${p.name}»?" else "«${p.name}»"
        addView(act.hbox(8).apply {
            addView(act.label(title, 18f, C.TEXT, bold = true).apply {
                setSingleLine(true); ellipsize = TextUtils.TruncateAt.MIDDLE
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (p.viaRoot) addView(act.label("КАК ROOT", 11f, Color.WHITE, mono = true, bold = true).apply {
                setPadding(act.dp(8), act.dp(3), act.dp(8), act.dp(3))
                background = rounded(C.DANGER, act.dp(10).toFloat())
                contentDescription = "удаление с правами root"
            })
        })
        addView(act.label(p.path, 12f, C.MUTED, mono = true).apply {
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.MIDDLE
            contentDescription = "путь: ${p.path}"
        })
        p.owner?.let { addView(ownerRow(it)) }
        addView(sizeLine())
        if (p.dir && p.top.isNotEmpty()) addView(children())
        if (hardlink) addView(act.label("жёсткая ссылка — место может не освободиться", 13f, C.WARN))
        if (p.kind == Kind.INDEX) addView(act.label("размер по индексу, приблизительно", 13f, C.MUTED))
        if (p.cacheTime != null) addView(act.label("размеры по скану от ${p.cacheTime}", 13f, C.MUTED))
        if (p.block == null && p.fast) addView(fastRow())
        if (p.block == null) addView(act.label("Без корзины. Отменить нельзя.", 14f, C.DANGER))
        addView(buttons())
    }

    /** «быстро через root»: удаление через /data/media/<n>/ под su, затем пересканирование галереи. */
    private fun fastRow(): View = act.vbox(2).apply {
        val note = act.label("галерея обновится через несколько секунд", 12f, C.MUTED)
        val box = CheckBox(act).apply {
            text = "быстро через root (в обход FUSE)"
            textSize = 14f
            setTextColor(C.TEXT)
            minHeight = act.dp(44)
            isChecked = DeletePolicy.fastByDefault(p.items)
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
        ownerText = act.label("$name · данные приложения", 14f, C.TEXT).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        addView(ownerText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        contentDescription = "владелец: $name, данные приложения"
    }

    /** Главное число листа; apparent — приглушённо рядом, если отличается. */
    private fun sizeLine(): View = act.hbox(10).apply {
        isBaselineAligned = true
        gravity = Gravity.BOTTOM
        val main = Fmt.size(p.disk) + if (p.dir) " · ${Fmt.count(p.items)} эл." else ""
        addView(act.label(main, 18f, C.TEXT, mono = true, bold = true).apply { setSingleLine(true) })
        if (p.apparent != p.disk) addView(act.label("apparent ${Fmt.size(p.apparent)}", 13f, C.MUTED, mono = true)
            .apply { setSingleLine(true); ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "размер на диске ${Fmt.size(p.disk)}" +
            (if (p.dir) ", ${Fmt.count(p.items)} элементов" else "") +
            (if (p.apparent != p.disk) ", apparent ${Fmt.size(p.apparent)}" else "")
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
                    addView(View(act).apply { setBackgroundColor(if (nm.endsWith("/")) C.ACCENT else C.FILE) },
                        FrameLayout.LayoutParams(w, act.dp(8)))
                }
                addView(frame, LinearLayout.LayoutParams(barMax, act.dp(8)))
                addView(act.label(nm, 13f, C.TEXT, mono = true).apply {
                    setSingleLine(true); ellipsize = TextUtils.TruncateAt.MIDDLE
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(act.label(Fmt.size(size), 13f, C.MUTED, mono = true))
                contentDescription = "$nm, ${Fmt.size(size)}"
            })
        }
        if (p.more > 0) addView(act.label("…ещё ${Fmt.count(p.more.toLong())}", 12f, C.MUTED, mono = true).apply {
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun buttons(): View = act.hbox(10).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        setPadding(0, act.dp(6), 0, 0)
        val b = p.block
        if (b != null) {
            blockText = act.label(b, 13f, C.MUTED).apply { contentDescription = b }
            addView(blockText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        cancelButton = button(if (b == null) "Отмена" else "Закрыть", C.CHIP, C.TEXT) { dialog.dismiss() }
        addView(cancelButton)
        if (b == null) {
            deleteButton = button(readyLabel, C.DANGER, Color.WHITE) {
                if (deleteButton?.isEnabled == true) { dialog.dismiss(); onDelete(fastBox?.isChecked == true) }
            }.apply {
                contentDescription = "$readyLabel, «${p.name}»"
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
            background = rounded(bg, act.dp(10).toFloat())
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }

    companion object {
        const val PAUSE_MS = 1500L
    }
}
