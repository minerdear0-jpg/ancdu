package dev.ancdu

import android.view.View
import android.view.View.MeasureSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [Flow.reserve]: первый ребёнок, который может вырасти (появится сегмент Δ), переносит соседей
 * сразу — высота строки чипов не меняется, когда он вырастет (русские подписи шире: сдвиг списка).
 */
@RunWith(AndroidJUnit4::class)
class FlowReserveTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixed(w: Int, h: Int) = object : View(ctx) {
        var width0 = w
        override fun onMeasure(ws: Int, hs: Int) = setMeasuredDimension(width0, h)
    }

    private fun heightOf(flow: Flow, w: Int): Int {
        flow.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return flow.measuredHeight
    }

    @Test fun reservedGrowthDoesNotChangeHeight() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val flow = Flow(ctx, 10, 5, endLast = true)
            val sorts = fixed(500, 100); val sizes = fixed(450, 100)
            flow.addView(sorts); flow.addView(sizes)
            // Без запаса: 500 + 10 + 450 = 960 ≤ 1000 — одна строка; с выросшим первым (550) — 1010, две.
            assertEquals(100, heightOf(flow, 1000))
            sorts.width0 = 550
            assertEquals(205, heightOf(flow, 1000))
            // С запасом 50 до роста — уже две строки; рост (запас снят) высоту не меняет.
            sorts.width0 = 500; flow.reserve = 50
            assertEquals(205, heightOf(flow, 1000))
            sorts.width0 = 550; flow.reserve = 0
            assertEquals(205, heightOf(flow, 1000))
            // Запас, который помещается, ничего не переносит.
            sorts.width0 = 400; flow.reserve = 50
            assertEquals(100, heightOf(flow, 1000))
        }
    }

    @Test fun reserveDoesNotMoveChildren() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val flow = Flow(ctx, 10, 5)
            val a = fixed(100, 40); val b = fixed(100, 40)
            flow.addView(a); flow.addView(b)
            flow.reserve = 30
            heightOf(flow, 1000)
            flow.layout(0, 0, 1000, flow.measuredHeight)
            assertEquals(0, a.left)
            // Запас не сдвигает соседей: только решение о переносе; строка одна.
            assertEquals(110, b.left)
            assertEquals(40, flow.measuredHeight)
        }
    }
}
