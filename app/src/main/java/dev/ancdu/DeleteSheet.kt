package dev.ancdu

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
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
import android.widget.CheckBox
import android.widget.FrameLayout
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
    /** Метка безопасности самого объекта (у группы — null: метки у строк [top]). */
    val tag: TagText? = null,
    /** Метки строк [top] по порядку (короче [top] — у остальных нет). */
    val topTags: List<TagText?> = emptyList(),
    /** Файлы строк [top] — для квадрата превью и карточки (null — каталог; короче [top] — у остальных нет). */
    val topPeek: List<QuickLookInfo?> = emptyList(),
    /** Каталоги строк [top]: до 4 крупнейших картинок и видео внутри ([ContactSheet]). */
    val topContact: List<List<QuickLookInfo>> = emptyList(),
    /** Сам файл листа одного файла — место превью 120dp (null — каталог, группа). */
    val selfPeek: QuickLookInfo? = null,
)

/** Лист подтверждения удаления: framework Dialog у нижнего края, без AndroidX. */
/**
 * [onDelete] получает выбор «быстро через root» (false, если быстрый путь недоступен); [onClose] — лист закрыт любым путём.
 * [group] — лист ГРУППЫ ([p] — сводка, GroupSheet.preview): заголовок «Удалить 3 объекта?», путь папки,
 * крупнейшие пять, владельцы, ярус по сумме. null — один объект, ровно прежний лист.
 * Превью: квадраты 40dp у строк-файлов, «контактный лист» у строк-каталогов, место 120dp у листа
 * одного файла — если лист открыт не из карточки ([fromCard]). Тап по строке-файлу или квадрату —
 * карточка ПОВЕРХ листа ([card]); «Назад» возвращает к листу, отсчёт идёт дальше.
 */
class DeleteSheet(private val act: Activity, val p: DeletePreview, private val onClose: () -> Unit = {},
                  val group: GroupInfo? = null, val fromCard: Boolean = false, private val onDelete: (Boolean) -> Unit) {
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
    /** Предупреждение над кнопками (null — удаление запрещено). */
    private var warnText: TextView? = null
    /** Предупреждение «⚠ Удаление от root · без корзины…» (null — удаление не через su или запрещено). */
    val rootText: TextView? get() = warnText?.takeIf { tier.root }
    /** Для тестов: показанные числа обратного отсчёта по порядку (перезапуск продолжает список). */
    val countdownShown = ArrayList<Long>()

    /** Для тестов: строка «данные 2 приложений: …» листа группы (null — владельцев меньше двух). */
    var ownersText: TextView? = null
        private set
    /** Для тестов: тексты показанных меток — объекта, затем строк детей. */
    val tagTexts = ArrayList<String>()
    /** Для тестов: строка «N уже нет на диске» листа группы. */
    var goneText: TextView? = null
        private set

    /** Превью листа; закрытие листа отменяет загрузку и освобождает битмапы. */
    internal val thumbs = SheetThumbs(act)
    /** Карточка, открытая поверх листа (null — нет). */
    var card: QuickLook? = null
        private set
    /** Для тестов: место превью 120dp листа одного файла (null — нет). */
    var selfBox: FrameLayout? = null
        private set
    /** Для тестов: строки детей по порядку и квадраты превью (строка без квадрата — null). */
    val childRows = ArrayList<View>()
    val childThumbs = ArrayList<FrameLayout?>()
    /** Для тестов: «контактные листы» строк-каталогов (строка без него — null). */
    val contactRows = ArrayList<LinearLayout?>()

    private val hardlink = group?.hardlink ?: (!p.dir && p.flags and F_HLDUP != 0)
    /** Данные другого приложения: своё (тест, свой кэш) «чужим» не считается. */
    private val owned = p.owner != null && p.owner != act.packageName
    /**
     * Ярус по текущему выбору: отмеченное «быстро через root» удаляет через su — ярус root
     * (пауза 2,5 с, ARM_ROOT, предупреждение root), как у root-сессии.
     */
    val tier: DeleteTier get() = group?.tier(p.viaRoot, fastBox?.isChecked == true)
        ?: DeletePolicy.tier(p.viaRoot, fastBox?.isChecked == true, owned, p.disk)
    private val readyLabel = if (hardlink) t.s(R.string.delete_btn) else t.s(R.string.delete_btn_size, Fmt.size(p.disk, t))
    private var enableAt = 0L
    /** Последнее показанное число ТЕКУЩЕГО отсчёта (null — отсчёт только начался). */
    private var lastShown: Long? = null

    private val tick = object : Runnable {
        override fun run() {
            val b = deleteButton ?: return
            val left = enableAt - SystemClock.uptimeMillis()
            if (left > 0) {
                val s = (left + 999) / 1000
                // Новое число — count; первое совпадает с arm и молчит.
                FeedbackPolicy.countCue(lastShown, s)?.let { Feedback.cue(b, it) }
                if (lastShown != s) { countdownShown += s; lastShown = s }
                val n = Fmt.count(s, t.locale)
                b.text = t.s(R.string.delete_in, n)
                b.contentDescription = t.s(R.string.delete_in_desc, n + Fmt.NBSP + t.s(R.string.unit_s))
                ui.postDelayed(this, minOf(left, 100))
            } else {
                enable(b)
            }
        }
    }

    private fun enable(b: TextView) {
        val was = b.isEnabled
        b.isEnabled = true
        b.alpha = 1f
        b.text = readyLabel
        b.contentDescription = t.s(R.string.delete_btn_desc, readyLabel, p.name)
        if (was) return
        Feedback.cue(b, Cue.READY)
        if (b.a11yOn()) b.announceForAccessibility(t.s(R.string.delete_ready))
    }

    /**
     * Отсчёт паузы ТЕКУЩЕГО яруса с начала: кнопка выключена до его конца; пауза 0 — включается
     * сразу. Прежний отсчёт снимается.
     */
    private fun restartCountdown() {
        val b = deleteButton ?: return
        ui.removeCallbacks(tick)
        lastShown = null
        val ms = tier.pauseMs
        if (ms <= 0) { enable(b); return }
        b.isEnabled = false
        b.alpha = 0.5f
        enableAt = SystemClock.uptimeMillis() + ms
        tick.run()
    }

    /** Галочка «быстро через root» сменилась: ярус, предупреждение и отсчёт — заново. */
    private fun fastToggled(on: Boolean) {
        warnText?.text = warning()
        if (!dialog.isShowing) return
        fastBox?.let { Feedback.cue(it, if (on && tier.root) Cue.ARM_ROOT else Cue.TAP) }
        restartCountdown()
    }

    private fun warning(): String = "⚠ " + t.s(if (tier.root) R.string.root_no_trash else R.string.no_trash)

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(build())
        // Въезд снизу 160 мс, уход 120 мс; затемнение 60% — вместе с окном. Без анимаций — 0.
        dialog.bottomSheet()
        dialog.setOnDismissListener {
            ui.removeCallbacks(tick)
            card?.dismiss(); card = null
            thumbs.close()
            onClose()
        }
    }

    fun show() {
        dialog.show()
        cancelButton.requestFocus()
        Feedback.cue(cancelButton, FeedbackPolicy.sheetOpen(blocked = p.block != null, viaRoot = tier.root))
        if (p.block == null && tier.pauseMs > 0) restartCountdown()
    }

    fun dismiss() = dialog.dismiss()

    /** Activity на паузе: плеер карточки поверх листа — на паузу. */
    fun pause() { card?.pause() }

    /** Карточка файла [info] поверх листа: без «УДАЛИТЬ…»/«ВЫБРАТЬ»; закрыта — лист как был. */
    fun openCard(info: QuickLookInfo) {
        if (!dialog.isShowing) return
        card?.dismiss()
        card = QuickLook(act, info, onClose = { }, fromSheet = true) {}.also { it.show() }
    }

    /** Строка или квадрат открывает карточку [info]: касание — TAP, TalkBack — «Быстрый просмотр». */
    private fun View.peeks(info: QuickLookInfo) {
        isClickable = true; isFocusable = true
        isSoundEffectsEnabled = false
        if (background == null) background = act.pressable(Color.TRANSPARENT)
        setOnClickListener { Feedback.cue(this, Cue.TAP); openCard(info) }
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, n: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, n)
                n.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, t.s(R.string.sheet_peek)))
            }
        }
    }

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
        // Предупреждение — сразу над кнопками; через su (root-сессия или «быстро через root») —
        // одной строкой с ним.
        if (p.block == null) addView(act.label(warning(), 14f, C.DANGER_TEXT, bold = true).also { warnText = it })
        addView(buttons())
    }

    private fun body(): View = act.vbox(10).apply {
        val title = if (group != null) (if (p.block == null) GroupSheet.title(t, group.count) else GroupSheet.blockedTitle(t, group.count))
            else t.s(if (p.block == null) R.string.sheet_title else R.string.sheet_title_blocked, Bidi.visible(p.name))
        addView(act.hbox(8).apply {
            addView(act.label(title, 22f, C.TEXT, bold = true).apply {
                setSingleLine(true); ellipsize = TextUtils.TruncateAt.MIDDLE
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            p.tag?.let { addView(tagLabel(it)) }
            if (p.viaRoot) addView(act.caps(t.s(R.string.as_root), C.TEXT).apply {
                setPadding(act.dp(8), act.dp(4), act.dp(8), act.dp(4))
                background = act.box(Color.TRANSPARENT, C.FRAME)
                contentDescription = t.s(R.string.as_root_desc)
            })
        })
        // Путь целиком, с переносами.
        addView(act.label(Bidi.visible(p.path), 12f, C.MUTED, mono = true).apply {
            contentDescription = t.s(R.string.path_desc, p.path)
        })
        if (group != null && group.owners.size > 1) addView(ownersRow(group.owners))
        // Один владелец не у всех: «данные WhatsApp: 2 из 3»; у всех — прежний ownerRow (p.owner).
        else if (group != null && group.owners.size == 1 && p.owner == null)
            addView(ownersRow(group.owners, GroupSheet.ownerPart(t, label(group.owners[0]), group.ownerItems, group.count)))
        else p.owner?.let { addView(act.ownerRow(it, t).also { r -> ownerText = r.getChildAt(r.childCount - 1) as TextView }) }
        addView(sizeLine())
        if ((p.dir || group != null) && p.top.isNotEmpty()) addView(children())
        if (group != null && group.gone > 0) addView(act.label(GroupSheet.gone(t, group.gone), 13f, C.MUTED).also { goneText = it })
        if (hardlink) addView(act.label(t.s(R.string.hardlink), 13f, C.AMBER))
        if (p.kind == Kind.INDEX) addView(act.label(t.s(R.string.index_approx), 13f, C.MUTED))
        if (p.cacheTime != null) addView(act.label(t.s(R.string.cache_sizes, p.cacheTime), 13f, C.MUTED))
        if (p.block == null && p.fast) addView(fastRow())
        // Один файл, лист не из карточки: превью 120dp — над предупреждением.
        p.selfPeek?.let { info ->
            val kind = thumbs.kindOf(info)
            if (!SheetPeek.selfBox(fromCard, group != null, p.dir, kind)) return@let
            thumbs.box(info, kind)?.let { b ->
                selfBox = b
                b.peeks(info)
                b.contentDescription = Bidi.visible(info.name)
                b.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                addView(b, LinearLayout.LayoutParams(MATCH_PARENT, act.dp(SheetPeek.BOX_DP)))
            }
        }
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
            isSoundEffectsEnabled = false
            setOnCheckedChangeListener { _, on ->
                note.visibility = if (on) View.VISIBLE else View.GONE
                fastToggled(on)
            }
        }
        note.visibility = if (box.isChecked) View.VISIBLE else View.GONE
        fastBox = box
        addView(box)
        addView(note)
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

    /** Метка приложения [pkg] (одной строкой, bidi видимыми); нет пакета — его имя. */
    private fun label(pkg: String): String = AppLabels.get(act, pkg) ?: pkg

    /**
     * Владельцы группы: до трёх значков 20dp и [text] (по умолчанию «данные 5 приложений: A, B, C +2» —
     * «+N» только в тексте, один раз).
     */
    private fun ownersRow(pkgs: List<String>, text: String = GroupSheet.owners(t, pkgs.map { label(it) })): View = act.hbox(8).apply {
        val pm = act.packageManager
        for (pkg in pkgs.take(GroupSheet.ICONS)) {
            val icon = try { pm.getApplicationInfo(pkg, 0).loadIcon(pm) }
                catch (e: android.content.pm.PackageManager.NameNotFoundException) { null } ?: continue
            addView(android.widget.ImageView(act).apply {
                setImageDrawable(icon)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(act.dp(20), act.dp(20)))
        }
        addView(act.label(text, 14f, C.TEXT).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            ownersText = this
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        contentDescription = text
    }

    /** Метка безопасности (моно 12sp, цвет метки); TalkBack читает её словом («кэш»). */
    private fun tagLabel(tag: TagText): TextView = act.label(tag.text, 12f, tag.color, mono = true).apply {
        maxLines = 1
        contentDescription = tag.desc.removePrefix(", ")
        tagTexts += tag.text
    }

    private fun children(): View = act.vbox(4).apply {
        val barMax = act.dp(56)
        for ((k, pair) in p.top.withIndex()) {
            val (raw, size) = pair
            val nm = Bidi.visible(raw)
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
                // Файл: квадрат превью 40dp слева от имени (место — по расширению, без скачка).
                val peek = p.topPeek.getOrNull(k)
                val square = peek?.let { thumbs.square(it) }
                childThumbs += square
                square?.let { addView(it, LinearLayout.LayoutParams(act.dp(SheetPeek.THUMB_DP), act.dp(SheetPeek.THUMB_DP))) }
                // До двух строк, дальше — многоточие посередине (конец имени и «/» видны).
                val name = MiddleLines(act, nm, 2).apply {
                    textSize = 13f; setTextColor(C.TEXT); typeface = Fonts.get(act, mono = true, bold = false)
                }
                // Группа: у каталога — приглушённо «· 1 204 эл.» под именем; у каталога с медиа —
                // до 4 квадратов крупнейших картинок и видео внутри.
                val n = group?.topItems?.getOrNull(k)?.takeIf { it >= 0 }
                val contact = p.topContact.getOrNull(k)?.let { contactRow(it) }
                contactRows += contact
                if (n == null && contact == null) addView(name, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                else addView(act.vbox(4).apply {
                    addView(name)
                    if (n != null) addView(act.label("· " + t.items(n), 12f, C.MUTED, mono = true))
                    contact?.let { addView(it) }
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                val tag = p.topTags.getOrNull(k)
                tag?.let { addView(tagLabel(it)) }
                addView(act.label(Fmt.size(size, t), 13f, C.MUTED, mono = true))
                contentDescription = "$nm, ${Fmt.size(size, t)}" + (if (n != null) ", " + t.items(n) else "") + (tag?.desc ?: "")
                peek?.let { peeks(it) }
                childRows += this
            })
        }
        if (p.more > 0) addView(act.label(t.s(R.string.more_children, Fmt.count(p.more.toLong(), t.locale)), 12f, C.MUTED, mono = true).apply {
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    /** До 4 квадратов медиа каталога в ряд; пусто — ряда нет. Квадрат открывает карточку файла. */
    private fun contactRow(files: List<QuickLookInfo>): LinearLayout? {
        val row = act.hbox(4)
        for (f in files) {
            val sq = thumbs.square(f) ?: continue
            sq.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            sq.contentDescription = Bidi.visible(f.name)
            sq.peeks(f)
            row.addView(sq, LinearLayout.LayoutParams(act.dp(SheetPeek.THUMB_DP), act.dp(SheetPeek.THUMB_DP)))
        }
        return row.takeIf { it.childCount > 0 }
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
                Feedback.cue(b, if (tier.root) Cue.COMMIT_ROOT else Cue.COMMIT)
                dialog.dismiss(); onDelete(fastBox?.isChecked == true)
            }
        }.apply {
            contentDescription = t.s(R.string.delete_btn_desc, readyLabel, p.name)
            if (tier.pauseMs > 0) { isEnabled = false; alpha = 0.5f }
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
 * Текст [full] не длиннее [lines] строк: не влезает — самая длинная форма с «…» посередине
 * ([Ellipsis.middleFit]), проверка — StaticLayout с параметрами этого TextView.
 */
private class MiddleLines(ctx: Context, private val full: String, private val lines: Int) : TextView(ctx) {
    private var fitW = -1

    init { text = full }

    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws) - compoundPaddingLeft - compoundPaddingRight
        if (MeasureSpec.getMode(ws) != MeasureSpec.UNSPECIFIED && w > 0 && w != fitW) {
            fitW = w
            val fit = Ellipsis.middleFit(full) { s ->
                StaticLayout.Builder.obtain(s, 0, s.length, paint, w).setIncludePad(includeFontPadding)
                    .setBreakStrategy(breakStrategy).setHyphenationFrequency(hyphenationFrequency)
                    .build().lineCount <= lines
            }
            if (fit != text.toString()) text = fit
        }
        super.onMeasure(ws, hs)
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
