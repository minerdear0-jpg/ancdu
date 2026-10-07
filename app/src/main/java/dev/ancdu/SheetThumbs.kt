package dev.ancdu

import android.app.Activity
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.util.Locale

/**
 * Превью в листе удаления: квадраты строк [SheetPeek.THUMB_DP] и место [SheetPeek.BOX_DP] листа
 * одного файла. Загрузка — [PeekJob] (проверка обычного файла, O_NOFOLLOW + fstat, Throwable) на
 * [QuickLook.exec], по одной задаче за раз: второй поток остаётся карточке, открытой поверх листа.
 * У каждой задачи [Peek.TIMEOUT_MS]; не успела или не вышло — в квадрате остаётся знак вида
 * («IMG»/«VID»/«APK»), в месте — «превью недоступно». [close] (лист закрыт) отменяет всё и
 * освобождает битмапы. Фоновые задачи держат только [Link], не лист и не Activity.
 */
internal class SheetThumbs(private val act: Activity) {
    private val t: Txt = act.tx
    private val ui = Handler(Looper.getMainLooper())
    private val signal = CancellationSignal()
    private val link = Link(this)
    private val queue = ArrayDeque<Slot>()
    private var busy = false
    private var closed = false
    /** Для тестов: все места превью листа по порядку создания. */
    val slots = ArrayList<Slot>()

    inner class Slot(val info: QuickLookInfo, val kind: PeekKind, val view: FrameLayout, val px: Size, val box: Boolean) {
        val id = slots.size
        /** Показанный битмап (null — нет). */
        var bmp: Bitmap? = null
            internal set
        /** Итог пришёл или вышло время. */
        var done = false
            internal set
        /** Превью нет: знак вида или «превью недоступно». */
        var failed = false
            internal set
        internal var timeout: Runnable? = null
    }

    /** Вид превью узла (по расширению; путь только для root, ссылка, каталог — NONE). */
    fun kindOf(info: QuickLookInfo): PeekKind {
        val ext = Ellipsis.ext(info.name)
        val mime = ext?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase(Locale.ROOT)) }
        return Peek.kindFor(ext, mime, info.rootOnly, info.flags)
    }

    /** Квадрат 40dp (фон BG, контур 1dp LINE) со знаком вида; превью придёт само. null — у вида квадрата нет. */
    fun square(info: QuickLookInfo): FrameLayout? {
        val kind = kindOf(info)
        if (!SheetPeek.thumb(kind)) return null
        val v = frame()
        SheetPeek.glyph(kind)?.let { g ->
            v.addView(act.label(g, 10f, C.MUTED, mono = true).apply {
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        // Декодер уменьшает целой степенью: итог меньше 2× цели — цель = размер квадрата.
        val px = act.dp(SheetPeek.THUMB_DP)
        enqueue(Slot(info, kind, v, Size(px, px), box = false))
        return v
    }

    /** Место превью 120dp во всю ширину листа (один файл). null — у вида превью нет. */
    fun box(info: QuickLookInfo, kind: PeekKind = kindOf(info)): FrameLayout? {
        if (kind == PeekKind.NONE) return null
        val v = frame()
        val w = maxOf(1, act.resources.displayMetrics.widthPixels - act.dp(42))
        enqueue(Slot(info, kind, v, Size(w, act.dp(SheetPeek.BOX_DP - 2)), box = true))
        return v
    }

    private fun frame(): FrameLayout = FrameLayout(act).apply {
        background = act.box(C.BG, C.LINE)
        val one = act.dp(1)
        setPadding(one, one, one, one)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun enqueue(s: Slot) {
        slots += s
        queue.addLast(s)
        // После построения листа: очередь стартует, когда View уже в разметке.
        ui.post { pump() }
    }

    private fun pump() {
        if (closed || busy) return
        val s = queue.removeFirstOrNull() ?: return
        busy = true
        val id = s.id
        val link = link
        val job = PeekJob(s.info.path, s.kind, s.px, act.dp(SheetPeek.THUMB_DP), act.applicationContext.packageManager,
            t, signal)
        s.timeout = Runnable { settle(id, null) }.also { ui.postDelayed(it, Peek.TIMEOUT_MS) }
        QuickLook.exec.execute {
            val r = try { job.run() } catch (e: Throwable) {
                Log.i("ancdu", "sheet thumb: no preview (${e.javaClass.simpleName})")
                null
            }
            link.post(id, r)
        }
    }

    /** Итог места [id] (null — нет превью или вышло время); поздний итог отбрасывается. */
    internal fun settle(id: Int, r: Preview?) {
        val s = slots.getOrNull(id)
        if (closed || s == null || s.done) { recycle(r); return }
        s.done = true
        s.timeout?.let { ui.removeCallbacks(it) }
        show(s, r)
        busy = false
        pump()
    }

    private fun show(s: Slot, r: Preview?) {
        val v = s.view
        val bmp = when (r) {
            is Preview.Image -> r.bmp
            is Preview.Apk -> r.icon
            else -> null
        }
        when {
            bmp != null && !s.box -> {
                s.bmp = bmp
                v.removeAllViews()
                v.addView(ImageView(act).apply {
                    scaleType = if (r is Preview.Apk) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(bmp)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
            s.box && r is Preview.Image -> {
                s.bmp = r.bmp
                v.addView(ImageView(act).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(r.bmp)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
            s.box && r is Preview.Apk -> {
                s.bmp = r.icon
                v.addView(act.hbox(12).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(act.dp(12), 0, act.dp(12), 0)
                    r.icon?.let { addView(ImageView(act).apply {
                        setImageBitmap(it)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(act.dp(40), act.dp(40))) }
                    addView(act.vbox(2).apply {
                        addView(act.label(r.label, 15f, C.TEXT, bold = true).apply { maxLines = 1 })
                        addView(act.label(r.line, 12f, C.MUTED, mono = true).apply { maxLines = 1 })
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
            s.box && r is Preview.Text -> v.addView(act.label(r.text, 12f, C.TEXT, mono = true).apply {
                maxLines = BOX_LINES
                setPadding(act.dp(8), act.dp(6), act.dp(8), act.dp(6))
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            s.box -> {
                s.failed = true
                v.addView(act.label(t.s(R.string.ql_no_preview), 13f, C.MUTED, mono = true).apply { gravity = Gravity.CENTER },
                    FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
            // Квадрат без превью: знак вида остаётся (рамка та же — без скачка).
            else -> { s.failed = true; recycle(r) }
        }
    }

    /** Лист закрыт: задачи отменяются, поздние итоги отбрасываются, битмапы освобождаются. */
    fun close() {
        if (closed) return
        closed = true
        link.thumbs = null
        signal.cancel()
        ui.removeCallbacksAndMessages(null)
        queue.clear()
        for (s in slots) {
            for (i in 0 until s.view.childCount) (s.view.getChildAt(i) as? ImageView)?.setImageDrawable(null)
            (s.view.getChildAt(0) as? LinearLayout)?.let { row ->
                for (i in 0 until row.childCount) (row.getChildAt(i) as? ImageView)?.setImageDrawable(null)
            }
            s.bmp?.recycle()
            s.bmp = null
        }
    }

    /** Связь фоновой задачи с листом: закрытие ([thumbs] = null) обрывает её; итог — на главном потоке. */
    internal class Link(@Volatile var thumbs: SheetThumbs?) {
        fun post(id: Int, r: Preview?) { MAIN.post { val th = thumbs; if (th == null) recycle(r) else th.settle(id, r) } }
    }

    companion object {
        /** Строк текста в месте 120dp. */
        private const val BOX_LINES = 7
        private val MAIN = Handler(Looper.getMainLooper())

        private fun recycle(r: Preview?) {
            when (r) {
                is Preview.Image -> r.bmp.recycle()
                is Preview.Apk -> r.icon?.recycle()
                else -> {}
            }
        }
    }
}
