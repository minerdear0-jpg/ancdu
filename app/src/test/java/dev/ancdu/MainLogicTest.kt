package dev.ancdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ScanGateTest {
    private val moderate = ScanGate.THERMAL_MODERATE

    @Test fun startsOnlyWhenEveryConditionHolds() {
        assertEquals(Gate.START, ScanGate.decide(true, null, false, 0, false))
        assertEquals(Gate.START, ScanGate.decide(true, 60_001, false, moderate - 1, false))
    }

    @Test fun eachConditionBlocks() {
        assertEquals(Gate.NO_PERM, ScanGate.decide(false, null, false, 0, false))
        assertEquals(Gate.FRESH, ScanGate.decide(true, 60_000, false, 0, false))
        assertEquals(Gate.FRESH, ScanGate.decide(true, 0, false, 0, false))
        assertEquals(Gate.POWER, ScanGate.decide(true, null, true, 0, false))
        assertEquals(Gate.POWER, ScanGate.decide(true, null, false, moderate, false))
        assertEquals(Gate.POWER, ScanGate.decide(true, null, false, moderate + 3, false))
        assertEquals(Gate.RUNNING, ScanGate.decide(true, null, false, 0, true))
    }

    @Test fun orderPermissionFirstThenRunningThenFreshThenPower() {
        assertEquals(Gate.NO_PERM, ScanGate.decide(false, null, true, moderate, true))
        assertEquals(Gate.RUNNING, ScanGate.decide(true, 0, true, moderate, true))
        // свежий кэш — обновлять нечего, даже в энергосбережении
        assertEquals(Gate.FRESH, ScanGate.decide(true, 10, true, moderate, false))
        // будущее время кэша (часы перевели назад) — как устаревший
        assertEquals(Gate.START, ScanGate.decide(true, -5, false, 0, false))
    }
}

class FreshnessTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val t = 1_759_700_000_000L       // 21:33:20 UTC
    private fun line(running: Boolean = false, live: Long = 0, cache: Long? = null, scanned: Boolean = false,
                     blocked: Boolean = false, approx: Boolean = false, now: Long = t) =
        Freshness.line(running, live, cache, scanned, blocked, approx, now, utc)

    @Test fun fresh() {
        assertEquals("скан 21:33 · только что", line(cache = t - 5_000, scanned = true))
        assertEquals("скан 21:33 · 5 мин назад", line(cache = t, scanned = true, now = t + 5 * 60_000))
        assertEquals("кэш 21:33 · 2 ч назад", line(cache = t, now = t + 2 * 3_600_000 + 1))
        assertEquals("кэш 21:33 · 05.10", line(cache = t, now = t + 2 * 86_400_000L))
    }

    @Test fun refreshingAndFirst() {
        assertEquals("кэш 21:33 · обновляю… 1 234 эл.", line(running = true, live = 1234, cache = t))
        assertEquals("4 980 эл. · первый скан", line(running = true, live = 4980))
    }

    @Test fun blockedAndNothing() {
        assertEquals("обновить ›", line(blocked = true))
        assertEquals("кэш 21:33 · обновить ›", line(blocked = true, cache = t))
        assertEquals("обновить ›", line())
        // идущий скан важнее запрета
        assertEquals("0 эл. · первый скан", line(running = true, blocked = true))
    }

    @Test fun approximate() {
        assertEquals("приблизительно · 10 эл. · первый скан", line(running = true, live = 10, approx = true))
        assertEquals("приблизительно · обновить ›", line(approx = true))
    }

    @Test fun delta() {
        assertEquals("+1.5 MiB с прошлого скана", Freshness.delta(1_572_864))
        assertEquals("−2.0 KiB с прошлого скана", Freshness.delta(-2048))
        assertEquals("±0 B с прошлого скана", Freshness.delta(0))
    }
}

class PathWalkTest {
    /** Дерево имён: узел → (имя → ребёнок). 0 — корень. */
    private val tree = mapOf(
        0 to mapOf("DCIM" to 1, "Download" to 2),
        1 to mapOf("Camera" to 3),
        3 to mapOf("IMG_1.jpg" to 4),
        2 to emptyMap())
    private fun walk(vararg names: String) = PathWalk.resolve(names.toList()) { n, s -> tree[n]?.get(s) }

    @Test fun exactPath() {
        assertEquals(PathWalk.Hit(3, true), walk("DCIM", "Camera"))
        assertEquals(PathWalk.Hit(0, true), walk())
    }

    @Test fun deepestExistingAncestor() {
        assertEquals(PathWalk.Hit(1, false), walk("DCIM", "Screenshots", "x"))
        assertEquals(PathWalk.Hit(0, false), walk("gone"))
        assertEquals(PathWalk.Hit(2, false), walk("Download", "a"))
    }
}

class RootProbeTest {
    @Test fun grantedNeedsExitZeroAndUid0() {
        assertTrue(RootProbe.granted(0, "uid=0(root) gid=0(root) groups=0(root) context=u:r:magisk:s0\n", false))
        assertTrue(RootProbe.granted(0, "uid=0 gid=0", false))
        assertFalse(RootProbe.granted(1, "uid=0(root)", false))
        assertFalse(RootProbe.granted(0, "uid=10234(u0_a234) gid=10234", false))
        assertFalse(RootProbe.granted(0, "euid=0", false))
        assertFalse(RootProbe.granted(0, "uid=00", false))
        assertFalse(RootProbe.granted(0, "", false))
        assertFalse(RootProbe.granted(null, "uid=0(root)", false))
        assertFalse("таймаут", RootProbe.granted(0, "uid=0(root)", true))
        assertFalse("Permission denied", RootProbe.granted(1, "Permission denied", false))
    }

    @Test fun lastStateRoundTrip() {
        assertEquals("granted|123", RootProbe.format(RootState.GRANTED, 123))
        assertEquals("denied|5", RootProbe.format(RootState.DENIED, 5))
        assertEquals(RootState.GRANTED, RootProbe.parse("granted|123"))
        assertEquals(RootState.DENIED, RootProbe.parse("denied|5"))
        assertEquals(RootState.UNKNOWN, RootProbe.parse(null))
        assertEquals(RootState.UNKNOWN, RootProbe.parse("garbage"))
    }
}

class CacheMetaTest {
    @Test fun roundTripAndLegacy() {
        val m = CacheMeta("/storage/emulated/0", false, 4980, 294, 1_759_700_000_000, 123_456, 5000)
        assertEquals("/storage/emulated/0|false|4980|294|1759700000000|123456|5000", m.format())
        assertEquals(m, CacheMeta.parse(m.format()))
        // запись до Task 15: без объёма и элементов
        val old = CacheMeta.parse("/data|true|10|5|1759700000000")!!
        assertEquals("/data", old.root); assertTrue(old.su); assertEquals(10L, old.files)
        assertNull(old.disk); assertNull(old.items)
        assertNull(CacheMeta.parse("x|y"))
        assertNull(CacheMeta.parse("/a|false|nan|1|2"))
        assertNull(CacheMeta.parse(null))
    }
}
