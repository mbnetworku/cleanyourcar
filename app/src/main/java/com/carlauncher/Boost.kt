package com.carlauncher

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo

/** Frees RAM by ending background processes of apps the user is not relying on. */
object Boost {

    fun availMem(ctx: Context): Long {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.availMem
    }

    fun totalMem(ctx: Context): Long {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.totalMem
    }

    /**
     * Blocking, call off the UI thread. Never touches system apps or any app assigned to a slot
     * (radio, music, navigation...). Returns the number of bytes freed.
     */
    fun free(ctx: Context): Long {
        val before = availMem(ctx)
        val prefs = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        val keep = HashSet<String>()
        keep.add(ctx.packageName)
        for ((k, v) in prefs.all) {
            if (v is String && (k.startsWith("side") || k.startsWith("dock") || k == "radio_settings")) {
                v.split(",").forEach { keep.add(it) }
            }
        }
        val pm = ctx.packageManager
        for (ai in pm.getInstalledApplications(0)) {
            if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 || ai.packageName in keep) continue
            Apps.stop(ctx, ai.packageName)
        }
        // Other launchers waste RAM too; stop the ones that are not currently the Home launcher.
        val home = Apps.defaultHome(pm)
        for (l in Apps.otherLaunchers(ctx)) {
            if (l.pkg != home && l.pkg !in keep) Apps.stop(ctx, l.pkg)
        }
        Thread.sleep(800)
        return (availMem(ctx) - before).coerceAtLeast(0)
    }
}
