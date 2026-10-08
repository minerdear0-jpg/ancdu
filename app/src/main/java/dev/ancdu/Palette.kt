package dev.ancdu

/**
 * Палитра «ANCDU // TERMINAL»: тёмная [DARK] и светлая [LIGHT]. Действующая — [C.p] (её ставит
 * каждая Activity по своему ночному режиму); токены [C] читают её при каждом обращении. Цвет
 * нельзя запоминать в companion object или другом static: пересозданный экран берёт новый.
 * Пары текст/фон — TokenContrastTest. Чистый Kotlin.
 */
data class Palette(
    val dark: Boolean,
    val bg: Int,
    val panel: Int,
    /** Нажатое / выбранное. */
    val panel2: Int,
    /** Разделители, дорожки. */
    val line: Int,
    /** Скобки, контуры полос и кнопок — не текст. */
    val frame: Int,
    val text: Int,
    val muted: Int,
    /** Заливки: занято / каталоги / основное действие. Не текст. */
    val amber: Int,
    /** Амберный ТЕКСТ (%, «Дать доступ», ⚠, root ✗, плашка, ссылка ошибок). */
    val amberText: Int,
    /** Текст на амбере. */
    val ink: Int,
    /** Полосы: свободно / файлы / сведения. */
    val blue: Int,
    /** Синий текст: имена файлов, метки dl/cache. */
    val blueHi: Int,
    /** Свободная часть полосы раздела и квадрат её легенды (может быть полупрозрачным). */
    val free: Int,
    /** Тёмная ступень амбера — только заливки сегментов яруса 0 (не текст). */
    val amberDim: Int,
    /** Сегмент «аудио» яруса 0 (не текст). */
    val audioFill: Int,
    val dangerText: Int,
    /** Заливка кнопки удаления; текст на ней — белый. */
    val dangerFill: Int,
    /** Нажатая «Удалить». */
    val dangerPressed: Int,
    /** Только «root ✓»: текст, фон, контур. */
    val ok: Int,
    val okBg: Int,
    val okLine: Int,
    /** Контур фокуса на заливке. */
    val focus: Int,
    /** Затемнение под листом (ARGB: альфа — сила). */
    val scrim: Int,
    /** Выключенная заливная кнопка (отсчёт «Удалить») и её текст. */
    val disFill: Int,
    val disText: Int,
    /** Контур миниатюр. */
    val thumbLine: Int,
    /** Сцена видео (всегда тёмная): подложка надписи поверх кадра и её текст. */
    val stageScrim: Int,
    val stageText: Int,
    /** Развёртка скана: линия и альфа (0..255) вершины шлейфа. */
    val sweepLine: Int,
    val sweepGlowAlpha: Int,
    /** Неопределённая полоса скана без анимаций: альфа (0..255). */
    val idleLineAlpha: Int,
) {
    companion object {
        private fun argb(c: Long) = c.toInt()

        private const val D_BG = 0xFF0A0D10
        private const val D_TEXT = 0xFFE8E6E1

        val DARK = Palette(
            dark = true,
            bg = argb(D_BG),
            panel = argb(0xFF11171D),
            panel2 = argb(0xFF17202A),
            line = argb(0xFF26323D),
            frame = argb(0xFF5A6E80),
            text = argb(D_TEXT),
            muted = argb(0xFF9AA7B2),
            amber = argb(0xFFF2A93B),
            amberText = argb(0xFFF2A93B),
            ink = argb(0xFF0A0D10),
            blue = argb(0xFF5B9BD5),
            blueHi = argb(0xFF8CC0EE),
            free = argb(0x4D5B9BD5),               // BLUE @30%
            amberDim = argb(0xFFA87628),
            audioFill = argb(0xFF8CC0EE),          // = BLUE_HI
            dangerText = argb(0xFFFF7466),
            dangerFill = argb(0xFFB3261E),
            dangerPressed = argb(0xFF8C1D17),
            ok = argb(0xFF8FD18F),
            okBg = argb(0xFF142017),
            okLine = argb(0xFF2E4A32),
            focus = argb(0xFFFFFFFF),
            scrim = argb(0x99000000),              // чёрный 0.60
            // Прежний вид (альфа 0.5 над PANEL): DANGER_FILL и белый пополам с PANEL.
            disFill = argb(0xFF621F1E),
            disText = argb(0xFF888B8E),
            thumbLine = argb(0xFF26323D),          // = LINE
            stageScrim = argb((D_BG and 0x00FFFFFF) or 0xCC000000),
            stageText = argb(D_TEXT),
            sweepLine = argb(0xFFF2A93B),
            sweepGlowAlpha = 0x59,                 // 0.35
            idleLineAlpha = 102,                   // 0.40
        )

        val LIGHT = Palette(
            dark = false,
            bg = argb(0xFFF3F1EB),
            panel = argb(0xFFFBFAF7),
            panel2 = argb(0xFFE4E0D5),
            line = argb(0xFFD3CEC2),
            frame = argb(0xFF77818A),
            text = argb(0xFF15191D),
            muted = argb(0xFF545E67),
            amber = argb(0xFFBC760E),
            amberText = argb(0xFF8F5200),
            ink = argb(0xFF15191D),
            blue = argb(0xFF2F6DAD),
            blueHi = argb(0xFF1F5A94),
            free = argb(0xFFE3EBF3),
            amberDim = argb(0xFF6E430C),
            audioFill = argb(0xFF8DB3DA),
            dangerText = argb(0xFFB0251C),
            dangerFill = argb(0xFFB3261E),
            dangerPressed = argb(0xFF8C1D17),
            ok = argb(0xFF1D6A2B),
            okBg = argb(0xFFE2EFDF),
            okLine = argb(0xFF4F8A5B),
            focus = argb(0xFF15191D),              // = INK
            scrim = argb(0x6615191D),              // INK 0.40
            disFill = argb(0xFFE4E0D5),            // = PANEL2
            disText = argb(0xFF545E67),            // = MUTED
            thumbLine = argb(0xFF77818A),          // = FRAME
            stageScrim = DARK.stageScrim,
            stageText = DARK.stageText,
            sweepLine = argb(0xFF8F5200),          // = AMBER_TEXT
            sweepGlowAlpha = 0x33,                 // 0.20
            idleLineAlpha = 255,
        )

        /** Палитра ночного режима [night]. */
        fun of(night: Boolean): Palette = if (night) DARK else LIGHT
    }
}

/**
 * Роль цвета (текст меток, заливки сегментов): цвет берётся из палитры при показе, а не при
 * создании — то, что посчитано заранее или на рабочем потоке, хранит роль.
 */
enum class Role {
    TEXT, MUTED, BLUE_HI, AMBER, BLUE, AUDIO_FILL, AMBER_DIM, FRAME, FREE, AMBER_TEXT;

    fun color(p: Palette = C.p): Int = when (this) {
        TEXT -> p.text
        MUTED -> p.muted
        BLUE_HI -> p.blueHi
        AMBER -> p.amber
        BLUE -> p.blue
        AUDIO_FILL -> p.audioFill
        AMBER_DIM -> p.amberDim
        FRAME -> p.frame
        FREE -> p.free
        AMBER_TEXT -> p.amberText
    }
}
