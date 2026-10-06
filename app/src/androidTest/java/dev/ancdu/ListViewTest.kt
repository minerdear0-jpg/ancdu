package dev.ancdu

import android.content.res.Configuration
import android.graphics.Paint
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListViewTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    private class Src : RowSource {
        val clicks = mutableListOf<Int>()
        val longs = mutableListOf<Int>()
        override val count = 100
        override fun bind(index: Int, row: Row) {
            row.name = "item$index"; row.size = "${index} B"; row.bar = index / 100f
            row.desc = "item$index, ${index} B"
        }
        override fun click(index: Int) { clicks += index }
        override fun longClick(index: Int) { longs += index }
    }

    /** DOWN и UP на главном потоке, ожидание — на тестовом (иначе таймер long-press не сработает). */
    private fun tap(v: View, x: Float, y: Float, holdMs: Long = 0) {
        val t = SystemClock.uptimeMillis()
        ins.runOnMainSync { v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)) }
        Thread.sleep(holdMs + 20)
        ins.runOnMainSync {
            v.dispatchTouchEvent(MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0))
        }
    }

    @Test fun layoutScrollClickA11y() {
        val src = Src()
        lateinit var v: NcduListView
        ins.runOnMainSync {
            v = NcduListView(ins.targetContext)
            v.source = src
            v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, 1080, 1000)
            v.scroll = 3 * v.rowHeight
        }
        assertEquals(3 * v.rowHeight, v.scroll)
        ins.runOnMainSync { v.scroll = Int.MAX_VALUE }
        assertEquals(100 * v.rowHeight - 1000, v.scroll)
        ins.runOnMainSync { v.scroll = 0 }
        tap(v, 100f, v.rowHeight * 2.5f)
        ins.waitForIdleSync()
        assertEquals(listOf(2), src.clicks)

        tap(v, 100f, v.rowHeight * 0.5f, holdMs = 700)
        ins.waitForIdleSync()
        assertEquals(listOf(0), src.longs)

        val p = v.accessibilityNodeProvider
        assertNotNull(p)
        val info: AccessibilityNodeInfo = p.createAccessibilityNodeInfo(1)!!
        assertEquals("item1, 1 B", info.contentDescription)
        assertTrue(info.isClickable)
        ins.runOnMainSync { p.performAction(4, AccessibilityNodeInfo.ACTION_CLICK, null) }
        assertEquals(listOf(2, 4), src.clicks)

        fun hasAct(info: AccessibilityNodeInfo, a: Int) = info.actionList.any { it.id == a }
        val top = p.createAccessibilityNodeInfo(-1)!!
        assertTrue(hasAct(top, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
        assertTrue(!hasAct(top, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
        ins.runOnMainSync { p.performAction(-1, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null) }
        assertTrue(v.scroll > 0)
        ins.runOnMainSync { v.scroll = Int.MAX_VALUE }
        val bottom = p.createAccessibilityNodeInfo(-1)!!
        assertTrue(!hasAct(bottom, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
        assertTrue(hasAct(bottom, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
    }

    /** Крупный шрифт: строка не меньше 48 dp и вмещает текст (sp) с отступами 8 dp. */
    @Test fun rowsFitLargeFonts() {
        val base = ins.targetContext
        for (scale in listOf(1.0f, 1.3f, 2.0f)) {
            val cfg = Configuration(base.resources.configuration).apply { fontScale = scale }
            val ctx = base.createConfigurationContext(cfg)
            lateinit var v: NcduListView
            ins.runOnMainSync { v = NcduListView(ctx) }
            val textH = Paint().apply {
                typeface = Fonts.get(ctx, mono = true, bold = false)
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, ctx.resources.displayMetrics)
            }.fontMetricsInt.let { it.descent - it.ascent }
            assertTrue("scale=$scale", v.rowHeight >= ctx.dp(48))
            assertTrue("scale=$scale: ${v.rowHeight} < $textH + 16dp", v.rowHeight >= textH + 2 * ctx.dp(8))
        }
    }

    @Test fun longClickActionLabel() {
        lateinit var v: NcduListView
        ins.runOnMainSync {
            v = NcduListView(ins.targetContext)
            v.source = Src()
            v.longClickLabel = ins.targetContext.getString(R.string.long_click_label)
            v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, 1080, 1000)
        }
        val info = v.accessibilityNodeProvider.createAccessibilityNodeInfo(0)!!
        val a = info.actionList.first { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }
        assertEquals(ins.targetContext.getString(R.string.long_click_label), a.label)
    }
}
