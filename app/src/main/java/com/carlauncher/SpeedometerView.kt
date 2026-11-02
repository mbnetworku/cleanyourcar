package com.carlauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Animated speedometer. The static dial (track, ticks, numbers) is rendered once into a bitmap,
 * so each animation frame only draws the progress arc, needle and digits.
 */
class SpeedometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val startAngle = 135f
    private val sweep = 270f
    private val kmhPerMph = 1.609344f

    var useMph = false
        set(value) {
            field = value
            buildDial()
            invalidate()
        }

    var accent = 0xFF00D4FF.toInt()
        set(value) {
            field = value
            invalidate()
        }

    /** Called with the animated speed (km/h) on every frame, so the road can follow the needle. */
    var onShown: ((Float) -> Unit)? = null

    private val maxDisplay: Float get() = if (useMph) 160f else 240f
    private val unitLabel: String get() = if (useMph) "mph" else "km/h"

    private var targetKmh = 0f
    private var shownKmh = 0f
    private var animator: ValueAnimator? = null
    private var introRunning = false

    private var dial: Bitmap? = null
    private val rect = RectF()
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var arcR = 0f
    private var stroke = 0f

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFF1E2E3B.toInt()
    }
    private val progress = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        color = 0xFF8FA3B5.toInt()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = 0xFFB8C7D4.toInt()
    }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFF5A36.toInt()
    }
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF5A36.toInt() }
    private val hubInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B1620.toInt() }
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = 0xFFFFFFFF.toInt()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = 0xFF8FA3B5.toInt()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f - 4f * resources.displayMetrics.density
        stroke = radius * 0.07f
        arcR = radius - stroke
        rect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
        track.strokeWidth = stroke
        progress.strokeWidth = stroke
        needle.strokeWidth = radius * 0.028f
        labelPaint.textSize = radius * 0.075f
        speedPaint.textSize = radius * 0.40f
        unitPaint.textSize = radius * 0.10f
        buildDial()
    }

    private fun buildDial() {
        if (width == 0 || height == 0) return
        dial?.recycle()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawArc(rect, startAngle, sweep, false, track)
        val max = maxDisplay
        var v = 0
        while (v <= max.toInt()) {
            val major = v % 20 == 0
            val a = Math.toRadians((startAngle + sweep * v.toFloat() / max).toDouble())
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            val outer = arcR - stroke * 0.9f
            val inner = outer - radius * (if (major) 0.09f else 0.05f)
            tick.strokeWidth = radius * (if (major) 0.014f else 0.007f)
            c.drawLine(cx + outer * ca, cy + outer * sa, cx + inner * ca, cy + inner * sa, tick)
            if (major) {
                val lr = inner - radius * 0.09f
                c.drawText(v.toString(), cx + lr * ca, cy + lr * sa + labelPaint.textSize / 3f, labelPaint)
            }
            v += 10
        }
        dial = bmp
    }

    /** Digits only (no dial), on a small dark glass pill. */
    var numberOnly = false
        set(value) {
            field = value
            invalidate()
        }

    private val backdrop = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C0B1620.toInt() }
    private val pill = RectF()

    override fun onDraw(canvas: Canvas) {
        val shown = if (useMph) shownKmh / kmhPerMph else shownKmh
        if (numberOnly) {
            val h = height.toFloat()
            pill.set(0f, 0f, width.toFloat(), h)
            canvas.drawRoundRect(pill, h * 0.22f, h * 0.22f, backdrop)
            val s = speedPaint.textSize
            val u = unitPaint.textSize
            speedPaint.textSize = h * 0.52f
            unitPaint.textSize = h * 0.15f
            canvas.drawText(shown.roundToInt().toString(), width / 2f, h * 0.60f, speedPaint)
            canvas.drawText(unitLabel, width / 2f, h * 0.84f, unitPaint)
            speedPaint.textSize = s
            unitPaint.textSize = u
            return
        }
        // Soft dark disc keeps the dial readable over the road.
        canvas.drawCircle(cx, cy, radius, backdrop)
        dial?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        val frac = (shown / maxDisplay).coerceIn(0f, 1f)

        if (frac > 0.003f) {
            progress.color = when {
                frac < 0.5f -> accent
                frac < 0.75f -> 0xFFFFB300.toInt()
                else -> 0xFFFF3D3D.toInt()
            }
            canvas.drawArc(rect, startAngle, sweep * frac, false, progress)
        }

        canvas.save()
        canvas.rotate(startAngle + sweep * frac, cx, cy)
        canvas.drawLine(cx - radius * 0.10f, cy, cx + arcR - stroke * 0.9f - radius * 0.13f, cy, needle)
        canvas.restore()
        canvas.drawCircle(cx, cy, radius * 0.055f, hub)
        canvas.drawCircle(cx, cy, radius * 0.025f, hubInner)

        canvas.drawText(shown.roundToInt().toString(), cx, cy + radius * 0.56f, speedPaint)
        canvas.drawText(unitLabel, cx, cy + radius * 0.74f, unitPaint)
    }

    /** Sets the real speed in km/h; the needle glides to it. */
    fun setSpeed(kmh: Float) {
        targetKmh = kmh
        if (!introRunning) animateTo(kmh)
    }

    /** Needle sweeps to the maximum and back, like a car's start-up self test. */
    fun playIntro() {
        introRunning = false
        animator?.cancel()
        introRunning = true
        val maxKmh = if (useMph) maxDisplay * kmhPerMph else maxDisplay
        animator = ValueAnimator.ofFloat(0f, maxKmh, targetKmh).apply {
            duration = 1800
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                shownKmh = it.animatedValue as Float
                onShown?.invoke(shownKmh)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (introRunning) {
                        introRunning = false
                        animateTo(targetKmh)
                    }
                }
            })
            start()
        }
    }

    private fun animateTo(kmh: Float) {
        animator?.cancel()
        if (shownKmh == kmh) return
        animator = ValueAnimator.ofFloat(shownKmh, kmh).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shownKmh = it.animatedValue as Float
                onShown?.invoke(shownKmh)
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        introRunning = false
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
