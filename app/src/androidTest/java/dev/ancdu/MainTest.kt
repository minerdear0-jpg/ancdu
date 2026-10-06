package dev.ancdu

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MainTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = ins.targetContext

    private fun prefs(name: String) = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val prefs get() = prefs("caches")

    /** Снимок prefs до теста: реальные записи «Последний скан» и root_last пользователя. */
    private fun snapshot(name: String): Map<String, *> = HashMap(prefs(name).all)

    /** Возвращает prefs ровно к снимку (с типами значений). */
    @Suppress("UNCHECKED_CAST")
    private fun restore(name: String, snap: Map<String, *>) {
        val e = prefs(name).edit().clear()
        for ((k, v) in snap) when (v) {
            is String -> e.putString(k, v)
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is Float -> e.putFloat(k, v)
            is Set<*> -> e.putStringSet(k, v as Set<String>)
        }
        e.commit()
    }

    private lateinit var cachesSnap: Map<String, *>
    private lateinit var rootSnap: Map<String, *>
    private val storageCache get() = Holder.cacheFile(ctx, Scans.STORAGE, false)
    /** Копия кэша общего хранилища пользователя (фоновый скан теста его перезапишет). */
    private var cacheCopy: File? = null

    @Before fun saveState() {
        cachesSnap = snapshot("caches")
        rootSnap = snapshot("root")
        cacheCopy = storageCache.takeIf { it.exists() }?.let { f ->
            File(ctx.cacheDir, "maintest-" + f.name).also { f.copyTo(it, overwrite = true) }
        }
        BgScan.auto = false
        // Каждый тест — с чистого листа: ни дерева, ни ждущего, ни итога прежнего скана.
        ins.runOnMainSync { Holder.clear(); Holder.dropPending(); Scans.lastStorage = null }
    }

    @After fun restoreState() {
        drainIo()
        BgScan.auto = true
        Perms.filesOverride = null
        restore("caches", cachesSnap)
        restore("root", rootSnap)
        ins.runOnMainSync { Root.reset() }
        val copy = cacheCopy
        if (copy != null) { copy.copyTo(storageCache, overwrite = true); copy.delete() }
        else storageCache.delete()   // кэша у пользователя не было — созданный тестом убираем
    }

    /** Все задачи io (free, saveCache, запись «caches») выполнились. */
    private fun drainIo() {
        Holder.io.submit {}.get(30, TimeUnit.SECONDS)
        Thread.sleep(100)   // apply() «caches» после saveCache
    }

    private fun launch(): MainActivity {
        val a = ins.startActivitySync(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        ins.waitForIdleSync()
        return a
    }

    private fun findDesc(a: MainActivity, desc: String): View? {
        var v: View? = null
        ins.runOnMainSync {
            val found = ArrayList<View>()
            a.window.decorView.findViewsWithText(found, desc, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
            v = found.firstOrNull()
        }
        return v
    }

    private fun findText(a: MainActivity, text: String): View? {
        var v: View? = null
        ins.runOnMainSync {
            val found = ArrayList<View>()
            a.window.decorView.findViewsWithText(found, text, View.FIND_VIEWS_WITH_TEXT)
            v = found.firstOrNull()
        }
        return v
    }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(50) }
        return cond()
    }

    private fun onMain(cond: () -> Boolean): Boolean { var r = false; ins.runOnMainSync { r = cond() }; return r }

    /** Карточка — вход; без основной кнопки; общее хранилище не в «Последний скан», root-кэши — в блоке Root. */
    @Test fun cardIsTheEntryPointWithoutPrimaryButton() {
        var act: MainActivity? = null
        try {
            Perms.filesOverride = true
            prefs.edit().clear()
                .putString(storageCache.name, "/storage/emulated/0|false|4980|294|1759700000000|123456|5000")
                .putString("last-app_some_dir.ancdu", "/some/dir|false|10|5|1759700000001")
                .putString("last-su_data.ancdu", "/data|true|99|50|1759700000002")
                .commit()
            val a = launch().also { act = it }
            assertNull(findDesc(a, "Сканировать хранилище"))
            assertNull(findDesc(a, "Быстрый обзор"))
            assertNotNull(findDesc(a, a.getString(R.string.card_desc)))
            assertEquals(listOf(a.getString(R.string.last_scan, "/some/dir")), a.lastScans())
            assertEquals(listOf(a.getString(R.string.last_scan, "/data")), a.rootScans())
            ins.runOnMainSync {
                assertEquals(a.getString(R.string.shared_title), a.storage.storeTitle.text.toString())
                assertEquals("${Fmt.size(123456, a.tx)} · ${a.tx.items(5000)}", a.storage.storeTotal.text.toString())
                assertTrue(a.storage.freshTxt.text.toString(), a.storage.freshTxt.text.startsWith(a.prefixOf(R.string.fresh_cache)))
                assertNotNull(a.window.decorView.findViewWithTag<SegBar>("segbar"))
                assertTrue(a.storage.view.isClickable)
            }
        } finally {
            act?.let { a -> ins.runOnMainSync { a.finish() } }
        }
    }

    /**
     * Время на карточке — от показанного дерева, а не от записи «caches»: свежая запись (новый
     * фоновый скан, ещё не подставленный) не даёт «только что» рядом со старыми итогами.
     */
    @Test fun cardTimeComesFromTheShownTree() {
        val dir = java.nio.file.Files.createTempDirectory(ctx.cacheDir.toPath(), "card").toFile()
        File(dir, "a.bin").writeBytes(ByteArray(4096))
        var act: MainActivity? = null
        try {
            Perms.filesOverride = true
            val h = Native.scanStart(dir.path, true, 1, IntArray(1))
            val p = LongArray(6)
            assertTrue(waitFor(10_000) { Native.progress(h, p); p[0] != ST_RUNNING.toLong() })
            val old = 1_759_700_000_000L
            ins.runOnMainSync { Holder.set(h, Kind.CACHE, Scans.STORAGE, false, old) }
            prefs.edit().putString(storageCache.name,
                CacheMeta(Scans.STORAGE, false, 999, 1, System.currentTimeMillis(), 999_999_999, 999).format()).commit()
            val a = launch().also { act = it }
            ins.runOnMainSync {
                val line = a.storage.freshTxt.text.toString()
                assertTrue(line, line.startsWith(a.prefixOf(R.string.fresh_cache)))
                assertFalse(line, line.endsWith(a.getString(R.string.just_now)))
                assertTrue(a.storage.storeTotal.text.toString(), a.storage.storeTotal.text.endsWith(" · " + a.tx.items(2)))
            }
        } finally {
            act?.let { a -> ins.runOnMainSync { a.finish() } }
            ins.runOnMainSync { Holder.clear() }
            dir.deleteRecursively()
        }
    }

    /** С доступом: кэш старше 60 с — фоновый скан сам обновляет карточку; тап открывает браузер. */
    @Test fun autoScanRefreshesCardAndTapOpensBrowser() {
        // Прежний режим вернётся после выхода процесса теста (см. AppOps.restoreAfterExit).
        AppOps.restoreAfterExit(ins, "MANAGE_EXTERNAL_STORAGE", AppOps.get(ins, "MANAGE_EXTERNAL_STORAGE"))
        AppOps.set(ins, "MANAGE_EXTERNAL_STORAGE", "allow")
        assertTrue("нет MANAGE_EXTERNAL_STORAGE", Perms.files())
        val pm = ctx.getSystemService(PowerManager::class.java)
        assumeTrue("энергосбережение/нагрев — автоскан законно не идёт",
            !pm.isPowerSaveMode && pm.currentThermalStatus < PowerManager.THERMAL_STATUS_MODERATE)
        // Кэш «старый»: время записи — давно.
        val old = CacheMeta.parse(prefs.getString(storageCache.name, null))
        if (old != null) prefs.edit().putString(storageCache.name, old.copy(time = 1).format()).commit()
        BgScan.auto = true
        val t0 = System.currentTimeMillis()
        val mon = ins.addMonitor(BrowserActivity::class.java.name, null, false)
        var act: MainActivity? = null
        try {
            val a = launch().also { act = it }
            assertTrue("фоновый скан не завершился", waitFor(30_000) {
                onMain { !BgScan.active && (Scans.lastStorage?.time ?: 0) >= t0 }
            })
            assertTrue("карточка не обновилась", waitFor(5_000) {
                onMain { a.storage.freshTxt.text.endsWith(a.getString(R.string.just_now)) && a.storage.storeTotal.text.contains(" · ") }
            })
            ins.runOnMainSync {
                assertEquals(Scans.STORAGE, Holder.root)
                assertEquals(Kind.SCAN, Holder.kind)
                a.storage.view.performClick()
            }
            val b = ins.waitForMonitorWithTimeout(mon, 5_000)
            assertNotNull("BrowserActivity не открыт", b)
            ins.runOnMainSync { b.finish() }
        } finally {
            ins.removeMonitor(mon)
            act?.let { a -> ins.runOnMainSync { a.finish() } }
        }
    }

    /** Без доступа: амберная строка; тап раскрывает объяснение с «Открыть настройки» (не нажимается). */
    @Test fun noPermissionShowsAmberRowAndInlineExplanation() {
        var act: MainActivity? = null
        try {
            Perms.filesOverride = false
            val a = launch().also { act = it }
            ins.runOnMainSync {
                assertEquals(a.getString(R.string.open_tree_need_access), a.storage.storeTitle.text.toString())
                assertEquals(C.WARN, a.storage.storeTitle.currentTextColor)
                assertEquals(View.GONE, a.storage.permBox.visibility)
                a.storage.view.performClick()
                assertEquals(View.VISIBLE, a.storage.permBox.visibility)
            }
            assertNotNull(findDesc(a, a.getString(R.string.open_settings)))
            assertNotNull(findText(a, a.getString(R.string.files_access_explain)))
            ins.runOnMainSync { a.storage.view.performClick(); assertEquals(View.GONE, a.storage.permBox.visibility) }
        } finally {
            act?.let { a -> ins.runOnMainSync { a.finish() } }
        }
    }

    /** «su»: подменённый результат (настоящий su не запускается) — su → su… → root ✓ / root ✗. */
    @Test fun suPillShowsRequestResult() {
        val prevCheck = Root.suCheck
        val prevProbe = Root.probe
        var act: MainActivity? = null
        try {
            Root.suCheck = { true }
            prefs("root").edit().remove("root_last").commit()
            ins.runOnMainSync { Root.reset() }
            val gate = CountDownLatch(1)
            var ok = true
            Root.probe = { _ ->
                gate.await(10, TimeUnit.SECONDS)
                if (ok) Root.Probe(0, "uid=0(root) gid=0(root)", false) else Root.Probe(null, "", true)
            }
            val a = launch().also { act = it }
            val pill = a.rootPanel.pill
            assertNotNull("нет пилюли su", pill)
            ins.runOnMainSync {
                assertEquals("su", pill!!.text.toString())
                assertTrue(pill.minHeight >= a.dp(44))
                assertEquals(a.getString(R.string.su_desc_ask), pill.contentDescription)
                pill.performClick()
                assertEquals(RootState.ASKING, Root.state)
                assertTrue(pill.text.startsWith("su"))
            }
            gate.countDown()
            assertTrue(waitFor(5_000) { onMain { pill!!.text.toString() == "root ✓" } })
            ins.runOnMainSync { assertEquals(C.OK_TXT, pill!!.currentTextColor) }
            assertTrue(prefs("root").getString("root_last", "")!!.startsWith("granted|"))

            ok = false   // таймаут
            ins.runOnMainSync { pill!!.performClick() }
            assertTrue(waitFor(5_000) { onMain { pill!!.text.toString() == "root ✗" } })
            ins.runOnMainSync { assertEquals(C.WARN, pill!!.currentTextColor) }
            assertNotNull(findText(a, a.getString(R.string.root_denied_hint)))
            assertTrue(prefs("root").getString("root_last", "")!!.startsWith("denied|"))
        } finally {
            Root.suCheck = prevCheck
            Root.probe = prevProbe
            act?.let { a -> ins.runOnMainSync { a.finish() } }
        }
    }

    /** su нет — пилюли нет («root: нет» не показывается). */
    @Test fun noSuNoPill() {
        val prevCheck = Root.suCheck
        var act: MainActivity? = null
        try {
            Root.suCheck = { false }
            val a = launch().also { act = it }
            assertNull(a.rootPanel.pill)
            assertNull(findText(a, "root: нет"))
        } finally {
            Root.suCheck = prevCheck
            act?.let { a -> ins.runOnMainSync { a.finish() } }
        }
    }

    /** P4: без доступа к истории использования карточка кликабельна и ведёт на AppsActivity. */
    @Test fun noUsageAccessCardOpensApps() {
        val prev = AppOps.get(ins, "GET_USAGE_STATS")
        val mon = ins.addMonitor(AppsActivity::class.java.name, null, false)
        var act: MainActivity? = null
        try {
            AppOps.set(ins, "GET_USAGE_STATS", "ignore")
            val a = launch().also { act = it }
            var card: View? = null
            assertTrue("нет карточки «нет доступа»",
                waitFor(10_000) { findDesc(a, a.getString(R.string.apps_no_access)).also { card = it } != null })
            ins.runOnMainSync { card!!.performClick() }
            val apps = ins.waitForMonitorWithTimeout(mon, 5_000)
            assertNotNull("AppsActivity не открыт", apps)
            ins.runOnMainSync { apps.finish() }
        } finally {
            ins.removeMonitor(mon)
            act?.let { a -> ins.runOnMainSync { a.finish() } }
            AppOps.set(ins, "GET_USAGE_STATS", prev)
        }
    }

    /** Битый кэш: открытие вне главного потока, запись и файл удаляются, сессия не меняется. */
    @Test fun corruptCacheIsDropped() {
        val name = "last-app_corrupt_test.ancdu"
        val f = File(ctx.filesDir, name).apply { writeText("not a cache") }
        var act: MainActivity? = null
        try {
            prefs.edit().putString(name, "/corrupt/test|false|1|1|1759700000000").commit()
            val a = launch().also { act = it }
            val before = Holder.h
            val row = findDesc(a, a.getString(R.string.last_scan, "/corrupt/test"))
            assertNotNull(row)
            ins.runOnMainSync { row!!.performClick() }
            assertTrue("запись кэша не удалена", waitFor(5_000) { !prefs.contains(name) })
            assertTrue("файл кэша не удалён", waitFor(5_000) { !f.exists() })
            ins.waitForIdleSync()
            assertTrue("строка не убрана", waitFor(5_000) { onMain { a.getString(R.string.last_scan, "/corrupt/test") !in a.lastScans() } })
            assertEquals(before, Holder.h)
            assertFalse(f.exists())
        } finally {
            act?.let { a -> ins.runOnMainSync { a.finish() } }
            prefs.edit().remove(name).commit()
            f.delete()
        }
    }
}
