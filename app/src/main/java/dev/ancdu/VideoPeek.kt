package dev.ancdu

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Раскадровка видео на [QuickLook.exec]: проверка обычного файла (как [PeekJob]), один
 * MediaMetadataRetriever на дескрипторе, проверенном fstat, — освобождается в finally. Сведения,
 * затем кадры по одному (getScaledFrameAtTime, OPTION_CLOSEST_SYNC, не больше [px]); первый кадр —
 * не позже [Storyboard.FIRST_MS], вся лента — [Storyboard.STRIP_MS], остальное пропускается.
 * Декодирует медиапроцесс системы — сюда приходят готовые битмапы.
 */
internal class FrameJob(private val path: String, private val px: Size, private val signal: CancellationSignal,
                        private val sink: Sink) {
    fun run() {
        val start = SystemClock.uptimeMillis()
        val ok = try {
            val real = PeekJob.regular(path, signal)
            real != null && PeekJob.withRegular(real) { fd -> frames(fd, start); true } == true
        } catch (e: Throwable) {
            Log.i("ancdu", "storyboard: no frames (${e.javaClass.simpleName})")
            false
        }
        if (!ok) sink.post { it.video?.failed() }
    }

    private fun frames(fd: java.io.FileDescriptor, start: Long) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(fd)
            fun num(key: Int) = r.extractMetadata(key)?.toLongOrNull()
            val dur = num(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val meta = Storyboard.meta(dur, (num(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: 0).toInt(),
                (num(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: 0).toInt(),
                (num(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0).toInt())
            val times = Storyboard.times(dur)
            sink.post { it.video?.meta(meta, dur ?: 0L, times.size) }
            for ((i, us) in times.withIndex()) {
                val spent = SystemClock.uptimeMillis() - start
                if (signal.isCanceled || spent > (if (i == 0) Storyboard.FIRST_MS else Storyboard.STRIP_MS)) break
                val bmp = try {
                    r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, px.width, px.height)
                } catch (e: Throwable) { null } ?: continue
                sink.post(bmp) { c -> c.video?.frame(i, bmp) == true }
            }
        } finally { r.release() }
    }
}

/**
 * Видео в месте превью карточки: большой кадр сверху (первый или выбранный), под ним лента из 5
 * кадров [Storyboard.STRIP_DP]; тап по большому кадру — мини-плеер там же (MediaPlayer на
 * TextureView, без звука по умолчанию, источник — проверенный дескриптор). Ошибка плеера — снова
 * раскадровка и «воспроизведение недоступно». Сама не автозапускается (и при reduced motion).
 */
internal class VideoBox(private val act: Activity, private val path: String, private val sink: Sink,
                        private val t: Txt) {
    private val ui = Handler(Looper.getMainLooper())
    val view: LinearLayout = act.vbox(2)
    /** Большой кадр (тап — плеер). */
    internal val main = FrameLayout(act)
    private val big = ImageView(act).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val bottom = FrameLayout(act)
    /** Для тестов: лента кадров и показанные кадры (null — ещё нет). */
    val strip: LinearLayout = act.hbox(2)
    val frames = ArrayList<Bitmap?>()
    private var shown = -1
    private var durMs = 0L
    var closed = false
        private set
    /** Для тестов: «воспроизведение недоступно» (null — не было ошибки). */
    var cantPlay: TextView? = null
        private set
    var player: MiniPlayer? = null
        private set

    init {
        main.addView(big, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        main.contentDescription = t.s(R.string.ql_play)
        main.setOnClickListener { play() }
        // Касаемо — с первым кадром.
        main.isClickable = false
        view.addView(main, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        bottom.addView(strip, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        view.addView(bottom, LinearLayout.LayoutParams(MATCH_PARENT, act.dp(Storyboard.STRIP_DP)))
    }

    /** Сведения пришли: [n] мест под кадры в ленте. */
    fun meta(text: String?, dur: Long, n: Int) {
        if (closed) return
        durMs = dur
        sink.card?.videoMeta(text)
        strip.removeAllViews()
        frames.clear()
        for (i in 0 until n) {
            frames += null
            strip.addView(ImageView(act).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = act.box(C.PANEL2)
                contentDescription = t.s(R.string.ql_frame, (i + 1).toString(), n.toString())
                isFocusable = true
                setOnClickListener { show(i) }
            }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        }
    }

    /** Кадр [i] готов; false — не нужен (закрыто), битмап освобождает вызвавший. */
    fun frame(i: Int, bmp: Bitmap): Boolean {
        if (closed || i !in frames.indices || frames[i] != null) return false
        frames[i] = bmp
        (strip.getChildAt(i) as? ImageView)?.setImageBitmap(bmp)
        if (shown < 0) {
            sink.card?.videoReady()
            main.isClickable = true
            show(i)
        }
        return true
    }

    /** Кадр [i] — крупно; играет плеер — остаётся плеер. */
    fun show(i: Int) {
        val b = frames.getOrNull(i) ?: return
        shown = i
        big.setImageBitmap(b)
        for (k in 0 until strip.childCount) strip.getChildAt(k).foreground =
            if (k == i) act.box(android.graphics.Color.TRANSPARENT, C.AMBER) else null
    }

    fun failed() { if (!closed) sink.card?.videoFailed() }

    /** Место кадра сменило размер (поворот, шрифт): видео вписывается заново. */
    private val refit = View.OnLayoutChangeListener { _, l, top, r, bot, ol, ot, or, ob ->
        if (r - l != or - ol || bot - top != ob - ot) player?.let { p -> main.post { p.refit() } }
    }

    private fun play() {
        if (closed || shown < 0) return
        player?.let { it.toggle(); return }
        val p = MiniPlayer(act, path, t, durMs, onError = ::playFailed)
        player = p
        cantPlay?.let { main.removeView(it) }
        cantPlay = null
        main.addView(p.texture, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
        main.addOnLayoutChangeListener(refit)
        strip.visibility = View.INVISIBLE
        bottom.addView(p.controls, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        p.start(sink)
    }

    /** Плеер не смог: снова раскадровка, «воспроизведение недоступно» внизу большого кадра. */
    private fun playFailed() {
        stopPlayer()
        if (closed) return
        cantPlay = act.label(t.s(R.string.ql_cant_play), 12f, C.TEXT, mono = true).apply {
            setBackgroundColor(0xCC101214.toInt())
            setPadding(act.dp(8), act.dp(4), act.dp(8), act.dp(4))
        }
        main.addView(cantPlay, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        if (main.a11yOn()) main.announceForAccessibility(cantPlay!!.text)
    }

    private fun stopPlayer() {
        val p = player ?: return
        player = null
        main.removeOnLayoutChangeListener(refit)
        p.release()
        main.removeView(p.texture)
        bottom.removeView(p.controls)
        strip.visibility = View.VISIBLE
    }

    /** Activity на паузе: плеер — на паузу (в фоне не играет). */
    fun pause() { player?.pause() }

    /** Карточка закрыта: плеер освобождается, кадры — тоже. */
    fun close() {
        if (closed) return
        closed = true
        stopPlayer()
        big.setImageDrawable(null)
        for (k in 0 until strip.childCount) (strip.getChildAt(k) as? ImageView)?.setImageDrawable(null)
        frames.forEach { it?.recycle() }
        frames.clear()
        ui.removeCallbacksAndMessages(null)
    }
}

/**
 * Мини-плеер: framework MediaPlayer на TextureView. Источник — setDataSource(FileDescriptor) с
 * дескриптора, проверенного fstat (на [QuickLook.exec]); путь и URI не передаются никогда. Без звука
 * по умолчанию (переключатель 44dp), тонкая янтарная полоса перемотки, время моно 12.
 */
internal class MiniPlayer(private val act: Activity, private val path: String, private val t: Txt,
                          private var durMs: Long, private val onError: () -> Unit) {
    private val ui = Handler(Looper.getMainLooper())
    private val mp = MediaPlayer()
    private val lock = Any()
    /** Источник ставится на фоне: release ждёт его конца (MediaPlayer не потокобезопасен). */
    private var sourcing = false
    private var dead = false
    private var surface: Surface? = null
    /** Источник поставлен (главный поток): с этого момента MediaPlayer трогает только главный. */
    private var sourced = false
    /** Для тестов. */
    var prepared = false
        private set
    var released = false
        private set
    var muted = true
        private set
    /** Пауза по просьбе Activity (onPause): готовый плеер сам не стартует, только тап. */
    var held = false
        private set
    /** Размер кадра видео (0 — ещё неизвестен): по нему [refit] при смене размера места. */
    private var vw = 0
    private var vh = 0
    val playing: Boolean get() = prepared && !released && try { mp.isPlaying } catch (e: IllegalStateException) { false }

    val texture = TextureView(act).apply {
        isOpaque = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface = Surface(st).also { if (sourced && !released) try { mp.setSurface(it) } catch (e: IllegalStateException) {} }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                if (sourced && !released) try { mp.setSurface(null) } catch (e: IllegalStateException) {}
                surface?.release(); surface = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }
    val playButton = PlayerIcon(act, PlayerIcon.PAUSE).apply { setOnClickListener { toggle() } }
    val muteButton = PlayerIcon(act, PlayerIcon.MUTED).apply { setOnClickListener { setMute(!muted) } }
    val seek = SeekBar(act).apply {
        progressTintList = ColorStateList.valueOf(C.AMBER)
        thumbTintList = ColorStateList.valueOf(C.AMBER)
        progressBackgroundTintList = ColorStateList.valueOf(C.LINE)
        max = maxOf(1, durMs.toInt())
        minHeight = act.dp(2); maxHeight = act.dp(2)
        contentDescription = t.s(R.string.ql_seek)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                if (user && prepared && !released) try { mp.seekTo(p.toLong(), MediaPlayer.SEEK_CLOSEST_SYNC) } catch (e: IllegalStateException) {}
                time.text = Storyboard.clock(p.toLong(), durMs)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }
    val time: TextView = act.label(Storyboard.clock(0, durMs), 12f, C.TEXT, mono = true)
    val controls: LinearLayout = act.hbox(4).apply {
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(C.BG)
        addView(playButton, LinearLayout.LayoutParams(act.dp(44), act.dp(44)))
        addView(muteButton, LinearLayout.LayoutParams(act.dp(44), act.dp(44)))
        addView(seek, LinearLayout.LayoutParams(0, act.dp(44), 1f))
        addView(time)
        setPadding(0, 0, act.dp(8), 0)
    }
    private val tick = object : Runnable {
        override fun run() {
            if (released || !prepared) return
            try { seek.progress = mp.currentPosition } catch (e: IllegalStateException) { return }
            ui.postDelayed(this, 250)
        }
    }

    init {
        label()
        mp.setOnPreparedListener {
            if (released) return@setOnPreparedListener
            prepared = true
            if (durMs <= 0) durMs = mp.duration.toLong().coerceAtLeast(0)
            seek.max = maxOf(1, durMs.toInt())
            ui.removeCallbacks(stall)
            mp.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
            // Activity ушла на паузу, пока готовился: не стартует в фоне.
            if (!held) { mp.start(); ui.post(tick) }
            label()
        }
        mp.setOnCompletionListener { label() }
        // Кадр видео — целиком в месте превью, с его пропорциями.
        mp.setOnVideoSizeChangedListener { _, w, h -> if (!released) { vw = w; vh = h; refit() } }
        mp.setOnErrorListener { _, what, extra ->
            Log.i("ancdu", "mini player: error $what/$extra")
            if (!released) onError()
            true
        }
    }

    /** Источник — на [QuickLook.exec]: обычный файл, O_NOFOLLOW, fstat; затем prepareAsync на главном. */
    fun start(sink: Sink) {
        val p = path
        // Источник и подготовка не уложились — снова раскадровка («воспроизведение недоступно»).
        ui.postDelayed(stall, STALL_MS)
        synchronized(lock) { sourcing = true }
        QuickLook.exec.execute {
            val ok = try {
                PeekJob.regular(p, null)?.let { real -> PeekJob.withRegular(real) { fd -> mp.setDataSource(fd); true } } == true
            } catch (e: Throwable) {
                Log.i("ancdu", "mini player: no source (${e.javaClass.simpleName})")
                false
            }
            synchronized(lock) {
                sourcing = false
                if (dead) { mp.release(); return@execute }
            }
            ui.post {
                if (released) return@post
                if (!ok) { onError(); return@post }
                sourced = true
                try {
                    surface?.let { mp.setSurface(it) }
                    mp.prepareAsync()
                } catch (e: IllegalStateException) { onError() }
            }
        }
    }

    private val stall = Runnable { if (!released && !prepared) { Log.i("ancdu", "mini player: prepare timed out"); onError() } }

    /** Кадр видео — целиком в месте [texture.parent], с пропорциями видео; зовётся и при смене размера места. */
    fun refit() {
        val box = texture.parent as? View ?: return
        val w = vw; val h = vh
        if (w <= 0 || h <= 0 || box.width <= 0 || box.height <= 0) return
        val k = minOf(box.width.toFloat() / w, box.height.toFloat() / h)
        val lp = FrameLayout.LayoutParams((w * k).toInt(), (h * k).toInt(), Gravity.CENTER)
        val old = texture.layoutParams as? FrameLayout.LayoutParams
        if (old == null || old.width != lp.width || old.height != lp.height) texture.layoutParams = lp
    }

    fun toggle() {
        held = false
        if (!prepared || released) return
        try { if (mp.isPlaying) mp.pause() else { mp.start(); ui.removeCallbacks(tick); ui.post(tick) } } catch (e: IllegalStateException) {}
        label()
    }

    fun pause() {
        held = true
        if (!prepared || released) return
        try { if (mp.isPlaying) mp.pause() } catch (e: IllegalStateException) {}
        label()
    }

    fun setMute(on: Boolean) {
        muted = on
        if (prepared && !released) try { if (on) mp.setVolume(0f, 0f) else mp.setVolume(1f, 1f) } catch (e: IllegalStateException) {}
        label()
    }

    private fun label() {
        val on = playing
        playButton.mode = if (on) PlayerIcon.PAUSE else PlayerIcon.PLAY
        playButton.contentDescription = t.s(if (on) R.string.ql_pause else R.string.ql_resume)
        muteButton.mode = if (muted) PlayerIcon.MUTED else PlayerIcon.SOUND
        muteButton.contentDescription = t.s(if (muted) R.string.ql_unmute else R.string.ql_mute)
    }

    fun release() {
        if (released) return
        released = true
        ui.removeCallbacksAndMessages(null)
        val now = synchronized(lock) { dead = true; !sourcing }
        if (now) mp.release()
        surface?.release(); surface = null
    }

    private companion object {
        /** Бюджет источника и подготовки. */
        const val STALL_MS = 4000L
    }
}

/** Значок кнопки 44dp: ▶, ❚❚, динамик (перечёркнутый — без звука), ✕. Рисуется, не из шрифта. */
internal class PlayerIcon(ctx: Context, mode: Int) : View(ctx) {
    var mode: Int = mode
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.TEXT; strokeWidth = ctx.dp(2).toFloat() }
    private val path = Path()

    init {
        background = ctx.pressable(android.graphics.Color.TRANSPARENT)
        isClickable = true; isFocusable = true
        isSoundEffectsEnabled = false
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height) * 0.4f
        val cx = width / 2f; val cy = height / 2f
        path.reset()
        paint.style = Paint.Style.FILL
        when (mode) {
            CLOSE -> {
                paint.style = Paint.Style.STROKE
                val r = s * 0.45f
                c.drawLine(cx - r, cy - r, cx + r, cy + r, paint)
                c.drawLine(cx - r, cy + r, cx + r, cy - r, paint)
            }
            PLAY -> { path.moveTo(cx - s * 0.4f, cy - s / 2); path.lineTo(cx + s * 0.5f, cy); path.lineTo(cx - s * 0.4f, cy + s / 2); path.close(); c.drawPath(path, paint) }
            PAUSE -> { val w = s * 0.28f; c.drawRect(cx - s * 0.4f, cy - s / 2, cx - s * 0.4f + w, cy + s / 2, paint); c.drawRect(cx + s * 0.4f - w, cy - s / 2, cx + s * 0.4f, cy + s / 2, paint) }
            else -> {
                path.moveTo(cx - s / 2, cy - s * 0.18f); path.lineTo(cx - s * 0.2f, cy - s * 0.18f); path.lineTo(cx + s * 0.1f, cy - s / 2)
                path.lineTo(cx + s * 0.1f, cy + s / 2); path.lineTo(cx - s * 0.2f, cy + s * 0.18f); path.lineTo(cx - s / 2, cy + s * 0.18f); path.close()
                c.drawPath(path, paint)
                paint.style = Paint.Style.STROKE
                if (mode == MUTED) c.drawLine(cx + s * 0.25f, cy - s * 0.2f, cx + s * 0.55f, cy + s * 0.2f, paint).also {
                    c.drawLine(cx + s * 0.25f, cy + s * 0.2f, cx + s * 0.55f, cy - s * 0.2f, paint)
                } else c.drawArc(cx - s * 0.1f, cy - s * 0.35f, cx + s * 0.5f, cy + s * 0.35f, -60f, 120f, false, paint)
            }
        }
    }

    companion object {
        const val PLAY = 0
        const val PAUSE = 1
        const val MUTED = 2
        const val SOUND = 3
        const val CLOSE = 4
    }
}
