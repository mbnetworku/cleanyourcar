package com.carlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.TimeZone

/** A broadcast band as used in one part of the world. Frequencies are in kHz. */
class Band(val name: String, val min: Int, val max: Int, val step: Int) {
    val isFm get() = name.startsWith("FM")
    fun bank(n: String) = Band(n, min, max, step)
    fun label(khz: Int) = if (isFm) "%.${if (step % 100 == 0) 1 else 2}f".format(khz / 1000f) else khz.toString()
    val unit get() = if (isFm) "MHz" else "kHz"
}

/** The band plan of a region, chosen from the device time zone so the right steps and limits are used. */
class Region(val name: String, val fm: Band, val am: Band)

object Regions {
    private val europe = Region("Europe / Africa / Middle East / Asia", Band("FM", 87500, 108000, 100), Band("AM", 531, 1602, 9))
    private val americas = Region("Americas", Band("FM", 87900, 107900, 200), Band("AM", 530, 1710, 10))
    private val japan = Region("Japan", Band("FM", 76000, 95000, 100), Band("AM", 531, 1602, 9))
    private val oceania = Region("Australia / New Zealand", Band("FM", 87500, 108000, 100), Band("AM", 531, 1701, 9))

    /** Picks the band plan from the time zone, e.g. America/... gets 200 kHz FM steps and 10 kHz AM steps. */
    fun fromTimeZone(tz: String = TimeZone.getDefault().id): Region = when {
        tz.startsWith("America/") || tz.startsWith("US/") || tz.startsWith("Canada/") || tz.startsWith("Brazil/") -> americas
        tz == "Asia/Tokyo" || tz == "Japan" -> japan
        tz.startsWith("Australia/") || tz.startsWith("Pacific/Auckland") || tz == "NZ" -> oceania
        else -> europe
    }
}

/**
 * The radio hardware. Android has no standard tuner API: every head unit maker ships its own driver,
 * so a real backend has to be written for the specific unit. [detect] reports what this device has.
 */
interface Tuner {
    val name: String
    fun open(): Boolean
    fun tune(band: Band, khz: Int)
    /** Searches up or down from [khz]; returns the next station found, or null. */
    fun seek(band: Band, khz: Int, up: Boolean): Int?
    fun signal(): Int
    fun mute(on: Boolean)
    fun close()

    companion object {
        /** Linux FM radio devices that tuner chips expose; their presence means a tuner is fitted. */
        private val nodes = listOf("/dev/radio0", "/dev/fmradio", "/dev/fm", "/dev/tef6686", "/dev/si4703", "/dev/rda5807")

        fun hardwareNode(): String? = nodes.firstOrNull { File(it).exists() }

        fun detect(): Tuner = NoTuner(hardwareNode())
    }
}

/** Used when no supported tuner is present: everything works on screen, nothing plays. */
class NoTuner(private val node: String?) : Tuner {
    override val name = if (node != null) "Tuner chip found at $node (driver not supported yet)" else "No FM/AM tuner found on this device"
    override fun open() = false
    override fun tune(band: Band, khz: Int) {}
    override fun seek(band: Band, khz: Int, up: Boolean): Int? = null
    override fun signal() = 0
    override fun mute(on: Boolean) {}
    override fun close() {}
}

/**
 * FM/AM radio that works like a car tuner: band switch, a dial for the region's band, fine tune, seek,
 * auto-store scan, and favorites for both FM and AM. Only real tuner hardware is used, never internet streams.
 */
class RadioActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var radio: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private var dn = 1f
    private fun px(v: Int) = (v * dn).toInt()
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private var accent = 0

    private val region = Regions.fromTimeZone()
    /** Car-radio style banks, each with its own last frequency and favorites. */
    private val banks = listOf(region.fm.bank("FM1"), region.fm.bank("FM2"), region.fm.bank("FM3"), region.am.bank("AM"))
    private var band = banks[0]
    private val bankBtns = ArrayList<TextView>()
    private fun bankOf(n: String) = banks.firstOrNull { it.name == n } ?: if (n.startsWith("AM")) banks[3] else banks[0]
    private var khz = 0
    private val tuner: Tuner = Tuner.detect()
    private var tunerOk = false

    private lateinit var freqText: TextView
    private lateinit var unitText: TextView
    private lateinit var statusText: TextView
    private lateinit var dial: DialView
    private lateinit var favBtn: TextView
    private lateinit var favBox: LinearLayout
    private lateinit var foundBox: LinearLayout
    private lateinit var foundTitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        radio = getSharedPreferences("tuner", MODE_PRIVATE)
        dn = resources.displayMetrics.density
        Themes.update(prefs)
        accent = Themes.accent(prefs)
        band = bankOf(radio.getString("band", "FM1") ?: "FM1")
        khz = radio.getInt("last_${band.name}", band.min).coerceIn(band.min, band.max)
        tunerOk = tuner.open()
        build()
        setBand(band, khz)
    }

    override fun onDestroy() {
        tuner.close()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        @Suppress("DEPRECATION")
        if (hasFocus) window.decorView.systemUiVisibility = MainActivity.immersiveFlags()
    }

    private fun text(t: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(dn * 3, 0f, dn, 0xCC000000.toInt())
    }

    private fun pill(sel: Boolean, r: Int = 16) = GradientDrawable().apply {
        cornerRadius = px(r).toFloat()
        setColor(if (sel) accent else 0x40000000)
    }

    private fun button(t: String, size: Float, w: Int, h: Int, onClick: () -> Unit) = text(t, size, bold = true).apply {
        gravity = Gravity.CENTER
        background = pill(false, h / 2)
        isClickable = true
        setOnClickListener {
            it.animate().scaleX(0.9f).scaleY(0.9f).setDuration(70).withEndAction { it.animate().scaleX(1f).scaleY(1f).setDuration(110).start() }.start()
            onClick()
        }
        layoutParams = LinearLayout.LayoutParams(if (w > 0) px(w) else wrap, px(h)).apply { leftMargin = px(6); rightMargin = px(6) }
    }

    // ---- layout ----

    private fun build() {
        val root = LinearLayout(this).apply {
            setPadding(px(16), px(16), px(16), px(16))
            background = Themes.backgroundDrawable(this@RadioActivity, prefs)
        }

        val tunerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(18), px(24), px(16))
            background = Themes.glass(Themes.glassPct(prefs), accent, px(20).toFloat(), px(1))
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("Radio", 26f, bold = true), LinearLayout.LayoutParams(0, wrap, 1f))
        for (b in banks) bankBtns.add(button(b.name, 15f, 70, 44) { setBand(b, radio.getInt("last_${b.name}", b.min)) }.also { head.addView(it) })
        head.addView(button("Home", 15f, 0, 44) { finish() }.apply { setPadding(px(20), 0, px(20), 0) })
        tunerPanel.addView(head)

        val display = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(16), px(10), px(16), px(10))
            background = GradientDrawable().apply { cornerRadius = px(18).toFloat(); setColor(0x66000000) }
        }
        val freqRow = LinearLayout(this).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
        freqText = text("", 72f, accent, bold = true).apply { typeface = Typeface.create("monospace", Typeface.BOLD) }
        unitText = text("", 20f, 0xCCFFFFFF.toInt()).apply { setPadding(px(6), 0, 0, px(16)) }
        freqRow.addView(freqText)
        freqRow.addView(unitText)
        display.addView(freqRow)
        statusText = text("", 14f, 0xDDFFFFFF.toInt()).apply { gravity = Gravity.CENTER }
        display.addView(statusText, LinearLayout.LayoutParams(match, wrap))
        tunerPanel.addView(display, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(12) })

        dial = DialView(this).apply { color = accent; onTune = { f, done -> tuneTo(f, done) } }
        tunerPanel.addView(dial, LinearLayout.LayoutParams(match, px(110)).apply { topMargin = px(10) })

        val ctrl = LinearLayout(this).apply { gravity = Gravity.CENTER }
        ctrl.addView(button("◀◀", 20f, 80, 66) { seek(false) })
        ctrl.addView(button("−", 26f, 66, 66) { tuneTo(khz - band.step, true) })
        favBtn = button("☆", 30f, 92, 84) { toggleFavorite() }
        ctrl.addView(favBtn)
        ctrl.addView(button("+", 26f, 66, 66) { tuneTo(khz + band.step, true) })
        ctrl.addView(button("▶▶", 20f, 80, 66) { seek(true) })
        tunerPanel.addView(ctrl, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(8) })

        val row2 = LinearLayout(this).apply { gravity = Gravity.CENTER }
        row2.addView(button("SEARCH ALL", 15f, 160, 46) { searchAll() })
        row2.addView(button("EQ", 15f, 90, 46) { startActivity(Intent(this, EqualizerActivity::class.java)) })
        tunerPanel.addView(row2, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(10) })
        // Without a supported tuner driver, hand over to the head unit's own radio app if it has one.
        if (!tunerOk) carRadioApp()?.let { (pkg, name) ->
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER }
            r.addView(button("Open the car's radio:  $name", 16f, 0, 52) {
                packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }.apply { background = pill(true, 26); setPadding(px(26), 0, px(26), 0) })
            tunerPanel.addView(r, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(12) })
        }
        tunerPanel.addView(text("Region: ${region.name}  (from time zone ${TimeZone.getDefault().id})", 12f, 0xBBFFFFFF.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(0, px(12), 0, 0)
        }, LinearLayout.LayoutParams(match, wrap))

        // Right: favorites (FM and AM) and stations found by Search all
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(16), px(18), px(12))
            background = Themes.glass(Themes.glassPct(prefs), accent, px(20).toFloat(), px(1))
        }
        side.addView(text("Favorites", 22f, bold = true))
        side.addView(text("Tap ☆ to add the current station. Hold a favorite to remove it.", 12f, 0xBBFFFFFF.toInt()))
        favBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        foundTitle = text("", 18f, bold = true).apply { setPadding(0, px(16), 0, px(4)) }
        foundBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(favBox)
        col.addView(foundTitle)
        col.addView(foundBox)
        side.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }, LinearLayout.LayoutParams(match, 0, 1f).apply { topMargin = px(8) })

        root.addView(tunerPanel, LinearLayout.LayoutParams(0, match, 1.25f))
        root.addView(side, LinearLayout.LayoutParams(0, match, 1f).apply { leftMargin = px(14) })
        setContentView(root)
        listOf(tunerPanel, side).forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = px(40).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(30L * i).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    /** The head unit's own FM/AM app (package and name), found by common names used by car-radio makers. */
    private fun carRadioApp(): Pair<String, String>? {
        val pm = packageManager
        val words = listOf("radio", "fm", "tuner", "am/fm")
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { (p, l) -> p != packageName && words.any { w -> l.lowercase().contains(w) || p.lowercase().contains(".$w") } }
            .filter { (p, l) -> !l.lowercase().contains("internet") && !p.contains("tunein") }
            .firstOrNull()
    }

    // ---- tuning ----

    private fun snap(f: Int) = (band.min + Math.round((f - band.min).toFloat() / band.step) * band.step).coerceIn(band.min, band.max)

    private fun setBand(b: Band, at: Int) {
        band = b
        radio.edit().putString("band", b.name).apply()
        bankBtns.forEachIndexed { i, v -> v.background = pill(banks[i] == b, 22) }
        unitText.text = b.unit
        dial.band = b
        tuneTo(at, true)
        refreshLists()
    }

    private fun tuneTo(f: Int, done: Boolean) {
        khz = snap(f)
        freqText.text = band.label(khz)
        dial.setFreq(khz, !done)
        if (!done) return
        radio.edit().putInt("last_${band.name}", khz).apply()
        tuner.tune(band, khz)
        refreshStatus()
        refreshFav()
    }

    private fun seek(up: Boolean) {
        if (!tunerOk) { noTuner(); return }
        val next = tuner.seek(band, khz, up)
        if (next != null) tuneTo(next, true) else Toast.makeText(this, "No station found", Toast.LENGTH_SHORT).show()
    }

    /** Sweeps the whole band and lists every station the tuner can receive here. */
    private fun searchAll() {
        if (!tunerOk) { noTuner(); return }
        statusText.text = "Searching ${band.name}…"
        Thread {
            val found = ArrayList<Int>()
            var f = band.min
            while (true) {
                val n = tuner.seek(band, f, true) ?: break
                if (n <= f || n in found) break
                found.add(n)
                f = n
            }
            ui.post {
                radio.edit().putString("found_${band.name}", found.joinToString(",")).apply()
                refreshLists()
                refreshStatus()
            }
        }.start()
    }

    private fun noTuner() = Toast.makeText(this, tuner.name, Toast.LENGTH_LONG).show()

    private fun refreshStatus() {
        statusText.text = if (tunerOk) "${band.name}  ·  signal ${tuner.signal()}%" else tuner.name
    }

    // ---- favorites (FM and AM together) ----

    private fun favorites(): List<Pair<String, Int>> =
        (radio.getString("favs", "") ?: "").split(";").filter { it.contains(":") }
            .mapNotNull { s -> s.substringAfter(":").toIntOrNull()?.let { s.substringBefore(":") to it } }

    private fun saveFavorites(list: List<Pair<String, Int>>) =
        radio.edit().putString("favs", list.joinToString(";") { "${it.first}:${it.second}" }).apply()

    private fun toggleFavorite() {
        val list = favorites().toMutableList()
        val me = band.name to khz
        if (list.remove(me)) Toast.makeText(this, "Removed from favorites", Toast.LENGTH_SHORT).show()
        else { list.add(me); Toast.makeText(this, "Added ${band.name} ${band.label(khz)} to favorites", Toast.LENGTH_SHORT).show() }
        saveFavorites(list.sortedWith(compareBy({ it.first }, { it.second })))
        refreshFav()
        refreshLists()
    }

    private fun refreshFav() {
        favBtn.text = if (favorites().contains(band.name to khz)) "★" else "☆"
        favBtn.setTextColor(if (favorites().contains(band.name to khz)) accent else Color.WHITE)
    }

    private fun row(label: String, sub: String, sel: Boolean, onClick: () -> Unit, onHold: (() -> Unit)? = null) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(14), px(8), px(14), px(8))
        background = pill(sel, 12)
        isClickable = true
        setOnClickListener { onClick() }
        if (onHold != null) setOnLongClickListener { onHold(); true }
        addView(text(label, 20f, bold = true), LinearLayout.LayoutParams(0, wrap, 1f))
        addView(text(sub, 13f, 0xCCFFFFFF.toInt()))
    }

    private fun refreshLists() {
        favBox.removeAllViews()
        val favs = favorites()
        if (favs.isEmpty()) favBox.addView(text("No favorites yet.", 14f, 0x99FFFFFF.toInt()).apply { setPadding(0, px(8), 0, 0) })
        for ((bn, f) in favs) {
            val b = bankOf(bn)
            favBox.addView(row("${b.label(f)} ${b.unit}", bn, bn == band.name && f == khz,
                { setBand(b, f) },
                { saveFavorites(favorites() - (bn to f)); refreshLists(); refreshFav() }),
                LinearLayout.LayoutParams(match, px(54)).apply { topMargin = px(6) })
        }
        val found = (radio.getString("found_${band.name}", "") ?: "").split(",").mapNotNull { it.toIntOrNull() }
        foundTitle.text = if (found.isEmpty()) "" else "Found on ${band.name}"
        foundBox.removeAllViews()
        for (f in found) foundBox.addView(row("${band.label(f)} ${band.unit}", "", f == khz, { tuneTo(f, true); refreshLists() }),
            LinearLayout.LayoutParams(match, px(50)).apply { topMargin = px(6) })
    }
}

/** Band dial: ticks across the current band, and a glowing needle you can drag to tune. */
class DialView(ctx: Context) : View(ctx) {
    var color = 0xFF1FD1B8.toInt()
    var band = Band("FM", 87500, 108000, 100)
        set(v) { field = v; invalidate() }
    private var freq = 98000f
    var onTune: ((Int, Boolean) -> Unit)? = null
    private val dn = resources.displayMetrics.density
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt(); strokeCap = Paint.Cap.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDDFFFFFF.toInt(); textAlign = Paint.Align.CENTER }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val r = RectF()

    private fun x(f: Float): Float {
        val pad = 24 * dn
        return pad + (width - 2 * pad) * (f - band.min) / (band.max - band.min)
    }

    fun setFreq(khz: Int, immediate: Boolean) {
        if (immediate) { freq = khz.toFloat(); invalidate(); return }
        val a = android.animation.ValueAnimator.ofFloat(freq, khz.toFloat()).setDuration(300)
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener { freq = it.animatedValue as Float; invalidate() }
        a.start()
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        r.set(0f, 0f, width.toFloat(), h)
        c.drawRoundRect(r, 18 * dn, 18 * dn, bg)
        label.textSize = 13 * dn
        val span = band.max - band.min
        // About 40 minor ticks and 10 labels across the band, on round numbers.
        val minor = listOf(100, 200, 250, 500, 1000, 2000).first { span / it <= 50 }.let { if (band.isFm) it else maxOf(10, span / 40 / 10 * 10) }
        val major = minor * if (band.isFm) 4 else 5
        var f = (band.min + minor - 1) / minor * minor
        while (f <= band.max) {
            val big = f % major == 0
            tick.strokeWidth = if (big) 2.5f * dn else 1.2f * dn
            c.drawLine(x(f.toFloat()), if (big) h * 0.18f else h * 0.30f, x(f.toFloat()), h * 0.58f, tick)
            if (big) c.drawText(if (band.isFm) (f / 1000).toString() else f.toString(), x(f.toFloat()), h * 0.82f, label)
            f += minor
        }
        val nx = x(freq)
        glow.shader = LinearGradient(nx - 22 * dn, 0f, nx + 22 * dn, 0f,
            intArrayOf(0x00000000, (0x77 shl 24) or (color and 0xFFFFFF), 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawRect(nx - 22 * dn, 0f, nx + 22 * dn, h, glow)
        needle.color = color
        needle.strokeWidth = 3.5f * dn
        c.drawLine(nx, h * 0.08f, nx, h * 0.70f, needle)
        c.drawCircle(nx, h * 0.08f, 5 * dn, needle)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val pad = 24 * dn
        val f = band.min + ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f) * (band.max - band.min)
        freq = f
        invalidate()
        val done = e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL
        onTune?.invoke(f.toInt(), done)
        return true
    }
}
