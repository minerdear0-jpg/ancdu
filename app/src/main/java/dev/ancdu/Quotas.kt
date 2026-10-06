package dev.ancdu

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Process
import android.os.storage.StorageManager

data class AppStat(val label: String, val pkg: String, val app: Long, val data: Long, val cache: Long) {
    val total: Long get() = app + data
}

class Tier0(val total: Long, val free: Long, val segs: List<Seg>?, val apps: List<AppStat>?)

object Quotas {
    /** Ярус 0: квоты ядра через StorageStatsManager. Не с главного потока. */
    fun load(ctx: Context): Tier0 {
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)
        val uuid = StorageManager.UUID_DEFAULT
        val total = ssm.getTotalBytes(uuid)
        val free = ssm.getFreeBytes(uuid)
        if (!Perms.usage(ctx)) return Tier0(total, free, null, null)
        val user = Process.myUserHandle()
        val pm = ctx.packageManager
        val apps = pm.getInstalledApplications(0).mapNotNull { ai ->
            try {
                val s = ssm.queryStatsForPackage(uuid, ai.packageName, user)
                AppStat(pm.getApplicationLabel(ai).toString(), ai.packageName, s.appBytes, s.dataBytes, s.cacheBytes)
            } catch (e: Exception) { null }
        }.sortedByDescending { it.total }
        val ext = ssm.queryExternalStatsForUser(uuid, user)
        val segs = Segments.compute(total, free, ext.videoBytes, ext.imageBytes, ext.audioBytes,
            ext.appBytes, ext.totalBytes, apps.sumOf { it.total })
        return Tier0(total, free, segs, apps)
    }
}

object AppRows {
    fun fill(t: Txt, a: AppStat, max: Long, sum: Long, row: Row) {
        val total = a.total
        row.name = a.label
        row.sub = a.pkg
        row.size = Fmt.size(total, t)
        row.bar = ListMath.bar(total, max)
        row.pct = Fmt.pct(total, sum)
        val f = { v: Long -> if (total <= 0) 0f else (v.toDouble() / total).toFloat() }
        row.segs = floatArrayOf(f(a.app), f(a.data - a.cache), f(a.cache))
        row.segColors = intArrayOf(C.BLUE, C.AMBER, C.MUTED)
        row.desc = t.s(R.string.app_desc, a.label, row.size, Fmt.size(a.app, t), Fmt.size(a.data - a.cache, t), Fmt.size(a.cache, t))
    }
}
