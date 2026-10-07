package dev.ancdu

/** Вид метки безопасности: цвет текста метки и описание для TalkBack ([desc]). */
enum class TagKind(val word: String, val color: Int, val desc: Int) {
    SYS("sys", C.MUTED, R.string.tag_sys),
    APP("app", C.MUTED, R.string.tag_app),
    CACHE("cache", C.BLUE_HI, R.string.tag_cache),
    DL("dl", C.BLUE_HI, R.string.tag_dl),
    MEDIA("media", C.MUTED, R.string.tag_media),
}

/**
 * Короткая моно-метка строки: «sys», «app:<Метка>», «cache», «dl», «media» — текст, не только
 * цвет. [pkg] — пакет владельца (другого приложения) у [TagKind.APP] и [TagKind.CACHE]: кэш чужого
 * приложения в описании TalkBack называет и владельца. Чистый Kotlin.
 */
class Tag(val kind: TagKind, val pkg: String? = null) {
    /** Текст метки; у приложения — «app:<label>», без метки — «app». [label] — из PackageManager. */
    fun text(label: String?): String {
        val l = label?.let(Bidi::label)?.trim().orEmpty()
        return if (kind == TagKind.APP && l.isNotEmpty()) "app:$l" else kind.word
    }

    /**
     * Добавка к описанию строки для TalkBack: «, кэш» / ", cache"; у приложения — с его меткой; у кэша
     * чужого приложения — и владелец («, кэш, данные приложения WhatsApp»).
     */
    fun desc(t: Txt, label: String?): String {
        val l = label?.let(Bidi::label)?.trim().orEmpty()
        val owner = if (l.isNotEmpty()) t.s(R.string.tag_app_named, l) else t.s(R.string.tag_app)
        return when {
            kind == TagKind.APP -> ", $owner"
            kind == TagKind.CACHE && pkg != null -> ", ${t.s(kind.desc)}, $owner"
            else -> ", " + t.s(kind.desc)
        }
    }

    companion object {
        private val CACHE_DIRS = setOf("cache", ".cache", "Cache")
        private val MEDIA_DIRS = setOf("DCIM", "Pictures", "Movies", "Music", "Recordings")
        private val MEDIA_EXT = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng",
            "mp4", "m4v", "mkv", "mov", "webm", "3gp", "avi",
            "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "amr")

        private fun digits(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' }

        /**
         * Метка пути [path] (флаги узла [flags]) или null. Порядок: sys > cache > app > dl > media.
         * - sys — есть запрет удаления [block] (кроме «дерево устарело» и «нет быстрого пути»);
         * - cache — каталог cache, .cache или Cache прямо в каталоге приложения
         *   (<хранилище>/Android/data/<pkg>, /data/data/<pkg>, /data/user(_de)/<n>/<pkg>) и всё в нём;
         *   кэш выше корня дерева [root] не считается (дерево внутри кэша — не метка на каждой строке);
         *   владелец — другое приложение: он в [Tag.pkg] (описание его называет);
         * - app — владелец [owner] (Owner.packageOf) — другое приложение, не [self];
         * - dl — <хранилище>/Download и всё в нём; media — DCIM, Pictures, Movies, Music, Recordings
         *   прямо в хранилище или файл с медиа-расширением.
         * Хранилище — корень дерева [root] (не «/»), /storage/emulated/<n> и /data/media/<n>.
         */
        fun of(path: String, flags: Int, owner: String?, block: Block?, root: String, self: String): Tag? {
            if (block != null && block != Block.REFRESH_FAILED && block != Block.NO_FAST) return Tag(TagKind.SYS)
            val other = owner?.takeIf { it != self }
            val s = path.split('/').filter { it.isNotEmpty() }
            if (s.isEmpty()) return other?.let { Tag(TagKind.APP, it) }
            val r = root.trimEnd('/')
            val rs = r.split('/').count { it.isNotEmpty() }
            val dir = flags and F_DIR != 0
            // Длины (в сегментах) корней хранилища, под которыми лежит путь.
            val stores = ArrayList<Int>(3)
            if (r.isNotEmpty() && path.startsWith("$r/")) stores += rs
            if (s.size > 3 && (s[0] == "storage" && s[1] == "emulated" || s[0] == "data" && s[1] == "media") && digits(s[2]))
                stores += 3
            // Индексы сегмента «cache» прямо в каталоге приложения.
            val caches = ArrayList<Int>(3)
            if (s.size > 3 && s[0] == "data" && s[1] == "data") caches += 3
            if (s.size > 4 && s[0] == "data" && (s[1] == "user" || s[1] == "user_de") && digits(s[2])) caches += 4
            for (l in stores) if (s.size > l + 3 && s[l] == "Android" && s[l + 1] == "data") caches += l + 3
            for (ci in caches) {
                if (ci < rs || s[ci] !in CACHE_DIRS) continue
                if (ci == s.size - 1 && !dir) continue   // файл с именем «cache» — не каталог кэша
                return Tag(TagKind.CACHE, other)
            }
            if (other != null) return Tag(TagKind.APP, other)
            if (stores.any { s.size > it && s[it] == "Download" }) return Tag(TagKind.DL)
            if (stores.any { s.size > it && s[it] in MEDIA_DIRS }) return Tag(TagKind.MEDIA)
            if (!dir && Ellipsis.ext(s.last())?.lowercase() in MEDIA_EXT) return Tag(TagKind.MEDIA)
            return null
        }

        /** Видимых знаков имени (кодовых точек без добавленного «…»). */
        private fun shown(full: String, cut: String): Int {
            val n = cut.codePointCount(0, cut.length)
            return if (cut != full && cut.contains(Ellipsis.MARK)) n - 1 else n
        }

        /**
         * Текст метки [tag] рядом с именем [name] в ширине [avail] (метка справа, [gap] между ними)
         * или null — метки нет. Метка не отнимает у имени 4 знака и больше (имя режется
         * [Ellipsis.stemKeepExt]): иначе короткая форма «app» у «app:<Метка>», иначе без метки.
         * Метка никогда не налезает на имя: имени остаётся avail − gap − ширина метки.
         */
        fun fit(name: String, tag: String, avail: Float, gap: Float, nameMeasure: (String) -> Float,
                tagMeasure: (String) -> Float): String? {
            val full = shown(name, Ellipsis.stemKeepExt(name, avail, nameMeasure))
            val forms = if (tag.startsWith("app:")) listOf(tag, "app") else listOf(tag)
            for (f in forms) {
                val w = tagMeasure(f) + gap
                if (w > avail) continue
                val left = shown(name, Ellipsis.stemKeepExt(name, avail - w, nameMeasure))
                if (full - left < 4) return f
            }
            return null
        }
    }
}

/** Метка для показа: текст, цвет и добавка к описанию TalkBack («, кэш»). */
class TagText(val text: String, val color: Int, val desc: String)

/** [label] — метка приложения владельца (у [TagKind.APP]) или null. */
fun Tag.resolve(t: Txt, label: String?): TagText = TagText(text(label), kind.color, desc(t, label))
