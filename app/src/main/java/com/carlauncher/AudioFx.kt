package com.carlauncher

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer

/**
 * Sound effects: a multi-band equalizer, bass boost, 3D surround and loudness.
 * They are attached to the global output (session 0) so they shape every app's sound on boxes that allow it,
 * and also to our own music player's session, which works on every Android device.
 * Settings live in the "eq" preferences and are re-applied whenever the launcher or player starts.
 */
object AudioFx {
    class Preset(val name: String, val curve: FloatArray, val bass: Int)

    /** Curves are in dB at 60 Hz, 230 Hz, 910 Hz, 3.6 kHz and 14 kHz; they are mapped to the device's own bands. */
    val presets = listOf(
        Preset("Flat", floatArrayOf(0f, 0f, 0f, 0f, 0f), 0),
        Preset("Bass boost", floatArrayOf(6f, 4f, 0f, 0f, 1f), 600),
        Preset("Deep bass", floatArrayOf(9f, 6f, 1f, 0f, 0f), 900),
        Preset("Rock", floatArrayOf(5f, 3f, -1f, 3f, 5f), 300),
        Preset("Pop", floatArrayOf(-1f, 2f, 4f, 2f, -1f), 100),
        Preset("Jazz", floatArrayOf(4f, 2f, -2f, 2f, 4f), 200),
        Preset("Classical", floatArrayOf(4f, 3f, -2f, 3f, 4f), 0),
        Preset("Dance", floatArrayOf(6f, 0f, 2f, 4f, 1f), 500),
        Preset("Vocal", floatArrayOf(-2f, -1f, 4f, 3f, 1f), 0),
        Preset("Treble boost", floatArrayOf(0f, 0f, 0f, 4f, 7f), 0),
        Preset("Car cabin", floatArrayOf(3f, 1f, 0f, 2f, 3f), 300)
    )
    private val presetHz = floatArrayOf(60f, 230f, 910f, 3600f, 14000f)

    private val attached = HashMap<Int, Array<Any?>>()

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("eq", Context.MODE_PRIVATE)

    /** Attaches effects to the global mix and to [session] (0 = global only), then applies the saved settings. */
    fun attach(ctx: Context, session: Int = 0) {
        for (s in setOf(0, session)) {
            if (attached.containsKey(s)) continue
            val fx = arrayOfNulls<Any>(4)
            try { fx[0] = Equalizer(1000, s) } catch (e: Throwable) { }
            try { fx[1] = BassBoost(1000, s) } catch (e: Throwable) { }
            try { fx[2] = Virtualizer(1000, s) } catch (e: Throwable) { }
            try { fx[3] = LoudnessEnhancer(s) } catch (e: Throwable) { }
            if (fx.any { it != null }) attached[s] = fx
        }
        apply(ctx)
    }

    fun detach(session: Int) {
        if (session == 0) return
        attached.remove(session)?.forEach { (it as? android.media.audiofx.AudioEffect)?.release() }
    }

    private fun eq(): Equalizer? = attached.values.firstNotNullOfOrNull { it[0] as? Equalizer }

    val available: Boolean get() = eq() != null
    fun bandCount(): Int = eq()?.numberOfBands?.toInt() ?: 5
    fun bandHz(i: Int): Int = eq()?.getCenterFreq(i.toShort())?.div(1000) ?: presetHz.getOrElse(i) { 1000f }.toInt()
    /** Band range in millibels, e.g. -1500..1500. */
    fun range(): IntArray = eq()?.bandLevelRange?.let { intArrayOf(it[0].toInt(), it[1].toInt()) } ?: intArrayOf(-1500, 1500)

    fun level(ctx: Context, i: Int): Int = prefs(ctx).getInt("band$i", 0)

    /** Applies a preset curve to the device's bands by matching the nearest frequency. */
    fun usePreset(ctx: Context, index: Int) {
        val p = presets[index]
        val e = prefs(ctx).edit().putInt("preset", index).putInt("bass", p.bass)
        val r = range()
        for (i in 0 until bandCount()) {
            val hz = bandHz(i).toFloat()
            var best = 0
            for (k in presetHz.indices) if (kotlin.math.abs(Math.log(presetHz[k] / hz.toDouble())) < kotlin.math.abs(Math.log(presetHz[best] / hz.toDouble()))) best = k
            e.putInt("band$i", (p.curve[best] * 100).toInt().coerceIn(r[0], r[1]))
        }
        e.apply()
        apply(ctx)
    }

    fun set(ctx: Context, key: String, v: Int) {
        val e = prefs(ctx).edit().putInt(key, v)
        if (key.startsWith("band")) e.putInt("preset", -1) // custom
        e.apply()
        apply(ctx)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("on", on).apply()
        apply(ctx)
    }

    fun apply(ctx: Context) {
        val p = prefs(ctx)
        val on = p.getBoolean("on", true)
        val bassOnly = p.getBoolean("bass_only", false)
        for (fx in attached.values) {
            try {
                (fx[0] as? Equalizer)?.let { e ->
                    for (i in 0 until e.numberOfBands) e.setBandLevel(i.toShort(), p.getInt("band$i", 0).toShort())
                    e.enabled = on && !bassOnly
                }
            } catch (e: Throwable) { }
            try {
                (fx[1] as? BassBoost)?.let { b ->
                    val s = p.getInt("bass", 0)
                    if (b.strengthSupported) b.setStrength(s.toShort())
                    b.enabled = on && (s > 0 || bassOnly)
                    if (bassOnly && s == 0 && b.strengthSupported) b.setStrength(700)
                }
            } catch (e: Throwable) { }
            try {
                (fx[2] as? Virtualizer)?.let { v ->
                    val s = p.getInt("surround", 0)
                    if (v.strengthSupported) v.setStrength(s.toShort())
                    v.enabled = on && !bassOnly && s > 0
                }
            } catch (e: Throwable) { }
            try {
                (fx[3] as? LoudnessEnhancer)?.let { l ->
                    val g = p.getInt("loud", 0)
                    l.setTargetGain(g)
                    l.enabled = on && !bassOnly && g > 0
                }
            } catch (e: Throwable) { }
        }
    }
}
