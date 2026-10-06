package dev.ancdu

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ScanTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = ins.targetContext

    /** Опрос с таймаутом: выходит при успехе, иначе после [ms]. [ok] — на главном потоке. */
    private fun waitFor(ms: Long = 10_000, ok: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            var r = false
            ins.runOnMainSync { r = ok() }
            if (r) return true
            Thread.sleep(20)
        }
        return false
    }

    /** Самое большое доступное приложению дерево — общее хранилище; нужен доступ ко всем файлам. */
    private fun bigRoot(): String {
        if (!filesRestoreScheduled) {
            // Прежний режим вернётся после выхода процесса теста (см. AppOps.restoreAfterExit).
            AppOps.restoreAfterExit(ins, "MANAGE_EXTERNAL_STORAGE", AppOps.get(ins, "MANAGE_EXTERNAL_STORAGE"))
            filesRestoreScheduled = true
        }
        AppOps.set(ins, "MANAGE_EXTERNAL_STORAGE", "allow")
        assertTrue("нет MANAGE_EXTERNAL_STORAGE", Perms.files())
        return "/storage/emulated/0"
    }

    /** Запуск без ожидания idle: тест получает экземпляр сразу после onCreate, пока скан идёт. */
    private fun launch(root: String): ScanActivity {
        val mon = ins.addMonitor(ScanActivity::class.java.name, null, false)
        ctx.startActivity(Intent(ctx, ScanActivity::class.java)
            .putExtra(EXTRA_ROOT, root).putExtra(EXTRA_SU, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val a = ins.waitForMonitorWithTimeout(mon, 10_000) as ScanActivity?
        ins.removeMonitor(mon)
        assertNotNull("ScanActivity не запустилась", a)
        return a!!
    }

    /** Все задачи io (free, saveCache) выполнились — ничего не висит. */
    private fun drainIo() { Holder.io.submit {}.get(30, TimeUnit.SECONDS) }

    /** Пересоздание Activity во время скана не перезапускает и не теряет скан. */
    @Test fun survivesRecreateWhileRunningAndOpensBrowser() {
        val root = bigRoot()
        val cache = Holder.cacheFile(ctx, root, false).apply { delete() }
        val prefs = ctx.getSharedPreferences("caches", Context.MODE_PRIVATE)
        prefs.edit().remove(cache.name).commit()
        val browserMon = ins.addMonitor(BrowserActivity::class.java.name, null, false)

        val act1 = launch(root)
        var act2: ScanActivity? = null
        val cb = ActivityLifecycleCallback { a, stage ->
            if (stage == Stage.CREATED && a is ScanActivity && a !== act1) act2 = a
        }
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(cb)
        try {
            var h1 = 0L
            var state = -1L
            var filesAtRecreate = -1L
            ins.runOnMainSync {
                h1 = Holder.h
                val p = Holder.progress()
                state = p[0]; filesAtRecreate = p[1]
                if (state == ST_RUNNING.toLong()) act1.recreate()
            }
            assertNotEquals(0L, h1)
            assertEquals("скан должен идти в момент recreate", ST_RUNNING.toLong(), state)

            // Новый экземпляр подхватил тот же скан и показывает его прогресс.
            assertTrue(waitFor { act2.let { it != null && it.files > 0 } })
            var shown = 0L
            ins.runOnMainSync { shown = act2!!.files }
            assertTrue("прогресс не восстановлен: $shown < $filesAtRecreate", shown >= filesAtRecreate)
            assertEquals(h1, Holder.h)

            val browser = ins.waitForMonitorWithTimeout(browserMon, 60_000)
            assertNotNull("браузер не открылся", browser)
            assertEquals(h1, Holder.h)   // тот же дескриптор — ни второго скана, ни утечки
            assertEquals(ST_DONE.toLong(), Holder.progress()[0])
            assertEquals(Kind.SCAN, Holder.kind)
            assertEquals(root, Holder.root)

            drainIo()   // кэш пишется на Holder.io
            assertTrue(cache.exists())
            assertTrue(waitFor { prefs.getString(cache.name, null)?.startsWith("$root|false|") == true })
            ins.runOnMainSync { browser.finish() }
        } finally {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(cb)
            ins.removeMonitor(browserMon)
            cache.delete()
            prefs.edit().remove(cache.name).commit()
        }
    }

    /** Назад во время скана: отмена, возврат к главному, сессия освобождена, браузера нет. */
    @Test fun backCancelsAndFinishes() {
        val root = bigRoot()
        val browserMon = ins.addMonitor(BrowserActivity::class.java.name, null, false)
        try {
            val act = launch(root)
            var state = -1L
            var h = 0L
            ins.runOnMainSync {
                h = Holder.h
                state = Holder.progress()[0]
                if (state == ST_RUNNING.toLong()) act.onBackPressed()
            }
            assertNotEquals(0L, h)
            assertEquals(ST_RUNNING.toLong(), state)
            ins.runOnMainSync { assertTrue(act.isFinishing) }
            assertEquals(0L, Holder.h)
            assertTrue(waitFor { act.isDestroyed })
            drainIo()   // free прошёл, io не завис
            assertEquals(0, browserMon.hits)
        } finally {
            ins.removeMonitor(browserMon)
        }
    }

    /** Скан не удался (корня нет): диалог; закрытие любым путём (здесь — «назад»/тап вне) возвращает к главному. */
    @Test fun failureDialogDismissFinishes() {
        val act = ins.startActivitySync(Intent(ctx, ScanActivity::class.java)
            .putExtra(EXTRA_ROOT, "/nonexistent-ancdu").putExtra(EXTRA_SU, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ScanActivity
        ins.waitForIdleSync()
        ins.runOnMainSync {
            val d = act.failure
            assertNotNull("нет диалога ошибки", d)
            assertTrue(d!!.isShowing)
            d.cancel()   // как «назад» или тап вне диалога
        }
        assertTrue(waitFor { act.isFinishing })   // слушатель закрытия диалога приходит сообщением
        assertTrue(waitFor { act.isDestroyed })
    }
}

/** Один отложенный возврат MANAGE_EXTERNAL_STORAGE на процесс теста. */
private var filesRestoreScheduled = false
