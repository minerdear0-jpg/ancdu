package dev.ancdu

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Токены «ANCDU // TERMINAL» действующей палитры [p] ([Palette]): каждое обращение читает её
 * (не const — не встраивается). Красный — только опасность; свободное место — синее.
 * Каждая пара текст/фон — не ниже 4.5:1 (TokenContrastTest).
 */
object C {
    /** Действующая палитра: ставит [LangActivity] по ночному режиму своей конфигурации. */
    @Volatile var p: Palette = Palette.DARK

    val BG get() = p.bg
    val PANEL get() = p.panel
    /** Нажатое / выбранное. */
    val PANEL2 get() = p.panel2
    /** Разделители, дорожки. */
    val LINE get() = p.line
    /** Скобки, контуры полос и кнопок — не текст. */
    val FRAME get() = p.frame
    val TEXT get() = p.text
    val MUTED get() = p.muted
    /** Заливки: занято / каталоги / основное действие (не текст — для текста [AMBER_TEXT]). */
    val AMBER get() = p.amber
    /** Амберный текст. */
    val AMBER_TEXT get() = p.amberText
    /** Текст на амбере. */
    val INK get() = p.ink
    /** Свободно / файлы / сведения (полосы). */
    val BLUE get() = p.blue
    /** Мелкий синий текст. */
    val BLUE_HI get() = p.blueHi
    /** Свободная часть полосы раздела и её квадрат в легенде. */
    val FREE get() = p.free
    val DANGER_TEXT get() = p.dangerText
    /** Заливка кнопки удаления; текст на ней — белый. */
    val DANGER_FILL get() = p.dangerFill
    val DANGER_PRESSED get() = p.dangerPressed
    const val WHITE = 0xFFFFFFFF.toInt()
    /** Только «root ✓»: текст, фон, контур. */
    val OK get() = p.ok
    val OK_BG get() = p.okBg
    val OK_LINE get() = p.okLine
    /** Тёмная ступень амбера — только заливки сегментов яруса 0 (не текст). */
    val AMBER_DIM get() = p.amberDim
    val AUDIO_FILL get() = p.audioFill
    val FOCUS get() = p.focus
    val SCRIM get() = p.scrim
    val DIS_FILL get() = p.disFill
    val DIS_TEXT get() = p.disText
    val THUMB_LINE get() = p.thumbLine
    val STAGE_SCRIM get() = p.stageScrim
    val STAGE_TEXT get() = p.stageText
}

/** Шрифты из res/font: Exo 2 — подписи и текст, JetBrains Mono — только данные. Главный поток. */
object Fonts {
    private var faces: Array<Typeface>? = null

    fun get(ctx: Context, mono: Boolean, bold: Boolean): Typeface {
        val f = faces ?: arrayOf(R.font.exo2_regular, R.font.exo2_semibold, R.font.jetbrains_mono_regular,
            R.font.jetbrains_mono_bold).map { ctx.applicationContext.resources.getFont(it) }.toTypedArray()
            .also { faces = it }
        return f[(if (mono) 2 else 0) + (if (bold) 1 else 0)]
    }
}

/** Анимации разрешены (масштаб длительности аниматоров не 0). [override] — для тестов. */
object Motion {
    @Volatile var override: Boolean? = null
    fun on(): Boolean = override ?: ValueAnimator.areAnimatorsEnabled()

    /**
     * Переход экранов (enter, exit): открытие — новый проявляется и въезжает с 4% (150 мс),
     * закрытие — обратное (120 мс); экран под ним стоит на месте. Без анимаций — (0, 0).
     */
    fun transition(open: Boolean): Pair<Int, Int> = when {
        !on() -> 0 to 0
        open -> R.anim.screen_open to R.anim.screen_hold_open
        else -> R.anim.screen_hold_close to R.anim.screen_close
    }

    /** Анимации окна листа удаления; без анимаций — 0. */
    fun sheet(): Int = if (on()) R.style.SheetAnim else 0
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** Прямоугольник (углы 0): заливка [fill] и, если задан, контур 1dp [stroke]. */
fun Context.box(fill: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
    setColor(fill)
    if (stroke != null) setStroke(dp(1), stroke)
}

/** Фон касаемого элемента: нажатие и фокус — PANEL2. */
fun Context.pressable(fill: Int, stroke: Int? = null): Drawable =
    StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), box(C.PANEL2, stroke))
        addState(intArrayOf(android.R.attr.state_focused), box(C.PANEL2, stroke))
        addState(intArrayOf(), box(fill, stroke))
    }

fun Context.label(s: CharSequence, sp: Float = 15f, color: Int = C.TEXT, mono: Boolean = false,
                  bold: Boolean = false): TextView = TextView(this).apply {
    text = s
    textSize = sp
    setTextColor(color)
    typeface = Fonts.get(this@label, mono, bold)
}

/** Подпись Exo 600 ПРОПИСНЫМИ, 12sp (только для подписей до трёх слов). text остаётся как в ресурсе. */
fun Context.caps(s: CharSequence, color: Int = C.MUTED, sp: Float = 12f): TextView =
    label(s, sp, color, bold = true).apply { isAllCaps = true; letterSpacing = 0.08f }

/** Волосяная линия 1dp во всю ширину. */
fun LinearLayout.hairline(topDp: Int = 0, bottomDp: Int = 0) {
    addView(View(context).apply { setBackgroundColor(C.LINE) }, LinearLayout.LayoutParams(MATCH_PARENT, context.dp(1)).apply {
        topMargin = context.dp(topDp); bottomMargin = context.dp(bottomDp)
    })
}

private fun Context.spacer(w: Int, h: Int) = GradientDrawable().apply { setSize(w, h) }

fun Context.vbox(gapDp: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (gapDp > 0) { dividerDrawable = spacer(0, dp(gapDp)); showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE }
}

fun Context.hbox(gapDp: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    if (gapDp > 0) { dividerDrawable = spacer(dp(gapDp), 0); showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE }
}

/**
 * Включена ли доступность. Любая отправка AccessibilityEvent / announceForAccessibility при
 * выключенной обязана быть за этой проверкой: на Android 17 AccessibilityManager бросает
 * IllegalStateException("Accessibility off"). Не чистая функция (нужен системный сервис),
 * поэтому без JVM-теста.
 */
fun View.a11yOn(): Boolean =
    (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isEnabled == true

/** Метка кнопок action() и строк-переходов navRow() (тесты отличают их от прочих view). */
const val ACTION_TAG = "action"

/** Чип 44dp с контуром; выбранный — амберная заливка. */
fun Context.chip(text: String, selected: Boolean, onClick: () -> Unit): TextView =
    label(text, 13f, if (selected) C.INK else C.TEXT, mono = true, bold = selected).apply {
        gravity = Gravity.CENTER
        minHeight = dp(44); minWidth = dp(44)
        setPadding(dp(12), 0, dp(12), 0)
        background = if (selected) box(C.AMBER) else pressable(C.BG, C.FRAME)
        isClickable = true; isFocusable = true
        feedbackClick { onClick() }
    }

/**
 * Сегментированный переключатель 44dp: [options] в общем контуре, [selected] — выбранный
 * (isSelected). [amber] — выбранный амберный с тёмным текстом, иначе PANEL2. Сегменты — дети
 * в порядке [options].
 */
fun Context.segmented(options: List<String>, selected: Int, amber: Boolean, onPick: (Int) -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        // Контур — поверх сегментов (foreground): заливка выбранного его не закрывает.
        foreground = box(Color.TRANSPARENT, C.FRAME)
        val one = dp(1)
        dividerDrawable = GradientDrawable().apply { setColor(C.FRAME); setSize(one, 0) }
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        for ((i, o) in options.withIndex()) {
            val on = i == selected
            addView(caps(o, if (on) (if (amber) C.INK else C.TEXT) else C.MUTED).apply {
                gravity = Gravity.CENTER
                minHeight = dp(44); minWidth = dp(44)
                maxLines = 1
                setPadding(dp(12), 0, dp(12), 0)
                background = if (on) box(if (amber) C.AMBER else C.PANEL2) else pressable(Color.TRANSPARENT)
                isSelected = on
                isClickable = true; isFocusable = true
                feedbackClick { onPick(i) }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
    }

/** Кнопка 56dp: основная — амберная заливка, иначе контур FRAME. [cue] — звук касания. */
fun Context.action(title: String, sub: String?, primary: Boolean, cue: Cue = Cue.TAP, onClick: () -> Unit): LinearLayout =
    vbox().apply {
        tag = ACTION_TAG
        minimumHeight = dp(56)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = if (primary) StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), box(C.PANEL2, C.AMBER))
            addState(intArrayOf(), box(C.AMBER))
        } else pressable(Color.TRANSPARENT, C.FRAME)
        val fg = if (primary) ColorStateList(arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(C.AMBER_TEXT, C.INK)) else ColorStateList.valueOf(C.TEXT)
        addView(caps(title, sp = 14f).apply { setTextColor(fg); gravity = Gravity.CENTER; isDuplicateParentStateEnabled = true })
        if (sub != null) addView(label(sub, 12f, C.MUTED).apply {
            gravity = Gravity.CENTER
            if (primary) { setTextColor(fg); isDuplicateParentStateEnabled = true }
        })
        isClickable = true; isFocusable = true
        contentDescription = if (sub != null) "$title, $sub" else title
        feedbackClick(cue) { onClick() }
    }

/**
 * Строка-переход 48dp (без карточки): подпись [head] прописными, [body] (под крупный шрифт
 * переносится, не обрезается) и «›». [desc] — для TalkBack.
 */
fun Context.navRow(head: String, body: CharSequence, desc: String, bodyColor: Int = C.TEXT, mono: Boolean = true,
                   onClick: () -> Unit): LinearLayout = hbox(10).apply {
    tag = ACTION_TAG
    minimumHeight = dp(48)
    setPadding(0, dp(6), 0, dp(6))
    background = pressable(C.BG)
    addView(caps(head))
    addView(label(body, if (mono) 13f else 14f, bodyColor, mono = mono, bold = !mono),
        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    addView(label("›", 18f, C.MUTED))
    isClickable = true; isFocusable = true
    contentDescription = desc
    feedbackClick { onClick() }
}

/**
 * Владелец пути: значок 20dp и «<приложение> · данные приложения» (последний ребёнок — текст).
 * Пакета нет (удалён, другой профиль) — только имя пакета, без значка.
 */
fun Context.ownerRow(pkg: String, t: Txt): LinearLayout = hbox(8).apply {
    val pm = packageManager
    var name = pkg
    try {
        val ai = pm.getApplicationInfo(pkg, 0)
        // Метка — данные другого приложения: одной строкой, bidi видимыми.
        name = Bidi.label(ai.loadLabel(pm).toString())
        addView(ImageView(context).apply {
            setImageDrawable(ai.loadIcon(pm))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
    } catch (e: PackageManager.NameNotFoundException) {
        // без значка
    }
    addView(label(t.s(R.string.owner, name), 14f, C.TEXT).apply {
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    contentDescription = t.s(R.string.owner_desc, name)
}

/**
 * Метки приложений (для «app:<Метка>», владельцев в листе удаления): одной строкой, bidi видимыми;
 * кэш на процесс. Найденная метка живёт до конца процесса; «не установлен» — [LabelPolicy.MISS_TTL_MS]
 * (приложение могли поставить). [get] — блокирующий (io, открытие листа); с главного потока в
 * отрисовке — [cached] и [fetch] на своём потоке.
 */
object AppLabels {
    private class Entry(val label: String?, val at: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val exec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ancdu-labels").apply { isDaemon = true }
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun now() = android.os.SystemClock.elapsedRealtime()

    private fun fresh(e: Entry?): Boolean = e != null && !LabelPolicy.stale(e.label != null, now() - e.at)

    /** Метка уже известна (есть или «не установлен», не устарело). Любой поток. */
    fun known(pkg: String): Boolean = fresh(cache[pkg])

    /** Известная метка или null (не установлен или ещё не искали — см. [known]). Любой поток. */
    fun cached(pkg: String): String? = cache[pkg]?.takeIf(::fresh)?.label

    /** Метка установленного пакета [pkg] или null (не установлен, другой профиль). Любой поток, блокирует. */
    fun get(ctx: Context, pkg: String): String? {
        cache[pkg]?.takeIf(::fresh)?.let { return it.label }
        val pm = ctx.applicationContext.packageManager
        val label = try { Bidi.label(pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()).ifEmpty { null } }
            catch (e: PackageManager.NameNotFoundException) { null }
        cache[pkg] = Entry(label, now())
        return label
    }

    /** Главный поток: искать [pkg] на своём потоке; найдено — [onReady] на главном. Уже ищется — не повторяет. */
    fun fetch(ctx: Context, pkg: String, onReady: () -> Unit) {
        if (!pending.add(pkg)) return
        val app = ctx.applicationContext
        exec.execute {
            try { get(app, pkg) } finally { pending.remove(pkg) }
            main.post(onReady)
        }
    }

    /** Метка [tag] для показа ([Tag.resolve]) с меткой приложения, если она есть. Блокирует (см. [get]). */
    fun resolve(ctx: Context, t: Txt, tag: Tag): TagText = tag.resolve(t, tag.pkg?.let { get(ctx, it) })
}

fun Context.backButton(onClick: () -> Unit): TextView = label("‹", 28f).apply {
    gravity = Gravity.CENTER
    minWidth = dp(44); minHeight = dp(44)
    background = pressable(Color.TRANSPARENT)
    contentDescription = tx.s(R.string.back)
    isClickable = true; isFocusable = true
    feedbackClick(Cue.BACK) { onClick() }
}

/**
 * Окно листа у нижнего края во всю ширину: затемнение [C.SCRIM] (тёмная — 60%, светлая — 40%), въезд снизу (SheetAnim; без анимаций — 0).
 * Вызывать после setContentView.
 */
fun Dialog.bottomSheet() {
    setCanceledOnTouchOutside(true)
    window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(MATCH_PARENT, WRAP_CONTENT)
        setGravity(Gravity.BOTTOM)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // Затемнение окна — всегда чёрное: от SCRIM берётся сила (альфа); светлый INK почти чёрный.
        setDimAmount((C.SCRIM ushr 24) / 255f)
        setWindowAnimations(Motion.sheet())
    }
}

/**
 * Вертикальный LinearLayout не выше [fraction] высоты экрана: ребёнок с весом (прокрутка тела)
 * сжимается, остальные (кнопки) видны всегда.
 */
class MaxHeightBox(ctx: Context, private val fraction: Float) : LinearLayout(ctx) {
    init { orientation = VERTICAL }

    override fun onMeasure(ws: Int, hs: Int) {
        val cap = (resources.displayMetrics.heightPixels * fraction).toInt()
        val size = MeasureSpec.getSize(hs)
        val h = if (MeasureSpec.getMode(hs) == MeasureSpec.UNSPECIFIED) cap else minOf(size, cap)
        super.onMeasure(ws, MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))
    }
}

/**
 * Полосы системы под палитру экрана: фон BG; в светлой — тёмные значки (LIGHT_*_BARS), в тёмной —
 * светлые. Вызывать в onCreate после выбора палитры ([LangActivity]).
 */
fun Activity.systemBars() {
    window.statusBarColor = C.BG
    window.navigationBarColor = C.BG
    val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    window.insetsController?.setSystemBarsAppearance(if (C.p.dark) 0 else mask, mask)
}

/** [onDismiss] — после закрытия любым путём (кнопка, «назад», тап вне диалога). */
fun Activity.alert(title: String, msg: String, ok: String = getString(android.R.string.ok), cancel: String? = null,
                   onDismiss: (() -> Unit)? = null, onOk: () -> Unit = {}): AlertDialog =
    AlertDialog.Builder(this, R.style.Theme_Ancdu_Alert)
        .setTitle(title).setMessage(msg)
        .setPositiveButton(ok) { _, _ -> onOk() }
        .apply { if (cancel != null) setNegativeButton(cancel, null) }
        .apply { if (onDismiss != null) setOnDismissListener { onDismiss() } }
        .show()
