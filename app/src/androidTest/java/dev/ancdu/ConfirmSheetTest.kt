package dev.ancdu

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Лист подтверждения приложения ([ConfirmSheet]) вместо системного диалога: защита «опасной» кнопки
 * от раннего касания, фокус по умолчанию — «Отмена», «назад» закрывает, обе палитры, 200% —
 * кнопки друг под другом. Ничего не удаляется: действие кнопки — только счётчик.
 */
@RunWith(AndroidJUnit4::class)
class ConfirmSheetTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private var main: MainActivity? = null
    private var sheet: ConfirmSheet? = null

    @After fun tearDown() {
        ins.runOnMainSync { sheet?.dismiss(); main?.finish() }
        Lang.fontScale = null
        ins.waitForIdleSync()
    }

    private fun waitFor(ms: Long = 5_000, ok: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            var r = false
            ins.runOnMainSync { r = ok() }
            if (r) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun launch(): MainActivity =
        (ins.startActivitySync(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity)
            .also { main = it; ins.waitForIdleSync() }

    private fun open(a: MainActivity, onOk: () -> Unit): ConfirmSheet {
        var s: ConfirmSheet? = null
        ins.runOnMainSync {
            s = ConfirmSheet(a, a.tx.s(R.string.log_clear_title), "body", ok = a.tx.s(R.string.log_clear_ok),
                cancel = a.tx.s(R.string.cancel), danger = true, onOk = onOk).show()
        }
        sheet = s
        assertTrue(waitFor { s!!.isShowing })
        return s!!
    }

    @Test fun dangerGuardFocusAndBack() {
        val a = launch()
        var oks = 0
        val s = open(a) { oks++ }
        // Касание «опасной» кнопки сразу после открытия (вторая половина двойного тапа) — мимо.
        ins.runOnMainSync {
            val ok = s.okButton!!
            val t0 = SystemClock.uptimeMillis()
            ok.dispatchTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, 5f, 5f, 0))
            ok.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 30, MotionEvent.ACTION_UP, 5f, 5f, 0))
        }
        ins.waitForIdleSync()
        ins.runOnMainSync {
            assertEquals(1, s.guardedTaps)
            assertEquals(0, oks)
            assertTrue(s.isShowing)
            assertTrue(s.danger)
            assertEquals(C.WHITE, s.okButton!!.currentTextColor)
            // Кнопки подписаны.
            assertEquals(a.tx.s(R.string.cancel), s.cancelButton.text.toString())
            assertTrue(s.cancelButton.isFocusable)
            assertFalse(s.okButton!!.isFocused)
        }
        var touch = true
        ins.runOnMainSync { touch = s.cancelButton.isInTouchMode }
        if (!touch) assertTrue("Cancel not focused", waitFor { s.cancelButton.isFocused })
        // «Назад» закрывает лист, действие не выполняется.
        ins.runOnMainSync {
            s.dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            s.dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        assertTrue(waitFor { !s.isShowing })
        assertEquals(0, oks)
        // После защитного окна — намеренное нажатие проходит.
        val s2 = open(a) { oks++ }
        Thread.sleep(ConfirmSheet.OPEN_GUARD_MS + 100)
        ins.runOnMainSync { assertTrue(s2.okButton!!.performClick()) }
        assertTrue(waitFor { !s2.isShowing })
        assertEquals(1, oks)
    }

    /** Сообщение с одной кнопкой: её нажатие — TAP (не BACK), действие — ровно один раз; «назад» — BACK без действия. */
    @Test fun loneOkTapsOnce() {
        val a = launch()
        var oks = 0
        var s: ConfirmSheet? = null
        ins.runOnMainSync { s = a.alert(a.tx.s(R.string.delete_cancelled_title), "x") { oks++ } }
        sheet = s
        assertTrue(waitFor { s!!.isShowing })
        ins.runOnMainSync {
            assertEquals(null, s!!.okButton)
            assertTrue(s!!.cancelButton.performClick())
            assertEquals(Cue.TAP, Feedback.lastCue)
            s!!.cancelButton.performClick()
        }
        assertTrue(waitFor { !s!!.isShowing })
        assertEquals(1, oks)
        var s2: ConfirmSheet? = null
        ins.runOnMainSync { s2 = a.alert(a.tx.s(R.string.delete_cancelled_title), "x") { oks++ } }
        sheet = s2
        assertTrue(waitFor { s2!!.isShowing })
        ins.runOnMainSync { s2!!.cancel() }
        assertTrue(waitFor { !s2!!.isShowing })
        assertEquals(Cue.BACK, Feedback.lastCue)
        assertEquals(1, oks)
    }

    @Test fun bothPalettes() {
        val a = launch()
        val was = C.p
        try {
            for (p in listOf(Palette.DARK, Palette.LIGHT)) {
                C.p = p
                val s = open(a) {}
                ins.runOnMainSync {
                    assertEquals(p.text, s.titleText.currentTextColor)
                    assertEquals(p.text, s.cancelButton.currentTextColor)
                    s.dismiss()
                }
                ins.waitForIdleSync()
            }
        } finally { C.p = was }
    }

    @Test fun largeFontStacksButtons() {
        Lang.fontScale = 2f
        val a = launch()
        var s: ConfirmSheet? = null
        ins.runOnMainSync {
            s = a.alert(a.tx.s(R.string.nothing_deleted), "x", ok = a.tx.s(R.string.refresh_btn), cancel = a.tx.s(R.string.close))
        }
        sheet = s
        assertTrue(waitFor { s!!.isShowing && s!!.cancelButton.height > 0 })
        ins.runOnMainSync {
            val sh = s!!
            assertTrue(sh.cancelButton.height >= a.dp(44) && sh.okButton!!.height >= a.dp(44))
            // При 200% подписи не влезают в половину ширины — друг под другом, действие сверху.
            if (sh.pair!!.stacked) assertTrue(sh.okButton!!.bottom <= sh.cancelButton.top)
            else assertEquals(sh.okButton!!.top, sh.cancelButton.top)
        }
    }
}
