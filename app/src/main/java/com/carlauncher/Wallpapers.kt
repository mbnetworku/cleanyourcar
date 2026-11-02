package com.carlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Random

/**
 * Random full-HD wallpapers. Tries a random photo from the internet first and falls back to a
 * generated 1920x1080 gradient-mesh picture, so it also works with no connection.
 */
object Wallpapers {
    private const val W = 1920
    private const val H = 1080
    private val ui = Handler(Looper.getMainLooper())

    /** Wallpaper change: 0 = off, 1 = every time the launcher starts, 2 = once a day. */
    fun autoIfDue(ctx: Context, onDone: () -> Unit) {
        val p = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        val mode = p.getInt("auto_wall", 0)
        if (mode == 0) return
        val last = p.getLong("auto_wall_at", 0L)
        val due = mode == 1 || System.currentTimeMillis() - last > 24L * 3600 * 1000
        if (due && !running) refresh(ctx, onDone)
    }

    /** Seeds from 1000 up are the bundled photos in assets/wall; smaller seeds are generated pictures. */
    val presetSeeds = longArrayOf(1000, 1001, 1002, 1003, 1004, 1005, 1006, 1007, 1008)
    private val photos = arrayOf("w1.webp", "w2.webp", "w3.webp", "w4.webp", "w5.png", "w6.png", "w7.png", "w8.png", "w9.webp")

    /** Decodes a bundled photo, trims any blank white band, and centre-crops it to [w] x [h]. */
    private fun photo(ctx: Context, seed: Long, w: Int, h: Int): Bitmap? = try {
        val name = "wall/" + photos[(seed - 1000).toInt()]
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.assets.open(name).use { BitmapFactory.decodeStream(it, null, b) }
        var s = 1
        while (b.outWidth / (s * 2) >= w) s *= 2
        val src = ctx.assets.open(name).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }!!
        var sh = src.height
        fun blank(y: Int) = (1..8).all { i -> val px = src.getPixel(src.width * i / 9, y); Color.alpha(px) < 30 || Color.red(px) > 245 && Color.green(px) > 245 && Color.blue(px) > 245 }
        while (sh > 10 && blank(sh - 1)) sh--
        val scale = maxOf(w / src.width.toFloat(), h / sh.toFloat())
        val cw = (w / scale).toInt().coerceAtMost(src.width)
        val ch = (h / scale).toInt().coerceAtMost(sh)
        val crop = Bitmap.createBitmap(src, (src.width - cw) / 2, (sh - ch) / 2, cw, ch)
        Bitmap.createScaledBitmap(crop, w, h, true)
    } catch (e: Exception) {
        null
    }

    /** Small preview: the right half is darkened with a round light/dark badge, like a CarPlay wallpaper tile. */
    fun preview(ctx: Context, seed: Long): Bitmap {
        val bmp = ((if (seed >= 1000) photo(ctx, seed, 480, 270) else null) ?: mesh(seed, 480, 270)).copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x66000000
        c.drawRect(240f, 0f, 480f, 270f, p)
        p.color = Color.WHITE
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        c.drawCircle(240f, 232f, 18f, p)
        p.style = Paint.Style.FILL
        c.drawArc(android.graphics.RectF(222f, 214f, 258f, 250f), 90f, 180f, true, p)
        return bmp
    }

    fun applySeed(ctx: Context, seed: Long, onDone: () -> Unit) {
        val app = ctx.applicationContext
        Thread {
            try {
                FileOutputStream(Themes.photoFile(app)).use { ((if (seed >= 1000) photo(app, seed, W, H) else null) ?: mesh(seed, W, H)).compress(Bitmap.CompressFormat.JPEG, 92, it) }
                app.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit()
                    .putBoolean("bg_photo", true).putLong("wall_seed", seed).apply()
                ui.post(onDone)
            } catch (e: Exception) {
            }
        }.start()
    }

    @Volatile private var running = false

    fun refresh(ctx: Context, onDone: () -> Unit) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        Thread {
            val bmp = download() ?: generate()
            try {
                FileOutputStream(Themes.photoFile(app)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                app.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit()
                    .putBoolean("bg_photo", true).putLong("auto_wall_at", System.currentTimeMillis()).apply()
                ui.post(onDone)
            } catch (e: Exception) {
            } finally {
                running = false
            }
        }.start()
    }

    private fun download(): Bitmap? = try {
        val c = URL("https://picsum.photos/$W/$H").openConnection() as HttpURLConnection
        c.connectTimeout = 7000
        c.readTimeout = 15000
        c.instanceFollowRedirects = true
        val b = c.inputStream.use { BitmapFactory.decodeStream(it) }
        if (b != null && b.width >= 1280) b else null
    } catch (e: Exception) {
        null
    }

    /** Soft aurora-style mesh: a dark-to-colour base with several big blurry colour blobs. */
    fun generate(): Bitmap = mesh(Random().nextLong(), W, H)

    /** Same seed always gives the same picture, so presets can be previewed small and applied in Full HD. */
    fun mesh(seed: Long, w: Int, h: Int): Bitmap {
        val r = Random(seed * -7046029254386353131L + 1442695040888963407L)
        r.nextFloat()
        val k = w / W.toFloat()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hue = r.nextFloat() * 360f
        fun col(h: Float, s: Float, v: Float) = Color.HSVToColor(floatArrayOf((h % 360f + 360f) % 360f, s, v))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), col(hue, 0.7f, 0.35f), col(hue + 40f, 0.8f, 0.15f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        for (i in 0 until 6) {
            val cx = r.nextFloat() * w
            val cy = r.nextFloat() * h
            val rad = (450f + r.nextFloat() * 500f) * k
            val colr = col(hue + r.nextFloat() * 120f - 30f, 0.55f + r.nextFloat() * 0.35f, 0.75f + r.nextFloat() * 0.25f)
            val soft = Paint(Paint.ANTI_ALIAS_FLAG)
            soft.shader = RadialGradient(cx, cy, rad, (0xA0 shl 24) or (colr and 0xFFFFFF), colr and 0xFFFFFF, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, rad, soft)
        }
        return bmp
    }
}
