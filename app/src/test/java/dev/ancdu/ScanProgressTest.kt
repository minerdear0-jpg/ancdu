package dev.ancdu

import dev.ancdu.XmlTxt.Companion.EN
import dev.ancdu.XmlTxt.Companion.RU
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Полоса и плашка обновления дерева в шапке браузера (и полоса карточки главного экрана). */
class ScanProgressTest {
    private val N = Fmt.NBSP

    /** files / оценка, до конца — не больше 0.97; без оценки — неопределённая (null). */
    @Test fun fractionIsCapped() {
        assertNull(ScanProgress.fraction(10, null))
        assertNull(ScanProgress.fraction(10, 0))
        assertNull(ScanProgress.fraction(10, -1))
        assertEquals(0f, ScanProgress.fraction(0, 1000)!!, 0f)
        assertEquals(0.5f, ScanProgress.fraction(500, 1000)!!, 1e-6f)
        assertEquals(0.97f, ScanProgress.fraction(970, 1000)!!, 1e-6f)
        assertEquals(0.97f, ScanProgress.fraction(999, 1000)!!, 1e-6f)
        assertEquals("дерево выросло", 0.97f, ScanProgress.fraction(5000, 1000)!!, 1e-6f)
        assertEquals(0f, ScanProgress.fraction(-3, 1000)!!, 0f)
    }

    @Test fun badgeText() {
        assertEquals("refresh · 12,400", ScanProgress.badge(EN, ScanState.RUNNING, 12_400))
        assertEquals("обновление · 12${N}400", ScanProgress.badge(RU, ScanState.RUNNING, 12_400))
        assertEquals("refresh · 0", ScanProgress.badge(EN, ScanState.RUNNING, 0))
        assertEquals("refresh · waiting", ScanProgress.badge(EN, ScanState.QUEUED, 0))
        assertEquals("обновление · ждёт", ScanProgress.badge(RU, ScanState.QUEUED, 5))
        assertEquals("", ScanProgress.badge(EN, ScanState.NONE, 5))
    }

    /** Счёт на плашке — не чаще раза в секунду; смена состояния — сразу. */
    @Test fun badgeTicksAtMostOncePerSecond() {
        assertEquals(true, ScanProgress.badgeDue(changed = true, now = 100, last = 50))
        assertEquals(false, ScanProgress.badgeDue(changed = false, now = 1049, last = 50))
        assertEquals(true, ScanProgress.badgeDue(changed = false, now = 1050, last = 50))
    }
}
