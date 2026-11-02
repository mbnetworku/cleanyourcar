package com.carlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.min
import kotlin.math.sin

/**
 * A road in perspective with a car driving on it. The lane markings scroll at a rate that follows
 * the real speed, and the view only redraws every frame while the car is actually moving.
 */
class RoadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var accent = 0xFF1FD1B8.toInt()
        set(v) {
            field = v
            buildStatic()
            invalidate()
        }

    /** Car colour, style (0 sedan, 1 SUV, 2 sports) and size (1 = default). */
    var carColor = 0xFF3A4D5A.toInt()
        set(v) { field = v; body.color = v; cabin.color = Themes.blend(v, 0xFF000000.toInt(), 0.3f); invalidate() }
    var carStyle = 0
        set(v) { field = v; invalidate() }
    /** A real car photo to show instead of the drawn car (null = drawn car). */
    var carBitmap: android.graphics.Bitmap? = null
        set(v) { field = v; invalidate() }
    private val photoShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    var carScale = 1f
        set(v) { field = v; invalidate() }

    private val dn = resources.displayMetrics.density
    private var target = 0f
    private var shown = 0f
    private var phase = 0f
    private var last = 0L
    private var running = false
    private var attached = false

    private var hy = 0f
    private var topHalf = 0f
    private var bottomHalf = 0f

    private val sky = Paint()
    private val ground = Paint().apply { color = 0xFF08121A.toInt() }
    private val hills = Paint(Paint.ANTI_ALIAS_FLAG)
    private val road = Paint()
    private val edgeGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A4D5A.toInt() }
    private val cabin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A3a46.toInt() }
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0E1A22.toInt() }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
    private val tail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF2D3A.toInt() }
    private val tailGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FF2D3A }
    private val wheel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF05090C.toInt() }
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
    private val tread = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A3036.toInt(); strokeWidth = 2f }
    private val trim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB8C2CC.toInt(); strokeWidth = 3f; strokeCap = Paint.Cap.ROUND }
    private val bumper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }

    private val hillPath = Path()
    private val roadPath = Path()
    private val tmp = Path()
    private val r = RectF()

    private val frame = Runnable { step() }

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 16 * dn)
            }
        }
        clipToOutline = true
        edge.strokeWidth = 3 * dn
        edgeGlow.strokeWidth = 10 * dn
    }

    /** Current speed in km/h; the lane markings scroll in proportion. */
    fun setSpeed(kmh: Float) {
        target = kmh
        if (!running && attached && (kmh > 0.5f || shown > 0.5f)) start()
    }

    private fun start() {
        running = true
        last = System.nanoTime()
        postOnAnimation(frame)
    }

    private fun step() {
        val now = System.nanoTime()
        val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
        last = now
        shown += (target - shown) * min(1f, dt * 6f)
        phase = (phase + shown * dt * 0.02f) % 1f
        invalidate()
        if (target > 0.5f || shown > 0.5f) {
            postOnAnimation(frame)
        } else {
            shown = 0f
            running = false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        if (target > 0.5f && !running) start()
    }

    override fun onDetachedFromWindow() {
        attached = false
        running = false
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        hy = h * 0.30f
        topHalf = w * 0.06f
        bottomHalf = w * 0.62f
        val cx = w / 2f
        roadPath.reset()
        roadPath.moveTo(cx - topHalf, hy)
        roadPath.lineTo(cx + topHalf, hy)
        roadPath.lineTo(cx + bottomHalf, h.toFloat())
        roadPath.lineTo(cx - bottomHalf, h.toFloat())
        roadPath.close()
        buildStatic()
    }

    private fun buildStatic() {
        if (width == 0 || height == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        sky.shader = LinearGradient(
            0f, 0f, 0f, hy, 0xFF06121A.toInt(), Themes.blend(accent, 0xFF0B1620.toInt(), 0.45f), Shader.TileMode.CLAMP
        )
        road.shader = LinearGradient(0f, hy, 0f, h, 0xFF101C25.toInt(), 0xFF1D313D.toInt(), Shader.TileMode.CLAMP)
        hills.color = Themes.blend(accent, 0xFF000000.toInt(), 0.78f)
        edge.color = accent
        edgeGlow.color = (0x44 shl 24) or (accent and 0xFFFFFF)
        hillPath.reset()
        hillPath.moveTo(0f, hy)
        val steps = 10
        for (i in 0..steps) {
            val x = w * i / steps
            val peak = hy - h * 0.10f * (0.45f + 0.55f * sin(i * 1.9f + 0.8f).let { it * it })
            hillPath.lineTo(x, peak)
        }
        hillPath.lineTo(w, hy)
        hillPath.close()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f

        c.drawRect(0f, 0f, w, hy, sky)
        c.drawPath(hillPath, hills)
        c.drawRect(0f, hy, w, h, ground)
        c.drawPath(roadPath, road)

        c.drawLine(cx - topHalf, hy, cx - bottomHalf, h, edgeGlow)
        c.drawLine(cx + topHalf, hy, cx + bottomHalf, h, edgeGlow)
        c.drawLine(cx - topHalf, hy, cx - bottomHalf, h, edge)
        c.drawLine(cx + topHalf, hy, cx + bottomHalf, h, edge)

        val n = 9
        for (lane in 0..1) {
            val f = if (lane == 0) -0.34f else 0.34f
            for (i in 0 until n) {
                val u0 = (i + phase) / n
                val u1 = (i + phase + 0.55f) / n
                val t0 = u0 * u0
                val t1 = min(1f, u1 * u1)
                if (t1 <= t0) continue
                val y0 = hy + (h - hy) * t0
                val y1 = hy + (h - hy) * t1
                val x0 = cx + f * (topHalf + (bottomHalf - topHalf) * t0)
                val x1 = cx + f * (topHalf + (bottomHalf - topHalf) * t1)
                val w0 = (0.6f + 5f * t0) * dn
                val w1 = (0.6f + 5f * t1) * dn
                tmp.reset()
                tmp.moveTo(x0 - w0, y0)
                tmp.lineTo(x0 + w0, y0)
                tmp.lineTo(x1 + w1, y1)
                tmp.lineTo(x1 - w1, y1)
                tmp.close()
                dash.alpha = (40 + 215 * t1).toInt()
                c.drawPath(tmp, dash)
            }
        }
        val photo = carBitmap
        if (photo != null) drawPhotoCar(c, photo, cx, h) else drawCar(c, cx, h)
    }

    /** A real car photo standing on the road; it bobs and sways with speed like the drawn car. */
    private fun drawPhotoCar(c: Canvas, bmp: android.graphics.Bitmap, cx: Float, h: Float) {
        val carW = min(width * 0.46f, height * 0.80f) * carScale
        val carH = carW * bmp.height / bmp.width
        val moving = shown > 0.5f
        val t = System.nanoTime() / 1e9
        val bob = if (moving) (sin(t * 18) * 1.2 * dn).toFloat() else 0f
        val sway = if (moving) (sin(t * 0.9) * min(shown / 120f, 1f) * 6 * dn).toFloat() else 0f
        val bottom = h - h * 0.02f + bob
        val left = cx + sway - carW / 2f
        // App-made soft shadow under the car (the photos carry none).
        val scx = cx + sway
        val sr = carW * 0.70f
        photoShadow.shader = android.graphics.RadialGradient(scx, bottom, sr, 0xF0000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        c.save()
        c.scale(1f, 0.20f, scx, bottom)
        c.drawCircle(scx, bottom, sr, photoShadow)
        c.restore()
        r.set(left, bottom - carH, left + carW, bottom)
        c.drawBitmap(bmp, null, r, photoPaint)
    }

    /**
     * Rear view of the chosen car model, drawn from shapes (no photos, no badges):
     * 0 classic sedan, 1 off-road 4x4, 2 sport sedan, 3 luxury SUV, 4 electric sedan, 5 executive sedan.
     * It bobs and sways with speed, and the tyre tread scrolls so the wheels look like they roll.
     */
    private fun drawCar(c: Canvas, cx: Float, h: Float) {
        val st = carStyle.coerceIn(0, 5)
        val carW = min(width * 0.34f, height * 0.55f) * carScale
        val carH = carW * floatArrayOf(0.62f, 0.86f, 0.58f, 0.74f, 0.55f, 0.60f)[st]
        val ci = floatArrayOf(0.22f, 0.09f, 0.24f, 0.16f, 0.27f, 0.23f)[st] // roof inset
        val belt = floatArrayOf(0.60f, 0.56f, 0.58f, 0.58f, 0.60f, 0.60f)[st] // body top
        val moving = shown > 0.5f
        val t = System.nanoTime() / 1e9
        val bob = if (moving) (sin(t * 18) * 1.2 * dn).toFloat() else 0f
        val sway = if (moving) (sin(t * 0.9) * min(shown / 120f, 1f) * 6 * dn).toFloat() else 0f
        val x = cx + sway
        val bottom = h - h * 0.07f + bob
        val left = x - carW / 2f
        fun X(f: Float) = left + carW * f
        fun Y(f: Float) = bottom - carH * f

        r.set(left - carW * 0.04f, bottom - carH * 0.07f, left + carW * 1.04f, bottom + carH * 0.07f)
        c.drawOval(r, shadow)

        // Tyres with scrolling tread grooves.
        val ww = carW * (if (st == 1) 0.17f else 0.15f)
        val wh = carH * 0.30f
        for (wx in floatArrayOf(0.05f, 0.95f - ww / carW)) {
            r.set(X(wx), bottom - wh, X(wx) + ww, bottom)
            c.drawRoundRect(r, ww * 0.3f, ww * 0.3f, wheel)
            val n = 5
            val gap = wh / n
            val off = (phase * 23f % 1f) * gap
            for (i in 0..n) {
                val gy = bottom - wh + i * gap + off
                if (gy > bottom - wh + 2 && gy < bottom - 2) c.drawLine(X(wx) + ww * 0.15f, gy, X(wx) + ww * 0.85f, gy, tread)
            }
        }

        // Cabin and rear glass.
        tmp.reset()
        tmp.moveTo(X(ci), Y(1f)); tmp.lineTo(X(1 - ci), Y(1f))
        tmp.lineTo(X(0.93f), Y(belt - 0.02f)); tmp.lineTo(X(0.07f), Y(belt - 0.02f)); tmp.close()
        c.drawPath(tmp, cabin)
        tmp.reset()
        tmp.moveTo(X(ci + 0.05f), Y(0.94f)); tmp.lineTo(X(0.95f - ci), Y(0.94f))
        tmp.lineTo(X(0.85f), Y(belt + 0.03f)); tmp.lineTo(X(0.15f), Y(belt + 0.03f)); tmp.close()
        c.drawPath(tmp, glassPaint)
        tmp.reset()
        tmp.moveTo(X(ci + 0.05f), Y(0.94f)); tmp.lineTo(X(ci + 0.22f), Y(0.94f))
        tmp.lineTo(X(ci + 0.08f), Y(belt + 0.03f)); tmp.lineTo(X(0.15f), Y(belt + 0.03f)); tmp.close()
        c.drawPath(tmp, shine)
        if (st == 1) { // roof rails
            c.drawLine(X(ci + 0.02f), Y(1.04f), X(ci + 0.02f), Y(1f), trim)
            c.drawLine(X(0.98f - ci), Y(1.04f), X(0.98f - ci), Y(1f), trim)
            c.drawLine(X(ci), Y(1.04f), X(1 - ci), Y(1.04f), trim)
        }

        // Body, bumper and highlight.
        val rad = carH * (if (st == 1) 0.06f else 0.14f)
        r.set(left, Y(belt), left + carW, Y(0.10f))
        c.drawRoundRect(r, rad, rad, body)
        r.set(X(0.02f), Y(0.24f), X(0.98f), Y(0.10f))
        c.drawRoundRect(r, rad * 0.6f, rad * 0.6f, bumper)
        r.set(X(0.02f), Y(belt - 0.02f), X(0.98f), Y(belt - 0.10f))
        c.drawRoundRect(r, carH * 0.05f, carH * 0.05f, shine)
        if (st == 2) { // trunk spoiler
            r.set(X(0.18f), Y(belt + 0.05f), X(0.82f), Y(belt + 0.01f))
            c.drawRoundRect(r, 3 * dn, 3 * dn, cabin)
        }

        // Tail lights per model.
        val g = 3 * dn
        val ly = Y(belt - 0.10f)
        fun lamp(l: Float, top: Float, rr: Float, b: Float, round: Float) {
            r.set(X(l) - g, top - g, X(rr) + g, b + g); c.drawRoundRect(r, round + g, round + g, tailGlow)
            r.set(X(l), top, X(rr), b); c.drawRoundRect(r, round, round, tail)
        }
        when (st) {
            0 -> for (fx in floatArrayOf(0.11f, 0.22f, 0.78f, 0.89f)) { // round pairs
                c.drawCircle(X(fx), ly + carH * 0.06f, carH * 0.075f, tailGlow)
                c.drawCircle(X(fx), ly + carH * 0.06f, carH * 0.06f, tail)
            }
            1 -> { // tall corner lamps + spare wheel
                lamp(0.02f, Y(belt - 0.04f), 0.10f, Y(0.26f), 2 * dn)
                lamp(0.90f, Y(belt - 0.04f), 0.98f, Y(0.26f), 2 * dn)
                c.drawCircle(X(0.62f), Y(0.38f), carH * 0.17f, wheel)
                c.drawCircle(X(0.62f), Y(0.38f), carH * 0.09f, trim)
            }
            2 -> { // split rectangles
                lamp(0.04f, ly, 0.26f, ly + carH * 0.11f, 2 * dn)
                lamp(0.74f, ly, 0.96f, ly + carH * 0.11f, 2 * dn)
            }
            3 -> lamp(0.03f, ly + carH * 0.02f, 0.97f, ly + carH * 0.07f, carH * 0.03f) // full-width light bar
            4 -> { // slim swept lamps
                lamp(0.03f, ly, 0.27f, ly + carH * 0.07f, carH * 0.035f)
                lamp(0.73f, ly, 0.97f, ly + carH * 0.07f, carH * 0.035f)
            }
            else -> { // wide lamps joined by a chrome bar
                lamp(0.03f, ly, 0.30f, ly + carH * 0.11f, carH * 0.05f)
                lamp(0.70f, ly, 0.97f, ly + carH * 0.11f, carH * 0.05f)
                r.set(X(0.30f), ly + carH * 0.04f, X(0.70f), ly + carH * 0.07f)
                c.drawRoundRect(r, 2 * dn, 2 * dn, trim)
            }
        }

        // Blank number plate.
        val px = if (st == 1) X(0.30f) else x
        r.set(px - carW * 0.09f, Y(0.34f), px + carW * 0.09f, Y(0.25f))
        c.drawRoundRect(r, 3 * dn, 3 * dn, plate)
    }
}
