package dev.ancdu

import dev.ancdu.XmlTxt.Companion.EN
import dev.ancdu.XmlTxt.Companion.RU
import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentsTest {
    private val G = 1L shl 30

    @Test fun splitsUsedSpace() {
        val s = Segments.compute(total = 256 * G, free = 17 * G, video = 20 * G, image = 10 * G,
            audio = 1 * G, extApp = 5 * G, extTotal = 60 * G, apps = 80 * G)
        val m = s.associate { RU.s(it.label) to it.bytes }
        assertEquals(listOf("Видео", "Фото", "Аудио", "Приложения", "Прочие файлы", "Система", "Свободно"), s.map { RU.s(it.label) })
        assertEquals(listOf("Video", "Photos", "Audio", "Apps", "Other files", "System", "Free"), s.map { EN.s(it.label) })
        assertEquals(24 * G, m["Прочие файлы"])                 // 60 - 20 - 10 - 1 - 5
        assertEquals((239 - 20 - 10 - 1 - 80 - 24) * G, m["Система"])
        assertEquals(17 * G, m["Свободно"])
        assertEquals(256 * G, s.sumOf { it.bytes })
    }

    @Test fun neverNegative() {
        val s = Segments.compute(total = 100, free = 90, video = 50, image = 50, audio = 0,
            extApp = 0, extTotal = 10, apps = 70)
        s.forEach { assert(it.bytes >= 0) { it } }
        assertEquals(0L, s.first { it.label == R.string.seg_system }.bytes)
    }

    /** Сегменты хранят роль (считаются на рабочем потоке), цвет — из палитры при показе. */
    @Test fun storesRolesResolvedByPalette() {
        val s = Segments.compute(total = 256 * G, free = 17 * G, video = 20 * G, image = 10 * G, audio = 1 * G,
            extApp = 0, extTotal = 40 * G, apps = 70 * G)
        assertEquals(listOf(Role.AMBER, Role.BLUE, Role.AUDIO_FILL, Role.AMBER_DIM, Role.MUTED, Role.FRAME, Role.FREE),
            s.map { it.role })
        for (p in listOf(Palette.LIGHT, Palette.DARK)) {
            assertEquals(listOf(p.amber, p.blue, p.audioFill, p.amberDim, p.muted, p.frame, p.free), s.map { it.role.color(p) })
        }
    }
}
