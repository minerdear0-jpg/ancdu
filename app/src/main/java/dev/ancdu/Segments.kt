package dev.ancdu

/** [label] — ресурс подписи. */
data class Seg(val label: Int, val bytes: Long, val color: Int)

object Segments {
    /**
     * total/free — раздел /data; video/image/audio/extApp/extTotal — queryExternalStatsForUser;
     * apps — сумма (app + data) по пакетам (data уже включает их внешние каталоги, поэтому
     * extApp вычитается из «прочих файлов»).
     */
    fun compute(total: Long, free: Long, video: Long, image: Long, audio: Long,
                extApp: Long, extTotal: Long, apps: Long): List<Seg> {
        val used = (total - free).coerceAtLeast(0)
        val other = (extTotal - video - image - audio - extApp).coerceAtLeast(0)
        val known = video + image + audio + apps + other
        val system = (used - known).coerceAtLeast(0)
        return listOf(
            Seg(R.string.seg_video, video, C.ACCENT),
            Seg(R.string.seg_photo, image, C.FILE),
            Seg(R.string.seg_audio, audio, C.AUDIO),
            Seg(R.string.seg_apps, apps, C.APPS),
            Seg(R.string.seg_other, other, C.OTHER),
            Seg(R.string.seg_system, system, C.SYS),
            Seg(R.string.seg_free, free.coerceAtLeast(0), C.CHIP),
        )
    }
}
