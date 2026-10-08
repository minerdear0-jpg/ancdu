package dev.ancdu

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import java.io.File

/**
 * Instrumentation runner for every suite: before any test (and before the app's first screen)
 * the delete log is routed into a fresh mkdtemp under cacheDir, so no test ever reads or writes the
 * user's filesDir/deletes.tsv. The sandbox is removed when the run finishes (absolute path checked).
 */
class SandboxRunner : AndroidJUnitRunner() {
    private var sandbox: File? = null

    override fun onCreate(arguments: Bundle?) {
        // Before super.onCreate (which starts the run): nothing may see the real log first.
        sandbox = LogSandbox.create(targetContext.cacheDir, "deletelog-run")
        DeleteLog.fileOverride = File(sandbox, DeleteLog.FILE)
        super.onCreate(arguments)
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        sandbox?.let { LogSandbox.remove(targetContext.cacheDir, it) }
        super.finish(resultCode, results)
    }
}

/** A mkdtemp under cacheDir for a delete log: created and removed only with the absolute path asserted. */
object LogSandbox {
    fun create(cache: File, prefix: String): File =
        java.nio.file.Files.createTempDirectory(cache.toPath(), prefix).toFile().also {
            check(it.isAbsolute && it.path.startsWith(cache.path + "/")) { "log sandbox outside cacheDir: $it" }
        }

    fun remove(cache: File, dir: File) {
        if (dir.isAbsolute && dir.path.startsWith(cache.path + "/")) dir.deleteRecursively()
    }
}

/**
 * Per-test delete log in its own mkdtemp under cacheDir (suites that delete): [file] is the log the
 * app writes during the test; afterwards the runner's sandbox log is back and this one is removed.
 */
class LogSandboxRule : org.junit.rules.ExternalResource() {
    private val cache get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    private var dir: File? = null
    private var prev: File? = null
    lateinit var file: File
        private set

    override fun before() {
        Holder.io.submit {}.get()
        prev = DeleteLog.fileOverride
        dir = LogSandbox.create(cache, "deletelog")
        file = File(dir, DeleteLog.FILE)
        DeleteLog.fileOverride = file
        // Process-global log state from earlier tests: the notice of another test's log is not ours.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { DeleteLog.testReset() }
    }

    override fun after() {
        Holder.io.submit {}.get()
        DeleteLog.fileOverride = prev
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { DeleteLog.testReset() }
        dir?.let { LogSandbox.remove(cache, it) }
    }
}
