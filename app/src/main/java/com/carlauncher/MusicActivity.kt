package com.carlauncher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.sin

/**
 * The launcher's own music player: Songs / Folders / Artists views, filtered by source
 * (All, Internal storage, SD card, each USB drive), and an animated now-playing panel.
 */
class MusicActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private var dn = 1f
    private fun px(v: Int) = (v * dn).toInt()
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private var accent = 0

    private lateinit var root: LinearLayout
    private lateinit var nav: LinearLayout
    private lateinit var sourceBox: LinearLayout
    private lateinit var listPanel: LinearLayout
    private lateinit var listTitle: TextView
    private lateinit var listSub: TextView
    private lateinit var list: ListView
    private lateinit var nowPanel: LinearLayout
    private lateinit var disc: DiscView
    private lateinit var eq: EqView
    private lateinit var tTitle: TextView
    private lateinit var tArtist: TextView
    private lateinit var seek: SeekBar
    private lateinit var tPos: TextView
    private lateinit var tDur: TextView
    private lateinit var bPlay: ImageView
    private lateinit var bShuffle: TextView
    private lateinit var bRepeat: TextView

    private val tabNames = listOf("Songs", "Folders", "Artists")
    private val tabViews = ArrayList<TextView>()
    private var tab = 0
    private var source: String? = null
    /** Inside a folder or artist: its name and tracks; null = showing the group list. */
    private var openGroup: Pair<String, List<Track>>? = null
    private var rows: List<Row> = emptyList()
    private val adapter = RowAdapter()
    private var dragging = false

    private class Row(val title: String, val sub: String, val glyph: String, val track: Track?, val group: Pair<String, List<Track>>?)

    private val libListener: () -> Unit = { refreshSources(); showList() }
    private val playerListener: () -> Unit = { ui.post { updateNow() } }
    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            ui.postDelayed(this, 500)
        }
    }

    /** Rescans when a USB drive or SD card is plugged in or removed. */
    private val mountReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            ui.postDelayed({ MusicLibrary.scan(this@MusicActivity) }, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        dn = resources.displayMetrics.density
        Themes.update(prefs)
        accent = Themes.accent(prefs)
        buildUi()
        Storage.ensure(this, "your music")
        if (MusicLibrary.tracks.isEmpty() && !MusicLibrary.scanning) MusicLibrary.scan(this)
        refreshSources()
        selectTab(0)
    }

    private fun askPermission() {
        if (Build.VERSION.SDK_INT < 23) return
        val perm = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_AUDIO" else android.Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(perm), 3)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        MusicLibrary.scan(this)
    }

    override fun onResume() {
        super.onResume()
        MusicLibrary.addListener(libListener)
        MusicService.addListener(playerListener)
        registerReceiver(mountReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        })
        updateNow()
        ui.post(tick)
        // Access may have just been granted in system settings.
        if (Storage.hasAccess(this) && MusicLibrary.tracks.isEmpty() && !MusicLibrary.scanning) MusicLibrary.scan(this)
    }

    override fun onPause() {
        MusicLibrary.removeListener(libListener)
        MusicService.removeListener(playerListener)
        try { unregisterReceiver(mountReceiver) } catch (e: Exception) { }
        ui.removeCallbacks(tick)
        disc.spinning = false
        eq.running = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        @Suppress("DEPRECATION")
        if (hasFocus) window.decorView.systemUiVisibility = MainActivity.immersiveFlags()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (openGroup != null) { openGroup = null; showList() } else finish()
    }

    // ---- layout ----

    private fun glass() = Themes.glass(Themes.glassPct(prefs), accent, px(20).toFloat(), px(1))

    private fun text(t: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun pill(selected: Boolean) = GradientDrawable().apply {
        cornerRadius = px(14).toFloat()
        setColor(if (selected) accent else 0x33000000)
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            setPadding(px(16), px(16), px(16), px(16))
            background = Themes.backgroundDrawable(this@MusicActivity, prefs)
        }

        // Left: views and sources
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(16), px(12), px(12))
            background = glass()
        }
        nav.addView(text("Music", 26f, bold = true).apply { setPadding(px(8), 0, 0, px(14)) })
        tabNames.forEachIndexed { i, n ->
            val t = text(n, 18f).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(16), 0, px(12), 0)
                isClickable = true
                setOnClickListener { selectTab(i) }
            }
            tabViews.add(t)
            nav.addView(t, LinearLayout.LayoutParams(match, px(52)).apply { bottomMargin = px(6) })
        }
        nav.addView(text("SOURCE", 12f, 0xCCFFFFFF.toInt()).apply { setPadding(px(10), px(18), 0, px(6)) })
        sourceBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nav.addView(sourceBox)
        nav.addView(View(this), LinearLayout.LayoutParams(match, 0, 1f))
        nav.addView(text("EQ  Equalizer", 16f).apply {
            gravity = Gravity.CENTER
            background = pill(true)
            isClickable = true
            setOnClickListener { startActivity(Intent(this@MusicActivity, EqualizerActivity::class.java)) }
        }, LinearLayout.LayoutParams(match, px(48)).apply { bottomMargin = px(8) })
        nav.addView(text("↻  Rescan", 16f).apply {
            gravity = Gravity.CENTER
            background = pill(false)
            isClickable = true
            setOnClickListener { MusicLibrary.scan(this@MusicActivity) }
        }, LinearLayout.LayoutParams(match, px(48)).apply { bottomMargin = px(8) })
        nav.addView(text("Home", 16f).apply {
            gravity = Gravity.CENTER
            background = pill(false)
            isClickable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(match, px(48)))

        // Middle: list
        listPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(16), px(18), px(8))
            background = glass()
        }
        listTitle = text("Songs", 24f, bold = true)
        listSub = text("", 13f, 0xCCFFFFFF.toInt()).apply { setPadding(0, px(2), 0, px(8)) }
        listPanel.addView(listTitle)
        listPanel.addView(listSub)
        list = ListView(this).apply {
            divider = null
            dividerHeight = px(4)
            selector = GradientDrawable().apply { cornerRadius = px(12).toFloat(); setColor(0x33FFFFFF) }
            isVerticalScrollBarEnabled = false
            adapter = this@MusicActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> onRow(pos) }
        }
        listPanel.addView(list, LinearLayout.LayoutParams(match, 0, 1f))

        // Right: now playing
        nowPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(20), px(20), px(20), px(16))
            background = glass()
        }
        disc = DiscView(this)
        nowPanel.addView(disc, LinearLayout.LayoutParams(px(240), px(240)))
        eq = EqView(this).apply { color = accent }
        nowPanel.addView(eq, LinearLayout.LayoutParams(px(180), px(34)).apply { topMargin = px(10) })
        tTitle = text("Nothing playing", 22f, bold = true).apply { gravity = Gravity.CENTER }
        tArtist = text("Pick a song", 15f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER }
        nowPanel.addView(tTitle, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(8) })
        nowPanel.addView(tArtist, LinearLayout.LayoutParams(match, wrap))
        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) tPos.text = fmt(p) }
                override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
                override fun onStopTrackingTouch(s: SeekBar) {
                    dragging = false
                    MusicService.instance?.seek(s.progress)
                }
            })
        }
        nowPanel.addView(seek, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(14) })
        val times = LinearLayout(this)
        tPos = text("0:00", 12f, 0xCCFFFFFF.toInt())
        tDur = text("0:00", 12f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.END }
        times.addView(tPos, LinearLayout.LayoutParams(0, wrap, 1f))
        times.addView(tDur, LinearLayout.LayoutParams(0, wrap, 1f))
        nowPanel.addView(times, LinearLayout.LayoutParams(match, wrap).apply { leftMargin = px(16); rightMargin = px(16) })

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun round(size: Int, bg: Int, v: View, onClick: () -> Unit): View {
            v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
            v.isClickable = true
            v.setOnClickListener {
                it.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }.start()
                onClick()
            }
            controls.addView(v, LinearLayout.LayoutParams(px(size), px(size)).apply { leftMargin = px(8); rightMargin = px(8) })
            return v
        }
        bShuffle = text("⇄", 22f).apply { gravity = Gravity.CENTER }
        round(48, 0x00000000, bShuffle) { MusicService.instance?.toggleShuffle() }
        round(58, 0x33000000, ImageView(this).apply { setImageResource(R.drawable.ic_prev); setPadding(px(16), px(16), px(16), px(16)) }) {
            MusicService.instance?.prev()
        }
        bPlay = ImageView(this).apply { setImageResource(R.drawable.ic_play); setPadding(px(22), px(22), px(22), px(22)) }
        round(78, accent, bPlay) { playPause() }
        round(58, 0x33000000, ImageView(this).apply { setImageResource(R.drawable.ic_next); setPadding(px(16), px(16), px(16), px(16)) }) {
            MusicService.instance?.next()
        }
        bRepeat = text("↻", 22f).apply { gravity = Gravity.CENTER }
        round(48, 0x00000000, bRepeat) { MusicService.instance?.cycleRepeat() }
        nowPanel.addView(controls, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(14) })

        root.addView(nav, LinearLayout.LayoutParams(px(230), match))
        root.addView(listPanel, LinearLayout.LayoutParams(0, match, 1f).apply { leftMargin = px(14) })
        root.addView(nowPanel, LinearLayout.LayoutParams(px(430), match).apply { leftMargin = px(14) })
        setContentView(root)
        Themes.shadowText(root)

        // Slide panels in on open.
        listOf(nav, listPanel, nowPanel).forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = px(40).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(30L * i).setDuration(180)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    // ---- library views ----

    private fun refreshSources() {
        sourceBox.removeAllViews()
        val names = listOf<String?>(null) + MusicLibrary.sources.map { it.label }
        if (source != null && source !in names) source = null
        for (s in names) {
            val count = MusicLibrary.inSource(s).size
            val label = (s ?: "All") + "  ·  $count"
            sourceBox.addView(text(label, 15f).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(14), 0, px(10), 0)
                background = pill(s == source)
                isClickable = true
                setOnClickListener {
                    source = s
                    openGroup = null
                    refreshSources()
                    showList()
                }
            }, LinearLayout.LayoutParams(match, px(44)).apply { bottomMargin = px(6) })
        }
    }

    private fun selectTab(i: Int) {
        tab = i
        openGroup = null
        tabViews.forEachIndexed { j, t -> t.background = if (j == i) pill(true) else null }
        showList()
    }

    private fun showList() {
        val where = source ?: "All sources"
        val g = openGroup
        rows = when {
            g != null -> g.second.map { Row(it.title, it.artist.ifEmpty { it.folderName }, "♪", it, null) }
            tab == 1 -> MusicLibrary.folders(source).map { (path, t) ->
                Row(java.io.File(path).name, "${t.size} songs  ·  ${t.first().source}", "▤", null, path to t)
            }
            tab == 2 -> MusicLibrary.artists(source).map { (a, t) -> Row(a, "${t.size} songs", "♫", null, a to t) }
            else -> MusicLibrary.inSource(source).map { Row(it.title, it.artist.ifEmpty { it.folderName } + "  ·  " + it.source, "♪", it, null) }
        }
        listTitle.text = if (g != null) "‹  " + (if (tab == 1) java.io.File(g.first).name else g.first) else tabNames[tab]
        listTitle.setOnClickListener { if (openGroup != null) { openGroup = null; showList() } }
        listSub.text = when {
            MusicLibrary.scanning -> "Looking for music on all drives…"
            rows.isEmpty() -> "No music found on $where. Plug in a USB drive or tap Rescan."
            else -> "$where  ·  ${rows.size} " + (if (g != null || tab == 0) "songs" else if (tab == 1) "folders" else "artists")
        }
        adapter.notifyDataSetChanged()
        list.setSelection(0)
        list.alpha = 0f
        list.animate().alpha(1f).setDuration(220).start()
    }

    private fun onRow(pos: Int) {
        val r = rows.getOrNull(pos) ?: return
        if (r.group != null) {
            openGroup = r.group
            showList()
            return
        }
        val tracks = rows.mapNotNull { it.track }
        val start = tracks.indexOf(r.track)
        MusicService.with(this) { it.playQueue(tracks, start) }
    }

    private fun playPause() {
        val s = MusicService.instance
        if (s?.current != null) { s.toggle(); return }
        val all = MusicLibrary.inSource(source)
        if (all.isNotEmpty()) MusicService.with(this) { it.playQueue(all, 0) }
    }

    // ---- now playing ----

    private fun updateNow() {
        val s = MusicService.instance
        val t = s?.current
        val playing = s?.isPlaying == true
        tTitle.text = if (t == null) "Nothing playing" else s.metaTitle
        tArtist.text = if (t == null) "Pick a song" else s.metaArtist
        bPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        disc.setArt(s?.art)
        disc.spinning = playing
        eq.running = playing
        bShuffle.setTextColor(if (s?.shuffle == true) accent else 0x99FFFFFF.toInt())
        bRepeat.text = if (s?.repeat == 2) "↻¹" else "↻"
        bRepeat.setTextColor(if ((s?.repeat ?: 1) > 0) accent else 0x99FFFFFF.toInt())
        adapter.notifyDataSetChanged()
        updateProgress()
    }

    private fun updateProgress() {
        val s = MusicService.instance ?: return
        val d = s.duration
        seek.max = d.coerceAtLeast(1)
        if (!dragging) {
            seek.progress = s.position
            tPos.text = fmt(s.position)
        }
        tDur.text = fmt(d)
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    // ---- list rows ----

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = (convert as? LinearLayout) ?: LinearLayout(this@MusicActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(10), px(8), px(12), px(8))
                background = GradientDrawable().apply { cornerRadius = px(12).toFloat(); setColor(0x26000000) }
                addView(text("", 18f).apply {
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply { cornerRadius = px(10).toFloat(); setColor(0x33FFFFFF) }
                }, LinearLayout.LayoutParams(px(44), px(44)))
                val col = LinearLayout(this@MusicActivity).apply { orientation = LinearLayout.VERTICAL }
                col.addView(text("", 17f))
                col.addView(text("", 13f, 0xBBFFFFFF.toInt()))
                addView(col, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = px(14) })
            }
            val r = rows[p]
            val playing = r.track != null && r.track.path == MusicService.instance?.current?.path
            (v.getChildAt(0) as TextView).apply {
                text = if (playing) "▶" else r.glyph
                setTextColor(if (playing) accent else Color.WHITE)
            }
            val col = v.getChildAt(1) as LinearLayout
            (col.getChildAt(0) as TextView).apply {
                text = r.title
                setTextColor(if (playing) accent else Color.WHITE)
            }
            (col.getChildAt(1) as TextView).text = r.sub
            Themes.shadowText(v)
            return v
        }
    }
}

/** Album art on a spinning vinyl record; the record turns only while music plays. */
class DiscView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var art: Bitmap? = null
    private var angle = 0f
    private var last = 0L
    private val vinyl = Paint(Paint.ANTI_ALIAS_FLAG)
    private val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x22FFFFFF; strokeWidth = 1.5f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B1620.toInt() }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val note = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textAlign = Paint.Align.CENTER }

    var spinning = false
        set(v) {
            if (field == v) return
            field = v
            last = System.nanoTime()
            if (v) postInvalidateOnAnimation()
        }

    fun setArt(b: Bitmap?) {
        if (art === b) return
        art = b
        label.shader = null
        animate().scaleX(0.92f).scaleY(0.92f).setDuration(120).withEndAction {
            animate().scaleX(1f).scaleY(1f).setDuration(220).start()
        }.start()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 4f
        if (spinning) {
            val now = System.nanoTime()
            angle = (angle + (now - last) / 1e9f * 40f) % 360f
            last = now
        }
        vinyl.shader = RadialGradient(cx, cy, r, intArrayOf(0xFF1C1C20.toInt(), 0xFF08080A.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, vinyl)
        var gr = r * 0.95f
        while (gr > r * 0.55f) { c.drawCircle(cx, cy, gr, groove); gr -= r * 0.045f }

        c.save()
        c.rotate(angle, cx, cy)
        val lr = r * 0.5f
        val b = art
        if (b != null) {
            if (label.shader == null) {
                val s = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val scale = 2 * lr / minOf(b.width, b.height)
                s.setLocalMatrix(Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(cx - b.width * scale / 2, cy - b.height * scale / 2)
                })
                label.shader = s
            }
            c.drawCircle(cx, cy, lr, label)
        } else {
            label.shader = null
            label.color = (parent as? View)?.let { 0xFF2A8A80.toInt() } ?: 0xFF2A8A80.toInt()
            c.drawCircle(cx, cy, lr, label)
            note.textSize = lr * 0.9f
            c.drawText("♪", cx, cy + lr * 0.32f, note)
        }
        c.restore()
        c.drawCircle(cx, cy, r * 0.05f, hole)
        // Fixed light reflection across the record.
        shine.shader = android.graphics.LinearGradient(cx - r, cy - r, cx + r, cy + r,
            intArrayOf(0x00FFFFFF, 0x22FFFFFF, 0x00FFFFFF), floatArrayOf(0.3f, 0.5f, 0.7f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, shine)
        if (spinning) postInvalidateOnAnimation()
    }
}

/** Bouncing equalizer bars while music plays; they settle down when paused. */
class EqView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var color = 0xFF1FD1B8.toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val heights = FloatArray(14) { 0.15f }
    private val r = RectF()
    var running = false
        set(v) {
            field = v
            postInvalidateOnAnimation()
        }

    override fun onDraw(c: Canvas) {
        val n = heights.size
        val gap = width / (n * 3f)
        val bw = (width - gap * (n - 1)) / n
        val t = System.nanoTime() / 1e9
        var moving = false
        for (i in 0 until n) {
            val target = if (running) (0.25f + 0.75f * (0.5f + 0.5f * sin(t * (5.0 + i * 0.9) + i * 1.7)).toFloat() *
                (0.6f + 0.4f * sin(t * 2.3 + i).toFloat().let { it * it })) else 0.12f
            heights[i] += (target - heights[i]) * 0.25f
            if (kotlin.math.abs(target - heights[i]) > 0.01f) moving = true
            val h = height * heights[i]
            val x = i * (bw + gap)
            r.set(x, height - h, x + bw, height.toFloat())
            p.color = color
            p.alpha = (140 + 115 * heights[i]).toInt().coerceAtMost(255)
            c.drawRoundRect(r, bw / 2, bw / 2, p)
        }
        if (running || moving) postInvalidateOnAnimation()
    }
}
