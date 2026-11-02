package com.carlauncher

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Sound equalizer: presets, one vertical slider per band, bass boost, surround and loudness. */
class EqualizerActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var eq: SharedPreferences
    private var dn = 1f
    private fun px(v: Int) = (v * dn).toInt()
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private var accent = 0

    private lateinit var presetBox: LinearLayout
    private val sliders = ArrayList<BandSlider>()
    private val dbLabels = ArrayList<TextView>()
    private lateinit var curve: CurveView
    private val knobs = HashMap<String, SeekBar>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        eq = AudioFx.prefs(this)
        dn = resources.displayMetrics.density
        Themes.update(prefs)
        accent = Themes.accent(prefs)
        AudioFx.attach(this, MusicService.instance?.audioSession ?: 0)
        build()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        @Suppress("DEPRECATION")
        if (hasFocus) window.decorView.systemUiVisibility = MainActivity.immersiveFlags()
    }

    private fun glass() = Themes.glass(Themes.glassPct(prefs), accent, px(20).toFloat(), px(1))

    private fun text(t: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun pill(sel: Boolean) = GradientDrawable().apply {
        cornerRadius = px(14).toFloat()
        setColor(if (sel) accent else 0x40000000)
    }

    private fun build() {
        val root = LinearLayout(this).apply {
            setPadding(px(16), px(16), px(16), px(16))
            background = Themes.backgroundDrawable(this@EqualizerActivity, prefs)
        }

        // Left: on/off and presets
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(16), px(14), px(12))
            background = glass()
        }
        left.addView(text("Equalizer", 26f, bold = true))
        left.addView(Switch(this).apply {
            text = "Sound effects on"
            textSize = 16f
            setTextColor(Color.WHITE)
            isChecked = eq.getBoolean("on", true)
            setOnCheckedChangeListener { _, on -> AudioFx.setEnabled(this@EqualizerActivity, on) }
        }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(10); bottomMargin = px(10) })
        left.addView(Switch(this).apply {
            text = "Bass only"
            textSize = 16f
            setTextColor(Color.WHITE)
            isChecked = eq.getBoolean("bass_only", false)
            setOnCheckedChangeListener { _, on ->
                eq.edit().putBoolean("bass_only", on).apply()
                AudioFx.apply(this@EqualizerActivity)
            }
        }, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = px(10) })
        left.addView(text("PRESETS", 12f, 0xCCFFFFFF.toInt()).apply { setPadding(px(6), 0, 0, px(6)) })
        presetBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(presetBox) }, LinearLayout.LayoutParams(match, 0, 1f))
        left.addView(text("Done", 16f).apply {
            gravity = Gravity.CENTER
            background = pill(false)
            isClickable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(match, px(48)).apply { topMargin = px(8) })

        // Middle: response curve + band sliders
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(16), px(20), px(12))
            background = glass()
        }
        mid.addView(text("Bands", 22f, bold = true))
        mid.addView(text(if (AudioFx.available) "Drag a slider up for more, down for less of that sound."
            else "This device did not allow the system equalizer; it still works on the Music player.", 13f, 0xCCFFFFFF.toInt()))
        curve = CurveView(this).apply { color = accent }
        mid.addView(curve, LinearLayout.LayoutParams(match, px(90)).apply { topMargin = px(8) })
        val bands = LinearLayout(this)
        val r = AudioFx.range()
        for (i in 0 until AudioFx.bandCount()) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val db = text("", 13f).apply { gravity = Gravity.CENTER }
            dbLabels.add(db)
            col.addView(db)
            val s = BandSlider(this).apply {
                color = accent
                min = r[0]; max = r[1]
                value = AudioFx.level(this@EqualizerActivity, i)
                onChange = { v, done ->
                    db.text = fmtDb(v)
                    curve.levels[i] = v / 100f
                    curve.invalidate()
                    if (done) { AudioFx.set(this@EqualizerActivity, "band$i", v); refreshPresets() }
                }
            }
            sliders.add(s)
            col.addView(s, LinearLayout.LayoutParams(px(56), 0, 1f).apply { topMargin = px(4); bottomMargin = px(4) })
            val hz = AudioFx.bandHz(i)
            col.addView(text(if (hz >= 1000) "${hz / 1000}k" else "$hz", 13f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER })
            bands.addView(col, LinearLayout.LayoutParams(0, match, 1f))
        }
        curve.levels = FloatArray(AudioFx.bandCount())
        mid.addView(bands, LinearLayout.LayoutParams(match, 0, 1f).apply { topMargin = px(8) })

        // Right: bass, surround, loudness
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(16), px(20), px(12))
            background = glass()
        }
        right.addView(text("Effects", 22f, bold = true))
        fun knob(title: String, hint: String, key: String, max: Int) {
            right.addView(text(title, 18f).apply { setPadding(0, px(18), 0, 0) })
            right.addView(text(hint, 12f, 0xBBFFFFFF.toInt()))
            val pct = text("", 14f, accent).apply { gravity = Gravity.END }
            val sb = SeekBar(this).apply {
                this.max = max
                progress = eq.getInt(key, 0)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                        pct.text = "${p * 100 / max}%"
                        if (fromUser) AudioFx.set(this@EqualizerActivity, key, p)
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) { refreshPresets() }
                })
            }
            pct.text = "${sb.progress * 100 / max}%"
            knobs[key] = sb
            right.addView(pct, LinearLayout.LayoutParams(match, wrap))
            right.addView(sb, LinearLayout.LayoutParams(match, wrap))
        }
        knob("Bass boost", "Deeper, stronger low sound", "bass", 1000)
        knob("Surround", "Wider 3D sound stage", "surround", 1000)
        knob("Loudness", "Louder overall (careful at high volume)", "loud", 1500)
        right.addView(View(this), LinearLayout.LayoutParams(match, 0, 1f))
        right.addView(text("Reset to flat", 16f).apply {
            gravity = Gravity.CENTER
            background = pill(false)
            isClickable = true
            setOnClickListener {
                AudioFx.usePreset(this@EqualizerActivity, 0)
                AudioFx.set(this@EqualizerActivity, "surround", 0)
                AudioFx.set(this@EqualizerActivity, "loud", 0)
                eq.edit().putInt("preset", 0).apply()
                syncFromPrefs()
            }
        }, LinearLayout.LayoutParams(match, px(48)))

        root.addView(left, LinearLayout.LayoutParams(px(250), match))
        root.addView(mid, LinearLayout.LayoutParams(0, match, 1f).apply { leftMargin = px(14) })
        root.addView(right, LinearLayout.LayoutParams(px(360), match).apply { leftMargin = px(14) })
        setContentView(root)
        syncFromPrefs()
        Themes.shadowText(root)
        listOf(left, mid, right).forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = px(40).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(60L * i).setDuration(320).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun fmtDb(mb: Int) = (if (mb > 0) "+" else "") + "%.1f".format(mb / 100f)

    private fun syncFromPrefs() {
        sliders.forEachIndexed { i, s ->
            val v = AudioFx.level(this, i)
            s.animateTo(v)
            dbLabels[i].text = fmtDb(v)
            curve.levels[i] = v / 100f
        }
        curve.invalidate()
        knobs.forEach { (k, sb) -> sb.progress = eq.getInt(k, 0) }
        refreshPresets()
    }

    private fun refreshPresets() {
        presetBox.removeAllViews()
        val sel = eq.getInt("preset", 0)
        val names = AudioFx.presets.map { it.name } + "Custom"
        names.forEachIndexed { i, n ->
            val isSel = if (i == AudioFx.presets.size) sel == -1 else sel == i
            presetBox.addView(text(n, 16f).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(14), 0, px(10), 0)
                background = pill(isSel)
                isClickable = true
                setShadowLayer(dn * 3, 0f, dn, 0xCC000000.toInt())
                setOnClickListener {
                    if (i < AudioFx.presets.size) {
                        AudioFx.usePreset(this@EqualizerActivity, i)
                        syncFromPrefs()
                    }
                }
            }, LinearLayout.LayoutParams(match, px(44)).apply { bottomMargin = px(6) })
        }
    }
}

/** A vertical slider with a glowing fill from the centre line (0 dB). */
class BandSlider(ctx: Context) : View(ctx) {
    var color = 0xFF1FD1B8.toInt()
    var min = -1500
    var max = 1500
    var value = 0
    var onChange: ((Int, Boolean) -> Unit)? = null
    private val dn = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val mid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF; strokeWidth = 2f }
    private val r = RectF()

    private fun yFor(v: Int): Float {
        val pad = 14 * dn
        return pad + (height - 2 * pad) * (1f - (v - min).toFloat() / (max - min))
    }

    fun animateTo(v: Int) {
        val a = android.animation.ValueAnimator.ofInt(value, v).setDuration(260)
        a.addUpdateListener { value = it.animatedValue as Int; invalidate() }
        a.start()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val tw = 8 * dn
        r.set(cx - tw / 2, 14 * dn, cx + tw / 2, height - 14 * dn)
        c.drawRoundRect(r, tw, tw, track)
        val y0 = yFor(0)
        val y = yFor(value)
        c.drawLine(cx - 14 * dn, y0, cx + 14 * dn, y0, mid)
        fill.shader = LinearGradient(0f, y0, 0f, y, Themes.blend(color, Color.WHITE, 0.3f), color, Shader.TileMode.CLAMP)
        r.set(cx - tw / 2, minOf(y, y0), cx + tw / 2, maxOf(y, y0))
        c.drawRoundRect(r, tw, tw, fill)
        knob.setShadowLayer(6 * dn, 0f, 2 * dn, 0x88000000.toInt())
        c.drawCircle(cx, y, 13 * dn, knob)
        fill.shader = null
        fill.color = color
        c.drawCircle(cx, y, 6 * dn, fill)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val pad = 14 * dn
        val f = 1f - ((e.y - pad) / (height - 2 * pad)).coerceIn(0f, 1f)
        var v = (min + f * (max - min)).toInt()
        v = (v / 50) * 50 // 0.5 dB steps
        if (kotlin.math.abs(v) < 60) v = 0 // snap to flat
        value = v
        invalidate()
        val done = e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL
        onChange?.invoke(v, done)
        return true
    }
}

/** Smooth line through the band levels, so the shape of the sound is easy to see. */
class CurveView(ctx: Context) : View(ctx) {
    var color = 0xFF1FD1B8.toInt()
    var levels = FloatArray(5)
    private val dn = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val area = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint().apply { color = 0x33FFFFFF; strokeWidth = 1f }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val n = levels.size
        if (n < 2) return
        val h = height.toFloat()
        val w = width.toFloat()
        c.drawLine(0f, h / 2, w, h / 2, grid)
        fun x(i: Int) = w * (i + 0.5f) / n
        fun y(i: Int) = h / 2 - levels[i] / 15f * (h / 2 - 6 * dn)
        path.reset()
        path.moveTo(0f, y(0))
        path.lineTo(x(0), y(0))
        for (i in 1 until n) {
            val mx = (x(i - 1) + x(i)) / 2
            path.cubicTo(mx, y(i - 1), mx, y(i), x(i), y(i))
        }
        path.lineTo(w, y(n - 1))
        val fillPath = Path(path).apply { lineTo(w, h); lineTo(0f, h); close() }
        area.shader = LinearGradient(0f, 0f, 0f, h, (0x66 shl 24) or (color and 0xFFFFFF), 0x00000000, Shader.TileMode.CLAMP)
        c.drawPath(fillPath, area)
        line.color = color
        line.strokeWidth = 3 * dn
        c.drawPath(path, line)
    }
}
