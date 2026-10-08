package dev.ancdu

/** [label] — ресурс подписи; [role] — роль цвета заливки (цвет — на главном потоке, при показе). */
data class Seg(val label: Int, val bytes: Long, val role: Role)

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
            Seg(R.string.seg_video, video, Role.AMBER),
            Seg(R.string.seg_photo, image, Role.BLUE),
            Seg(R.string.seg_audio, audio, Role.AUDIO_FILL),
            Seg(R.string.seg_apps, apps, Role.AMBER_DIM),
            Seg(R.string.seg_other, other, Role.MUTED),
            Seg(R.string.seg_system, system, Role.FRAME),
            Seg(R.string.seg_free, free.coerceAtLeast(0), Role.FREE),
        )
    }
}
