package dev.ancdu

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MainTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    @Test fun showsActionsAndLastScans() {
        val ctx = ins.targetContext
        ctx.getSharedPreferences("caches", Context.MODE_PRIVATE).edit().clear()
            .putString("last-app_storage_emulated_0.ancdu", "/storage/emulated/0|false|4980|294|1759700000000")
            .commit()
        val act = ins.startActivitySync(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        ins.waitForIdleSync()
        val root = act.window.decorView
        val found = ArrayList<android.view.View>()
        root.findViewsWithText(found, "Сканировать хранилище, /storage/emulated/0 · без root",
            android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        assertEquals(1, found.size)
        assertEquals(listOf("Последний скан: /storage/emulated/0"), act.lastScans())
        assertNotNull(root.findViewWithTag<SegBar>("segbar"))
        ins.runOnMainSync { act.finish() }
        ctx.getSharedPreferences("caches", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun shell(cmd: String) {
        ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(cmd)).use { it.readBytes() }
    }

    private fun findDesc(act: MainActivity, desc: String): View? {
        var v: View? = null
        ins.runOnMainSync {
            val found = ArrayList<View>()
            act.window.decorView.findViewsWithText(found, desc, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
            v = found.firstOrNull()
        }
        return v
    }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(50) }
        return cond()
    }

    /** P4: без доступа к истории использования карточка кликабельна и ведёт на AppsActivity. */
    @Test fun noUsageAccessCardOpensApps() {
        val ctx = ins.targetContext
        shell("appops set ${ctx.packageName} GET_USAGE_STATS ignore")
        val mon = ins.addMonitor(AppsActivity::class.java.name, null, false)
        val act = ins.startActivitySync(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            var card: View? = null
            assertTrue("нет карточки «нет доступа»",
                waitFor(10_000) { findDesc(act, "Приложения: нет доступа").also { card = it } != null })
            ins.runOnMainSync { card!!.performClick() }
            val apps = ins.waitForMonitorWithTimeout(mon, 5_000)
            assertNotNull("AppsActivity не открыт", apps)
            ins.runOnMainSync { apps.finish() }
        } finally {
            ins.removeMonitor(mon)
            ins.runOnMainSync { act.finish() }
            shell("appops set ${ctx.packageName} GET_USAGE_STATS default")
        }
    }

    /** Битый кэш: открытие вне главного потока, запись и файл удаляются, сессия не меняется. */
    @Test fun corruptCacheIsDropped() {
        val ctx = ins.targetContext
        val name = "last-app_corrupt_test.ancdu"
        val f = File(ctx.filesDir, name).apply { writeText("not a cache") }
        val prefs = ctx.getSharedPreferences("caches", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(name, "/corrupt/test|false|1|1|1759700000000").commit()
        val act = ins.startActivitySync(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            ins.waitForIdleSync()
            val before = Holder.h
            val row = findDesc(act, "Последний скан: /corrupt/test")
            assertNotNull(row)
            ins.runOnMainSync { row!!.performClick() }
            assertTrue("запись кэша не удалена", waitFor(5_000) { !prefs.contains(name) })
            assertTrue("файл кэша не удалён", waitFor(5_000) { !f.exists() })
            ins.waitForIdleSync()
            assertTrue("строка не убрана", waitFor(5_000) {
                var empty = false
                ins.runOnMainSync { empty = act.lastScans().isEmpty() }
                empty
            })
            assertEquals(before, Holder.h)
            assertFalse(f.exists())
        } finally {
            ins.runOnMainSync { act.finish() }
            prefs.edit().clear().commit()
            f.delete()
        }
    }
}
