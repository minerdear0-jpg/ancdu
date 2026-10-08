package dev.ancdu

import dev.ancdu.XmlTxt.Companion.EN
import dev.ancdu.XmlTxt.Companion.RU
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

/** «Что выросло»: тексты Δ (EN/RU), сортировка Δ, «ушло» во множественном числе. */
class GrowthTest {
    private val N = Fmt.NBSP
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val mib = 1L shl 20
    private val gib = 1L shl 30
    /** 1 окт. 2026, 09:12 UTC. */
    private val oct1 = 1_790_845_920_000L

    @Test fun signedSizes() {
        assertEquals("+1.7${N}GiB", GrowthText.signed((1.7 * gib).toLong(), EN))
        assertEquals("−84.0${N}MiB", GrowthText.signed(-84 * mib, EN))
        assertEquals("±0", GrowthText.signed(0, EN))
        assertEquals("+512${N}B", GrowthText.signed(512, EN))
        assertEquals("+1,7${N}ГиБ", GrowthText.signed((1.7 * gib).toLong(), RU))
        assertEquals("−84,0${N}МиБ", GrowthText.signed(-84 * mib, RU))
        assertEquals("±0", GrowthText.signed(0, RU))
        // Long.MIN_VALUE не переполняется при смене знака.
        assertEquals('−', GrowthText.signed(Long.MIN_VALUE, EN)[0])
    }

    @Test fun colourRoles() {
        assertEquals(Role.AMBER_TEXT, GrowthText.role(1))
        assertEquals(Role.MUTED, GrowthText.role(-1))
        assertEquals(Role.TEXT, GrowthText.role(0))
        assertEquals(Palette.DARK.amberText, Role.AMBER_TEXT.color(Palette.DARK))
        assertEquals(Palette.LIGHT.amberText, Role.AMBER_TEXT.color(Palette.LIGHT))
    }

    @Test fun datesAndBadge() {
        assertEquals("Oct 1", GrowthText.since(EN, oct1, utc))
        assertEquals("1 окт.", GrowthText.since(RU, oct1, utc))
        assertEquals("Δ vs Oct 1 09:12", GrowthText.badge(EN, oct1, utc))
        assertEquals("Δ с 1 окт. 09:12", GrowthText.badge(RU, oct1, utc))
        assertEquals("6.4${N}GiB · +1.9${N}GiB since Oct 1",
            GrowthText.summary(EN, (6.4 * gib).toLong(), (1.9 * gib).toLong(), oct1, utc))
        assertEquals("6,4${N}ГиБ · +1,9${N}ГиБ с 1 окт.",
            GrowthText.summary(RU, (6.4 * gib).toLong(), (1.9 * gib).toLong(), oct1, utc))
        assertEquals("6,4${N}ГиБ · ±0 с 1 окт.", GrowthText.summary(RU, (6.4 * gib).toLong(), 0, oct1, utc))
    }

    @Test fun daysAgo() {
        val day = 86_400_000L
        assertEquals("today", GrowthText.daysAgo(EN, oct1 + 3_600_000, oct1))
        assertEquals("1 day ago", GrowthText.daysAgo(EN, oct1 + day, oct1))
        assertEquals("9 days ago", GrowthText.daysAgo(EN, oct1 + 9 * day + 5, oct1))
        assertEquals("сегодня", GrowthText.daysAgo(RU, oct1, oct1))
        assertEquals("1 день назад", GrowthText.daysAgo(RU, oct1 + day, oct1))
        assertEquals("3 дня назад", GrowthText.daysAgo(RU, oct1 + 3 * day, oct1))
        assertEquals("11 дней назад", GrowthText.daysAgo(RU, oct1 + 11 * day, oct1))
        assertEquals("21 день назад", GrowthText.daysAgo(RU, oct1 + 21 * day, oct1))
        // Время точки отсчёта в будущем (часы ушли назад) — «сегодня», не «−1 день».
        assertEquals("today", GrowthText.daysAgo(EN, oct1, oct1 + day))
    }

    @Test fun goneSummaryPlurals() {
        assertEquals("gone: 1 item · −4.0${N}KiB", GrowthText.gone(EN, 1, 4096))
        assertEquals("gone: 3 items · −120.0${N}MiB", GrowthText.gone(EN, 3, 120 * mib))
        assertEquals("ушло: 1 объект · −4,0${N}КиБ", GrowthText.gone(RU, 1, 4096))
        assertEquals("ушло: 3 объекта · −120,0${N}МиБ", GrowthText.gone(RU, 3, 120 * mib))
        assertEquals("ушло: 5 объектов · −1,0${N}КиБ", GrowthText.gone(RU, 5, 1024))
        assertEquals("ушло: 22 объекта · −1,0${N}КиБ", GrowthText.gone(RU, 22, 1024))
        assertEquals("ушло: 1${N}011 объектов · ±0", GrowthText.gone(RU, 1011, 0))
        assertNull(GrowthText.goneOrNull(EN, 0, 0))
        assertEquals(GrowthText.gone(EN, 2, 10), GrowthText.goneOrNull(EN, 2, 10))
    }

    @Test fun rowDescriptions() {
        assertEquals("a.bin, +1.0${N}MiB since Oct 1, now 3.0${N}MiB, new",
            GrowthText.rowDesc(EN, "a.bin", mib, 3 * mib, oct1, isNew = true, dir = false, utc))
        assertEquals("Video, −2,0${N}МиБ с 1 окт., сейчас 3,0${N}МиБ, каталог",
            GrowthText.rowDesc(RU, "Video", -2 * mib, 3 * mib, oct1, isNew = false, dir = true, utc))
    }

    @Test fun deltaSortOrder() {
        // id: 10 +5, 11 −3, 12 0 (размер 50), 13 +5 (размер больше), 14 0 (размер 70), 15 +5 (тот же размер, что 10)
        val delta = mapOf(10 to 5L, 11 to -3L, 12 to 0L, 13 to 5L, 14 to 0L, 15 to 5L)
        val size = mapOf(10 to 100L, 11 to 9L, 12 to 50L, 13 to 900L, 14 to 70L, 15 to 100L)
        val ids = intArrayOf(12, 11, 15, 10, 14, 13, 99)
        GrowthSort.sort(ids, 6, { delta.getValue(it) }, { size.getValue(it) })
        // Рост первым, при равном Δ — крупнее первым, затем меньший id; хвост за n не трогается.
        assertArrayEquals(intArrayOf(13, 10, 15, 14, 12, 11, 99), ids)
        val one = intArrayOf(7)
        GrowthSort.sort(one, 1, { 0L }, { 0L })
        assertArrayEquals(intArrayOf(7), one)
        GrowthSort.sort(IntArray(0), 0, { 0L }, { 0L })
    }

    @Test fun signedBar() {
        assertEquals(1f, GrowthSort.bar(10, 10), 0f)
        assertEquals(-0.5f, GrowthSort.bar(-5, 10), 0f)
        assertEquals(0f, GrowthSort.bar(0, 0), 0f)
        assertEquals(0f, GrowthSort.bar(3, 0), 0f)
        assertEquals(10L, GrowthSort.maxAbs(longArrayOf(-10, 3, 0)))
        assertEquals(Long.MAX_VALUE, GrowthSort.maxAbs(longArrayOf(Long.MIN_VALUE)))
    }
}
