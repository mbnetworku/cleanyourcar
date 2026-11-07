package com.carlauncher

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Car Bluetooth: pair and connect a phone, play its music through the car, make calls, and rename the car.
 * Phone music needs the A2DP-sink profile and calls need the hands-free (HFP client) profile. Both are only
 * present when the head unit's own Bluetooth stack runs in car mode; [support] reports what this unit has.
 */
class BluetoothActivity : Activity() {

    companion object {
        /** Hidden profile ids for the car side of Bluetooth. */
        private const val A2DP_SINK = 11
        private const val HEADSET_CLIENT = 16
    }

    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private var dn = 1f
    private fun px(v: Int) = (v * dn).toInt()
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private var accent = 0
    private val bt: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private val tabs = listOf("Devices", "Music", "Phone", "Settings")
    private val tabViews = ArrayList<TextView>()
    private var tab = 0
    private lateinit var content: LinearLayout
    private lateinit var header: TextView
    private val found = ArrayList<BluetoothDevice>()
    private var hfp: BluetoothProfile? = null
    private var sinkReady = false
    private var number = StringBuilder()
    private lateinit var np: NowPlaying

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == BluetoothDevice.ACTION_FOUND) {
                @Suppress("DEPRECATION")
                val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (d != null && found.none { it.address == d.address }) found.add(d)
            }
            refreshHeader()
            if (tab == 0) show(0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        dn = resources.displayMetrics.density
        Themes.update(prefs)
        accent = Themes.accent(prefs)
        if (Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN"), 5)
        np = NowPlaying(this) { if (tab == 1) ui.post { show(1) } }
        connectProfiles()
        build()
        show(0)
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED)
        })
        np.start()
        refreshHeader()
    }

    override fun onPause() {
        try { unregisterReceiver(receiver) } catch (e: Exception) { }
        try { bt?.cancelDiscovery() } catch (e: Exception) { }
        np.stop()
        super.onPause()
    }

    override fun onDestroy() {
        hfp?.let { try { bt?.closeProfileProxy(HEADSET_CLIENT, it) } catch (e: Exception) { } }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        @Suppress("DEPRECATION")
        if (hasFocus) window.decorView.systemUiVisibility = MainActivity.immersiveFlags()
    }

    /** Asks the system for the car-side profiles; they only connect on units whose Bluetooth runs in car mode. */
    private fun connectProfiles() {
        val a = bt ?: return
        try {
            a.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) { hfp = proxy; ui.post { refreshHeader() } }
                override fun onServiceDisconnected(p: Int) { hfp = null }
            }, HEADSET_CLIENT)
        } catch (e: Throwable) { }
        try {
            a.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) { sinkReady = true; a.closeProfileProxy(p, proxy); ui.post { refreshHeader() } }
                override fun onServiceDisconnected(p: Int) { sinkReady = false }
            }, A2DP_SINK)
        } catch (e: Throwable) { }
    }

    private val callsSupported get() = hfp != null
    private val phoneMusicSupported get() = sinkReady

    // ---- layout ----

    private fun text(t: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(dn * 3, 0f, dn, 0xCC000000.toInt())
    }

    private fun pill(sel: Boolean, r: Int = 16, color: Int = accent) = GradientDrawable().apply {
        cornerRadius = px(r).toFloat()
        setColor(if (sel) color else 0x40000000)
    }

    private fun button(t: String, size: Float, h: Int, sel: Boolean = false, color: Int = accent, onClick: () -> Unit) = text(t, size, bold = true).apply {
        gravity = Gravity.CENTER
        background = pill(sel, h / 2, color)
        isClickable = true
        setPadding(px(18), 0, px(18), 0)
        setOnClickListener {
            it.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).withEndAction { it.animate().scaleX(1f).scaleY(1f).setDuration(110).start() }.start()
            onClick()
        }
    }

    private fun build() {
        val root = LinearLayout(this).apply {
            setPadding(px(16), px(16), px(16), px(16))
            background = Themes.backgroundDrawable(this@BluetoothActivity, prefs)
        }
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(18), px(14), px(14))
            background = Themes.glass(Themes.glassPct(prefs), accent, px(20).toFloat(), px(1))
        }
        val icon = ImageView(this).apply { setImageResource(R.drawable.ic_bt) }
        nav.addView(icon, LinearLayout.LayoutParams(px(56), px(56)))
        nav.addView(text("Bluetooth", 24f, bold = true).apply { setPadding(0, px(8), 0, px(14)) })
        val glyphs = listOf("⇄", "♫", "☎", "≡")
        tabs.forEachIndexed { i, n ->
            val t = text("${glyphs[i]}   $n", 18f).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(16), 0, px(12), 0)
                isClickable = true
                setOnClickListener { show(i) }
            }
            tabViews.add(t)
            nav.addView(t, LinearLayout.LayoutParams(match, px(54)).apply { bottomMargin = px(6) })
        }
        nav.addView(View(this), LinearLayout.LayoutParams(match, 0, 1f))
        nav.addView(button("Home", 16f, 48) { finish() }, LinearLayout.LayoutParams(match, px(48)))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(18), px(24), px(14))
            background = Themes.glass(Themes.glassPct(prefs), accent, px(20).toFloat(), px(1))
        }
        header = text("", 14f, 0xDDFFFFFF.toInt()).apply {
            setPadding(px(16), px(10), px(16), px(10))
            background = GradientDrawable().apply { cornerRadius = px(14).toFloat(); setColor(0x55000000) }
        }
        panel.addView(header, LinearLayout.LayoutParams(match, wrap))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(content) }, LinearLayout.LayoutParams(match, 0, 1f).apply { topMargin = px(12) })

        root.addView(nav, LinearLayout.LayoutParams(px(250), match))
        root.addView(panel, LinearLayout.LayoutParams(0, match, 1f).apply { leftMargin = px(14) })
        setContentView(root)
        listOf(nav, panel).forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = px(40).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(30L * i).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun connectedDevice(): BluetoothDevice? = bt?.bondedDevices?.firstOrNull { d ->
        try { d.javaClass.getMethod("isConnected").invoke(d) as Boolean } catch (e: Throwable) { false }
    }

    private fun refreshHeader() {
        val a = bt
        header.text = when {
            a == null -> "This device has no Bluetooth"
            !a.isEnabled -> "Bluetooth is off  ·  car name: ${a.name}"
            else -> {
                val d = connectedDevice()
                "Car name: ${a.name}  ·  " + (if (d != null) "Connected to ${d.name ?: d.address}" else "No phone connected") +
                    "  ·  Calls: " + (if (callsSupported) "supported" else "not supported") +
                    "  ·  Phone music: " + (if (phoneMusicSupported) "supported" else "not supported")
            }
        }
    }

    private fun show(i: Int) {
        tab = i
        tabViews.forEachIndexed { j, t -> t.background = if (j == i) pill(true, 14) else null }
        content.removeAllViews()
        when (i) {
            0 -> devicesTab()
            1 -> musicTab()
            2 -> phoneTab()
            else -> settingsTab()
        }
        Themes.shadowText(content)
        content.alpha = 0f
        content.animate().alpha(1f).setDuration(200).start()
    }

    private fun title(t: String) = content.addView(text(t, 22f, bold = true).apply { setPadding(0, px(6), 0, px(6)) })
    private fun note(t: String) = content.addView(text(t, 14f, 0xCCFFFFFF.toInt()).apply { setPadding(0, px(2), 0, px(8)) })

    private fun row(name: String, sub: String, action: String, sel: Boolean, onClick: () -> Unit) {
        val r = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(16), px(10), px(12), px(10))
            background = pill(false, 14)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(name, 18f, if (sel) accent else Color.WHITE, bold = true))
        col.addView(text(sub, 13f, 0xBBFFFFFF.toInt()))
        r.addView(col, LinearLayout.LayoutParams(0, wrap, 1f))
        r.addView(button(action, 15f, 44, sel) { onClick() }, LinearLayout.LayoutParams(wrap, px(44)))
        content.addView(r, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(8) })
    }

    // ---- Devices ----

    /**
     * The head unit's own Bluetooth phone app. Most Android car radios run calls and phone music through a
     * separate Bluetooth chip controlled only by that app, so connecting the phone there is what works.
     */
    private fun carBtApp(): Pair<String, String>? {
        val pm = packageManager
        val known = listOf("goodocom", "com.tw.bt", "syu.bt", "nwd.bt", "btphone", "bluetoothphone", "com.ts.bt", "hzbhd.bt", "yt.bt", "carbt", ".bt")
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { (p, l) ->
                p != packageName && !p.startsWith("com.android") && !p.startsWith("com.google") &&
                    (known.any { p.lowercase().contains(it) } || l.lowercase().let { x -> x.contains("bluetooth") || x.contains("bt ") || x == "bt" || x.contains("phone") || x.contains("蓝牙") })
            }
            .firstOrNull()
    }

    /** Big button to the car's own Bluetooth app, shown on every tab when one exists. */
    private fun carBtButton() {
        val (pkg, name) = carBtApp() ?: return
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(12), px(16), px(12))
            background = GradientDrawable().apply { cornerRadius = px(16).toFloat(); setColor((0x44 shl 24) or (accent and 0xFFFFFF)) }
        }
        r.addView(text("Phone calls and phone music on this radio work through its own Bluetooth app.", 14f, Color.WHITE))
        r.addView(button("Open the car's Bluetooth:  $name", 17f, 52, sel = true) {
            packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, LinearLayout.LayoutParams(wrap, px(52)).apply { topMargin = px(10) })
        content.addView(r, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = px(12) })
    }

    /** Android 12+ needs the "Nearby devices" permission before anything Bluetooth works. */
    private fun needsBtPermission(): Boolean =
        Build.VERSION.SDK_INT >= 31 && checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun devicesTab() {
        carBtButton()
        if (needsBtPermission()) {
            note("Bluetooth needs the \"Nearby devices\" permission.")
            content.addView(button("Allow Bluetooth", 16f, 50, sel = true) { startActivity(Intent(this, PermissionsActivity::class.java)) },
                LinearLayout.LayoutParams(wrap, px(50)))
            return
        }
        val a = bt ?: run { title("No Bluetooth on this device"); return }
        val bar = LinearLayout(this)
        bar.addView(button(if (a.isEnabled) "Turn off" else "Turn on", 15f, 46) {
            if (a.isEnabled) a.disable() else a.enable()
            ui.postDelayed({ refreshHeader(); show(0) }, 1500)
        }, LinearLayout.LayoutParams(wrap, px(46)))
        bar.addView(button(if (a.isDiscovering) "Searching…" else "Search for phones", 15f, 46, sel = true) {
            found.clear()
            a.cancelDiscovery()
            a.startDiscovery()
            show(0)
        }, LinearLayout.LayoutParams(wrap, px(46)).apply { leftMargin = px(10) })
        bar.addView(button("Let phone find the car", 15f, 46) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
        }, LinearLayout.LayoutParams(wrap, px(46)).apply { leftMargin = px(10) })
        content.addView(bar)
        note("To connect from your phone: tap \"Let phone find the car\", then on the phone open Bluetooth and choose \"${a.name}\".")

        if (!a.isEnabled) return
        val paired = a.bondedDevices ?: emptySet()
        if (paired.isNotEmpty()) title("Paired")
        val now = connectedDevice()
        for (d in paired) {
            val on = d.address == now?.address
            row(d.name ?: d.address, if (on) "Connected" else "Paired", if (on) "Connected" else "Connect", on) { connect(d) }
        }
        val nearby = found.filter { f -> paired.none { it.address == f.address } }
        if (nearby.isNotEmpty()) title("Nearby")
        for (d in nearby) row(d.name ?: d.address, d.address, "Pair", false) {
            Toast.makeText(this, "Confirm the code on your phone", Toast.LENGTH_LONG).show()
            d.createBond()
        }
    }

    /** Connects every profile the unit offers: car-side hands-free and music first, then normal audio. */
    private fun connect(d: BluetoothDevice) {
        val a = bt ?: return
        a.cancelDiscovery()
        for (profile in intArrayOf(HEADSET_CLIENT, A2DP_SINK, BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            try {
                a.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        try { proxy.javaClass.getMethod("connect", BluetoothDevice::class.java).invoke(proxy, d) } catch (e: Throwable) { }
                        if (p != HEADSET_CLIENT) a.closeProfileProxy(p, proxy)
                    }
                    override fun onServiceDisconnected(p: Int) {}
                }, profile)
            } catch (e: Throwable) { }
        }
        Toast.makeText(this, "Connecting to ${d.name ?: d.address}…", Toast.LENGTH_SHORT).show()
        ui.postDelayed({
            if (connectedDevice()?.address != d.address) Toast.makeText(this,
                if (Build.VERSION.SDK_INT >= 31) "Android ${Build.VERSION.RELEASE} only lets the radio's own Bluetooth app connect phones." + (if (carBtApp() != null) " Use \"Open the car's Bluetooth\"." else "")
                else "Couldn't connect. Make sure the phone is unlocked and nearby.", Toast.LENGTH_LONG).show()
        }, 6000)
        ui.postDelayed({ refreshHeader(); if (tab == 0) show(0) }, 3000)
    }

    // ---- Music from the phone ----

    private fun musicTab() {
        carBtButton()
        title("Phone music")
        if (!phoneMusicSupported) note("This unit's Bluetooth can't receive music from a phone (no A2DP-sink profile). On a car head unit with phone-music support, the song from your phone shows here.")
        val c = np.controller
        val md = c?.metadata
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(20), px(20), px(20), px(20))
            background = pill(false, 22)
        }
        val art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (bmp != null) art.setImageBitmap(bmp) else art.setImageResource(R.drawable.ic_disc)
        card.addView(art, LinearLayout.LayoutParams(px(180), px(180)))
        card.addView(text(md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Nothing playing", 24f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, px(14), 0, 0) },
            LinearLayout.LayoutParams(match, wrap))
        card.addView(text(md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Play a song on your phone", 16f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(match, wrap))
        val ctrl = LinearLayout(this).apply { gravity = Gravity.CENTER }
        ctrl.addView(button("◀◀", 24f, 70) { key(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }, LinearLayout.LayoutParams(px(90), px(70)))
        ctrl.addView(button(if (np.isPlaying) "❚❚" else "▶", 28f, 84, sel = true) { key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); ui.postDelayed({ if (tab == 1) show(1) }, 600) },
            LinearLayout.LayoutParams(px(120), px(84)).apply { leftMargin = px(16); rightMargin = px(16) })
        ctrl.addView(button("▶▶", 24f, 70) { key(KeyEvent.KEYCODE_MEDIA_NEXT) }, LinearLayout.LayoutParams(px(90), px(70)))
        card.addView(ctrl, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(18) })
        content.addView(card, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(6) })
        if (!np.hasAccess()) note("Tip: allow \"notification access\" for Car Launcher in Settings → Launcher to see the song name and cover.")
    }

    /** Media keys go to the active player; with a phone connected for music that is the phone (AVRCP). */
    private fun key(code: Int) {
        val c = np.controller
        if (c != null) {
            c.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            c.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            return
        }
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    // ---- Phone (hands-free calls) ----

    private fun phoneTab() {
        carBtButton()
        title("Phone")
        if (!callsSupported) note("This unit's Bluetooth can't make calls through a phone (no hands-free client profile). On a car head unit with calling support, calls dial through your connected phone.")
        val display = text(if (number.isEmpty()) "Enter a number" else number.toString(), 34f, if (number.isEmpty()) 0x88FFFFFF.toInt() else Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, px(8), 0, px(8))
            background = pill(false, 16)
        }
        content.addView(display, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(4) })
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
        for (r in keys.chunked(3)) {
            val line = LinearLayout(this).apply { gravity = Gravity.CENTER }
            for (k in r) line.addView(button(k, 26f, 64) {
                if (number.length < 20) number.append(k)
                display.text = number
                display.setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(px(150), px(64)).apply { leftMargin = px(8); rightMargin = px(8) })
            content.addView(line, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(8) })
        }
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER }
        actions.addView(button("⌫", 22f, 64) {
            if (number.isNotEmpty()) number.deleteCharAt(number.length - 1)
            display.text = if (number.isEmpty()) "Enter a number" else number
        }, LinearLayout.LayoutParams(px(150), px(64)).apply { leftMargin = px(8); rightMargin = px(8) })
        actions.addView(button("☎  Call", 22f, 64, sel = true, color = 0xFF30D158.toInt()) { call("dial") },
            LinearLayout.LayoutParams(px(150), px(64)).apply { leftMargin = px(8); rightMargin = px(8) })
        actions.addView(button("End", 22f, 64, sel = true, color = 0xFFFF453A.toInt()) { call("terminateCall") },
            LinearLayout.LayoutParams(px(150), px(64)).apply { leftMargin = px(8); rightMargin = px(8) })
        content.addView(actions, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(12) })
        val more = LinearLayout(this).apply { gravity = Gravity.CENTER }
        more.addView(button("Answer", 16f, 48) { call("acceptCall") }, LinearLayout.LayoutParams(px(140), px(48)).apply { leftMargin = px(6); rightMargin = px(6) })
        more.addView(button("Reject", 16f, 48) { call("rejectCall") }, LinearLayout.LayoutParams(px(140), px(48)).apply { leftMargin = px(6); rightMargin = px(6) })
        more.addView(button("Redial", 16f, 48) { if (number.isEmpty()) number.append(getSharedPreferences("bt", MODE_PRIVATE).getString("last", "")); call("dial") },
            LinearLayout.LayoutParams(px(140), px(48)).apply { leftMargin = px(6); rightMargin = px(6) })
        content.addView(more, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(10) })
    }

    /** Hands-free calls go through the hidden BluetoothHeadsetClient API of car-mode Bluetooth stacks. */
    private fun call(method: String) {
        val p = hfp
        val d = connectedDevice()
        if (p == null) { Toast.makeText(this, "Calls aren't supported by this unit's Bluetooth", Toast.LENGTH_LONG).show(); return }
        if (d == null) { Toast.makeText(this, "Connect your phone first", Toast.LENGTH_SHORT).show(); return }
        try {
            when (method) {
                "dial" -> {
                    if (number.isEmpty()) return
                    getSharedPreferences("bt", MODE_PRIVATE).edit().putString("last", number.toString()).apply()
                    p.javaClass.getMethod("dial", BluetoothDevice::class.java, String::class.java).invoke(p, d, number.toString())
                }
                "acceptCall" -> p.javaClass.getMethod("acceptCall", BluetoothDevice::class.java, Int::class.javaPrimitiveType).invoke(p, d, 0)
                "rejectCall" -> p.javaClass.getMethod("rejectCall", BluetoothDevice::class.java).invoke(p, d)
                "terminateCall" -> p.javaClass.methods.first { it.name == "terminateCall" }.let { m ->
                    if (m.parameterTypes.size == 2) m.invoke(p, d, null) else m.invoke(p, d, 0)
                }
            }
        } catch (e: Throwable) {
            Toast.makeText(this, "The phone didn't accept that (${e.javaClass.simpleName})", Toast.LENGTH_LONG).show()
        }
    }

    // ---- Settings (rename) ----

    private fun settingsTab() {
        val a = bt ?: return
        title("Car name")
        note("This is the name your phone sees when it searches for Bluetooth.")
        val input = EditText(this).apply {
            setText(a.name)
            setTextColor(Color.WHITE)
            textSize = 20f
            setSingleLine()
            background = pill(false, 14)
            setPadding(px(18), 0, px(18), 0)
        }
        content.addView(input, LinearLayout.LayoutParams(match, px(56)))
        content.addView(button("Save name", 16f, 50, sel = true) {
            val n = input.text.toString().trim()
            if (n.isEmpty()) return@button
            if (a.setName(n)) Toast.makeText(this, "Car name changed to $n", Toast.LENGTH_SHORT).show()
            else Toast.makeText(this, "Turn Bluetooth on to change the name", Toast.LENGTH_SHORT).show()
            ui.postDelayed({ refreshHeader() }, 800)
        }, LinearLayout.LayoutParams(wrap, px(50)).apply { topMargin = px(12) })

        title("What this unit supports")
        note("Pair and connect a phone: ${if (a.isEnabled) "yes" else "turn Bluetooth on"}")
        note("Music from your phone: ${if (phoneMusicSupported) "yes" else "no (needs a car head unit with phone-music support)"}")
        note("Phone calls: ${if (callsSupported) "yes" else "no (needs a car head unit with hands-free calling)"}")
        content.addView(button("Open Android Bluetooth settings", 15f, 48) {
            try { startActivity(Intent("android.settings.BLUETOOTH_SETTINGS")) } catch (e: Exception) { }
        }, LinearLayout.LayoutParams(wrap, px(48)).apply { topMargin = px(10) })
    }
}
