package dev.ancdu

import android.app.Instrumentation
import android.os.ParcelFileDescriptor

/** Тестовый доступ к appops: тесты меняют режим и обязаны вернуть прежний. */
object AppOps {
    private fun shell(ins: Instrumentation, cmd: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(cmd))
            .use { String(it.readBytes()) }

    /** Текущий режим [op] пакета; «No operations.» и отсутствие строки — «default». */
    fun get(ins: Instrumentation, op: String): String {
        val out = shell(ins, "appops get ${ins.targetContext.packageName} $op")
        val line = out.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("$op:") }
            ?: return "default"
        return line.substringAfter(':').substringBefore(';').trim().ifEmpty { "default" }
    }

    fun set(ins: Instrumentation, op: String, mode: String) {
        shell(ins, "appops set ${ins.targetContext.packageName} $op $mode")
    }

    /**
     * Возврат режима после смерти процесса теста. Смена MANAGE_EXTERNAL_STORAGE заставляет систему
     * убить приложение («MANAGE_EXTERNAL_STORAGE changed»), поэтому вернуть его изнутри теста нельзя:
     * отдельный sh (uid shell) ждёт, пока процесс пакета не исчезнет, и только тогда ставит [mode].
     */
    fun restoreAfterExit(ins: Instrumentation, op: String, mode: String) {
        val pkg = ins.targetContext.packageName
        val fds = ins.uiAutomation.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(fds[1]).use {
            // setsid + nohup: сеанс `am instrument` по завершении шлёт SIGHUP своей группе.
            it.write(("nohup setsid sh -c 'while pidof $pkg >/dev/null; do sleep 1; done; " +
                "appops set $pkg $op $mode' </dev/null >/dev/null 2>&1 &\nexit\n").toByteArray())
        }
        // Не читать до EOF: фоновый sh держит копию stdout-канала до самого восстановления.
        fds[0].close()
    }
}
