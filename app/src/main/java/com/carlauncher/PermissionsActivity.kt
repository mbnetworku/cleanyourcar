package com.carlauncher

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * One place to grant everything the launcher needs, shown automatically the first time it opens and
 * reachable later from Settings. Normal permissions use Android's own pop-up; special ones (all files,
 * notification access, installing apps) open the matching system page and are re-checked on return.
 */
class PermissionsActivity : Activity() {

    private class Need(val title: String, val why: String, val granted: () -> Boolean, val request: () -> Unit)

    private lateinit var prefs: SharedPreferences
    private var dn = 1f
    private fun px(v: Int) = (v * dn).toInt()
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private var accent = 0
    private lateinit var listBox: LinearLayout
    private lateinit var needs: List<Need>
    /** "Allow all" walks through the missing ones one by one. */
    private var allowAll = false

    companion object {
        fun runtimePerms(): List<String> = buildList {
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) {
                add("android.permission.READ_MEDIA_AUDIO"); add("android.permission.READ_MEDIA_VIDEO"); add("android.permission.READ_MEDIA_IMAGES")
                add("android.permission.POST_NOTIFICATIONS")
            } else if (Build.VERSION.SDK_INT >= 23) {
                add(android.Manifest.permission.READ_EXTERNAL_STORAGE); add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= 31) { add("android.permission.BLUETOOTH_CONNECT"); add("android.permission.BLUETOOTH_SCAN") }
        }

        /** True when something the launcher uses is still not allowed. */
        fun missing(act: Activity): Boolean {
            if (Build.VERSION.SDK_INT < 23) return false
            if (runtimePerms().any { act.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) return true
            return !Storage.hasAccess(act)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        dn = resources.displayMetrics.density
        Themes.update(prefs)
        accent = Themes.accent(prefs)
        needs = buildNeeds()
        build()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (allowAll) ui().postDelayed({ nextMissing() }, 400)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        @Suppress("DEPRECATION")
        if (hasFocus) window.decorView.systemUiVisibility = MainActivity.immersiveFlags()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        // Refused instantly without a pop-up: Android won't ask again ("don't ask again", or the unit blocks it).
        val instant = android.os.SystemClock.uptimeMillis() - askedAt < 350
        if (grantResults.isNotEmpty() && grantResults.all { it != PackageManager.PERMISSION_GRANTED } && instant) {
            allowAll = false
            openAppPage("Android won't show the pop-up again. Tap Permissions and turn them on here.")
            return
        }
        if (allowAll) ui().postDelayed({ nextMissing() }, 300)
    }

    private fun ui() = listBox

    private fun has(p: String) = Build.VERSION.SDK_INT < 23 || checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private var askedAt = 0L

    private fun ask(vararg p: String) {
        val list = p.filter { Build.VERSION.SDK_INT >= 23 && !has(it) }
        if (list.isEmpty()) return
        askedAt = android.os.SystemClock.uptimeMillis()
        requestPermissions(list.toTypedArray(), 9)
    }

    /** Android's app page, where every permission can be switched on by hand. */
    private fun openAppPage(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    /** Opens a system page; if this unit doesn't have it, falls back to the app's own page in Android settings. */
    private fun open(i: Intent) = try {
        startActivity(i)
    } catch (e: Exception) {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        catch (e2: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun buildNeeds(): List<Need> = buildList {
        add(Need("Files, music, videos and USB drives",
            "Lets Music, Videos and Files show what is on this unit, SD cards and every USB drive you plug in.",
            { Storage.hasAccess(this@PermissionsActivity) && runtimePerms().filter { it.contains("MEDIA") || it.contains("STORAGE") }.all { has(it) } },
            {
                val media = runtimePerms().filter { it.contains("MEDIA") || it.contains("STORAGE") }
                if (media.any { !has(it) }) ask(*media.toTypedArray())
                else if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager())
                    open(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            }))
        add(Need("Location (GPS)", "Shows your driving speed on the speedometer and finds Wi-Fi networks.",
            { has(android.Manifest.permission.ACCESS_FINE_LOCATION) },
            { ask(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION) }))
        if (Build.VERSION.SDK_INT >= 31) add(Need("Bluetooth (nearby devices)", "Pair and connect your phone, phone music and calls.",
            { has("android.permission.BLUETOOTH_CONNECT") && has("android.permission.BLUETOOTH_SCAN") },
            { ask("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN") }))
        if (Build.VERSION.SDK_INT >= 33) add(Need("Notifications", "Music controls in the notification bar while a song plays.",
            { has("android.permission.POST_NOTIFICATIONS") },
            { ask("android.permission.POST_NOTIFICATIONS") }))
        add(Need("Now playing access", "Shows the song name and cover from other music apps and your phone on the home screen.",
            { NowPlaying(this@PermissionsActivity) {}.hasAccess() },
            { open(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }))
        if (Build.VERSION.SDK_INT >= 26) add(Need("Install apps", "Install APK files you open in Files, for example from a USB drive.",
            { packageManager.canRequestPackageInstalls() },
            { open(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }))
    }

    private fun text(t: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(dn * 3, 0f, dn, 0xCC000000.toInt())
    }

    private fun pill(color: Int, r: Int = 22) = GradientDrawable().apply { cornerRadius = px(r).toFloat(); setColor(color) }

    private fun build() {
        val root = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(px(40), px(24), px(40), px(24))
            background = Themes.backgroundDrawable(this@PermissionsActivity, prefs)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(32), px(26), px(32), px(20))
            background = Themes.glass(Themes.glassPct(prefs), accent, px(24).toFloat(), px(1))
        }
        card.addView(text("Welcome to Car Launcher", 30f, bold = true))
        card.addView(text("Allow these so every feature works. You can change them later in Settings → Launcher → Permissions.", 15f, 0xDDFFFFFF.toInt())
            .apply { setPadding(0, px(6), 0, px(14)) })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(listBox) }, LinearLayout.LayoutParams(match, 0, 1f))
        val bar = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(0, px(14), 0, 0) }
        bar.addView(text("Later", 16f, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(px(26), 0, px(26), 0); background = pill(0x40000000); isClickable = true
            setOnClickListener { finishSetup() }
        }, LinearLayout.LayoutParams(wrap, px(52)).apply { rightMargin = px(12) })
        bar.addView(text("Allow all", 17f, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(px(34), 0, px(34), 0); background = pill(accent); isClickable = true
            setOnClickListener { allowAll = true; nextMissing() }
        }, LinearLayout.LayoutParams(wrap, px(52)))
        card.addView(bar, LinearLayout.LayoutParams(match, wrap))
        root.addView(card, LinearLayout.LayoutParams(px(900), match))
        setContentView(root)
        card.alpha = 0f
        card.translationY = px(30).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun refresh() {
        listBox.removeAllViews()
        for (n in needs) {
            val ok = n.granted()
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(18), px(12), px(14), px(12))
                background = pill(0x40000000, 16)
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(n.title, 18f, bold = true))
            col.addView(text(n.why, 13f, 0xCCFFFFFF.toInt()))
            row.addView(col, LinearLayout.LayoutParams(0, wrap, 1f))
            row.addView(text(if (ok) "✓ Allowed" else "Allow", 15f, if (ok) 0xFF30D158.toInt() else Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(px(20), 0, px(20), 0)
                background = if (ok) null else pill(accent)
                isClickable = !ok
                setOnClickListener { allowAll = false; n.request() }
            }, LinearLayout.LayoutParams(wrap, px(46)).apply { leftMargin = px(12) })
            listBox.addView(row, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = px(8) })
        }
    }

    /** Asks for the next permission that isn't allowed yet; when none are left, the setup is done. */
    private fun nextMissing() {
        val n = needs.firstOrNull { !it.granted() }
        if (n == null) { allowAll = false; finishSetup(); return }
        n.request()
    }

    private fun finishSetup() {
        prefs.edit().putBoolean("perm_setup_done", true).apply()
        finish()
    }
}
