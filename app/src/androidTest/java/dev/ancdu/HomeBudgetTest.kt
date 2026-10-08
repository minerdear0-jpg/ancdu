package dev.ancdu

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI BUDGET on the main screen: the card has at most 5 text lines plus the bar, the status line
 * shows one fact by priority, and the storage-full rule folds the rows under the card. No deletes.
 */
@RunWith(AndroidJUnit4::class)
class HomeBudgetTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = ins.targetContext
    private val prefs get() = ctx.getSharedPreferences(Scans.PREFS, Context.MODE_PRIVATE)
    private val storageCache get() = Holder.cacheFile(ctx, Scans.STORAGE, false)
    private val gib = 1L shl 30
    private var meta: String? = null
    private var usage = "default"
    private var act: MainActivity? = null

    @Before fun setUp() {
        meta = prefs.getString(storageCache.name, null)
        usage = AppOps.get(ins, "GET_USAGE_STATS")
        BgScan.auto = false
        Perms.filesOverride = true
        ins.runOnMainSync { Holder.clear(); Holder.dropPending(); Scans.lastStorage = null }
    }

    @After fun tearDown() {
        act?.let { a -> ins.runOnMainSync { a.finish() } }
        StorageCard.fakeStatfs = null
        Perms.filesOverride = null
        BgScan.auto = true
        AppOps.set(ins, "GET_USAGE_STATS", usage)
        val e = prefs.edit()
        if (meta != null) e.putString(storageCache.name, meta) else e.remove(storageCache.name)
        e.commit()
    }

    private fun cache(time: Long) = prefs.edit().putString(storageCache.name,
        CacheMeta(Scans.STORAGE, false, 4980, 294, time, 35 * gib, 5000).format()).commit()

    private fun launch(): MainActivity {
        val a = ins.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        ins.waitForIdleSync()
        act = a
        return a
    }

    private fun cut() = InterruptedDelete(1, Scans.STORAGE, false, listOf("Download".toByteArray()), dir = true,
        disk = 10 * gib, time = System.currentTimeMillis() - 60_000)

    /**
     * fontScale 1.0, with and without usage access: label, hero, free, shared, status — 5 lines at
     * most. With access the tier-0 category bar shows, but no legend lines (TalkBack has them).
     */
    private fun cardLines(usageMode: String) {
        AppOps.set(ins, "GET_USAGE_STATS", usageMode)
        assertEquals(1.0f, ctx.resources.configuration.fontScale)
        cache(System.currentTimeMillis())
        val a = launch()
        // Tier 0 has answered (the apps row exists) before counting.
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            ins.runOnMainSync { ready = a.appsTotal != null }
            if (ready) break
            Thread.sleep(50)
        }
        ins.runOnMainSync { a.storage.interrupted = cut(); a.storage.render() }
        ins.waitForIdleSync()
        ins.runOnMainSync {
            assertTrue(a.storage.status is Status.Interrupted)
            val n = a.storage.textLines()
            assertTrue("card lines ($usageMode): $n", n <= 5)
            // The status line is one line, never wraps.
            assertEquals(1, a.storage.statusTxt.lineCount)
            // Default states are silent: no «только что», no item count on the shared row.
            assertFalse(a.storage.storeTotal.text.toString(), a.storage.storeTotal.text.contains(" · "))
            val bar = a.window.decorView.findViewWithTag<View>("catbar")
            if (usageMode == "allow" && bar != null && (bar.parent as View).visibility == View.VISIBLE)
                assertFalse("category bar without a TalkBack legend", bar.contentDescription.isNullOrEmpty())
        }
    }

    @Test fun cardHasAtMostFiveTextLinesWithoutUsageAccess() = cardLines("ignore")

    @Test fun cardHasAtMostFiveTextLinesWithUsageAccess() = cardLines("allow")

    /** interrupted > stale >= 24 h > nothing; the slot collapses when there is nothing. */
    @Test fun statusLinePriorities() {
        cache(System.currentTimeMillis() - 3 * 86_400_000L)
        val a = launch()
        ins.runOnMainSync {
            assertTrue(a.storage.status.toString(), a.storage.status is Status.Stale)
            assertEquals(View.VISIBLE, a.storage.statusTxt.visibility)
            assertTrue(a.storage.statusTxt.isClickable)
            a.storage.interrupted = cut(); a.storage.render()
            assertTrue(a.storage.status is Status.Interrupted)
            assertTrue(a.storage.statusTxt.text.toString(), a.storage.statusTxt.text.startsWith("⚠"))
            a.storage.interrupted = null
        }
        cache(System.currentTimeMillis())
        ins.runOnMainSync {
            a.storage.render()
            assertTrue(a.storage.status.toString(), a.storage.status === Status.None)
            assertEquals(View.GONE, a.storage.statusTxt.visibility)
        }
    }

    /** free < 1 GiB: the hero is the free number, «мало места», the rows fold into one «ещё» row. */
    @Test fun storageFullFoldsRows() {
        StorageCard.fakeStatfs = longArrayOf(224 * gib, gib / 2, gib / 2)
        cache(System.currentTimeMillis())
        val a = launch()
        ins.runOnMainSync {
            assertTrue(a.storage.full)
            // At most two accents: the free hero and the status line; «⚠ N» waits in the browser footer.
            assertEquals(View.GONE, a.storage.errTxt.visibility)
            val low = a.getString(R.string.card_low, "")
            assertTrue(a.storage.freeTxt.text.toString(), a.storage.freeTxt.text.startsWith(low))
            assertEquals(View.VISIBLE, a.moreRow.visibility)
            assertTrue(a.rootPanel.block == null || a.rootPanel.block!!.visibility == View.GONE)
            val row = a.moreRow.getChildAt(0) as TextView
            assertTrue(row.text.toString(), row.text.startsWith(a.getString(R.string.more_row, "").removeSuffix(" ›")))
            assertTrue(row.performClick())
            assertEquals(View.GONE, a.moreRow.visibility)
        }
        // Not full: no fold, the hero is «used».
        ins.runOnMainSync { a.finish() }
        act = null
        StorageCard.fakeStatfs = longArrayOf(224 * gib, 142 * gib, 142 * gib)
        val b = launch()
        ins.runOnMainSync {
            assertFalse(b.storage.full)
            assertEquals(View.GONE, b.moreRow.visibility)
            assertTrue(b.storage.freeTxt.text.toString(), b.storage.freeTxt.text.startsWith(b.getString(R.string.card_free, "")))
        }
    }

    /** The «apps and system» figure is /data used minus shared storage (here 82 − 35 = 47 GiB). */
    @Test fun appsAndSystemFigure() {
        StorageCard.fakeStatfs = longArrayOf(224 * gib, 142 * gib, 142 * gib)
        cache(System.currentTimeMillis())
        val a = launch()
        val ok = (0 until 100).any {
            var shown = false
            ins.runOnMainSync { shown = a.appsTotal?.text?.toString() == Fmt.size(47 * gib, a.tx) }
            shown || run { Thread.sleep(100); false }
        }
        assertTrue("apps figure: ${a.appsTotal?.text}", ok)
    }

    /** Storage full never pre-checks fast root nor shortens the countdown (DeletePolicy has no storage input). */
    @Test fun storageFullKeepsDeletePolicy() {
        StorageCard.fakeStatfs = longArrayOf(224 * gib, gib / 2, gib / 2)
        cache(System.currentTimeMillis())
        val a = launch()
        ins.runOnMainSync { assertTrue(a.storage.full) }
        assertFalse(DeletePolicy.fastByDefault(999, RootState.GRANTED))
        assertEquals(DeletePolicy.ROOT_PAUSE_MS, DeletePolicy.tier(false, true, false, 1).pauseMs)
        assertEquals(DeletePolicy.PAUSE_MS, DeletePolicy.tier(false, false, true, 1).pauseMs)
    }
}
