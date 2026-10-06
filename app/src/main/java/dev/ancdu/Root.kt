package dev.ancdu

import android.content.Context

object Root {
    fun helper(ctx: Context): String = ctx.applicationInfo.nativeLibraryDir + "/libancdu_scan.so"
}
