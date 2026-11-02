package com.carlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Round analog clock with a seconds hand. It only ticks while visible. */
class AnalogClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var accent = 0xFF1FD1B8.toInt()
        set(v) {
            field = v
            secondHand.color = v
            invalidate()
        }

    private val dn = resources.displayMetrics.density
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x99FFFFFF.toInt()
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        color = 0xCCFFFFFF.toInt()
    }
    private val numbers = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = 0xFFFFFFFF.toInt()
    }
    private val hourHand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val minuteHand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        color = 0xFFEAF2F7.toInt()
    }
    private val secondHand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        color = accent
    }

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 1000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(ticker)
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        removeCallbacks(ticker)
        if (visibility == VISIBLE) post(ticker)
    }

    private fun hand(c: Canvas, cx: Float, cy: Float, degrees: Float, length: Float, p: Paint) {
        val a = Math.toRadians(degrees - 90.0)
        c.drawLine(cx, cy, cx + length * cos(a).toFloat(), cy + length * sin(a).toFloat(), p)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 3 * dn
        if (r <= 0f) return

        ring.strokeWidth = r * 0.03f
        numbers.textSize = r * 0.2f
        c.drawCircle(cx, cy, r, face)
        c.drawCircle(cx, cy, r, ring)

        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0 - 90.0)
            val major = i % 3 == 0
            val outer = r * 0.94f
            val inner = r * (if (major) 0.80f else 0.87f)
            tickPaint.strokeWidth = r * (if (major) 0.035f else 0.02f)
            c.drawLine(
                cx + outer * cos(a).toFloat(), cy + outer * sin(a).toFloat(),
                cx + inner * cos(a).toFloat(), cy + inner * sin(a).toFloat(), tickPaint
            )
        }
        for (i in intArrayOf(12, 3, 6, 9)) {
            val a = Math.toRadians((i % 12) * 30.0 - 90.0)
            val nr = r * 0.62f
            c.drawText(i.toString(), cx + nr * cos(a).toFloat(), cy + nr * sin(a).toFloat() + numbers.textSize / 3f, numbers)
        }

        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR)
        val m = cal.get(Calendar.MINUTE)
        val s = cal.get(Calendar.SECOND)
        hourHand.strokeWidth = r * 0.07f
        minuteHand.strokeWidth = r * 0.05f
        secondHand.strokeWidth = r * 0.025f
        hand(c, cx, cy, (h + m / 60f) * 30f, r * 0.45f, hourHand)
        hand(c, cx, cy, (m + s / 60f) * 6f, r * 0.68f, minuteHand)
        hand(c, cx, cy, s * 6f, r * 0.78f, secondHand)
        c.drawCircle(cx, cy, r * 0.055f, secondHand)
    }
}
