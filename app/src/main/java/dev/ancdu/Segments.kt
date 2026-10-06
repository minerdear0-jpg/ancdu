package dev.ancdu

data class Seg(val label: String, val bytes: Long, val color: Int)

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
            Seg("Видео", video, C.ACCENT),
            Seg("Фото", image, C.FILE),
            Seg("Аудио", audio, C.AUDIO),
            Seg("Приложения", apps, C.APPS),
            Seg("Прочие файлы", other, C.OTHER),
            Seg("Система", system, C.SYS),
            Seg("Свободно", free.coerceAtLeast(0), C.CHIP),
        )
    }
}
