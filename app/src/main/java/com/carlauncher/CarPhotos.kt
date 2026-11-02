package com.carlauncher

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import java.io.File

/**
 * Real-photo cars for the road view. car_photo: -1 = drawn car, 0..5 = bundled photos, 100 = the
 * user's own picture. Photos with a solid background get soft faded edges so they blend into the road;
 * a PNG that already has a transparent background is used as it is.
 */
object CarPhotos {
    /** Lexus RX, Tesla Model S, Corolla, Lancer, Pajero, FJ Cruiser, Camry: rear views with the background removed. */
    val names = arrayOf("c1.png", "c2.png", "c3.png", "c4.png", "c5.png", "c6.png", "c7.png")

    fun customFile(ctx: Context) = File(ctx.filesDir, "car.png")

    fun key(ctx: Context, p: SharedPreferences): String {
        val sel = p.getInt("car_photo", -1)
        return if (sel == 100) "custom:${customFile(ctx).lastModified()}" else "sel:$sel"
    }

    fun thumb(ctx: Context, i: Int): Bitmap? = try {
        ctx.assets.open("cars/" + names[i]).use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }

    fun load(ctx: Context, p: SharedPreferences): Bitmap? {
        val sel = p.getInt("car_photo", -1)
        val src = try {
            when {
                sel == 100 -> BitmapFactory.decodeFile(customFile(ctx).path)
                sel in names.indices -> ctx.assets.open("cars/" + names[sel]).use { BitmapFactory.decodeStream(it) }
                else -> null
            }
        } catch (e: Exception) {
            null
        } ?: return null
        val big = maxOf(src.width, src.height)
        val b = if (big > 700) Bitmap.createScaledBitmap(src, src.width * 700 / big, src.height * 700 / big, true) else src
        return if (transparentCorners(b)) b else feather(b)
    }

    private fun transparentCorners(b: Bitmap): Boolean {
        if (!b.hasAlpha()) return false
        val w = b.width - 1
        val h = b.height - 1
        return listOf(b.getPixel(0, 0), b.getPixel(w, 0), b.getPixel(0, h), b.getPixel(w, h)).all { Color.alpha(it) < 40 }
    }

    /** Keeps the middle (the car) solid and fades the background out towards the edges. */
    private fun feather(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(src, 0f, 0f, null)
        val cx = w / 2f
        val cy = h * 0.55f
        val g = RadialGradient(cx, cy, w * 0.52f, intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT), floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
        g.setLocalMatrix(Matrix().apply { setScale(1f, h / w.toFloat() * 1.1f, cx, cy) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = g
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return out
    }
}
