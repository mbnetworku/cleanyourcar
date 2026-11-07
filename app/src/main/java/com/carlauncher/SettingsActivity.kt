package com.carlauncher

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.text.InputType
import android.widget.EditText
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/**
 * The launcher's own settings hub:
 *  - Launcher: theme, glass, widgets, units
 *  - Android: shortcuts into the system settings
 *  - Radio: head-unit / car settings apps found on this device
 *  - Speed up: what this box is, free RAM, stop other launchers, turn animations off
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var side: LinearLayout
    private lateinit var panel: ScrollView
    private lateinit var content: LinearLayout
    private val tabViews = ArrayList<View>()
    private val ui = Handler(Looper.getMainLooper())
    private var dn = 1f
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val tabNames = listOf("Launcher", "Wi-Fi", "Bluetooth", "Android", "Radio", "Speed up", "Wallpaper", "Drive", "Hotspot")
    private var current = 0
    private var listBox: LinearLayout? = null
    private var statusView: TextView? = null
    private val found = ArrayList<BluetoothDevice>()
    private val wifi by lazy { applicationContext.getSystemService(WIFI_SERVICE) as WifiManager }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == BluetoothDevice.ACTION_FOUND) {
                @Suppress("DEPRECATION")
                val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (d != null && found.none { it.address == d.address }) found.add(d)
            }
            when (current) {
                1 -> refreshWifi()
                2 -> refreshBt()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        registerReceiver(receiver, f)
        if (current == 5) select(5) // back from an app page: show the new enabled state
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(receiver) } catch (e: Exception) { }
        try { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() } catch (e: Exception) { }
    }

    private fun px(v: Int) = (v * dn).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        dn = resources.displayMetrics.density

        root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(16), px(16), px(16), px(16))
        }
        side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(8), px(12), px(8), px(12))
        }
        side.addView(TextView(this).apply {
            text = "Settings"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(px(12), px(4), px(12), px(16))
        })
        val tints = intArrayOf(0xFF0A84FF.toInt(), 0xFF30D158.toInt(), 0xFF0A84FF.toInt(), 0xFF8E8E93.toInt(), 0xFFFF9F0A.toInt(), 0xFFFF453A.toInt(), 0xFFAF52DE.toInt(), 0xFF5E5CE6.toInt(), 0xFFFF9F0A.toInt())
        val icons = intArrayOf(R.drawable.si_home, R.drawable.si_wifi, R.drawable.si_bt, R.drawable.si_android, R.drawable.si_radio,
            R.drawable.si_speed, R.drawable.si_wall, R.drawable.si_drive, R.drawable.si_hotspot)
        // Shown grouped; the numbers are the page indexes used everywhere else, so pages keep their ids.
        val groups = listOf("CONNECTIONS" to listOf(1, 2, 8), "LOOK & DRIVE" to listOf(0, 6, 7), "SYSTEM" to listOf(4, 3, 5))
        repeat(tabNames.size) { tabViews.add(View(this)) }
        for ((group, ids) in groups) {
            side.addView(TextView(this).apply {
                text = group
                textSize = 11f
                setTextColor(0xAAFFFFFF.toInt())
                setPadding(px(14), px(8), 0, px(4))
            })
            for (i in ids) {
            val name = tabNames[i]
            val tab = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(10), 0, px(10), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { select(i) }
            }
            tab.addView(ImageView(this).apply {
                setImageResource(icons[i])
                setPadding(px(6), px(6), px(6), px(6))
                background = GradientDrawable().apply { cornerRadius = px(9).toFloat(); setColor(tints[i]) }
            }, LinearLayout.LayoutParams(px(34), px(34)))
            tab.addView(TextView(this).apply {
                text = name
                textSize = 17f
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = px(12) })
            if (i == 1 || i == 2 || i == 8) {
                tab.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                tab.addView(quickSwitch(i))
            }
            tabViews[i] = tab
            side.addView(tab, LinearLayout.LayoutParams(match, px(48)).apply { bottomMargin = px(4) })
            }
        }
        side.addView(View(this), LinearLayout.LayoutParams(match, 0, 1f))
        side.addView(button("Done") { finish() }, LinearLayout.LayoutParams(match, px(56)))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(20), px(24), px(20))
        }
        panel = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        root.addView(side, LinearLayout.LayoutParams(px(240), match))
        root.addView(panel, LinearLayout.LayoutParams(0, match, 1f).apply { leftMargin = px(12) })
        setContentView(root)
        applyLook(true)
        if (Build.VERSION.SDK_INT >= 23) {
            val perms = arrayListOf(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) {
                perms.add("android.permission.BLUETOOTH_CONNECT")
                perms.add("android.permission.BLUETOOTH_SCAN")
            }
            requestPermissions(perms.toTypedArray(), 1)
        }
        select(intent.getIntExtra("tab", 0).coerceIn(0, tabNames.lastIndex))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = finish()

    /** Glass panels and background; the photo is only decoded again when [background] is true. */
    private fun applyLook(background: Boolean) {
        Themes.update(prefs)
        val accent = Themes.accent(prefs)
        val pct = Themes.glassPct(prefs)
        if (background) root.background = Themes.backgroundDrawable(this, prefs)
        side.background = Themes.glass(pct, accent, px(20).toFloat(), px(1))
        panel.background = Themes.glass(pct, accent, px(20).toFloat(), px(1))
    }

    private fun select(index: Int) {
        val accent = Themes.accent(prefs)
        tabViews.forEachIndexed { i, t ->
            t.background = if (i == index) {
                GradientDrawable().apply {
                    cornerRadius = px(14).toFloat()
                    setColor(0xFF0A84FF.toInt())
                }
            } else {
                null
            }
        }
        content.removeAllViews()
        current = index
        listBox = null
        statusView = null
        when (index) {
            0 -> launcherTab()
            1 -> wifiTab()
            2 -> bluetoothTab()
            3 -> androidTab()
            4 -> radioTab()
            5 -> speedTab()
            6 -> wallpaperTab()
            7 -> driveTab()
            else -> hotspotTab()
        }
        panel.scrollTo(0, 0)
        Themes.shadowText(root)
    }

    // ---- small UI helpers ----

    private fun title(t: String) = TextView(this).apply {
        text = t
        textSize = 32f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        setPadding(0, 0, 0, px(12))
    }

    private fun label(t: String) = TextView(this).apply {
        text = t.uppercase()
        textSize = 13f
        setTextColor(0xFFE3F1F5.toInt())
        setPadding(px(14), px(22), 0, px(8))
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(0xCCFFFFFF.toInt())
    }

    private fun button(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 16f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(px(16), px(12), px(16), px(12))
        background = iosRow()
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** iOS grouped-list row: rounded card that lights up while pressed. */
    private fun iosRow(): Drawable {
        fun card(c: Int) = GradientDrawable().apply { cornerRadius = px(14).toFloat(); setColor(c) }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), card(0x66FFFFFF))
            addState(intArrayOf(android.R.attr.state_focused), card(0x55FFFFFF))
            addState(intArrayOf(), card(if (Themes.dark) 0x55000000 else 0x40000000))
        }
    }

    private fun toggle(t: String, key: String, def: Boolean) = android.widget.Switch(this).apply {
        text = t
        textSize = 17f
        setTextColor(Color.WHITE)
        setPadding(px(16), px(12), px(16), px(12))
        background = iosRow()
        isChecked = prefs.getBoolean(key, def)
        setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
    }.also { it.layoutParams = LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(8) } }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    /** A row of equal-width buttons with [gap] between them. */
    private fun buttonRow(vararg buttons: View) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEachIndexed { i, b ->
            row.addView(b, LinearLayout.LayoutParams(0, px(56), 1f).apply { if (i > 0) leftMargin = px(12) })
        }
        content.addView(row, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(8) })
    }

    private fun swatches(count: Int, selected: () -> Int, drawable: (Int, Boolean) -> Drawable, onPick: (Int) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val views = ArrayList<View>()
        fun paint() {
            val sel = selected()
            views.forEachIndexed { i, v -> v.background = drawable(i, i == sel) }
        }
        for (i in 0 until count) {
            val v = View(this).apply {
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    onPick(i)
                    paint()
                }
            }
            views.add(v)
            row.addView(v, LinearLayout.LayoutParams(px(44), px(44)).apply { rightMargin = px(12) })
        }
        paint()
        content.addView(row)
    }

    // ---- Launcher tab ----

    private fun launcherTab() {
        content.addView(title("Launcher"))

        content.addView(label("Accent color"))
        swatches(
            Themes.accents.size,
            { prefs.getInt("accent", 0) },
            { i, sel -> Themes.swatch(Themes.accents[i], sel, dn) }
        ) { i ->
            prefs.edit().putInt("accent", i).apply()
            applyLook(false)
        }

        content.addView(label("Appearance"))
        val mode = prefs.getInt("theme_mode", 0)
        fun modeButton(name: String, value: Int) = button(if (mode == value) "✓ $name" else name) {
            prefs.edit().putInt("theme_mode", value).apply()
            applyLook(true)
            select(0)
        }
        buttonRow(modeButton("Auto", 0), modeButton("Light", 1), modeButton("Dark", 2))
        content.addView(hint("Auto switches to dark when it gets dark outside (car lights time) and back to light by day. Light and Dark stay fixed."))

        content.addView(label("Glass strength"))
        content.addView(SeekBar(this).apply {
            max = 60
            progress = Themes.glassPct(prefs)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        prefs.edit().putInt("glass", p.coerceAtLeast(6)).apply()
                        applyLook(false)
                    }
                }

                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        })

        content.addView(label("Home screen"))
        content.addView(toggle("Show analog clock card", "show_clock", true))
        content.addView(toggle("Show now-playing card", "show_media", true))
        content.addView(toggle("Show speed in mph", "mph", false))
        content.addView(hint("Tap an app slot on the home screen to open it. Hold a slot to change or clear it."))

        content.addView(label("Permissions"))
        buttonRow(button(if (PermissionsActivity.missing(this)) "Some permissions are missing: review" else "✓ All permissions allowed: review") {
            startActivity(Intent(this, PermissionsActivity::class.java))
        })

        content.addView(label("Now playing"))
        buttonRow(button("Allow now-playing access") {
            try {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            } catch (e: ActivityNotFoundException) {
                toast("Not available on this device")
            }
        })
    }

    /** On/off switch beside Wi-Fi (1), Bluetooth (2) and Hotspot (8) in the side list. */
    @Suppress("DEPRECATION")
    private fun quickSwitch(i: Int) = android.widget.Switch(this).apply {
        val bt = BluetoothAdapter.getDefaultAdapter()
        isChecked = when (i) {
            1 -> wifi.isWifiEnabled
            2 -> bt?.isEnabled == true
            else -> Hotspot.isOn(this@SettingsActivity)
        }
        setOnCheckedChangeListener { _, on ->
            when (i) {
                1 -> wifi.isWifiEnabled = on
                2 -> if (on) bt?.enable() else bt?.disable()
                else -> if (!Hotspot.apply(this@SettingsActivity, Hotspot.name(this@SettingsActivity), Hotspot.password(this@SettingsActivity), on)) {
                    toast("This unit doesn't let apps control the hotspot")
                    openSystem("android.settings.TETHER_SETTINGS")
                }
            }
            // Refresh the open page so it shows the new state.
            ui.postDelayed({ if (current == i) select(i) }, 1500)
        }
    }

    // ---- Hotspot tab ----

    private fun hotspotTab() {
        content.addView(title("Hotspot"))
        content.addView(hint("Share this unit's internet with phones and tablets in the car."))
        val on = Hotspot.isOn(this)
        content.addView(label("Status"))
        content.addView(hint(if (on) "Hotspot is ON  ·  name: ${Hotspot.name(this)}" else "Hotspot is off"))

        content.addView(label("Hotspot name"))
        val name = EditText(this).apply {
            setText(Hotspot.name(this@SettingsActivity))
            setTextColor(Color.WHITE)
            textSize = 18f
            setSingleLine()
            background = iosRow()
            setPadding(px(16), 0, px(16), 0)
        }
        content.addView(name, LinearLayout.LayoutParams(match, px(54)))
        content.addView(label("Password (at least 8 characters, empty = no password)"))
        val pass = EditText(this).apply {
            setText(Hotspot.password(this@SettingsActivity))
            setTextColor(Color.WHITE)
            textSize = 18f
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            background = iosRow()
            setPadding(px(16), 0, px(16), 0)
        }
        content.addView(pass, LinearLayout.LayoutParams(match, px(54)))

        fun save(turnOn: Boolean) {
            val n = name.text.toString().trim()
            val p = pass.text.toString()
            if (n.isEmpty()) { toast("Enter a hotspot name"); return }
            if (p.isNotEmpty() && p.length < 8) { toast("The password needs at least 8 characters"); return }
            if (Hotspot.apply(this, n, p, turnOn)) {
                toast(if (turnOn) "Hotspot on: $n" else "Hotspot saved and turned off")
            } else {
                toast("This unit doesn't let apps control the hotspot. Opening the system hotspot screen.")
                openSystem("android.settings.TETHER_SETTINGS")
            }
            ui.postDelayed({ if (current == 8) select(8) }, 2500)
        }
        buttonRow(
            button(if (on) "Save and keep on" else "Turn hotspot on") { save(true) },
            button("Turn off") { save(false) }
        )
        buttonRow(button("Open system hotspot settings") { openSystem("android.settings.TETHER_SETTINGS") })
        content.addView(hint("While the hotspot is on, normal Wi-Fi is turned off on most units because they share the same radio."))
    }

    // ---- Drive tab (speedometer + car) ----

    private fun choice(name: String, key: String, value: Int, def: Int) =
        button(if (prefs.getInt(key, def) == value) "✓ $name" else name) {
            prefs.edit().putInt(key, value).apply()
            select(7)
        }

    private fun sizeSlider(key: String, min: Int, maxV: Int) = SeekBar(this).apply {
        max = maxV - min
        progress = prefs.getInt(key, 100) - min
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) prefs.edit().putInt(key, p + min).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun driveTab() {
        content.addView(title("Drive"))
        content.addView(hint("Changes show on the home screen when you go back."))

        content.addView(label("Home layout"))
        content.addView(hint("Apps sidebar"))
        buttonRow(choice("Left", "apps_side", 0, 0), choice("Right", "apps_side", 1, 0))
        content.addView(hint("Clock and music"))
        buttonRow(choice("Left", "widgets_side", 0, 0), choice("Right", "widgets_side", 1, 0))

        content.addView(hint("Number of apps in the dock (hold any dock app to change it)"))
        buttonRow(choice("6 apps", "dock_count", 6, 8), choice("8 apps", "dock_count", 8, 8), choice("10 apps", "dock_count", 10, 8))

        content.addView(label("Speedometer style"))
        buttonRow(choice("Dial", "speedo_style", 0, 0), choice("Number only", "speedo_style", 1, 0))
        content.addView(label("Speedometer size"))
        content.addView(sizeSlider("speedo_size", 30, 130))
        content.addView(label("Speedometer position"))
        buttonRow(choice("Left", "speedo_h", 0, 1), choice("Center", "speedo_h", 1, 1), choice("Right", "speedo_h", 2, 1))
        buttonRow(choice("Top", "speedo_v", 0, 0), choice("Middle", "speedo_v", 1, 0), choice("Bottom", "speedo_v", 2, 0))

        content.addView(label("Real car photo"))
        val sel = prefs.getInt("car_photo", -1)
        val photoRow = LinearLayout(this)
        CarPhotos.names.indices.forEach { i ->
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(CarPhotos.thumb(this@SettingsActivity, i))
                setPadding(px(3), px(3), px(3), px(3))
                background = GradientDrawable().apply {
                    cornerRadius = px(12).toFloat()
                    setColor(if (sel == i) 0xFF0A84FF.toInt() else 0x33FFFFFF)
                }
                isClickable = true
                setOnClickListener {
                    prefs.edit().putInt("car_photo", i).apply()
                    select(7)
                }
            }
            photoRow.addView(img, LinearLayout.LayoutParams(0, px(110), 1f).apply { if (i > 0) leftMargin = px(8) })
        }
        content.addView(photoRow, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(8) })
        buttonRow(
            button(if (sel == 100) "✓ My own car photo" else "Choose my own car photo") {
                try {
                    startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_CAR)
                } catch (e: ActivityNotFoundException) {
                    toast("No gallery app found on this device")
                }
            },
            button(if (sel == -1) "✓ Drawn car" else "Use drawn car") {
                prefs.edit().putInt("car_photo", -1).apply()
                select(7)
            }
        )
        content.addView(hint("Lexus RX, Tesla Model S, Corolla, Lancer, Pajero, FJ Cruiser, Camry.\nTip: a PNG with the background removed looks best. Car style and color apply to the drawn car."))

        content.addView(label("Car style"))
        buttonRow(choice("Classic sedan", "car_style", 0, 0), choice("Off-road 4x4", "car_style", 1, 0), choice("Sport sedan", "car_style", 2, 0))
        buttonRow(choice("Luxury SUV", "car_style", 3, 0), choice("Electric sedan", "car_style", 4, 0), choice("Executive sedan", "car_style", 5, 0))
        content.addView(label("Car color"))
        swatches(
            Themes.carColors.size,
            { prefs.getInt("car_color", 0) },
            { i, sel -> Themes.swatch(Themes.carColors[i], sel, dn) }
        ) { i -> prefs.edit().putInt("car_color", i).apply() }
        content.addView(label("Car size"))
        content.addView(sizeSlider("car_size", 50, 150))
    }

    // ---- Wallpaper tab ----

    private fun wallpaperTab() {
        content.addView(title("Wallpaper"))
        content.addView(hint("Pick a color wallpaper or use your own photo. It shows behind the whole launcher."))

        val chosen = if (prefs.getBoolean("bg_photo", false)) prefs.getLong("wall_seed", -1L) else -2L
        val tiles = ArrayList<ImageView>()
        for (rowSeeds in Wallpapers.presetSeeds.toList().chunked(3)) {
            val row = LinearLayout(this)
            for ((j, seed) in rowSeeds.withIndex()) {
                val frame = android.widget.FrameLayout(this).apply {
                    setPadding(px(3), px(3), px(3), px(3))
                    background = GradientDrawable().apply {
                        cornerRadius = px(16).toFloat()
                        setColor(if (seed == chosen) 0xFF0A84FF.toInt() else 0x00000000)
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        toast("Applying wallpaper…")
                        Wallpapers.applySeed(this@SettingsActivity, seed) { applyLook(true); select(6) }
                    }
                }
                val img = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(0x33FFFFFF)
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(v: View, o: android.graphics.Outline) =
                            o.setRoundRect(0, 0, v.width, v.height, px(13).toFloat())
                    }
                    clipToOutline = true
                }
                tiles.add(img)
                frame.addView(img, android.widget.FrameLayout.LayoutParams(match, px(150)))
                row.addView(frame, LinearLayout.LayoutParams(0, wrap, 1f).apply { if (j > 0) leftMargin = px(10) })
            }
            content.addView(row, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(10) })
        }
        Thread {
            Wallpapers.presetSeeds.forEachIndexed { i, s ->
                val b = Wallpapers.preview(this, s)
                ui.post { if (current == 6) tiles[i].setImageBitmap(b) }
            }
        }.start()

        content.addView(label("Colors"))
        swatches(
            Themes.backgrounds.size,
            { if (prefs.getBoolean("bg_photo", false)) -1 else prefs.getInt("bg", 0) },
            { i, sel -> Themes.swatchGradient(Themes.backgrounds[i], sel, dn) }
        ) { i ->
            prefs.edit().putInt("bg", i).putBoolean("bg_photo", false).apply()
            applyLook(true)
        }

        content.addView(label("Automatic random wallpaper (Full HD)"))
        val auto = prefs.getInt("auto_wall", 0)
        fun autoButton(name: String, v: Int) = button(if (auto == v) "✓ $name" else name) {
            prefs.edit().putInt("auto_wall", v).apply()
            if (v != 0) {
                toast("Getting a wallpaper…")
                Wallpapers.refresh(this) { applyLook(true); select(6) }
            } else {
                select(6)
            }
        }
        buttonRow(autoButton("Off", 0), autoButton("Every start", 1), autoButton("Every day", 2))
        buttonRow(button("New random wallpaper now") {
            toast("Getting a wallpaper…")
            Wallpapers.refresh(this) { applyLook(true); select(6) }
        })
        content.addView(hint("Uses a random photo from the internet, or a generated one when offline."))

        content.addView(label("Your photo"))
        buttonRow(
            button("Choose photo from gallery") { pickPhoto() },
            button("Remove photo") {
                prefs.edit().putBoolean("bg_photo", false).apply()
                applyLook(true)
                select(6)
            }
        )

        content.addView(label("Photo darkness (keeps text readable)"))
        content.addView(SeekBar(this).apply {
            max = 80
            progress = prefs.getInt("dim", 8)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) prefs.edit().putInt("dim", p).apply()
                }

                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {
                    applyLook(true)
                }
            })
        })
    }

    private fun pickPhoto() {
        try {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_PHOTO)
        } catch (e: ActivityNotFoundException) {
            toast("No gallery app found on this device")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        if (requestCode == REQ_CAR) {
            Thread {
                try {
                    val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    if (bmp != null) {
                        // PNG keeps a transparent background if the picture has one.
                        FileOutputStream(CarPhotos.customFile(this)).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        prefs.edit().putInt("car_photo", 100).apply()
                        ui.post { select(7) }
                    }
                } catch (e: Exception) {
                    ui.post { toast("Could not load that picture") }
                }
            }.start()
            return
        }
        if (requestCode != REQ_PHOTO) return
        Thread {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / sample > 2000) sample *= 2
                val bmp = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
                if (bmp != null) {
                    FileOutputStream(File(filesDir, "bg.jpg")).use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
                    prefs.edit().putBoolean("bg_photo", true).apply()
                    ui.post {
                        applyLook(true)
                        select(0)
                    }
                }
            } catch (e: Exception) {
                ui.post { toast("Could not load that picture") }
            }
        }.start()
    }

    // ---- Wi-Fi tab ----

    private fun row(name: String, sub: String, onClick: () -> Unit) = button("$name\n$sub", onClick).apply {
        gravity = Gravity.CENTER_VERTICAL
        textSize = 16f
    }

    private fun addRow(box: LinearLayout, v: View) =
        box.addView(v, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(8) })

    private fun wifiTab() {
        content.addView(title("Wi-Fi"))
        val status = hint("")
        statusView = status
        content.addView(status)
        content.addView(android.widget.Switch(this).apply {
            background = iosRow()
            setPadding(px(16), px(14), px(16), px(14))
            text = "Wi-Fi"
            textSize = 16f
            setTextColor(Color.WHITE)
            isChecked = wifi.isWifiEnabled
            setOnCheckedChangeListener { _, on ->
                @Suppress("DEPRECATION")
                wifi.isWifiEnabled = on
                ui.postDelayed({ if (current == 1) { wifi.startScan(); refreshWifi() } }, 2000)
            }
        })
        buttonRow(
            button("Scan again") { wifi.startScan(); toast("Scanning…") },
            button("Open system Wi-Fi") { openSystem("android.settings.WIFI_SETTINGS") }
        )
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listBox = box
        content.addView(box)
        wifi.startScan()
        refreshWifi()
    }

    @Suppress("DEPRECATION")
    private fun refreshWifi() {
        val box = listBox ?: return
        box.removeAllViews()
        if (!wifi.isWifiEnabled) { statusView?.text = "Wi-Fi is off"; return }
        val info = wifi.connectionInfo
        val connectedSsid = if (info != null && info.networkId != -1) info.ssid.trim('"') else null
        statusView?.text = if (connectedSsid != null) "Connected to $connectedSsid" else "Not connected"
        val configured = try { wifi.configuredNetworks } catch (e: Exception) { null } ?: emptyList()
        val nets = (wifi.scanResults ?: emptyList()).filter { it.SSID.isNotEmpty() }
            .groupBy { it.SSID }.map { g -> g.value.maxByOrNull { it.level }!! }
            .sortedByDescending { it.level }
        for (r in nets) {
            val secured = r.capabilities.contains("WPA") || r.capabilities.contains("WEP")
            val saved = configured.firstOrNull { it.SSID == "\"${r.SSID}\"" }
            val state = when {
                r.SSID == connectedSsid -> "Connected"
                saved != null -> "Saved"
                secured -> "Secured"
                else -> "Open"
            }
            val signal = WifiManager.calculateSignalLevel(r.level, 5) * 25
            addRow(box, row(r.SSID, "$state  •  signal $signal%") { connectWifi(r, saved, secured) })
        }
    }

    @Suppress("DEPRECATION")
    private fun connectWifi(r: ScanResult, saved: WifiConfiguration?, secured: Boolean) {
        if (saved != null) { joinWifi(saved.networkId); return }
        fun config() = WifiConfiguration().apply { SSID = "\"${r.SSID}\"" }
        if (!secured) {
            joinWifi(wifi.addNetwork(config().apply { allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE) }))
            return
        }
        val input = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        AlertDialog.Builder(this).setTitle(r.SSID).setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val pw = input.text.toString()
                val c = config()
                if (r.capabilities.contains("WEP")) {
                    c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    c.wepKeys[0] = "\"$pw\""
                    c.wepTxKeyIndex = 0
                } else {
                    c.preSharedKey = "\"$pw\""
                }
                joinWifi(wifi.addNetwork(c))
            }
            .setNegativeButton("Cancel", null).show()
    }

    @Suppress("DEPRECATION")
    private fun joinWifi(id: Int) {
        if (id == -1) { toast("Could not add this network"); return }
        wifi.disconnect()
        wifi.enableNetwork(id, true)
        wifi.reconnect()
        wifi.saveConfiguration()
        toast("Connecting…")
        ui.postDelayed({ if (current == 1) refreshWifi() }, 4000)
    }

    // ---- Bluetooth tab ----

    private fun bluetoothTab() {
        content.addView(title("Bluetooth"))
        val status = hint("")
        statusView = status
        content.addView(status)
        val a = BluetoothAdapter.getDefaultAdapter()
        if (a == null) { status.text = "No Bluetooth on this device"; return }
        content.addView(android.widget.Switch(this).apply {
            background = iosRow()
            setPadding(px(16), px(14), px(16), px(14))
            text = "Bluetooth"
            textSize = 16f
            setTextColor(Color.WHITE)
            isChecked = a.isEnabled
            setOnCheckedChangeListener { _, on ->
                if (on) a.enable() else a.disable()
                ui.postDelayed({ if (current == 2) refreshBt() }, 1500)
            }
        })
        buttonRow(
            button("Search nearby") {
                found.clear()
                a.cancelDiscovery()
                if (a.isEnabled) { a.startDiscovery(); toast("Searching…") }
                refreshBt()
            },
            button("Open system Bluetooth") { openSystem("android.settings.BLUETOOTH_SETTINGS") }
        )
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listBox = box
        content.addView(box)
        refreshBt()
    }

    private fun refreshBt() {
        val box = listBox ?: return
        box.removeAllViews()
        val a = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!a.isEnabled) { statusView?.text = "Bluetooth is off"; return }
        statusView?.text = if (a.isDiscovering) "Searching…" else "Bluetooth is on"
        val paired = a.bondedDevices ?: emptySet()
        if (paired.isNotEmpty()) box.addView(label("Paired devices"))
        for (d in paired) addRow(box, row(d.name ?: d.address, "Paired  •  tap to connect") { connectBt(a, d) })
        val nearby = found.filter { f -> paired.none { it.address == f.address } }
        if (nearby.isNotEmpty()) box.addView(label("Nearby devices"))
        for (d in nearby) addRow(box, row(d.name ?: d.address, "Tap to pair") {
            toast("Pairing…")
            d.createBond()
        })
    }

    private fun connectBt(a: BluetoothAdapter, d: BluetoothDevice) {
        a.cancelDiscovery()
        for (profile in intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            a.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    try {
                        proxy.javaClass.getMethod("connect", BluetoothDevice::class.java).invoke(proxy, d)
                    } catch (e: Exception) { }
                    a.closeProfileProxy(p, proxy)
                }
                override fun onServiceDisconnected(p: Int) {}
            }, profile)
        }
        toast("Connecting to ${d.name ?: d.address}…")
    }

    // ---- Android tab ----

    private fun androidTab() {
        content.addView(title("Android settings"))
        content.addView(hint("Shortcuts straight into the system settings of this box."))
        val items = listOf(
            "All settings" to "android.settings.SETTINGS",
            "Wi-Fi" to "android.settings.WIFI_SETTINGS",
            "Bluetooth" to "android.settings.BLUETOOTH_SETTINGS",
            "Display" to "android.settings.DISPLAY_SETTINGS",
            "Sound" to "android.settings.SOUND_SETTINGS",
            "Location" to "android.settings.LOCATION_SOURCE_SETTINGS",
            "Date & time" to "android.settings.DATE_SETTINGS",
            "Language" to "android.settings.LOCALE_SETTINGS",
            "Apps" to "android.settings.APPLICATION_SETTINGS",
            "Storage" to "android.settings.INTERNAL_STORAGE_SETTINGS",
            "About device" to "android.settings.DEVICE_INFO_SETTINGS",
            "Default launcher" to "android.settings.HOME_SETTINGS",
            "Network" to "android.settings.WIRELESS_SETTINGS",
            "Ethernet / Data" to "android.settings.DATA_ROAMING_SETTINGS",
            "Keyboard" to "android.settings.INPUT_METHOD_SETTINGS",
            "Accessibility" to "android.settings.ACCESSIBILITY_SETTINGS",
            "Security" to "android.settings.SECURITY_SETTINGS",
            "Developer options" to "android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
            "Cast / Screen" to "android.settings.CAST_SETTINGS",
            "Memory" to "android.settings.MEMORY_CARD_SETTINGS",
            "Manage apps" to "android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS",
            "Sync / Accounts" to "android.settings.SYNC_SETTINGS",
            "Privacy / Backup" to "android.settings.PRIVACY_SETTINGS",
            "Battery / Power" to "android.settings.BATTERY_SAVER_SETTINGS"
        )
        content.addView(View(this), LinearLayout.LayoutParams(match, px(8)))
        for (chunk in items.chunked(3)) {
            val views = chunk.map { (name, action) -> button(name) { openSystem(action) } }.toMutableList<View>()
            while (views.size < 3) views.add(View(this))
            buttonRow(*views.toTypedArray())
        }

        // Every page this box's own Settings app exposes, including head-unit specific ones.
        content.addView(title("Every settings page on this box"))
        content.addView(hint("All pages found in the system Settings app, so nothing is out of reach."))
        val pages = try {
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageInfo("com.android.settings", PackageManager.GET_ACTIVITIES)
            (info.activities ?: emptyArray()).filter { it.exported && it.enabled }
                .map { it.loadLabel(packageManager).toString() to it.name }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }
        } catch (e: Exception) {
            emptyList()
        }
        for (chunk in pages.chunked(3)) {
            val views = chunk.map { (name, cls) ->
                button(name) {
                    try {
                        startActivity(Intent().setClassName("com.android.settings", cls))
                    } catch (e: Exception) {
                        toast("Not available on this device")
                    }
                }
            }.toMutableList<View>()
            while (views.size < 3) views.add(View(this))
            buttonRow(*views.toTypedArray())
        }
    }

    private fun openSystem(action: String) {
        try {
            startActivity(Intent(action))
        } catch (e: Exception) {
            toast("Not available on this device")
        }
    }

    // ---- Radio tab ----

    private fun radioTab() {
        content.addView(title("Radio / head-unit settings"))
        content.addView(hint("Car and radio settings apps found on this box. Tap one to open it."))

        val pm = packageManager
        val words = listOf("setting", "factory", "radio", "carsetting")
        val vendors = listOf("syu", "fyt", "mtc", "hct", "zxw", "microntek", "factory")
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = pm.queryIntentActivities(intent, 0)
            .map { AppItem(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != packageName && it.pkg != "com.android.settings" }
            .filter { a ->
                words.any { a.label.lowercase().contains(it) } || vendors.any { a.pkg.lowercase().contains(it) }
            }
        val saved = prefs.getString("radio_settings", "")!!.split(",")
            .filter { it.isNotEmpty() && Apps.intent(pm, it) != null }
            .map { AppItem(it, try { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() } catch (e: Exception) { it }) }
        val tiles = (saved + found).distinctBy { it.pkg }

        content.addView(View(this), LinearLayout.LayoutParams(match, px(8)))
        if (tiles.isEmpty()) content.addView(hint("Nothing detected automatically. Use the button below to pick the settings app of your radio."))
        for (chunk in tiles.chunked(4)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until 4) {
                val item = chunk.getOrNull(i)
                if (item == null) {
                    row.addView(View(this), LinearLayout.LayoutParams(0, wrap, 1f))
                    continue
                }
                val v = layoutInflater.inflate(R.layout.item_app, row, false)
                v.findViewById<TextView>(R.id.label).text = item.label
                IconLoader.load(pm, item.pkg, v.findViewById<ImageView>(R.id.icon))
                v.isClickable = true
                v.isFocusable = true
                v.setBackgroundResource(R.drawable.bg_sel)
                v.setOnClickListener { open(item.pkg) }
                row.addView(v, LinearLayout.LayoutParams(0, wrap, 1f))
            }
            content.addView(row)
        }
        buttonRow(button("Choose another app") {
            AppPicker.show(this, "Choose your radio settings app", false) { pkg ->
                if (pkg != null) {
                    val list = prefs.getString("radio_settings", "")!!.split(",").filter { it.isNotEmpty() }.toMutableList()
                    if (pkg !in list) list.add(pkg)
                    prefs.edit().putString("radio_settings", list.joinToString(",")).apply()
                    select(2)
                    open(pkg)
                }
            }
        })
    }

    private fun open(pkg: String) {
        val i = Apps.intent(packageManager, pkg)
        if (i == null) {
            toast("App not found")
            return
        }
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("Could not open it")
        }
    }

    // ---- Speed up tab ----

    /** Sizes in GB (e.g. "1.74 GB"); amounts under 1 GB stay in MB so small numbers still mean something. */
    private fun mb(bytes: Long) =
        if (bytes >= 1L shl 30) "%.2f GB".format(bytes / 1073741824.0) else "${bytes / 1048576} MB"

    private fun isEnabled(pkg: String) = try {
        @Suppress("DEPRECATION")
        packageManager.getApplicationInfo(pkg, PackageManager.GET_DISABLED_COMPONENTS).enabled && pkg !in Apps.blocked(this)
    } catch (e: Exception) {
        true
    }

    /**
     * Turns an app on or off. Android only lets system-signed apps do this directly, so when that is refused
     * the app's own system page is opened, where the Disable / Enable button is one tap away.
     */
    private fun setAppEnabled(pkg: String, label: String, on: Boolean) {
        // A real system-level disable where the unit allows it; otherwise the launcher blocks it itself.
        try {
            packageManager.setApplicationEnabledSetting(pkg,
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0)
        } catch (e: SecurityException) { }
        Apps.setBlocked(this, pkg, !on)
        if (!on) Apps.stop(this, pkg)
        AppPicker.invalidate()
        android.app.AlertDialog.Builder(this)
            .setTitle(if (on) "Launcher enabled" else "Launcher disabled")
            .setMessage(if (on) "$label is enabled" else "$label is disabled.\nCar Launcher keeps it stopped and hidden. It is not uninstalled.")
            .setPositiveButton("OK", null).show()
    }

    /**
     * Android only lets the user pick the Home app. With no default set, pressing Home shows the chooser
     * (pick Car Launcher, then "Always"); with another one set, Android's Home screen picker is opened.
     */
    private fun makeDefault() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Apps.defaultHome(packageManager) == null) {
            toast("Choose Car Launcher, then tap \"Always\"")
            startActivity(home)
            return
        }
        try {
            startActivity(Intent("android.settings.HOME_SETTINGS"))
            toast("Choose Car Launcher")
        } catch (e: Exception) {
            toast("Choose Car Launcher, then tap \"Always\"")
            startActivity(Intent.createChooser(home, "Home app"))
        }
    }


    /**
     * Read-only probe of the head unit: build and firmware properties, the vendor radio/MCU interfaces each
     * common head-unit family ships, radio-related apps and services, and kernel radio nodes.
     */
    private fun radioReport(): String {
        val out = StringBuilder()
        fun prop(k: String) = try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, k) as String
        } catch (e: Throwable) { "" }
        out.append("Board     ${Build.BOARD} / ${Build.HARDWARE}\n")
        out.append("Model     ${Build.MANUFACTURER} ${Build.MODEL}\n")
        out.append("Android   ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        for (k in listOf("ro.build.display.id", "ro.product.board", "ro.board.platform", "ro.fota.version",
                "persist.sys.mcu.version", "ro.mcu.version", "ro.build.version.incremental")) {
            prop(k).takeIf { it.isNotBlank() }?.let { out.append("${k.padEnd(28)} $it\n") }
        }
        out.append("\nRadio interfaces found:\n")
        val classes = listOf(
            "android.tw.john.TWUtil" to "Topway / TW",
            "android.microntek.CarManager" to "Microntek (MTC)",
            "android.microntek.mtcser.MTCService" to "Microntek service",
            "com.syu.ipc.RemoteModuleProxy" to "FYT (SYU)",
            "com.syu.ipc.IRemoteToolkit" to "FYT toolkit",
            "android.car.hardware.radio.CarRadioManager" to "Android Automotive radio",
            "android.hardware.radio.RadioManager" to "Android broadcast radio",
            "com.hzbhd.proxy.RadioManager" to "HZBHD",
            "com.ts.MainUI.RadioManager" to "TS",
            "com.yuntian.radio.RadioManager" to "YT",
            "android.yt.YtManager" to "YT"
        )
        var any = false
        for ((cls, name) in classes) {
            val ok = try { Class.forName(cls); true } catch (e: Throwable) { false }
            if (ok) { out.append("  ✓ $name  ($cls)\n"); any = true }
        }
        if (!any) out.append("  none of the known ones\n")
        out.append("\nRadio-related apps:\n")
        val words = listOf("radio", "fm", "tuner", "mcu", "car", ".tw.", "syu", "microntek", "yt", "canbus", "bt", "phone")
        packageManager.getInstalledPackages(0).map { it.packageName }.sorted()
            .filter { p -> words.any { p.lowercase().contains(it) } && !p.startsWith("com.google") && p != packageName }
            .forEach { p ->
                val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)) } catch (e: Exception) { "" }
                out.append("  $p  $label\n")
            }
        out.append("\nSystem services:\n")
        try {
            @Suppress("UNCHECKED_CAST")
            val names = Class.forName("android.os.ServiceManager").getMethod("listServices").invoke(null) as Array<String>
            names.filter { n -> listOf("radio", "fm", "mcu", "car", "tw", "yt", "can").any { n.lowercase().contains(it) } }
                .forEach { out.append("  $it\n") }
        } catch (e: Throwable) { out.append("  (not readable)\n") }
        out.append("\nKernel nodes:\n")
        listOf("/dev/radio0", "/dev/fmradio", "/dev/tef6686", "/dev/si47xx", "/dev/mcu", "/dev/ttyS1", "/dev/ttyS3", "/dev/ttyS4")
            .filter { File(it).exists() }.forEach { out.append("  $it\n") }
        return out.toString()
    }

    private fun speedTab() {
        content.addView(title("Speed up"))

        content.addView(label("This device"))
        val info = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setTextColor(Color.WHITE)
            setLineSpacing(0f, 1.2f)
        }
        content.addView(info)
        fun refreshInfo() {
            info.text = deviceInfo()
        }
        refreshInfo()

        content.addView(label("Memory"))
        val result = hint("Ends background apps you are not using. System apps and the apps in your slots are never touched.")
        buttonRow(button("Free memory now") {
            result.text = "Cleaning..."
            Thread {
                val freed = Boost.free(this)
                ui.post {
                    result.text = "Freed ${mb(freed)}. Free RAM now: ${mb(Boost.availMem(this))}."
                    refreshInfo()
                }
            }.start()
        })
        content.addView(result)
        content.addView(toggle("Free memory automatically when the launcher starts", "auto_free", false))

        content.addView(label("Car radio info"))
        content.addView(hint("Shows which radio system this unit uses, so a matching FM driver can be added. Take a photo of the result and send it."))
        val radioInfo = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
        }
        buttonRow(button("Check radio system") {
            radioInfo.text = "Checking…"
            Thread { val r = radioReport(); ui.post { radioInfo.text = r } }.start()
        })
        content.addView(radioInfo)

        content.addView(label("Default launcher"))
        val home = Apps.defaultHome(packageManager)
        if (home == packageName) {
            content.addView(hint("✓ Car Launcher is your default launcher. The Home button always opens it."))
        } else {
            content.addView(hint(if (home == null) "No default launcher chosen yet, so Android asks every time you press Home."
                else "Your Home button opens another launcher right now."))
            buttonRow(button("Make Car Launcher the default") { makeDefault() })
        }

        content.addView(label("Other launchers"))
        content.addView(hint("Stop ends its background work for now. Disable turns it off completely so it uses no memory; Enable brings it back. Nothing is uninstalled."))
        // Disabled launchers drop out of Android's lists, so remember every one ever seen to offer Enable later.
        val seen = (prefs.getStringSet("known_launchers", emptySet()) ?: emptySet()).toMutableSet()
        val live = Apps.otherLaunchers(this)
        seen.addAll(live.map { it.pkg })
        prefs.edit().putStringSet("known_launchers", seen).apply()
        val others = seen.mapNotNull { p ->
            live.firstOrNull { it.pkg == p } ?: try {
                @Suppress("DEPRECATION")
                val ai = packageManager.getApplicationInfo(p, PackageManager.GET_DISABLED_COMPONENTS)
                AppItem(p, packageManager.getApplicationLabel(ai).toString())
            } catch (e: Exception) { null }
        }.sortedBy { it.label.lowercase() }
        if (others.isEmpty()) content.addView(hint("No other launcher found."))
        for (l in others) {
            val on = isEnabled(l.pkg)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(ImageView(this).also { IconLoader.load(packageManager, l.pkg, it) }, LinearLayout.LayoutParams(px(40), px(40)))
            row.addView(TextView(this).apply {
                text = l.label + if (on) "" else "  (disabled)"
                textSize = 16f
                setTextColor(if (on) Color.WHITE else 0x99FFFFFF.toInt())
            }, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = px(12) })
            if (on) {
                row.addView(button("Open") { open(l.pkg) }, LinearLayout.LayoutParams(px(100), px(48)).apply { rightMargin = px(8) })
                row.addView(button("Stop") {
                    Apps.stop(this, l.pkg)
                    toast("${l.label} stopped")
                    ui.postDelayed({ refreshInfo() }, 900)
                }, LinearLayout.LayoutParams(px(100), px(48)).apply { rightMargin = px(8) })
            }
            row.addView(button(if (on) "Disable" else "Enable") {
                setAppEnabled(l.pkg, l.label, !on)
                ui.postDelayed({ if (current == 5) select(5) }, 800)
            }, LinearLayout.LayoutParams(px(120), px(48)))
            content.addView(row, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(8) })
        }

        content.addView(label("System animations"))
        content.addView(hint("Turning animations off makes a slow box feel much faster. Android needs a one-time permission, given from a PC over ADB:"))
        content.addView(TextView(this).apply {
            text = "adb shell pm grant com.carlauncher android.permission.WRITE_SECURE_SETTINGS"
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(Themes.accent(prefs))
            setTextIsSelectable(true)
            setPadding(0, px(6), 0, px(6))
        })
        buttonRow(
            button("Animations off") { setAnimations(0f) },
            button("Animations normal") { setAnimations(1f) }
        )
    }

    private fun setAnimations(scale: Float) {
        try {
            Settings.Global.putFloat(contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, scale)
            Settings.Global.putFloat(contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, scale)
            Settings.Global.putFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
            toast(if (scale == 0f) "System animations are off" else "System animations are back to normal")
        } catch (e: SecurityException) {
            toast("Permission needed: run the ADB command shown above once")
        }
    }

    private fun deviceInfo(): String {
        val pm = packageManager
        val dm = resources.displayMetrics
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        val installed = pm.getInstalledApplications(0).map { it.packageName }
        val platform = when {
            installed.any { it.startsWith("com.syu") } -> "SYU / FYT"
            installed.any { it.startsWith("com.microntek") || it.startsWith("android.microntek") } -> "Microntek (MTCD)"
            installed.any { it.startsWith("com.hct") } -> "HCT"
            else -> "not recognised"
        }
        fun yes(feature: String) = if (pm.hasSystemFeature(feature)) "yes" else "no"
        return listOf(
            "Device      ${Build.MANUFACTURER} ${Build.MODEL}",
            "Android     ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "CPU         ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}, ${Runtime.getRuntime().availableProcessors()} cores",
            "RAM         ${mb(Boost.availMem(this))} free of ${mb(Boost.totalMem(this))}",
            "Storage     ${mb(free)} free of ${mb(total)}",
            "Screen      ${dm.widthPixels}x${dm.heightPixels}, ${dm.densityDpi} dpi",
            "GPS         ${yes(PackageManager.FEATURE_LOCATION_GPS)}",
            "Wi-Fi       ${yes(PackageManager.FEATURE_WIFI)}",
            "Bluetooth   ${yes(PackageManager.FEATURE_BLUETOOTH)}",
            "Head unit   $platform (guess)",
            "Apps        ${installed.size} installed"
        ).joinToString("\n")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        @Suppress("DEPRECATION")
        if (hasFocus) window.decorView.systemUiVisibility = MainActivity.immersiveFlags()
    }

    companion object {
        private const val REQ_PHOTO = 1
        private const val REQ_CAR = 2
    }
}
