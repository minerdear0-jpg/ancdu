package dev.ancdu

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.provider.Settings

object Perms {
    /** Для тестов: подмена «доступа ко всем файлам» (его смена убивает процесс). null — настоящий. */
    @Volatile var filesOverride: Boolean? = null

    fun files(): Boolean = filesOverride ?: Environment.isExternalStorageManager()

    fun usage(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(),
            ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }

    fun askFiles(a: Activity) {
        a.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:" + a.packageName)))
    }

    fun askUsage(a: Activity) {
        a.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }
}
