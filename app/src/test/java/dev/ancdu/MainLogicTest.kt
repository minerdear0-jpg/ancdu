package dev.ancdu

import dev.ancdu.XmlTxt.Companion.EN
import dev.ancdu.XmlTxt.Companion.RU
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
        Freshness.line(RU, running, live, cache, scanned, blocked, approx, now, utc)

    @Test fun fresh() {
        assertEquals("скан 21:33 · только что", line(cache = t - 5_000, scanned = true))
        assertEquals("скан 21:33 · 5${Fmt.NBSP}мин назад", line(cache = t, scanned = true, now = t + 5 * 60_000))
        assertEquals("кэш 21:33 · 2${Fmt.NBSP}ч назад", line(cache = t, now = t + 2 * 3_600_000 + 1))
        assertEquals("кэш 21:33 · 05.10", line(cache = t, now = t + 2 * 86_400_000L))
    }

    @Test fun refreshingAndFirst() {
        assertEquals("кэш 21:33 · обновляю… 1${Fmt.NBSP}234 эл.", line(running = true, live = 1234, cache = t))
        assertEquals("4${Fmt.NBSP}980 эл. · первый скан", line(running = true, live = 4980))
    }

    @Test fun blockedAndNothing() {
        assertEquals("обновить ›", line(blocked = true))
        assertEquals("кэш 21:33 · обновить ›", line(blocked = true, cache = t))
        assertEquals("обновить ›", line())
        // идущий скан важнее запрета
        assertEquals("0 эл. · первый скан", line(running = true, blocked = true))
    }

    @Test fun english() {
        fun en(running: Boolean = false, live: Long = 0, cache: Long? = null, scanned: Boolean = false,
               blocked: Boolean = false, approx: Boolean = false, now: Long = t) =
            Freshness.line(EN, running, live, cache, scanned, blocked, approx, now, utc)
        assertEquals("scan 21:33 · just now", en(cache = t - 5_000, scanned = true))
        assertEquals("cache 21:33 · 5${Fmt.NBSP}min ago", en(cache = t, now = t + 5 * 60_000))
        assertEquals("cache 21:33 · Oct 5", en(cache = t, now = t + 2 * 86_400_000L))
        assertEquals("cache 21:33 · updating… 1,234 items", en(running = true, live = 1234, cache = t))
        assertEquals("1 item · first scan", en(running = true, live = 1))
        assertEquals("approximate · refresh ›", en(approx = true))
    }

    @Test fun approximate() {
        assertEquals("приблизительно · 10 эл. · первый скан", line(running = true, live = 10, approx = true))
        assertEquals("приблизительно · обновить ›", line(approx = true))
    }

    /** Индекс — не скан: у него нет времени, даже если в Holder записано «сейчас». */
    @Test fun indexTreeHasNoTime() {
        assertNull(Freshness.treeTime(Kind.INDEX, t))
        assertNull(Freshness.treeTime(Kind.SCAN, 0))
        assertEquals(t, Freshness.treeTime(Kind.SCAN, t))
        assertEquals(t, Freshness.treeTime(Kind.CACHE, t))
    }

    /** Карточка с индексом: после неудачного скана — повтор «обновить ›», пока идёт — «первый скан». */
    @Test fun indexLinesFailedAndRunning() {
        val idx = Freshness.treeTime(Kind.INDEX, t)
        val failed = line(cache = idx, scanned = false, approx = true)
        assertEquals("приблизительно · обновить ›", failed)
        assertTrue(failed.endsWith(Freshness.refresh(RU)))     // тап по строке — ручной повтор
        assertEquals("приблизительно · 1${Fmt.NBSP}234 эл. · первый скан",
            line(running = true, live = 1234, cache = idx, approx = true))
        // и под энергосбережением — тот же повтор
        assertEquals("приблизительно · обновить ›", line(cache = idx, blocked = true, approx = true))
    }

    @Test fun delta() {
        assertEquals("+1,5${Fmt.NBSP}МиБ с прошлого скана", Freshness.delta(RU, 1_572_864))
        assertEquals("−2,0${Fmt.NBSP}КиБ с прошлого скана", Freshness.delta(RU, -2048))
        assertEquals("±0${Fmt.NBSP}Б с прошлого скана", Freshness.delta(RU, 0))
        assertEquals("+1.5${Fmt.NBSP}MiB since last scan", Freshness.delta(EN, 1_572_864))
    }
}

class PathWalkTest {
    private fun b(s: String) = s.toByteArray(Charsets.UTF_8)
    private fun raw(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** Дерево имён (байты): узел → [(имя, ребёнок)]. 0 — корень. */
    private val tree: Map<Int, List<Pair<ByteArray, Int>>> = mapOf(
        0 to listOf(b("DCIM") to 1, b("Download") to 2, raw(0xff) to 5, raw(0xfe) to 6),
        1 to listOf(b("Camera") to 3),
        3 to listOf(b("IMG_1.jpg") to 4),
        2 to emptyList(), 5 to emptyList(), 6 to emptyList())
    private fun walk(vararg names: ByteArray) = PathWalk.resolve(names.toList()) { n, s ->
        tree[n]?.firstOrNull { it.first.contentEquals(s) }?.second
    }

    @Test fun exactPath() {
        assertEquals(PathWalk.Hit(3, true), walk(b("DCIM"), b("Camera")))
        assertEquals(PathWalk.Hit(0, true), walk())
    }

    @Test fun deepestExistingAncestor() {
        assertEquals(PathWalk.Hit(1, false), walk(b("DCIM"), b("Screenshots"), b("x")))
        assertEquals(PathWalk.Hit(0, false), walk(b("gone")))
        assertEquals(PathWalk.Hit(2, false), walk(b("Download"), b("a")))
    }

    /** Невалидный UTF-8: оба имени декодируются в «\uFFFD», но это разные папки. */
    @Test fun invalidUtf8NamesStayDistinct() {
        assertEquals(String(raw(0xff), Charsets.UTF_8), String(raw(0xfe), Charsets.UTF_8))
        assertEquals(PathWalk.Hit(5, true), walk(raw(0xff)))
        assertEquals(PathWalk.Hit(6, true), walk(raw(0xfe)))
        assertEquals(PathWalk.Hit(0, false), walk(b("\uFFFD")))
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

class SwapTest {
    @Test fun directOnlyWhenVisibleUnpinnedIdleAndOwned() {
        assertTrue(Swap.direct(visible = true, browsers = 0, deleting = false, owns = true))
        assertFalse(Swap.direct(visible = false, browsers = 0, deleting = false, owns = true))
        assertFalse(Swap.direct(visible = true, browsers = 1, deleting = false, owns = true))
        assertFalse(Swap.direct(visible = true, browsers = 0, deleting = true, owns = true))
        assertFalse("чужой корень (root-сессия)", Swap.direct(visible = true, browsers = 0, deleting = false, owns = false))
    }

    /** B1: ждущее дерево — «новее» только того же корня И того же режима su. */
    @Test fun newerNeedsSameRootAndSameSuMode() {
        val s = "/storage/emulated/0"
        assertTrue(Swap.newer(7L, s, false, s, false))
        assertTrue(Swap.newer(7L, "/data", true, "/data", true))
        assertFalse("слот пуст", Swap.newer(0L, s, false, s, false))
        assertFalse("другой корень", Swap.newer(7L, "/data", false, s, false))
        // скан без root не предлагается поверх root-дерева того же пути (и наоборот)
        assertFalse(Swap.newer(7L, s, false, s, true))
        assertFalse(Swap.newer(7L, s, true, s, false))
    }

    /** Root-скан сам (без действия пользователя) — только после root-удаления или если root выдан. */
    @Test fun autoRootNeedsAFreshGrant() {
        for (st in RootState.values()) {
            assertTrue("без su — всегда", Swap.autoRoot(su = false, delRoot = false, state = st))
            assertTrue("после root-удаления", Swap.autoRoot(su = true, delRoot = true, state = st))
        }
        assertTrue(Swap.autoRoot(su = true, delRoot = false, state = RootState.GRANTED))
        for (st in listOf(RootState.UNKNOWN, RootState.ASKING, RootState.DENIED))
            assertFalse("$st", Swap.autoRoot(su = true, delRoot = false, state = st))
    }

    @Test fun promoteOnReturnNeedsAStoragePending() {
        assertTrue(Swap.promoteOnMain(pendingStorage = true, mainResumed = true, browsers = 0, deleting = false, owns = true))
        assertFalse(Swap.promoteOnMain(pendingStorage = false, mainResumed = true, browsers = 0, deleting = false, owns = true))
        assertFalse(Swap.promoteOnMain(pendingStorage = true, mainResumed = false, browsers = 0, deleting = false, owns = true))
    }

    /**
     * «Назад» из браузера: Browser.onPause → Main.onResume → Browser.onDestroy. Браузер снимает
     * закрепление в onPause при isFinishing, поэтому в Main.onResume browsers уже 0; а снятие
     * закрепления при уже видимом главном экране снова проверяет подстановку.
     */
    @Test fun backNavigationOrder() {
        val pins = Swap.Pins()
        pins.pin()                                   // браузер открыт
        assertFalse(Swap.promoteOnMain(true, mainResumed = false, browsers = pins.count, deleting = false, owns = true))
        pins.unpin()                                 // Browser.onPause, isFinishing
        assertTrue(Swap.promoteOnMain(true, mainResumed = true, browsers = pins.count, deleting = false, owns = true))
        pins.unpin()                                 // Browser.onDestroy — идемпотентно
        assertEquals(0, pins.count)
    }

    @Test fun unpinNotifiesSoAResumedMainRechecks() {
        var notified = 0
        val pins = Swap.Pins { notified++ }
        pins.pin(); pins.unpin(); pins.unpin()
        assertEquals("уведомление только при реальном снятии", 1, notified)
    }
}
