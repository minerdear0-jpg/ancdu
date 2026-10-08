package dev.ancdu

/** Что из удаляемого не вернуть: медиа ([media] — вид) или данные приложения [app] (метка или пакет). */
class Risk(val media: MediaKind?, val app: String?, val disk: Long)

enum class MediaKind(val res: Int) {
    PHOTO(R.string.warn_photo), VIDEO(R.string.warn_video), AUDIO(R.string.warn_audio), MIXED(R.string.warn_media)
}

/**
 * Чистый Kotlin: единственная строка-предупреждение листа удаления. Последствия называются только
 * для необратимого — медиа (фото, видео, аудио) и данных другого приложения; кэш и загрузки молчат.
 */
object SheetWarning {
    private val PHOTO = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng")
    private val VIDEO = setOf("mp4", "m4v", "mkv", "mov", "webm", "3gp", "avi")
    private val AUDIO = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "amr")

    /** Риск объекта [name] с меткой [tag] или null — его не вернуть можно (или незачем называть). */
    fun risk(tag: TagText?, name: String, dir: Boolean, disk: Long): Risk? = when (tag?.kind) {
        TagKind.MEDIA -> Risk(if (dir) MediaKind.MIXED else kindOf(name), null, disk)
        TagKind.APP -> Risk(null, tag.label ?: tag.pkg ?: tag.text.removePrefix("app:"), disk)
        else -> null
    }

    private fun kindOf(name: String): MediaKind = when (Ellipsis.ext(name)?.lowercase()) {
        in PHOTO -> MediaKind.PHOTO
        in VIDEO -> MediaKind.VIDEO
        in AUDIO -> MediaKind.AUDIO
        else -> MediaKind.MIXED
    }

    /**
     * «⚠ Без корзины. Не вернуть: видео 3,8 ГиБ и данные WhatsApp 0,6 ГиБ.»; без необратимого —
     * «⚠ Без корзины. Отменить нельзя.»; через su — с «Удаление от root · » впереди.
     */
    fun text(t: Txt, root: Boolean, risks: List<Risk>): String {
        val parts = ArrayList<String>(2)
        val media = risks.filter { it.media != null }
        if (media.isNotEmpty()) {
            val kinds = media.map { it.media!! }.distinct()
            val kind = kinds.singleOrNull() ?: MediaKind.MIXED
            parts += t.s(R.string.warn_part, t.s(kind.res), Fmt.size(DeletePolicy.sum(media.map { it.disk }), t))
        }
        val apps = risks.filter { it.app != null }
        if (apps.isNotEmpty()) {
            val names = apps.map { it.app!! }.distinct()
            val size = Fmt.size(DeletePolicy.sum(apps.map { it.disk }), t)
            parts += names.singleOrNull()?.let { t.s(R.string.warn_app, Bidi.visible(it), size) }
                ?: t.q(R.plurals.warn_apps, names.size.toLong(), Fmt.count(names.size.toLong(), t.locale), size)
        }
        val body = when (parts.size) {
            0 -> t.s(R.string.no_trash)
            1 -> t.s(R.string.warn_gone, parts[0])
            else -> t.s(R.string.warn_gone, t.s(R.string.warn_and, parts[0], parts[1]))
        }
        return "⚠ " + if (root) t.s(R.string.warn_root, body) else body
    }
}
