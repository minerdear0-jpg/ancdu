package dev.ancdu

import android.media.AudioManager
import android.view.accessibility.AccessibilityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** Звуки: файлы синтезируются на Holder.io; при «Выкл» SoundPool.play не вызывается. */
@RunWith(AndroidJUnit4::class)
class FeedbackTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private var prevMode = FxMode.SYSTEM
    private var tmp: File? = null

    private fun waitFor(ms: Long = 10_000, ok: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            if (ok()) return true
            Thread.sleep(20)
        }
        return false
    }

    @Before fun setUp() {
        ins.runOnMainSync { Feedback.init(ctx) }
        prevMode = Feedback.mode
    }

    @After fun tearDown() {
        Feedback.mode = prevMode
        val d = tmp ?: return
        Feedback.dirOverride = null
        ins.runOnMainSync { Feedback.reload(ctx) }
        assertTrue(waitFor { Feedback.ready })
        // Только файлы звуков внутри свежего временного каталога теста (абсолютные пути).
        val base = ctx.cacheDir.canonicalFile
        check(d.isAbsolute && d.canonicalFile.parentFile == base && d.name.startsWith("fxtest")) { "$d" }
        d.listFiles()?.forEach { f ->
            check(f.isAbsolute && f.canonicalFile.parentFile == d.canonicalFile && f.name.startsWith("fx_v2_")) { "$f" }
            f.delete()
        }
        d.delete()
    }

    @Test fun initGeneratesFilesOffMain() {
        val d = Files.createTempDirectory(ctx.cacheDir.toPath(), "fxtest").toFile().absoluteFile.also { tmp = it }
        assertEquals(0, d.list()!!.size)
        Feedback.dirOverride = d
        ins.runOnMainSync { Feedback.reload(ctx) }
        val sr = Feedback.sampleRate(ctx)
        val want = Synth.NAMES.map { File(d, "fx_v2_${it}_$sr.wav") }
        assertTrue(waitFor { want.all { it.isFile } && Feedback.ready })
        assertEquals("ancdu-io", Feedback.genThread)
        for (f in want) {
            val b = f.readBytes()
            assertEquals(f.name, "RIFF", String(b, 0, 4, Charsets.US_ASCII))
            assertTrue(f.name, b.size > 44)
        }
        assertEquals(want.map { it.name }.toSet(), d.list()!!.toSet())   // без .tmp
    }

    @Test fun offPlaysNothing() {
        assertTrue(waitFor { Feedback.ready })
        Feedback.mode = FxMode.OFF
        val before = Feedback.plays.get()
        ins.runOnMainSync { for (c in Cue.entries) Feedback.cue(null, c) }
        assertEquals(before, Feedback.plays.get())
    }

    /** Контроль: с «Вкл», ringer NORMAL и без TalkBack тот же вызов доходит до SoundPool.play. */
    @Test fun onPlays() {
        val am = ctx.getSystemService(AudioManager::class.java)
        assumeTrue(am.ringerMode == AudioManager.RINGER_MODE_NORMAL)
        assumeTrue(ctx.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled != true)
        assertTrue(waitFor { Feedback.ready })
        Feedback.mode = FxMode.ON
        val before = Feedback.plays.get()
        ins.runOnMainSync { Feedback.cue(null, Cue.DONE) }
        assertEquals(before + 1, Feedback.plays.get())
    }
}
