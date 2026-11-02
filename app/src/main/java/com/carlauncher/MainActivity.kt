package com.carlauncher

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaMetadata
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private class Slot(val key: String, val icon: ImageView, val label: TextView)

    private lateinit var prefs: SharedPreferences
    private lateinit var root: View
    private lateinit var speedo: SpeedometerView
    private lateinit var road: RoadView
    private lateinit var analog: AnalogClockView
    private lateinit var dateText: TextView
    private lateinit var leftCol: View
    private lateinit var clockCard: View
    private lateinit var mediaCard: View
    private lateinit var heroCard: View
    private lateinit var dock: View
    private lateinit var btnApps: View
    private lateinit var mArt: ImageView
    private lateinit var mTitle: TextView
    private lateinit var mSub: TextView
    private lateinit var mPlay: ImageView
    private lateinit var np: NowPlaying

    private val slots = ArrayList<Slot>()
    private val handler = Handler(Looper.getMainLooper())
    private var bgKey = ""
    private var carKey = ""
    private lateinit var conn: ConnStatus
    private var locating = false
    private var askedPermission = false

    private val noSignal = Runnable { speedo.setSpeed(0f) }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            var kmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
            if (kmh < 2f) kmh = 0f // GPS jitter while parked
            speedo.setSpeed(kmh)
            handler.removeCallbacks(noSignal)
            handler.postDelayed(noSignal, 5000)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)

        root = findViewById(R.id.root)
        speedo = findViewById(R.id.speedo)
        road = findViewById(R.id.road)
        analog = findViewById(R.id.analog)
        dateText = findViewById(R.id.dateText)
        leftCol = findViewById(R.id.leftCol)
        clockCard = findViewById(R.id.clockCard)
        mediaCard = findViewById(R.id.mediaCard)
        heroCard = findViewById(R.id.heroCard)
        dock = findViewById(R.id.dock)
        btnApps = findViewById(R.id.btnApps)
        mArt = findViewById(R.id.mArt)
        mTitle = findViewById(R.id.mTitle)
        mSub = findViewById(R.id.mSub)
        mPlay = findViewById(R.id.mPlay)
        val strip = LinearLayout(this)
        (heroCard as android.widget.FrameLayout).addView(strip, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP or android.view.Gravity.START
        ).apply { setMargins((22 * resources.displayMetrics.density).toInt(), (22 * resources.displayMetrics.density).toInt(), 0, 0) })
        conn = ConnStatus(this, strip, window.decorView as android.widget.FrameLayout)

        // The road follows the needle, so the intro sweep and real driving both animate it.
        speedo.onShown = { road.setSpeed(it) }
        speedo.setOnClickListener {
            speedo.useMph = !speedo.useMph
            prefs.edit().putBoolean("mph", speedo.useMph).apply()
        }

        buildSlots(findViewById(R.id.sideSlots), "side", 4)
        dockCount = prefs.getInt("dock_count", 8)
        buildSlots(findViewById(R.id.dock), "dock", dockCount)

        btnApps.setOnClickListener {
            openLibrary()
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        np = NowPlaying(this) { updateMedia() }
        // Our own player takes the buttons when it has a song loaded; otherwise they drive whatever app is playing.
        fun own() = MusicService.instance?.takeIf { it.current != null }
        findViewById<View>(R.id.mPrev).setOnClickListener { own()?.prev() ?: np.prev() }
        findViewById<View>(R.id.mNext).setOnClickListener { own()?.next() ?: np.next() }
        mPlay.setOnClickListener { own()?.toggle() ?: np.toggle() }
        mediaCard.setOnClickListener {
            val other = np.controller?.packageName?.takeIf { it != packageName && own() == null }
            if (other != null) launch(other) else startActivity(Intent(this, MusicActivity::class.java))
        }

        applyTheme()
        applyCarDock()
        slots.forEach { refreshSlot(it) }
        updateMedia()
        applyDefaults()

        speedo.post { speedo.playIntro() }
        AppPicker.preload(this)
        try { AudioFx.attach(this) } catch (e: Throwable) { }
        Wallpapers.autoIfDue(this) { applyTheme() }
        if (prefs.getBoolean("auto_free", false)) Thread { Boost.free(this) }.start()
    }

    // ---- theme ----

    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val lux = e.values[0]
            val now = prefs.getBoolean("auto_dark", false)
            val next = if (now) lux < 40f else lux < 10f // hysteresis so it does not flicker
            if (next != now) {
                prefs.edit().putBoolean("auto_dark", next).apply()
                applyTheme()
            }
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    /** Auto mode: dark when it is dark outside (headlights time). Falls back to the clock without a light sensor. */
    private fun startAutoDark() {
        if (prefs.getInt("theme_mode", 0) != 0) return
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (s != null) {
            sm.registerListener(lightListener, s, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            prefs.edit().putBoolean("auto_dark", h >= 19 || h < 6).apply()
            applyTheme()
        }
    }

    /** Speedometer style, size and position over the road, all from Settings. */
    private fun layoutSpeedo(dn: Float) {
        val numberOnly = prefs.getInt("speedo_style", 0) == 1
        val k = prefs.getInt("speedo_size", 100) / 100f
        speedo.numberOnly = numberOnly
        val w = ((if (numberOnly) 220 else 440) * k * dn).toInt()
        val h = ((if (numberOnly) 140 else 350) * k * dn).toInt()
        val hg = when (prefs.getInt("speedo_h", 1)) { 0 -> android.view.Gravity.LEFT; 2 -> android.view.Gravity.RIGHT; else -> android.view.Gravity.CENTER_HORIZONTAL }
        val vg = when (prefs.getInt("speedo_v", 0)) { 1 -> android.view.Gravity.CENTER_VERTICAL; 2 -> android.view.Gravity.BOTTOM; else -> android.view.Gravity.TOP }
        val lp = speedo.layoutParams as android.widget.FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h || lp.gravity != (hg or vg)) {
            lp.width = w
            lp.height = h
            lp.gravity = hg or vg
            val m = (12 * dn).toInt()
            val gear = if (hg == android.view.Gravity.RIGHT && vg == android.view.Gravity.TOP) (76 * dn).toInt() else m
            lp.setMargins(m, m, gear, m)
            speedo.layoutParams = lp
        }
    }

    /** Driver layout: app sidebar and the clock/music column can each sit on the left or the right. */
    private fun arrangeSides(dn: Float) {
        val gap = (12 * dn).toInt()
        val rootL = root as LinearLayout
        val side = findViewById<View>(R.id.sideSlots).parent as View
        val main = rootL.getChildAt(if (rootL.indexOfChild(side) == 0) 1 else 0)
        val appsRight = prefs.getInt("apps_side", 0) == 1
        if ((rootL.indexOfChild(side) == 1) != appsRight) {
            rootL.removeView(side)
            rootL.addView(side, if (appsRight) 1 else 0)
        }
        (main.layoutParams as LinearLayout.LayoutParams).apply {
            leftMargin = if (appsRight) 0 else gap
            rightMargin = if (appsRight) gap else 0
        }
        val row = leftCol.parent as LinearLayout
        val widgetsRight = prefs.getInt("widgets_side", 0) == 1
        if ((row.indexOfChild(leftCol) == 1) != widgetsRight) {
            row.removeView(leftCol)
            row.addView(leftCol, if (widgetsRight) 1 else 0)
        }
        (leftCol.layoutParams as LinearLayout.LayoutParams).leftMargin = if (widgetsRight) gap else 0
        (heroCard.layoutParams as LinearLayout.LayoutParams).leftMargin = if (widgetsRight) 0 else gap
        rootL.requestLayout()
    }

    private fun applyTheme() {
        Themes.update(prefs)
        val accent = Themes.accent(prefs)
        val pct = Themes.glassPct(prefs)
        val dn = resources.displayMetrics.density
        val stroke = dn.toInt().coerceAtLeast(1)

        val key = Themes.backgroundKey(this, prefs)
        if (key != bgKey) {
            root.background = Themes.backgroundDrawable(this, prefs)
            bgKey = key
        }
        for (card in listOf(clockCard, mediaCard, heroCard, dock, btnApps)) {
            card.background = Themes.glass(pct, accent, 20 * dn, stroke)
        }
        speedo.accent = accent
        layoutSpeedo(dn)
        arrangeSides(dn)
        road.carColor = Themes.carColors[prefs.getInt("car_color", 0).coerceIn(0, Themes.carColors.lastIndex)]
        road.carStyle = prefs.getInt("car_style", 0)
        road.carScale = prefs.getInt("car_size", 100) / 100f
        val ck = CarPhotos.key(this, prefs)
        if (ck != carKey) {
            carKey = ck
            road.carBitmap = CarPhotos.load(this, prefs)
        }
        road.accent = accent
        analog.accent = accent
        dateText.setTextColor(accent)
        mPlay.background = Themes.ring(accent, dn)

        Themes.shadowText(root)
        val showClock = prefs.getBoolean("show_clock", true)
        val showMedia = prefs.getBoolean("show_media", true)
        clockCard.visibility = if (showClock) View.VISIBLE else View.GONE
        mediaCard.visibility = if (showMedia) View.VISIBLE else View.GONE
        leftCol.visibility = if (showClock || showMedia) View.VISIBLE else View.GONE

        val mph = prefs.getBoolean("mph", false)
        if (speedo.useMph != mph) speedo.useMph = mph
    }

    // ---- app slots (sidebar and dock) ----

    private fun buildSlots(container: LinearLayout, prefix: String, count: Int) {
        val horizontal = container.orientation == LinearLayout.HORIZONTAL
        for (i in 0 until count) {
            val v = layoutInflater.inflate(R.layout.slot, container, false)
            val lp = if (horizontal) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            container.addView(v, lp)
            val slot = Slot("$prefix$i", v.findViewById(R.id.slotIcon), v.findViewById(R.id.slotLabel))
            slots.add(slot)
            v.setOnClickListener {
                val pkg = prefs.getString(slot.key, null)
                if (pkg == null || !launch(pkg)) chooseSlot(slot)
            }
            v.setOnLongClickListener {
                chooseSlot(slot)
                true
            }
        }
    }

    private fun chooseSlot(slot: Slot) {
        AppPicker.show(this, "Choose app", prefs.contains(slot.key)) { pkg ->
            prefs.edit().apply { if (pkg == null) remove(slot.key) else putString(slot.key, pkg) }.apply()
            refreshSlot(slot)
        }
    }

    private fun refreshSlot(slot: Slot) {
        val pkg = prefs.getString(slot.key, null)
        if (pkg == null || Apps.intent(packageManager, pkg) == null) {
            slot.icon.tag = null
            slot.icon.setImageResource(R.drawable.ic_plus)
            slot.label.text = "Add"
        } else {
            IconLoader.load(packageManager, pkg, slot.icon)
            slot.label.text = label(pkg)
        }
    }

    private var dockCount = 8

    /** Rebuilds the dock when its size was changed in Settings. */
    private fun rebuildDockIfNeeded() {
        val n = prefs.getInt("dock_count", 8)
        if (n == dockCount) return
        dockCount = n
        val dockView = findViewById<LinearLayout>(R.id.dock)
        slots.removeAll { it.key.startsWith("dock") }
        dockView.removeAllViews()
        buildSlots(dockView, "dock", n)
        slots.filter { it.key.startsWith("dock") }.forEach { refreshSlot(it) }
    }

    /** The dock starts with the car apps: Music, Videos, FM Radio, Bluetooth, Equalizer, Wi-Fi, Settings. */
    private fun applyCarDock() {
        if (prefs.getBoolean("car_dock_done", false)) return
        prefs.edit().apply {
            Apps.builtIns.forEachIndexed { i, b -> putString("dock$i", b.key) }
            putBoolean("car_dock_done", true)
        }.apply()
    }

    // ---- swipe up from the bottom opens the app library ----

    private var swipeStartY = -1f
    private var swipeStartX = 0f

    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        val h = window.decorView.height
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                swipeStartY = if (e.rawY > h * 0.70f) e.rawY else -1f
                swipeStartX = e.rawX
            }
            android.view.MotionEvent.ACTION_MOVE -> if (swipeStartY > 0) {
                val dy = swipeStartY - e.rawY
                val dx = Math.abs(e.rawX - swipeStartX)
                if (dy > 140 * resources.displayMetrics.density && dy > dx * 1.5f) {
                    swipeStartY = -1f
                    // Cancel the touch on whatever was under the finger, then open the library.
                    super.dispatchTouchEvent(android.view.MotionEvent.obtain(e).apply { action = android.view.MotionEvent.ACTION_CANCEL })
                    openLibrary()
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(e)
    }

    private fun openLibrary() = AppPicker.show(this, "Apps", false) { pkg -> if (pkg != null) launch(pkg) }

    /** First run: fill the slots with apps that look right (navigation, radio, music...). The user can change them. */
    private fun applyDefaults() {
        if (prefs.getBoolean("defaults_done", false)) return
        Thread {
            val all = AppPicker.query(this)
            fun find(vararg words: String) = all.firstOrNull { a -> words.any { a.label.lowercase().contains(it) } }?.pkg
            val guesses = mapOf(
                "side0" to find("navi", "maps", "waze", "gps"),
                "side1" to find("bluetooth"),
                "side2" to find("radio"),
                "side3" to find("video", "movie"),
                "dock0" to find("music", "player"),
                "dock1" to (find("youtube") ?: find("video")),
                "dock2" to find("play store", "google play"),
                "dock3" to find("settings"),
                "dock4" to find("chrome", "browser"),
                "dock5" to find("file", "gallery")
            )
            runOnUiThread {
                prefs.edit().apply {
                    for ((k, v) in guesses) if (v != null && !prefs.contains(k)) putString(k, v)
                    putBoolean("defaults_done", true)
                }.apply()
                slots.forEach { refreshSlot(it) }
            }
        }.start()
    }

    private fun label(pkg: String): String = Apps.builtIn(pkg)?.label ?: try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun launch(pkg: String): Boolean {
        val intent = Apps.intent(packageManager, pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---- now playing ----

    private fun openListenerSettings() {
        try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Open Settings > Security > Notification access", Toast.LENGTH_LONG).show()
        }
    }

    private val musicListener: () -> Unit = { handler.post { updateMedia() } }

    private fun updateMedia() {
        val own = MusicService.instance?.takeIf { it.current != null }
        if (own != null) {
            mTitle.text = own.metaTitle
            mSub.text = own.metaArtist
            own.art?.let { mArt.setImageBitmap(it) } ?: mArt.setImageResource(R.drawable.ic_disc)
            mPlay.setImageResource(if (own.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            return
        }
        val c = np.controller
        when {
            !np.hasAccess() -> {
                mTitle.text = "Music"
                mSub.text = "Tap to open your music"
                mArt.setImageResource(R.drawable.ic_disc)
            }
            !np.hasAccess() -> {
                mTitle.text = "Enable now playing"
                mSub.text = "Tap to allow access"
                mArt.setImageResource(R.drawable.ic_disc)
            }
            c == null -> {
                mTitle.text = "Music"
                mSub.text = "Nothing playing"
                mArt.setImageResource(R.drawable.ic_disc)
            }
            else -> {
                val md = c.metadata
                mTitle.text = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Music"
                mSub.text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                if (art != null) mArt.setImageBitmap(art) else mArt.setImageResource(R.drawable.ic_disc)
            }
        }
        mPlay.setImageResource(if (np.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    // ---- speed (GPS) ----

    private fun startLocation() {
        if (locating) return
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            if (!askedPermission) {
                askedPermission = true
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            }
            return
        }
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
            locating = true
        } catch (e: SecurityException) {
        } catch (e: IllegalArgumentException) {
        }
    }

    private fun stopLocation() {
        if (!locating) return
        (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(locationListener)
        locating = false
        handler.removeCallbacks(noSignal)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startLocation()
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
        startAutoDark()
        rebuildDockIfNeeded()
        conn.start()
        Thread { Apps.stopBlocked(this) }.start()
        startLocation()
        np.start()
        MusicService.addListener(musicListener)
        updateMedia()
    }

    override fun onPause() {
        stopLocation()
        np.stop()
        MusicService.removeListener(musicListener)
        conn.stop()
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(lightListener)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        @Suppress("DEPRECATION")
        if (hasFocus) window.decorView.systemUiVisibility = immersiveFlags()
    }

    // A launcher has nowhere to go back to.
    @Deprecated("A launcher has nowhere to go back to")
    override fun onBackPressed() {}

    companion object {
        @Suppress("DEPRECATION")
        fun immersiveFlags() = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
}
