package dev.ancdu

import android.content.Context
import android.content.Intent
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

    private val prefs get() = ins.targetContext.getSharedPreferences("caches", Context.MODE_PRIVATE)

    /** Снимок «caches» до теста: реальные записи «Последний скан» пользователя. */
    private fun snapshot(): Map<String, *> = HashMap(prefs.all)

    /** Возвращает «caches» ровно к снимку (с типами значений). */
    @Suppress("UNCHECKED_CAST")
    private fun restore(snap: Map<String, *>) {
        val e = prefs.edit().clear()
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

    @Test fun showsActionsAndLastScans() {
        val ctx = ins.targetContext
        val snap = snapshot()
        var act: MainActivity? = null
        try {
            ctx.getSharedPreferences("caches", Context.MODE_PRIVATE).edit().clear()
                .putString("last-app_storage_emulated_0.ancdu", "/storage/emulated/0|false|4980|294|1759700000000")
                .commit()
            val a = ins.startActivitySync(Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            act = a
            ins.waitForIdleSync()
            val root = a.window.decorView
            val found = ArrayList<android.view.View>()
            root.findViewsWithText(found, "Сканировать хранилище, /storage/emulated/0 · без root",
                android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
            assertEquals(1, found.size)
            assertEquals(listOf("Последний скан: /storage/emulated/0"), a.lastScans())
            assertNotNull(root.findViewWithTag<SegBar>("segbar"))
        } finally {
            act?.let { a -> ins.runOnMainSync { a.finish() } }
            restore(snap)
        }
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

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(50) }
        return cond()
    }

    /** P4: без доступа к истории использования карточка кликабельна и ведёт на AppsActivity. */
    @Test fun noUsageAccessCardOpensApps() {
        val ctx = ins.targetContext
        val prev = AppOps.get(ins, "GET_USAGE_STATS")
        val mon = ins.addMonitor(AppsActivity::class.java.name, null, false)
        var act: MainActivity? = null
        try {
            AppOps.set(ins, "GET_USAGE_STATS", "ignore")
            val a = ins.startActivitySync(Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            act = a
            var card: View? = null
            assertTrue("нет карточки «нет доступа»",
                waitFor(10_000) { findDesc(a, "Приложения: нет доступа").also { card = it } != null })
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
        val ctx = ins.targetContext
        val name = "last-app_corrupt_test.ancdu"
        val f = File(ctx.filesDir, name).apply { writeText("not a cache") }
        var act: MainActivity? = null
        try {
            prefs.edit().putString(name, "/corrupt/test|false|1|1|1759700000000").commit()
            val a = ins.startActivitySync(Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            act = a
            ins.waitForIdleSync()
            val before = Holder.h
            val row = findDesc(a, "Последний скан: /corrupt/test")
            assertNotNull(row)
            ins.runOnMainSync { row!!.performClick() }
            assertTrue("запись кэша не удалена", waitFor(5_000) { !prefs.contains(name) })
            assertTrue("файл кэша не удалён", waitFor(5_000) { !f.exists() })
            ins.waitForIdleSync()
            assertTrue("строка не убрана", waitFor(5_000) {
                var gone = false
                ins.runOnMainSync { gone = "Последний скан: /corrupt/test" !in a.lastScans() }
                gone
            })
            assertEquals(before, Holder.h)
            assertFalse(f.exists())
        } finally {
            act?.let { a -> ins.runOnMainSync { a.finish() } }
            prefs.edit().remove(name).commit()
            f.delete()
        }
    }
}
