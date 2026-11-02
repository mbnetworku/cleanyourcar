package com.carlauncher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File
import java.util.concurrent.Executors

/** Finds video files on every drive, the same way the music library does, kept per source. */
object VideoLibrary {
    val extensions = setOf("mp4", "m4v", "mkv", "webm", "3gp", "3g2", "avi", "mov", "ts", "mpg", "mpeg", "flv", "wmv", "vob")
    @Volatile var videos: List<Track> = emptyList()
        private set
    @Volatile var sources: List<String> = emptyList()
        private set
    @Volatile var scanning = false
        private set

    fun scan(ctx: Context, done: () -> Unit) {
        if (scanning) return
        scanning = true
        val app = ctx.applicationContext
        Thread {
            val out = ArrayList<Track>()
            val seen = HashSet<String>()
            val srcs = MusicLibrary.findSources(app)
            for (s in srcs) walk(s.root, 0, s.label, out, seen)
            videos = out.sortedBy { it.name.lowercase() }
            sources = srcs.map { it.label }.filter { l -> out.any { it.source == l } }
            scanning = false
            Handler(Looper.getMainLooper()).post(done)
        }.start()
    }

    private fun walk(dir: File, depth: Int, source: String, out: MutableList<Track>, seen: MutableSet<String>) {
        if (depth > 12) return
        val canon = try { dir.canonicalPath } catch (e: Exception) { return }
        if (!seen.add(canon)) return
        val kids = dir.listFiles() ?: return
        if (kids.any { it.name == ".nomedia" }) return
        for (f in kids) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                if (depth == 0 && (f.name == "Android" || f.name == "LOST.DIR")) continue
                walk(f, depth + 1, source, out, seen)
            } else if (f.extension.lowercase() in extensions && f.length() > 64 * 1024) {
                out.add(Track(f.path, source))
            }
        }
    }
}

/** Video library (grid with thumbnails, by source and folder) and a full-screen player with glass controls. */
class VideoActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private var dn = 1f
    private fun px(v: Int) = (v * dn).toInt()
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private var accent = 0

    private lateinit var library: LinearLayout
    private lateinit var sourceRow: LinearLayout
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var grid: GridView
    private val adapter = GridAdapter()
    private var items: List<Any> = emptyList() // Track or folder Pair
    private var source: String? = null
    private var byFolder = false
    private var openFolder: Pair<String, List<Track>>? = null

    private lateinit var playerLayer: FrameLayout
    private lateinit var video: VideoView
    private lateinit var controls: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var vTitle: TextView
    private lateinit var seek: SeekBar
    private lateinit var tPos: TextView
    private lateinit var tDur: TextView
    private lateinit var bPlay: ImageView
    private var playlist: List<Track> = emptyList()
    private var playIndex = 0
    private var dragging = false

    private val thumbs = LruCache<String, Bitmap>(60)
    private val thumbPool = Executors.newFixedThreadPool(2)
    private val hide = Runnable { showControls(false) }
    private val tick = object : Runnable {
        override fun run() {
            if (playerLayer.visibility == View.VISIBLE) {
                val d = video.duration.coerceAtLeast(1)
                seek.max = d
                if (!dragging) { seek.progress = video.currentPosition; tPos.text = fmt(video.currentPosition) }
                tDur.text = fmt(d)
                bPlay.setImageResource(if (video.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            }
            ui.postDelayed(this, 500)
        }
    }
    private val mountReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { ui.postDelayed({ rescan() }, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        dn = resources.displayMetrics.density
        Themes.update(prefs)
        accent = Themes.accent(prefs)
        build()
        if (Build.VERSION.SDK_INT >= 23) {
            val perm = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_VIDEO" else android.Manifest.permission.READ_EXTERNAL_STORAGE
            if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(perm), 4)
        }
        if (VideoLibrary.videos.isEmpty()) rescan() else showGrid()
        playFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        playFromIntent(intent)
    }

    /** Files app hands over a video (and its folder's other videos) to play straight away. */
    private fun playFromIntent(i: Intent?) {
        val paths = i?.getStringArrayListExtra("playlist") ?: return
        if (paths.isEmpty()) return
        playlist = paths.map { Track(it, "") }
        playAt(i.getIntExtra("start", 0))
        i.removeExtra("playlist")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        rescan()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(mountReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED); addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        })
        ui.post(tick)
    }

    override fun onPause() {
        try { unregisterReceiver(mountReceiver) } catch (e: Exception) { }
        ui.removeCallbacks(tick)
        if (video.isPlaying) video.pause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        @Suppress("DEPRECATION")
        if (hasFocus) window.decorView.systemUiVisibility = MainActivity.immersiveFlags()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            playerLayer.visibility == View.VISIBLE -> closePlayer()
            openFolder != null -> { openFolder = null; showGrid() }
            else -> finish()
        }
    }

    private fun text(t: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(dn * 3, 0f, dn, 0xCC000000.toInt())
    }

    private fun pill(sel: Boolean) = GradientDrawable().apply {
        cornerRadius = px(16).toFloat()
        setColor(if (sel) accent else 0x40000000)
    }

    private fun chip(t: String, sel: Boolean, onClick: () -> Unit) = text(t, 15f).apply {
        gravity = Gravity.CENTER
        setPadding(px(18), 0, px(18), 0)
        background = pill(sel)
        isClickable = true
        setOnClickListener { onClick() }
    }

    // ---- layout ----

    private fun build() {
        val root = FrameLayout(this).apply { background = Themes.backgroundDrawable(this@VideoActivity, prefs) }

        library = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(18), px(22), px(12))
            background = Themes.glass(Themes.glassPct(prefs), accent, px(20).toFloat(), px(1))
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        title = text("Videos", 26f, bold = true)
        head.addView(title, LinearLayout.LayoutParams(0, wrap, 1f))
        head.addView(chip("↻  Rescan", false) { rescan() }, LinearLayout.LayoutParams(wrap, px(44)).apply { leftMargin = px(10) })
        head.addView(chip("Home", false) { finish() }, LinearLayout.LayoutParams(wrap, px(44)).apply { leftMargin = px(10) })
        library.addView(head)
        sub = text("", 13f, 0xCCFFFFFF.toInt()).apply { setPadding(0, px(2), 0, px(10)) }
        library.addView(sub)
        sourceRow = LinearLayout(this)
        library.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(sourceRow)
        }, LinearLayout.LayoutParams(match, px(44)))
        grid = GridView(this).apply {
            numColumns = 4
            horizontalSpacing = px(14)
            verticalSpacing = px(14)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(0, px(14), 0, 0)
            selector = GradientDrawable().apply { cornerRadius = px(14).toFloat(); setColor(0x33FFFFFF) }
            adapter = this@VideoActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> onItem(pos) }
        }
        library.addView(grid, LinearLayout.LayoutParams(match, 0, 1f))
        root.addView(library, FrameLayout.LayoutParams(match, match).apply { setMargins(px(16), px(16), px(16), px(16)) })

        // Player layer
        playerLayer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        video = VideoView(this)
        playerLayer.addView(video, FrameLayout.LayoutParams(match, match, Gravity.CENTER))
        playerLayer.setOnClickListener { showControls(controls.visibility != View.VISIBLE) }
        video.setOnTouchListener { _, e -> if (e.action == android.view.MotionEvent.ACTION_UP) showControls(controls.visibility != View.VISIBLE); true }
        video.setOnPreparedListener { video.start(); showControls(true) }
        video.setOnCompletionListener { if (playIndex < playlist.lastIndex) playAt(playIndex + 1) else closePlayer() }
        video.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "Can't play this video format on this device", Toast.LENGTH_SHORT).show()
            if (playIndex < playlist.lastIndex) playAt(playIndex + 1) else closePlayer()
            true
        }

        topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(20), px(14), px(20), px(14))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xAA000000.toInt(), 0x00000000))
        }
        topBar.addView(chip("‹  Back", false) { closePlayer() }, LinearLayout.LayoutParams(wrap, px(44)))
        vTitle = text("", 20f, bold = true).apply { setPadding(px(16), 0, 0, 0) }
        topBar.addView(vTitle, LinearLayout.LayoutParams(0, wrap, 1f))
        playerLayer.addView(topBar, FrameLayout.LayoutParams(match, wrap, Gravity.TOP))

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(14), px(24), px(14))
            background = Themes.glass(30, accent, px(22).toFloat(), px(1))
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        tPos = text("0:00", 13f)
        tDur = text("0:00", 13f)
        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) tPos.text = fmt(p) }
                override fun onStartTrackingTouch(s: SeekBar) { dragging = true; ui.removeCallbacks(hide) }
                override fun onStopTrackingTouch(s: SeekBar) { dragging = false; video.seekTo(s.progress); bumpHide() }
            })
        }
        bar.addView(tPos)
        bar.addView(seek, LinearLayout.LayoutParams(0, wrap, 1f))
        bar.addView(tDur)
        controls.addView(bar)
        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun round(size: Int, bg: Int, v: View, onClick: () -> Unit) {
            v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
            v.isClickable = true
            v.setOnClickListener { onClick(); bumpHide() }
            buttons.addView(v, LinearLayout.LayoutParams(px(size), px(size)).apply { leftMargin = px(10); rightMargin = px(10) })
        }
        round(54, 0x40000000, ImageView(this).apply { setImageResource(R.drawable.ic_prev); setPadding(px(15), px(15), px(15), px(15)) }) {
            if (video.currentPosition > 3000 || playIndex == 0) video.seekTo(0) else playAt(playIndex - 1)
        }
        round(54, 0x40000000, text("-10", 15f, bold = true).apply { gravity = Gravity.CENTER }) {
            video.seekTo((video.currentPosition - 10000).coerceAtLeast(0))
        }
        bPlay = ImageView(this).apply { setImageResource(R.drawable.ic_pause); setPadding(px(20), px(20), px(20), px(20)) }
        round(70, accent, bPlay) { if (video.isPlaying) video.pause() else video.start() }
        round(54, 0x40000000, text("+10", 15f, bold = true).apply { gravity = Gravity.CENTER }) {
            video.seekTo((video.currentPosition + 10000).coerceAtMost(video.duration))
        }
        round(54, 0x40000000, ImageView(this).apply { setImageResource(R.drawable.ic_next); setPadding(px(15), px(15), px(15), px(15)) }) {
            if (playIndex < playlist.lastIndex) playAt(playIndex + 1)
        }
        controls.addView(buttons, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(6) })
        playerLayer.addView(controls, FrameLayout.LayoutParams(px(760), wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(24) })

        root.addView(playerLayer, FrameLayout.LayoutParams(match, match))
        setContentView(root)
        library.alpha = 0f
        library.translationY = px(40).toFloat()
        library.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(DecelerateInterpolator()).start()
    }

    // ---- library ----

    private fun rescan() {
        sub.text = "Looking for videos on all drives…"
        VideoLibrary.scan(this) { showGrid() }
    }

    private fun inSource() = VideoLibrary.videos.filter { source == null || it.source == source }

    private fun showGrid() {
        sourceRow.removeAllViews()
        if (source != null && source !in VideoLibrary.sources) source = null
        val chips = listOf<String?>(null) + VideoLibrary.sources
        for (s in chips) {
            val n = VideoLibrary.videos.count { s == null || it.source == s }
            sourceRow.addView(chip("${s ?: "All"}  ·  $n", s == source) { source = s; openFolder = null; showGrid() },
                LinearLayout.LayoutParams(wrap, match).apply { rightMargin = px(8) })
        }
        sourceRow.addView(View(this), LinearLayout.LayoutParams(px(20), 1))
        sourceRow.addView(chip("All videos", !byFolder) { byFolder = false; openFolder = null; showGrid() }, LinearLayout.LayoutParams(wrap, match).apply { rightMargin = px(8) })
        sourceRow.addView(chip("Folders", byFolder) { byFolder = true; openFolder = null; showGrid() }, LinearLayout.LayoutParams(wrap, match))

        val f = openFolder
        items = when {
            f != null -> f.second
            byFolder -> inSource().groupBy { it.folder }.toList().sortedBy { File(it.first).name.lowercase() }
            else -> inSource()
        }
        title.text = if (f != null) "‹  " + File(f.first).name else "Videos"
        title.setOnClickListener { if (openFolder != null) { openFolder = null; showGrid() } }
        sub.text = when {
            VideoLibrary.scanning -> "Looking for videos on all drives…"
            items.isEmpty() -> "No videos found on ${source ?: "any drive"}. Plug in a USB drive or tap Rescan."
            else -> "${source ?: "All sources"}  ·  ${items.size} " + (if (byFolder && f == null) "folders" else "videos")
        }
        adapter.notifyDataSetChanged()
        grid.alpha = 0f
        grid.animate().alpha(1f).setDuration(220).start()
    }

    @Suppress("UNCHECKED_CAST")
    private fun onItem(pos: Int) {
        when (val it = items[pos]) {
            is Track -> {
                playlist = items.filterIsInstance<Track>()
                playAt(playlist.indexOf(it))
            }
            is Pair<*, *> -> { openFolder = it as Pair<String, List<Track>>; showGrid() }
        }
    }

    // ---- player ----

    private fun playAt(i: Int) {
        playIndex = i.coerceIn(0, playlist.lastIndex)
        val t = playlist[playIndex]
        vTitle.text = t.name
        // Pause our music while a video plays.
        MusicService.instance?.let { if (it.isPlaying) it.pause() }
        if (playerLayer.visibility != View.VISIBLE) {
            playerLayer.visibility = View.VISIBLE
            playerLayer.alpha = 0f
            playerLayer.animate().alpha(1f).setDuration(250).start()
        }
        video.setVideoPath(t.path)
    }

    private fun closePlayer() {
        video.stopPlayback()
        playerLayer.animate().alpha(0f).setDuration(200).withEndAction { playerLayer.visibility = View.GONE }.start()
        ui.removeCallbacks(hide)
    }

    private fun showControls(show: Boolean) {
        for (v in listOf(controls, topBar)) {
            if (show) {
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).translationY(0f).setDuration(200).start()
            } else {
                v.animate().alpha(0f).translationY(if (v === controls) px(30).toFloat() else -px(30).toFloat()).setDuration(250)
                    .withEndAction { v.visibility = View.GONE }.start()
            }
        }
        if (show) bumpHide()
    }

    private fun bumpHide() {
        ui.removeCallbacks(hide)
        ui.postDelayed(hide, 4000)
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // ---- grid tiles ----

    private inner class GridAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = (convert as? LinearLayout) ?: LinearLayout(this@VideoActivity).apply {
                orientation = LinearLayout.VERTICAL
                val frame = FrameLayout(this@VideoActivity).apply {
                    background = GradientDrawable().apply { cornerRadius = px(14).toFloat(); setColor(0x55000000) }
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, view.width, view.height, px(14).toFloat())
                    }
                    clipToOutline = true
                }
                frame.addView(ImageView(this@VideoActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(match, match))
                frame.addView(text("▶", 26f).apply {
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x88000000.toInt()) }
                }, FrameLayout.LayoutParams(px(52), px(52), Gravity.CENTER))
                addView(frame, LinearLayout.LayoutParams(match, px(150)))
                addView(text("", 15f).apply { setPadding(px(4), px(6), px(4), 0) })
                addView(text("", 12f, 0xBBFFFFFF.toInt()).apply { setPadding(px(4), 0, px(4), px(4)) })
            }
            val frame = v.getChildAt(0) as FrameLayout
            val img = frame.getChildAt(0) as ImageView
            val badge = frame.getChildAt(1) as TextView
            val t1 = v.getChildAt(1) as TextView
            val t2 = v.getChildAt(2) as TextView
            when (val it = items[p]) {
                is Track -> {
                    t1.text = it.name
                    t2.text = it.folderName + "  ·  " + it.source
                    badge.text = "▶"
                    loadThumb(it.path, img)
                }
                is Pair<*, *> -> {
                    val list = it.second as List<*>
                    t1.text = File(it.first as String).name
                    t2.text = "${list.size} videos  ·  ${(list.first() as Track).source}"
                    badge.text = "▤"
                    loadThumb((list.first() as Track).path, img)
                }
            }
            return v
        }
    }

    private fun loadThumb(path: String, img: ImageView) {
        img.tag = path
        thumbs.get(path)?.let { img.setImageBitmap(it); return }
        img.setImageDrawable(null)
        thumbPool.execute {
            @Suppress("DEPRECATION")
            val b = try { ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND) } catch (e: Throwable) { null }
            if (b != null) {
                thumbs.put(path, b)
                ui.post { if (img.tag == path) { img.setImageBitmap(b); img.alpha = 0f; img.animate().alpha(1f).setDuration(200).start() } }
            }
        }
    }
}
