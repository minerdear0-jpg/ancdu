package dev.ancdu

import android.app.Activity
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Что карточка знает без чтения файла: из дерева в памяти. */
class QuickLookInfo(
    val name: String,
    val path: String,
    /** Путь папки, где лежит файл. */
    val parent: String,
    /** Размер из дерева (в показанном режиме: на диске или видимый). */
    val size: Long,
    val owner: String?,
    /** Путь читает только root ([Peek.rootOnly]): только сведения, содержимое не читается. */
    val rootOnly: Boolean,
    /** Флаги узла в дереве (F_SYMLINK…): ссылка — без места под превью. */
    val flags: Int = 0,
)

/**
 * Карточка быстрого просмотра файла: лист у нижнего края (как лист удаления), не выше 85%
 * экрана; тело прокручивается, кнопки закреплены. Строка вида, имя, папка, место под превью
 * (по расширению, до чтения — без скачка), сведения «размер · подробности · время». Превью и
 * время читаются на [exec] (не Holder.io — удаления не ждут), отменяются при закрытии; не пришло
 * за [Peek.TIMEOUT_MS] — «превью недоступно». [onDelete] — «Удалить…»: карточка уже закрыта.
 * [onSelect] — «ВЫБРАТЬ» ([selectLabel]; null — кнопки нет): карточка уже закрыта, звук даёт выбор.
 * [fromSheet] — карточка открыта над листом удаления: без «УДАЛИТЬ…» и «ВЫБРАТЬ» (действие у листа),
 * одна «Закрыть»; «Назад» возвращает к листу.
 * Видео — раскадровка и мини-плеер ([VideoBox]).
 */
class QuickLook(private val act: Activity, val info: QuickLookInfo, private val onClose: () -> Unit = {},
                private val selectLabel: String? = null, private val onSelect: (() -> Unit)? = null,
                val fromSheet: Boolean = false, private val onDelete: () -> Unit) {
    private val t: Txt = act.tx
    private val ui = Handler(Looper.getMainLooper())
    val dialog = Dialog(act, R.style.Theme_Ancdu_Sheet)
    private val ext = Ellipsis.ext(info.name)
    private val mime = ext?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase(Locale.ROOT)) }
    /**
     * Вид превью; у путей только для root и у ссылок / не обычных узлов дерева — NONE (места нет).
     * FIFO и устройства дерево не отличает — их отсекает [PeekJob] («превью недоступно» сразу).
     */
    val kind: PeekKind = Peek.kindFor(ext, mime, info.rootOnly, info.flags)

    lateinit var typeText: TextView
        private set
    lateinit var nameText: TextView
        private set
    lateinit var metaText: TextView
        private set
    lateinit var selectButton: TextView
        private set
    lateinit var deleteButton: TextView
        private set
    /**
     * ✕ карточки над листом — вверху справа, 44dp (null — обычная карточка): не там, где под
     * карточкой «Удалить» листа.
     */
    var closeButton: View? = null
        private set
    /** Раскадровка и плеер видео (null — не видео или ещё не загружается). */
    internal var video: VideoBox? = null
        private set
    /** Место под превью (null — у этого вида превью нет). */
    var box: FrameLayout? = null
        private set
    /** Для тестов: показанный текст превью / «превью недоступно» (null — ещё нет). */
    var previewText: TextView? = null
        private set
    var noPreview: TextView? = null
        private set

    private val signal = CancellationSignal()
    /** Единственная связь фоновых задач с карточкой. */
    private val sink = Sink(this)
    private var closed = false
    /** Превью показано или отказано (по итогу или по таймауту): поздний итог отбрасывается. */
    private var settled = false
    private var details: String? = null
    internal var mtime: String? = null
    /** Для тестов: превью отказано по таймауту (а не сразу — FIFO, ошибка, двоичный). */
    var timedOut = false
        private set
    private val timeout = Runnable { if (!settled) { timedOut = true; signal.cancel(); fail() } }

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(build())
        dialog.bottomSheet()
        // TalkBack: заголовок окна — имя файла.
        dialog.setTitle(Bidi.visible(info.name))
        // «Назад» и тап мимо карточки — tock; закрытие кнопкой «Удалить…» — без него (откроется лист).
        dialog.setOnCancelListener { Feedback.cue(closeButton ?: deleteButton, Cue.BACK) }
        dialog.setOnDismissListener {
            closed = true
            sink.card = null
            signal.cancel()
            video?.close()
            ui.removeCallbacksAndMessages(null)
            onClose()
        }
    }

    fun show() {
        dialog.show()
        load()
    }

    fun dismiss() = dialog.dismiss()

    /** Activity уходит на паузу: плеер — на паузу, в фоне не играет. */
    fun pause() { video?.pause() }

    private fun build(): View = MaxHeightBox(act, 0.85f).apply {
        background = Brackets(act, C.PANEL, bottom = false)
        setPadding(act.dp(20), act.dp(20), act.dp(20), act.dp(16))
        addView(ScrollView(act).apply { addView(body()) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1f))
        // Над листом кнопок внизу нет (действие у листа; закрытие — ✕ вверху).
        if (SheetPeek.cardActions(fromSheet))
            addView(actions(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = act.dp(16) })
        else actions()
    }

    private fun body(): View = act.vbox(10).apply {
        typeText = act.caps(Peek.typeLine(t, kind, ext, mime, text = false)).apply { isAllCaps = false }
        if (SheetPeek.cardActions(fromSheet)) addView(typeText)
        else addView(act.hbox(8).apply {
            addView(typeText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            closeButton = PlayerIcon(act, PlayerIcon.CLOSE).apply {
                contentDescription = t.s(R.string.close)
                setOnClickListener { Feedback.cue(this, Cue.BACK); dialog.dismiss() }
            }
            addView(closeButton, LinearLayout.LayoutParams(act.dp(44), act.dp(44)))
        })
        nameText = act.label(Bidi.visible(info.name), 16f, C.TEXT, mono = true, bold = true).apply { setTextIsSelectable(true) }
        addView(nameText)
        addView(act.label(Bidi.visible(info.parent), 12f, C.MUTED, mono = true).apply {
            setTextIsSelectable(true)
            contentDescription = t.s(R.string.path_desc, info.parent)
        })
        info.owner?.let { addView(act.ownerRow(it, t)) }
        if (kind != PeekKind.NONE) {
            box = FrameLayout(act).apply {
                background = act.box(C.BG, C.THUMB_LINE)
                val one = act.dp(1)
                setPadding(one, one, one, one)
            }
            addView(box, LinearLayout.LayoutParams(MATCH_PARENT, act.dp(BOX_DP)))
        }
        metaText = act.label("", 13f, C.TEXT, mono = true)
        addView(metaText)
        renderMeta()
    }

    /** «ВЫБРАТЬ» и «УДАЛИТЬ…» — равными колонками; одна — во всю ширину. */
    private fun actions(): View = act.hbox(10).apply {
        selectButton = button(selectLabel ?: t.s(R.string.ql_select), C.TEXT, C.FRAME).apply {
            visibility = if (onSelect == null || fromSheet) View.GONE else View.VISIBLE
            setOnClickListener { dialog.dismiss(); onSelect?.invoke() }
        }
        addView(selectButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        deleteButton = button(t.s(R.string.ql_delete), C.DANGER_TEXT, C.DANGER_TEXT).apply {
            // Звук даёт открывшийся лист удаления (arm).
            setOnClickListener { dialog.dismiss(); onDelete() }
            if (!SheetPeek.cardActions(fromSheet)) visibility = View.GONE
        }
        addView(deleteButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun button(text: String, fg: Int, stroke: Int): TextView = act.label(text, 15f, fg, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = act.dp(56)
        setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8))
        background = act.pressable(Color.TRANSPARENT, stroke)
        isClickable = true; isFocusable = true
        isSoundEffectsEnabled = false
    }

    internal fun renderMeta() {
        metaText.text = Peek.meta(listOf(Fmt.size(info.size, t), details, if (info.rootOnly) null else mtime ?: "—"))
    }

    /**
     * Время изменения и превью — на [exec]. Задачи держат только [PeekJob] и [sink], не карточку и
     * не Activity: закрытие обнуляет sink, поздний итог отбрасывается. Error (OOM от огромного
     * значка) — тоже «превью недоступно», процесс не падает.
     */
    private fun load() {
        if (info.rootOnly) return
        val sink = sink
        val path = info.path
        val tt = t
        exec.execute {
            val m = try { File(path).lastModified() } catch (e: Throwable) { 0L }
            sink.post { it.mtime = Peek.mtime(tt, m); it.renderMeta() }
        }
        val b = box ?: return
        ui.postDelayed(timeout, Peek.TIMEOUT_MS)
        val w = maxOf(1, act.resources.displayMetrics.widthPixels - act.dp(42))
        if (kind == PeekKind.VIDEO) {
            val v = VideoBox(act, path, sink, t)
            video = v
            b.addView(v.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            val job = FrameJob(path, Size(w, act.dp(BOX_DP - 4 - Storyboard.STRIP_DP)), signal, sink)
            exec.execute { job.run() }
            return
        }
        val job = PeekJob(path, kind, Size(w, act.dp(BOX_DP - 2)),
            act.dp(40), act.applicationContext.packageManager, t, signal)
        exec.execute {
            val r = try { job.run() } catch (e: Throwable) {
                Log.i("ancdu", "quick look: no preview (${e.javaClass.simpleName})")
                null
            }
            val bmp = when (r) { is Preview.Image -> r.bmp; is Preview.Apk -> r.icon; else -> null }
            if (bmp == null) sink.post { it.deliver(r) } else sink.post(bmp) { it.deliver(r) }
        }
    }

    /** false — итог не нужен (закрыто, уже показано или отказано): его битмап освобождает вызвавший. */
    internal fun deliver(r: Preview?): Boolean {
        if (closed || settled) return false
        settled = true
        ui.removeCallbacks(timeout)
        val b = box ?: return false
        when (r) {
            null -> fail()
            is Preview.Image -> {
                details = r.details; renderMeta()
                b.addView(ImageView(act).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(r.bmp)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
            is Preview.Text -> {
                typeText.text = Peek.typeLine(t, kind, ext, mime, text = true)
                b.addView(act.vbox(4).apply {
                    setPadding(act.dp(8), act.dp(6), act.dp(8), act.dp(6))
                    previewText = act.label(r.text, 12f, C.TEXT, mono = true).apply { maxLines = Peek.TEXT_LINES }
                    addView(previewText, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
                    r.note?.let { addView(act.label(it, 11f, C.MUTED, mono = true).apply { maxLines = 1 }) }
                }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
            is Preview.Apk -> b.addView(act.hbox(12).apply {
                setPadding(act.dp(12), 0, act.dp(12), 0)
                r.icon?.let { addView(ImageView(act).apply {
                    setImageBitmap(it)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(act.dp(40), act.dp(40))) }
                addView(act.vbox(2).apply {
                    addView(act.label(r.label, 15f, C.TEXT, bold = true))
                    addView(act.label(r.line, 12f, C.MUTED, mono = true))
                    r.installed?.let { addView(act.label(it, 12f, C.MUTED, mono = true)) }
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        return true
    }

    /** Сведения видео («0:42 · 1920×1080») — в строку сведений. */
    internal fun videoMeta(text: String?) { details = text; renderMeta() }

    /** Первый кадр раскадровки показан: превью есть. */
    internal fun videoReady() {
        if (closed || settled) return
        settled = true
        ui.removeCallbacks(timeout)
    }

    internal fun videoFailed() { if (!closed && !settled) { ui.removeCallbacks(timeout); fail() } }

    /** «превью недоступно» по центру места превью. */
    private fun fail() {
        settled = true
        video?.close()
        val b = box ?: return
        if (noPreview != null) return
        noPreview = act.label(t.s(R.string.ql_no_preview), 13f, C.MUTED, mono = true).apply { gravity = Gravity.CENTER }
        b.removeAllViews()
        b.addView(noPreview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    companion object {
        /** Высота места превью. */
        private const val BOX_DP = 220

        /** Два фоновых потока превью: зависший декодер не задерживает следующую карточку и удаления. */
        val exec: ExecutorService = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "ancdu-peek").apply { isDaemon = true }
        }
    }
}

/** Итог задачи превью. */
internal sealed class Preview {
    class Image(val bmp: Bitmap, val details: String?) : Preview()
    class Text(val text: String, val note: String?) : Preview()
    /** [icon] — уже уменьшенный до 40dp. */
    class Apk(val icon: Bitmap?, val label: String, val line: String, val installed: String?) : Preview()
}

/** Связь фоновой задачи с карточкой: закрытие ([card] = null) обрывает её; итог — на главном потоке. */
internal class Sink(@Volatile var card: QuickLook?) {
    fun post(f: (QuickLook) -> Unit) { MAIN.post { card?.let(f) } }

    /** Как [post] с битмапом [bmp]: карточки уже нет или [f] его не взяла (false) — освобождается. */
    fun post(bmp: Bitmap, f: (QuickLook) -> Boolean) { MAIN.post { val c = card; if (c == null || !f(c)) bmp.recycle() } }

    private companion object { val MAIN = Handler(Looper.getMainLooper()) }
}

/** Всё, что нужно задаче превью: без карточки и без Activity (PackageManager — приложения). */
internal class PeekJob(val path: String, val kind: PeekKind, val px: Size, val iconPx: Int,
                       val pm: PackageManager, val t: Txt, val signal: CancellationSignal) {
    /** На [QuickLook.exec]. null — превью нет (не обычный файл, не прочитался, двоичный, отменён). */
    fun run(): Preview? {
        val real = regular(path, signal) ?: return null
        return when (kind) {
            PeekKind.IMAGE -> {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                // Только размеры (decode вернёт null); null у withRegular — не обычный файл.
                withRegular(real) { fd -> BitmapFactory.decodeFileDescriptor(fd, null, o); true } ?: return null
                val dims = if (o.outWidth > 0 && o.outHeight > 0) Peek.dims(o.outWidth, o.outHeight) else null
                Preview.Image(ThumbnailUtils.createImageThumbnail(File(real), px, signal), dims)
            }
            // Карточка показывает видео раскадровкой ([FrameJob]); здесь — кадр для листа, с того же
            // проверенного дескриптора (не по пути).
            PeekKind.VIDEO -> withRegular(real) { fd ->
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(fd)
                    if (signal.isCanceled) null
                    else r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, px.width, px.height)
                } finally { r.release() }
            }?.let { Preview.Image(it, null) }
            PeekKind.TEXT -> withRegular(real) { fd ->
                val b = ByteArray(Peek.READ)
                var n = 0
                while (n < b.size) { val k = Os.read(fd, b, n, b.size - n); if (k <= 0) break; n += k }
                Peek.sniff(b, n)?.let { Preview.Text(it, Peek.textNote(t, Os.fstat(fd).st_size)) }
            }
            PeekKind.APK -> pm.getPackageArchiveInfo(real, 0)?.let { pi ->
                val ai = pi.applicationInfo
                ai?.sourceDir = real; ai?.publicSourceDir = real
                val installed = try {
                    pm.getPackageInfo(pi.packageName, 0).versionName?.let { t.s(R.string.ql_installed, Bidi.label(it)) }
                } catch (e: PackageManager.NameNotFoundException) { null }
                if (signal.isCanceled) return null
                Preview.Apk(ai?.let { icon(it.loadIcon(pm)) }, Bidi.label(ai?.loadLabel(pm)?.toString() ?: pi.packageName),
                    Bidi.label(listOfNotNull(pi.packageName, pi.versionName).joinToString(" · ")), installed)
            }
            PeekKind.NONE -> null
        }
    }

    /**
     * Значок APK — в битмап [iconPx]×[iconPx]: огромный или нулевой собственный размер не
     * раздувает память карточки (абсурдный — без значка).
     */
    private fun icon(d: Drawable): Bitmap? {
        val w = d.intrinsicWidth; val h = d.intrinsicHeight
        if (w > MAX_ICON || h > MAX_ICON) return null
        return Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888).also {
            d.setBounds(0, 0, iconPx, iconPx)
            d.draw(Canvas(it))
        }
    }

    companion object {
        private const val MAX_ICON = 4096

        /**
         * Путь, который можно читать ([Peek.regularTarget]: обычный файл или ссылка на него), или
         * null; отменено ([signal]) — тоже null.
         */
        fun regular(path: String, signal: CancellationSignal?): String? {
            if (signal?.isCanceled == true) return null
            val real = Peek.regularTarget(path, ::nodeType) { p ->
                try { File(p).canonicalPath } catch (e: IOException) { null }
            } ?: return null
            return if (signal?.isCanceled == true) null else real
        }

        private fun nodeType(p: String): Peek.NodeType = try {
            val m = Os.lstat(p).st_mode
            when {
                OsConstants.S_ISREG(m) -> Peek.NodeType.REGULAR
                OsConstants.S_ISLNK(m) -> Peek.NodeType.LINK
                else -> Peek.NodeType.OTHER
            }
        } catch (e: ErrnoException) { Peek.NodeType.MISSING }

        /**
         * [real] открывается без ожидания (O_NONBLOCK) и без перехода по ссылке; fstat того же
         * дескриптора — обычный файл (подмену на FIFO между проверкой и открытием видно), иначе null.
         */
        fun <T> withRegular(real: String, use: (FileDescriptor) -> T): T? {
            val fd = Os.open(real, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or
                OsConstants.O_CLOEXEC, 0)
            try {
                if (!OsConstants.S_ISREG(Os.fstat(fd).st_mode)) return null
                return use(fd)
            } finally { Os.close(fd) }
        }
    }
}
