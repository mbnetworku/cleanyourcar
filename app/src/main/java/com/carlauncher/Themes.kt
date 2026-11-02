package com.carlauncher

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import java.io.File

/** Colors and drawables for the glass look. Everything is generated so it can be customized at runtime. */
object Themes {
    val accents = intArrayOf(
        0xFF1FD1B8.toInt(), 0xFF3FA9FF.toInt(), 0xFFB26BFF.toInt(),
        0xFFFF9F43.toInt(), 0xFFFF5C7A.toInt(), 0xFF7CE35B.toInt()
    )

    val backgrounds = arrayOf(
        intArrayOf(0xFF11555A.toInt(), 0xFF2A8A80.toInt(), 0xFF0B3A44.toInt()),
        intArrayOf(0xFF1C3A8A.toInt(), 0xFF3F74D6.toInt(), 0xFF101F55.toInt()),
        intArrayOf(0xFF4B2A94.toInt(), 0xFF8B54C9.toInt(), 0xFF22104F.toInt()),
        intArrayOf(0xFF2A2E35.toInt(), 0xFF5B6470.toInt(), 0xFF16181C.toInt()),
        intArrayOf(0xFF8A2A3E.toInt(), 0xFFC9645A.toInt(), 0xFF3A0F22.toInt())
    )

    /** True while the launcher shows its dark look (set by [update]). */
    @Volatile var dark = false

    /** A photo wallpaper is set: glass turns clearer so the picture shows through the whole home screen. */
    @Volatile var photoMode = false

    /** theme_mode: 0 = auto (ambient light / night), 1 = light, 2 = dark. */
    fun update(p: SharedPreferences) {
        photoMode = p.getBoolean("bg_photo", false)
        dark = when (p.getInt("theme_mode", 0)) {
            1 -> false
            2 -> true
            else -> p.getBoolean("auto_dark", false)
        }
    }

    val carColors = intArrayOf(
        0xFF3A4D5A.toInt(), 0xFFE5E8EC.toInt(), 0xFF111317.toInt(), 0xFFC8102E.toInt(),
        0xFF1E5BD8.toInt(), 0xFFF2C230.toInt(), 0xFF1E8A4C.toInt(), 0xFFFF7A1A.toInt()
    )

    fun accent(p: SharedPreferences) = accents[p.getInt("accent", 0).coerceIn(0, accents.lastIndex)]

    fun glassPct(p: SharedPreferences) = p.getInt("glass", 28)

    fun blend(a: Int, b: Int, t: Float): Int {
        fun mix(x: Int, y: Int) = (x + (y - x) * t).toInt()
        return Color.argb(255, mix(Color.red(a), Color.red(b)), mix(Color.green(a), Color.green(b)), mix(Color.blue(a), Color.blue(b)))
    }

    /** Frosted glass card: translucent tinted gradient with a light edge. */
    fun glass(pct: Int, accent: Int, radiusPx: Float, strokePx: Int): Drawable {
        // Bright frosted look (iOS "liquid glass"): near-white body, strong specular sheen, crisp white rim.
        if (dark) return darkGlass(pct, accent, radiusPx, strokePx)
        val a = (pct * 255 * (if (photoMode) 4 else 9) / 1000).coerceIn(0, 255)
        val top = (a shl 24) or (blend(Color.WHITE, accent, 0.06f) and 0xFFFFFF)
        val bottom = ((a * 0.65f).toInt() shl 24) or (blend(Color.WHITE, accent, 0.2f) and 0xFFFFFF)
        val r = radiusPx * 1.3f
        // Liquid glass: tinted body, bright specular sheen on top, soft accent glow at the bottom edge, glossy rim.
        val body = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(top, bottom)).apply {
            cornerRadius = r
            setStroke(maxOf(strokePx, 1) * 2, 0xDDFFFFFF.toInt())
        }
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0x88FFFFFF.toInt(), 0x33FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x22FFFFFF)
        ).apply { cornerRadius = r }
        val glow = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf((0x33 shl 24) or (accent and 0xFFFFFF), 0x00000000, 0x00000000)
        ).apply { cornerRadius = r }
        if (photoMode) {
            val tint = GradientDrawable().apply { cornerRadius = r; setColor(0x66000000) }
            return LayerDrawable(arrayOf(tint, body, glow, sheen))
        }
        return LayerDrawable(arrayOf(body, glow, sheen))
    }

    private fun darkGlass(pct: Int, accent: Int, radiusPx: Float, strokePx: Int): Drawable {
        val a = (pct * 255 * 22 / 1000).coerceIn(if (photoMode) 40 else 90, 255) * (if (photoMode) 6 else 10) / 10
        val r = radiusPx * 1.3f
        val body = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf((a shl 24) or (blend(0x16222E, accent, 0.08f) and 0xFFFFFF), ((a * 0.75f).toInt() shl 24) or 0x0A121A)
        ).apply {
            cornerRadius = r
            setStroke(maxOf(strokePx, 1) * 2, 0x44FFFFFF)
        }
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0x30FFFFFF, 0x0CFFFFFF, 0x00FFFFFF, 0x00FFFFFF)
        ).apply { cornerRadius = r }
        val glow = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf((0x22 shl 24) or (accent and 0xFFFFFF), 0x00000000, 0x00000000)
        ).apply { cornerRadius = r }
        return LayerDrawable(arrayOf(body, glow, sheen))
    }

    /** Soft dark shadow behind every text in [v], so it stays readable on bright photos. */
    fun shadowText(v: android.view.View) {
        if (v is android.widget.TextView) v.setShadowLayer(v.resources.displayMetrics.density * 3f, 0f, v.resources.displayMetrics.density, 0xCC000000.toInt())
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) shadowText(v.getChildAt(i))
    }

    fun ring(accent: Int, dn: Float) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0x22FFFFFF)
        setStroke((2 * dn).toInt(), accent)
    }

    fun swatch(color: Int, selected: Boolean, dn: Float) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(if (selected) (3 * dn).toInt() else 0, Color.WHITE)
    }

    fun swatchGradient(colors: IntArray, selected: Boolean, dn: Float) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            shape = GradientDrawable.OVAL
            setStroke(if (selected) (3 * dn).toInt() else 0, Color.WHITE)
        }

    fun photoFile(ctx: Context) = File(ctx.filesDir, "bg.jpg")

    private fun usePhoto(ctx: Context, p: SharedPreferences) = p.getBoolean("bg_photo", false) && photoFile(ctx).exists()

    /** Cheap identity of the current background so callers only rebuild it when it changes. */
    fun backgroundKey(ctx: Context, p: SharedPreferences): String =
        (if (usePhoto(ctx, p)) "photo:${photoFile(ctx).lastModified()}:${p.getInt("dim", 8)}" else "grad:${p.getInt("bg", 0)}") + dark

    fun backgroundDrawable(ctx: Context, p: SharedPreferences): Drawable {
        if (usePhoto(ctx, p)) {
            val path = photoFile(ctx).path
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1920) sample *= 2
            val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            if (bmp != null) {
                return LayerDrawable(arrayOf(BitmapDrawable(ctx.resources, bmp), ColorDrawable(((p.getInt("dim", 8) + if (dark) 30 else 0).coerceAtMost(95) * 255 / 100 shl 24))))
            }
        }
        var colors = backgrounds[p.getInt("bg", 0).coerceIn(0, backgrounds.lastIndex)]
        if (dark) colors = IntArray(colors.size) { blend(colors[it], Color.BLACK, 0.72f) }
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
    }
}
