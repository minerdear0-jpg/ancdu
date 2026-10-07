package dev.ancdu

import android.app.Activity
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
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
import java.io.FileInputStream
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
)

/**
 * Карточка быстрого просмотра файла: лист у нижнего края (как лист удаления), не выше 85%
 * экрана; тело прокручивается, кнопки закреплены. Строка вида, имя, папка, место под превью
 * (по расширению, до чтения — без скачка), сведения «размер · подробности · время». Превью и
 * время читаются на [exec] (не Holder.io — удаления не ждут), отменяются при закрытии; не пришло
 * за [Peek.TIMEOUT_MS] — «превью недоступно». [onDelete] — «Удалить…»: карточка уже закрыта.
 */
class QuickLook(private val act: Activity, val info: QuickLookInfo, private val onClose: () -> Unit = {},
                private val onDelete: () -> Unit) {
    private val t: Txt = act.tx
    private val ui = Handler(Looper.getMainLooper())
    val dialog = Dialog(act, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
    private val ext = Ellipsis.ext(info.name)
    private val mime = ext?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase(Locale.ROOT)) }
    /** Вид превью; у путей только для root — NONE (содержимое не читается). */
    val kind: PeekKind = if (info.rootOnly) PeekKind.NONE else Peek.kind(ext, mime)

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
    /** Место под превью (null — у этого вида превью нет). */
    var box: FrameLayout? = null
        private set
    /** Для тестов: показанный текст превью / «превью недоступно» (null — ещё нет). */
    var previewText: TextView? = null
        private set
    var noPreview: TextView? = null
        private set

    private val signal = CancellationSignal()
    private var closed = false
    /** Превью показано или отказано (по итогу или по таймауту): поздний итог отбрасывается. */
    private var settled = false
    private var details: String? = null
    private var mtime: String? = null
    private val timeout = Runnable { if (!settled) { signal.cancel(); fail() } }

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(build())
        dialog.bottomSheet()
        // TalkBack: заголовок окна — имя файла.
        dialog.setTitle(Bidi.visible(info.name))
        // «Назад» и тап мимо карточки — tock; закрытие кнопкой «Удалить…» — без него (откроется лист).
        dialog.setOnCancelListener { Feedback.cue(deleteButton, Cue.BACK) }
        dialog.setOnDismissListener {
            closed = true
            signal.cancel()
            ui.removeCallbacksAndMessages(null)
            onClose()
        }
    }

    fun show() {
        dialog.show()
        load()
    }

    fun dismiss() = dialog.dismiss()

    private fun build(): View = MaxHeightBox(act, 0.85f).apply {
        background = Brackets(act, C.PANEL, bottom = false)
        setPadding(act.dp(20), act.dp(20), act.dp(20), act.dp(16))
        addView(ScrollView(act).apply { addView(body()) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1f))
        addView(actions(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = act.dp(16) })
    }

    private fun body(): View = act.vbox(10).apply {
        typeText = act.caps(Peek.typeLine(t, kind, ext, mime, text = false)).apply { isAllCaps = false }
        addView(typeText)
        nameText = act.label(Bidi.visible(info.name), 16f, C.TEXT, mono = true, bold = true).apply { setTextIsSelectable(true) }
        addView(nameText)
        addView(act.label(Bidi.visible(info.parent), 12f, C.MUTED, mono = true).apply {
            setTextIsSelectable(true)
            contentDescription = t.s(R.string.path_desc, info.parent)
        })
        info.owner?.let { addView(act.ownerRow(it, t)) }
        if (kind != PeekKind.NONE) {
            box = FrameLayout(act).apply {
                background = act.box(C.BG, C.LINE)
                val one = act.dp(1)
                setPadding(one, one, one, one)
            }
            addView(box, LinearLayout.LayoutParams(MATCH_PARENT, act.dp(BOX_DP)))
        }
        metaText = act.label("", 13f, C.TEXT, mono = true)
        addView(metaText)
        renderMeta()
    }

    /** «ВЫБРАТЬ» (до задачи 23 скрыта) и «УДАЛИТЬ…» — равными колонками; одна — во всю ширину. */
    private fun actions(): View = act.hbox(10).apply {
        selectButton = button(t.s(R.string.ql_select), C.TEXT, C.FRAME).apply { visibility = View.GONE }
        addView(selectButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        deleteButton = button(t.s(R.string.ql_delete), C.DANGER_TEXT, C.DANGER_TEXT).apply {
            // Звук даёт открывшийся лист удаления (arm).
            setOnClickListener { dialog.dismiss(); onDelete() }
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

    private fun renderMeta() {
        metaText.text = Peek.meta(listOf(Fmt.size(info.size, t), details, if (info.rootOnly) null else mtime ?: "—"))
    }

    /** Время изменения и превью — на [exec]; итог — на главном потоке, если карточка ещё открыта. */
    private fun load() {
        if (info.rootOnly) return
        val f = File(info.path)
        exec.execute {
            val m = f.lastModified()
            ui.post { if (!closed) { mtime = Peek.mtime(t, m); renderMeta() } }
        }
        if (box == null) return
        ui.postDelayed(timeout, Peek.TIMEOUT_MS)
        val px = Size(maxOf(1, act.resources.displayMetrics.widthPixels - act.dp(42)), act.dp(BOX_DP - 2))
        exec.execute {
            val r = try { preview(f, px) } catch (e: Exception) {
                Log.i("ancdu", "quick look: no preview (${e.javaClass.simpleName})")
                null
            }
            ui.post { deliver(r) }
        }
    }

    private sealed class Preview {
        class Image(val bmp: Bitmap, val details: String?) : Preview()
        class Text(val text: String, val note: String?) : Preview()
        class Apk(val icon: Drawable?, val label: String, val line: String, val installed: String?) : Preview()
    }

    /** На [exec]. null — превью нет (не прочитался, двоичный, отменён). */
    private fun preview(f: File, px: Size): Preview? = when (kind) {
        PeekKind.IMAGE -> {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            val dims = if (o.outWidth > 0 && o.outHeight > 0) Peek.dims(o.outWidth, o.outHeight) else null
            Preview.Image(ThumbnailUtils.createImageThumbnail(f, px, signal), dims)
        }
        PeekKind.VIDEO -> {
            val bmp = ThumbnailUtils.createVideoThumbnail(f, px, signal)
            val dur = MediaMetadataRetriever().run {
                try {
                    setDataSource(f.path)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { Peek.duration(it) }
                } finally { release() }
            }
            Preview.Image(bmp, dur)
        }
        PeekKind.TEXT -> {
            val b = ByteArray(Peek.READ)
            var n = 0
            FileInputStream(f).use { s ->
                while (n < b.size) { val k = s.read(b, n, b.size - n); if (k < 0) break; n += k }
            }
            Peek.sniff(b, n)?.let { Preview.Text(it, Peek.textNote(t, f.length())) }
        }
        PeekKind.APK -> {
            val pm = act.packageManager
            pm.getPackageArchiveInfo(f.path, 0)?.let { pi ->
                val ai = pi.applicationInfo
                ai?.sourceDir = f.path; ai?.publicSourceDir = f.path
                val installed = try {
                    pm.getPackageInfo(pi.packageName, 0).versionName?.let { t.s(R.string.ql_installed, it) }
                } catch (e: PackageManager.NameNotFoundException) { null }
                Preview.Apk(ai?.loadIcon(pm), ai?.loadLabel(pm)?.toString() ?: pi.packageName,
                    listOfNotNull(pi.packageName, pi.versionName).joinToString(" · "), installed)
            }
        }
        PeekKind.NONE -> null
    }

    private fun deliver(r: Preview?) {
        if (closed || settled) return
        settled = true
        ui.removeCallbacks(timeout)
        val b = box ?: return
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
                    setImageDrawable(it)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(act.dp(40), act.dp(40))) }
                addView(act.vbox(2).apply {
                    addView(act.label(r.label, 15f, C.TEXT, bold = true))
                    addView(act.label(r.line, 12f, C.MUTED, mono = true))
                    r.installed?.let { addView(act.label(it, 12f, C.MUTED, mono = true)) }
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    /** «превью недоступно» по центру места превью. */
    private fun fail() {
        settled = true
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
