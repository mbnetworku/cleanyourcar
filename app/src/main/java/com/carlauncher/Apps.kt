package com.carlauncher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** Helpers for finding, launching and stopping apps, including other home launchers. */
object Apps {

    /** One of the launcher's own apps. Slots store it as "builtin:<id>" in place of a package name. */
    class BuiltIn(val id: String, val label: String, val icon: Int, val cls: String, val tab: Int = -1) {
        val key get() = "builtin:$id"
    }

    val builtIns = listOf(
        BuiltIn("music", "Music", R.drawable.ic_music, "MusicActivity"),
        BuiltIn("videos", "Videos", R.drawable.ic_video, "VideoActivity"),
        BuiltIn("radio", "FM Radio", R.drawable.ic_radio, "RadioActivity"),
        BuiltIn("bluetooth", "Bluetooth", R.drawable.ic_bt, "BluetoothActivity"),
        BuiltIn("files", "Files", R.drawable.ic_files, "FilesActivity"),
        BuiltIn("eq", "Equalizer", R.drawable.ic_eq, "EqualizerActivity"),
        BuiltIn("wifi", "Wi-Fi", R.drawable.ic_wifi, "SettingsActivity", 1),
        BuiltIn("settings", "Settings", R.drawable.ic_settings, "SettingsActivity", 0)
    )

    fun builtIn(key: String): BuiltIn? = if (key.startsWith("builtin:")) builtIns.firstOrNull { it.key == key } else null

    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    /** Launch intent for [pkg] or a built-in key; also works for launchers that only declare the HOME category. */
    fun intent(pm: PackageManager, pkg: String): Intent? {
        builtIn(pkg)?.let { b ->
            return Intent().setClassName("com.carlauncher", "com.carlauncher.${b.cls}").apply { if (b.tab >= 0) putExtra("tab", b.tab) }
        }
        if (pkg.startsWith("builtin:")) return null
        pm.getLaunchIntentForPackage(pkg)?.let { return it }
        val ri = pm.queryIntentActivities(homeIntent().setPackage(pkg), 0).firstOrNull() ?: return null
        return homeIntent().setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
    }

    /** Every home launcher installed except this one (e.g. the stock Android box launcher). */
    fun otherLaunchers(ctx: Context): List<AppItem> {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(homeIntent(), 0)
            .map { AppItem(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
    }

    /** The launcher Android currently uses for the Home button, if one is chosen. */
    fun defaultHome(pm: PackageManager): String? {
        val pkg = pm.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return if (pkg == "android") null else pkg
    }

    /** Apps the user disabled in Car Launcher: kept stopped and hidden, never uninstalled. */
    fun blocked(ctx: Context): Set<String> =
        ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getStringSet("blocked_apps", emptySet()) ?: emptySet()

    fun setBlocked(ctx: Context, pkg: String, block: Boolean) {
        val p = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        val s = blocked(ctx).toMutableSet()
        if (block) s.add(pkg) else s.remove(pkg)
        p.edit().putStringSet("blocked_apps", s).apply()
    }

    /** Stops every disabled app again; called when the launcher comes to the front and at start-up. */
    fun stopBlocked(ctx: Context) = blocked(ctx).forEach { stop(ctx, it) }

    /** Ends the app's background processes. The app stays installed and enabled and can be opened again. */
    fun stop(ctx: Context, pkg: String) {
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg)
    }

    fun isSystem(pm: PackageManager, pkg: String): Boolean = try {
        pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
