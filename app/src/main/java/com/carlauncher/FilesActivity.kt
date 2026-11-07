package com.carlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.webkit.MimeTypeMap
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File manager. Drives (internal, SD, each USB) are tabs across the top. Tap opens; hold starts selecting, after
 * which taps add or remove files. The toolbar then offers Rename, Copy, Move, Delete, Info, Install (for APKs)
 * and Select all. Copy and Move are finished with "Paste here" in any folder or drive.
 */
class FilesActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private var dn = 1f
    private fun px(v: Int) = (v * dn).toInt()
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private var accent = 0

    private lateinit var driveRow: LinearLayout
    private lateinit var pathText: TextView
    private lateinit var infoText: TextView
    private lateinit var toolbar: LinearLayout
    private lateinit var pasteBar: LinearLayout
    private lateinit var pasteText: TextView
    private lateinit var list: ListView
    private val adapter = FileAdapter()

    private var sources: List<MusicSource> = emptyList()
    private var root: File? = null
    private var dir: File? = null
    private var items: List<File> = emptyList()
    private val selected = LinkedHashSet<String>()
    /** Files waiting to be pasted, and whether they are moved (true) or copied. */
    private var clip: List<File> = emptyList()
    private var clipMove = false
    private var busy = false
    private val dateFmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

    private val mountReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val added = i.action == Intent.ACTION_MEDIA_MOUNTED
            Toast.makeText(this@FilesActivity, if (added) "Drive connected" else "Drive removed", Toast.LENGTH_SHORT).show()
            ui.postDelayed({
                loadDrives()
                if (dir?.exists() != true) openDrive(sources.firstOrNull())
            }, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        dn = resources.displayMetrics.density
        Themes.update(prefs)
        accent = Themes.accent(prefs)
        Storage.ensure(this, "your files")
        // Android 7+ rejects file:// links to other apps (images, PDFs, APKs) without this; there is no AndroidX FileProvider here.
        android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.Builder().build())
        build()
        loadDrives()
        openDrive(sources.firstOrNull())
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        loadDrives()
        show(dir)
    }

    override fun onResume() {
        super.onResume()
        if (Storage.hasAccess(this) && items.isEmpty()) { loadDrives(); show(dir ?: sources.firstOrNull()?.root.also { root = it }) }
        registerReceiver(mountReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED); addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        })
    }

    override fun onPause() {
        try { unregisterReceiver(mountReceiver) } catch (e: Exception) { }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        @Suppress("DEPRECATION")
        if (hasFocus) window.decorView.systemUiVisibility = MainActivity.immersiveFlags()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val d = dir
        when {
            selected.isNotEmpty() -> clearSelection()
            d != null && root != null && d.path != root!!.path -> show(d.parentFile)
            else -> finish()
        }
    }

    // ---- small UI helpers ----

    private fun text(t: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.MIDDLE
        setShadowLayer(dn * 3, 0f, dn, 0xCC000000.toInt())
    }

    private fun pill(sel: Boolean, r: Int = 22, color: Int = accent) = GradientDrawable().apply {
        cornerRadius = px(r).toFloat()
        setColor(if (sel) color else 0x40000000)
    }

    private fun chip(t: String, sel: Boolean = false, color: Int = accent, onClick: () -> Unit) = text(t, 15f, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(px(18), 0, px(18), 0)
        background = pill(sel, 22, color)
        isClickable = true
        setOnClickListener {
            it.animate().scaleX(0.93f).scaleY(0.93f).setDuration(60).withEndAction { it.animate().scaleX(1f).scaleY(1f).setDuration(100).start() }.start()
            onClick()
        }
    }

    private fun chipParams() = LinearLayout.LayoutParams(wrap, px(46)).apply { rightMargin = px(8) }

    private fun size(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(b / 1073741824.0)
        b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
        b >= 1024 -> "${b / 1024} KB"
        else -> "$b B"
    }

    // ---- layout ----

    private fun build() {
        val rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(14), px(16), px(14))
            background = Themes.backgroundDrawable(this@FilesActivity, prefs)
        }

        // Top: title, drive tabs, home
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(18), px(12), px(14), px(12))
            background = Themes.glass(Themes.glassPct(prefs), accent, px(20).toFloat(), px(1))
        }
        top.addView(text("Files", 26f, bold = true).apply { setPadding(0, 0, px(20), 0) })
        driveRow = LinearLayout(this)
        top.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(driveRow) }, LinearLayout.LayoutParams(0, wrap, 1f))
        top.addView(chip("Home") { finish() }, LinearLayout.LayoutParams(wrap, px(46)))
        rootView.addView(top, LinearLayout.LayoutParams(match, wrap))

        // Main panel
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(14), px(20), px(10))
            background = Themes.glass(Themes.glassPct(prefs), accent, px(20).toFloat(), px(1))
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(chip("‹  Up") { onBackPressed() }.also { up -> dropTarget(up) { dir?.takeIf { it.path != root?.path }?.parentFile } }, chipParams())
        pathText = text("", 18f, bold = true).apply { setPadding(px(8), 0, px(10), 0) }
        bar.addView(pathText, LinearLayout.LayoutParams(0, wrap, 1f))
        bar.addView(chip("Select") { if (items.isNotEmpty()) toggle(items.first()) }, chipParams())
        bar.addView(chip("New folder") { newFolder() }, LinearLayout.LayoutParams(wrap, px(46)))
        panel.addView(bar)

        // Selection toolbar, shown while files are selected
        toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(px(10), px(8), px(10), px(8))
            background = GradientDrawable().apply { cornerRadius = px(18).toFloat(); setColor(0x66000000) }
        }
        panel.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(toolbar) },
            LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(10) })

        // Paste bar, shown while files wait to be copied or moved
        pasteBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(px(16), px(8), px(10), px(8))
            background = GradientDrawable().apply { cornerRadius = px(18).toFloat(); setColor((0x55 shl 24) or (accent and 0xFFFFFF)) }
        }
        pasteText = text("", 15f, bold = true)
        pasteBar.addView(pasteText, LinearLayout.LayoutParams(0, wrap, 1f))
        pasteBar.addView(chip("Paste here", true) { paste() }, chipParams())
        pasteBar.addView(chip("Cancel") { clip = emptyList(); refreshBars() }, LinearLayout.LayoutParams(wrap, px(46)))
        panel.addView(pasteBar, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(10) })

        infoText = text("", 13f, 0xCCFFFFFF.toInt()).apply { setPadding(px(4), px(10), 0, px(6)) }
        panel.addView(infoText)
        list = ListView(this).apply {
            divider = null
            dividerHeight = px(4)
            isVerticalScrollBarEnabled = false
            selector = GradientDrawable().apply { cornerRadius = px(12).toFloat(); setColor(0x33FFFFFF) }
            adapter = this@FilesActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> if (selected.isNotEmpty()) toggle(items[pos]) else open(items[pos]) }
            // Hold: select it and pick it up; drag onto a folder or Up to move, or just let go to keep it selected.
            setOnItemLongClickListener { _, row, pos, _ -> startDrag(row, items[pos]); true }
        }
        panel.addView(list, LinearLayout.LayoutParams(match, 0, 1f))
        rootView.addView(panel, LinearLayout.LayoutParams(match, 0, 1f).apply { topMargin = px(12) })
        setContentView(rootView)

        listOf(top, panel).forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = px(30).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(30L * i).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    // ---- drives ----

    private fun loadDrives() {
        // Empty card slots report 0 bytes; leave them out.
        sources = MusicLibrary.findSources(this).filter { s -> try { StatFs(s.root.path).blockCountLong > 0 } catch (e: Exception) { false } }
        driveRow.removeAllViews()
        for (s in sources) {
            val st = try { StatFs(s.root.path) } catch (e: Exception) { null }
            val free = st?.let { it.availableBlocksLong * it.blockSizeLong } ?: 0L
            val total = st?.let { it.blockCountLong * it.blockSizeLong } ?: 0L
            val sel = root?.path == s.root.path
            val v = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(18), px(4), px(18), px(4))
                background = pill(sel, 18)
                isClickable = true
                setOnClickListener { openDrive(s) }
            }
            v.addView(text(s.label, 16f, bold = true))
            v.addView(text("${size(free)} free of ${size(total)}", 12f, 0xDDFFFFFF.toInt()))
            driveRow.addView(v, LinearLayout.LayoutParams(wrap, px(54)).apply { rightMargin = px(8) })
        }
        if (sources.isEmpty()) driveRow.addView(text("No drives found", 14f, 0xAAFFFFFF.toInt()))
    }

    private fun openDrive(s: MusicSource?) {
        root = s?.root
        selected.clear()
        loadDrives()
        show(s?.root)
    }

    private fun show(d: File?) {
        dir = d
        selected.clear()
        if (d == null) { items = emptyList(); adapter.notifyDataSetChanged(); refreshBars(); return }
        val kids = d.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
        items = kids.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        val src = sources.firstOrNull { it.root.path == root?.path }
        val rel = d.path.removePrefix(root?.path ?: "").trim('/')
        pathText.text = (src?.label ?: "") + (if (rel.isEmpty()) "" else "  ›  " + rel.replace("/", "  ›  "))
        refreshBars()
        adapter.notifyDataSetChanged()
        list.setSelection(0)
        list.alpha = 0f
        list.animate().alpha(1f).setDuration(180).start()
    }

    // ---- selection ----

    private fun toggle(f: File) {
        if (!selected.remove(f.path)) selected.add(f.path)
        refreshBars()
        adapter.notifyDataSetChanged()
    }

    private fun clearSelection() {
        selected.clear()
        refreshBars()
        adapter.notifyDataSetChanged()
    }

    private fun picked(): List<File> = selected.map { File(it) }

    private fun refreshBars() {
        val n = selected.size
        val folders = items.count { it.isDirectory }
        infoText.text = when {
            busy -> "Working…"
            n > 0 -> "$n selected  ·  tap more files to add or remove them"
            dir?.canRead() == false -> "This folder can't be read"
            else -> "$folders folders  ·  ${items.size - folders} files  ·  hold a file to select it"
        }
        toolbar.removeAllViews()
        toolbar.visibility = if (n > 0) View.VISIBLE else View.GONE
        if (n > 0) {
            toolbar.addView(text("$n", 18f, accent, bold = true).apply { setPadding(px(8), 0, px(14), 0) })
            toolbar.addView(chip("Rename") { rename(picked()) }, chipParams())
            toolbar.addView(chip("Copy") { startClip(false) }, chipParams())
            toolbar.addView(chip("Move") { startClip(true) }, chipParams())
            toolbar.addView(chip("Delete", true, 0xFFFF453A.toInt()) { delete(picked()) }, chipParams())
            toolbar.addView(chip("Info") { info(picked()) }, chipParams())
            if (n == 1 && picked().first().extension.equals("apk", true)) toolbar.addView(chip("Install", true, 0xFF30D158.toInt()) { install(picked().first()) }, chipParams())
            if (n == 1 && picked().first().extension.equals("zip", true)) toolbar.addView(chip("Extract", true) { extract(picked().first()) }, chipParams())
            toolbar.addView(chip("Zip") { zip(picked()) }, chipParams())
            toolbar.addView(chip(if (n == items.size) "Select none" else "Select all") {
                if (n == items.size) selected.clear() else items.forEach { selected.add(it.path) }
                refreshBars(); adapter.notifyDataSetChanged()
            }, chipParams())
            toolbar.addView(chip("✕") { clearSelection() }, LinearLayout.LayoutParams(px(46), px(46)))
        }
        pasteBar.visibility = if (clip.isNotEmpty()) View.VISIBLE else View.GONE
        pasteText.text = "${clip.size} item${if (clip.size == 1) "" else "s"} to ${if (clipMove) "move" else "copy"}. Open the folder you want, then tap Paste here."
    }

    // ---- opening and installing ----

    private fun kind(f: File): String {
        val e = f.extension.lowercase()
        return when {
            f.isDirectory -> "folder"
            e in MusicLibrary.extensions && e != "mp4" && e != "webm" && e != "3gp" -> "audio"
            e in VideoLibrary.extensions -> "video"
            e in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp") -> "image"
            e == "apk" -> "apk"
            e in setOf("txt", "log", "srt", "json", "xml", "csv", "md") -> "text"
            e == "pdf" -> "pdf"
            e in setOf("zip", "rar", "7z", "tar", "gz") -> "archive"
            else -> "file"
        }
    }

    private fun glyph(k: String) = when (k) {
        "folder" -> "▤"; "audio" -> "♪"; "video" -> "▶"; "image" -> "▣"; "apk" -> "A"
        "text" -> "≡"; "pdf" -> "P"; "archive" -> "Z"; else -> "•"
    }

    private fun tint(k: String) = when (k) {
        "folder" -> 0xFF0A84FF.toInt(); "audio" -> 0xFFFF2D55.toInt(); "video" -> 0xFFFF453A.toInt(); "image" -> 0xFF30D158.toInt()
        "apk" -> 0xFF34C759.toInt(); "pdf" -> 0xFFFF9F0A.toInt(); "archive" -> 0xFFAF52DE.toInt(); else -> 0xFF8E8E93.toInt()
    }

    private fun open(f: File) {
        when (kind(f)) {
            "folder" -> show(f)
            "apk" -> install(f)
            "archive" -> if (f.extension.equals("zip", true)) {
                AlertDialog.Builder(this).setTitle(f.name).setMessage("Extract this ZIP into a folder here?")
                    .setPositiveButton("Extract") { _, _ -> extract(f) }.setNegativeButton("Cancel", null).show()
            } else toast("${f.extension.uppercase()} files can't be opened here. ZIP files can be extracted.")
            "video" -> {
                val vids = items.filter { kind(it) == "video" }
                startActivity(Intent(this, VideoActivity::class.java)
                    .putStringArrayListExtra("playlist", ArrayList(vids.map { it.path }))
                    .putExtra("start", vids.indexOfFirst { it.path == f.path }.coerceAtLeast(0)))
            }
            "audio" -> {
                val src = sources.firstOrNull { it.root.path == root?.path }?.label ?: ""
                val songs = items.filter { kind(it) == "audio" }.map { Track(it.path, src) }
                MusicService.with(this) { it.playQueue(songs, songs.indexOfFirst { t -> t.path == f.path }.coerceAtLeast(0)) }
                startActivity(Intent(this, MusicActivity::class.java))
            }
            else -> {
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(f), mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Toast.makeText(this, "No app on this device can open ${f.extension.uppercase()} files", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Opens Android's installer for an APK; if installs from files are blocked, offers the setting that allows them. */
    private fun install(f: File) {
        @Suppress("DEPRECATION")
        val allowed = if (Build.VERSION.SDK_INT >= 26) packageManager.canRequestPackageInstalls()
        else Settings.Secure.getInt(contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
        if (!allowed) {
            AlertDialog.Builder(this).setTitle("Allow installing apps")
                .setMessage("This unit blocks installing apps from files. Turn on \"Unknown sources\" on the next screen, then come back and tap the APK again.")
                .setPositiveButton("Open setting") { _, _ ->
                    val i = if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                    else Intent(Settings.ACTION_SECURITY_SETTINGS)
                    try { startActivity(i) } catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                }
                .setNegativeButton("Cancel", null).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "Android's installer couldn't open this file", Toast.LENGTH_LONG).show()
        }
    }

    // ---- actions ----

    private fun ask(title: String, start: String, hint: String? = null, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(start)
            setSingleLine()
            setSelection(start.substringBeforeLast('.').length)
        }
        AlertDialog.Builder(this).setTitle(title).setMessage(hint).setView(input)
            .setPositiveButton("OK") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() && !it.contains('/') }?.let(onOk) }
            .setNegativeButton("Cancel", null).show()
    }

    /** One file: a new name. Several: one base name, numbered "Holiday (1).jpg", "Holiday (2).jpg"… keeping each extension. */
    private fun rename(files: List<File>) {
        if (files.size == 1) {
            val f = files.first()
            ask("Rename", f.name) { n ->
                if (f.renameTo(File(f.parentFile, n))) show(dir) else toast("This drive doesn't allow renaming here")
            }
            return
        }
        ask("Rename ${files.size} items", "New name", "They will be numbered: New name (1), New name (2)…") { base ->
            var ok = 0
            files.forEachIndexed { i, f ->
                val ext = if (f.isDirectory || f.extension.isEmpty()) "" else "." + f.extension
                var target = File(f.parentFile, "$base (${i + 1})$ext")
                var k = i + 1
                while (target.exists()) { k += files.size; target = File(f.parentFile, "$base ($k)$ext") }
                if (f.renameTo(target)) ok++
            }
            toast("Renamed $ok of ${files.size}")
            show(dir)
        }
    }

    private fun startClip(move: Boolean) {
        clip = picked()
        clipMove = move
        clearSelection()
        toast("${clip.size} ready to ${if (move) "move" else "copy"}. Go to the folder you want and tap Paste here.")
    }

    private fun paste() {
        val target = dir ?: return
        if (busy || clip.isEmpty()) return
        val files = clip
        val move = clipMove
        if (files.any { target.path == it.path || target.path.startsWith(it.path + "/") }) {
            toast("A folder can't be put inside itself"); return
        }
        busy = true
        refreshBars()
        Thread {
            var ok = 0
            for (f in files) {
                var dest = File(target, f.name)
                if (dest.path == f.path && move) { ok++; continue }
                var k = 1
                while (dest.exists()) dest = File(target, "${f.nameWithoutExtension} (copy${if (k++ > 1) " $k" else ""})" + (if (f.isDirectory || f.extension.isEmpty()) "" else ".${f.extension}"))
                val done = try {
                    if (move && f.renameTo(dest)) true // same drive: instant
                    else {
                        f.copyRecursively(dest, overwrite = false)
                        if (move) f.deleteRecursively() else true
                    }
                } catch (e: Exception) { false }
                if (done) ok++
            }
            ui.post {
                busy = false
                clip = emptyList()
                toast("${if (move) "Moved" else "Copied"} $ok of ${files.size}")
                show(dir)
            }
        }.start()
    }

    private fun delete(files: List<File>) {
        val what = if (files.size == 1) files.first().name else "${files.size} items"
        AlertDialog.Builder(this).setTitle("Delete $what?")
            .setMessage("This can't be undone. Folders are deleted with everything inside them.")
            .setPositiveButton("Delete") { _, _ ->
                val ok = files.count { it.deleteRecursively() }
                toast("Deleted $ok of ${files.size}")
                show(dir)
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Name, type, size (folders counted), location and date; for several files, the totals. */
    private fun info(files: List<File>) {
        val dlg = AlertDialog.Builder(this).setTitle(if (files.size == 1) files.first().name else "${files.size} items")
            .setMessage("Counting…").setPositiveButton("OK", null).show()
        Thread {
            var bytes = 0L
            var fileCount = 0
            var folderCount = 0
            fun walk(f: File) {
                if (f.isDirectory) { folderCount++; f.listFiles()?.forEach { walk(it) } } else { fileCount++; bytes += f.length() }
            }
            files.forEach { walk(it) }
            val text = if (files.size == 1) {
                val f = files.first()
                listOf(
                    "Type:  " + if (f.isDirectory) "Folder" else (f.extension.uppercase().ifEmpty { "File" } + " file"),
                    "Size:  ${size(bytes)}" + if (f.isDirectory) "  ($fileCount files, ${folderCount - 1} folders)" else "",
                    "Location:  ${f.parent}",
                    "Modified:  ${dateFmt.format(Date(f.lastModified()))}",
                    "Can write:  ${if (f.canWrite()) "yes" else "no (read-only)"}"
                ).joinToString("\n\n")
            } else {
                "Total size:  ${size(bytes)}\n\n$fileCount files, $folderCount folders\n\nLocation:  ${dir?.path}"
            }
            ui.post { dlg.setMessage(text) }
        }.start()
    }

    /** Runs [work] off the screen thread with "Working…" shown, then reloads the folder. */
    private fun background(done: String, work: () -> Boolean) {
        if (busy) return
        busy = true
        refreshBars()
        Thread {
            val ok = try { work() } catch (e: Exception) { false }
            ui.post {
                busy = false
                toast(if (ok) done else "That didn't work on this drive")
                show(dir)
            }
        }.start()
    }

    /** Extracts a ZIP into a folder named after it, next to the file. Paths that try to escape the folder are skipped. */
    private fun extract(zipFile: File) {
        var out = File(zipFile.parentFile, zipFile.nameWithoutExtension)
        var k = 2
        while (out.exists()) out = File(zipFile.parentFile, "${zipFile.nameWithoutExtension} ($k)").also { k++ }
        background("Extracted to ${out.name}") {
            out.mkdirs()
            java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val dest = File(out, e.name)
                    if (!dest.canonicalPath.startsWith(out.canonicalPath + File.separator)) continue
                    if (e.isDirectory) dest.mkdirs() else {
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { zin.copyTo(it) }
                    }
                }
            }
            true
        }
    }

    /** Packs the selected files and folders into one ZIP in the current folder. */
    private fun zip(files: List<File>) {
        val d = dir ?: return
        ask("Zip ${files.size} item${if (files.size == 1) "" else "s"}", (if (files.size == 1) files.first().nameWithoutExtension else "Archive") + ".zip") { name ->
            val target = File(d, if (name.endsWith(".zip", true)) name else "$name.zip")
            if (target.exists()) { toast("${target.name} already exists"); return@ask }
            clearSelection()
            background("Created ${target.name}") {
                java.util.zip.ZipOutputStream(target.outputStream().buffered()).use { zout ->
                    fun add(f: File, prefix: String) {
                        if (f.isDirectory) f.listFiles()?.forEach { add(it, "$prefix${f.name}/") }
                        else {
                            zout.putNextEntry(java.util.zip.ZipEntry(prefix + f.name))
                            f.inputStream().use { it.copyTo(zout) }
                            zout.closeEntry()
                        }
                    }
                    files.forEach { add(it, "") }
                }
                true
            }
        }
    }

    /** Drag and drop: the dragged files are moved into [target] (a folder in the list, or the parent via "Up"). */
    private fun dropInto(target: File, paths: List<String>) {
        val files = paths.map { File(it) }.filter { it.path != target.path && !target.path.startsWith(it.path + "/") && it.parent != target.path }
        if (files.isEmpty()) return
        clip = files
        clipMove = true
        val back = dir
        dir = target
        paste()
        dir = back
    }

    private fun startDrag(row: View, f: File) {
        if (f.path !in selected) selected.add(f.path)
        refreshBars()
        adapter.notifyDataSetChanged()
        val data = android.content.ClipData.newPlainText("files", selected.joinToString("\n"))
        @Suppress("DEPRECATION")
        row.startDrag(data, View.DragShadowBuilder(row), null, 0)

    }

    /** Folders in the list and the Up button light up while something is dragged over them. */
    private fun dropTarget(v: View, target: () -> File?) = v.setOnDragListener { view, e ->
        when (e.action) {
            android.view.DragEvent.ACTION_DRAG_STARTED -> target() != null
            android.view.DragEvent.ACTION_DRAG_ENTERED -> { view.alpha = 0.6f; view.scaleX = 1.03f; view.scaleY = 1.03f; true }
            android.view.DragEvent.ACTION_DRAG_EXITED, android.view.DragEvent.ACTION_DRAG_ENDED -> { view.alpha = 1f; view.scaleX = 1f; view.scaleY = 1f; true }
            android.view.DragEvent.ACTION_DROP -> {
                val t = target() ?: return@setOnDragListener false
                val paths = e.clipData.getItemAt(0).text.toString().split("\n").filter { it.isNotEmpty() }
                dropInto(t, paths)
                true
            }
            else -> true
        }
    }

    private fun newFolder() {
        val d = dir ?: return
        ask("New folder", "New folder") { n ->
            if (File(d, n).mkdir()) show(d) else toast("Can't create a folder here")
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ---- rows ----

    private inner class FileAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = (convert as? LinearLayout) ?: LinearLayout(this@FilesActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(10), px(8), px(14), px(8))
                addView(text("", 20f, bold = true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(px(46), px(46)))
                val col = LinearLayout(this@FilesActivity).apply { orientation = LinearLayout.VERTICAL }
                col.addView(text("", 17f))
                col.addView(text("", 12f, 0xBBFFFFFF.toInt()))
                addView(col, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = px(14) })
                addView(text("", 20f, 0xCCFFFFFF.toInt(), bold = true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(px(40), px(40)))
            }
            val f = items[p]
            dropTarget(v) { f.takeIf { it.isDirectory && it.path !in selected } }
            val k = kind(f)
            val sel = f.path in selected
            v.background = GradientDrawable().apply {
                cornerRadius = px(12).toFloat()
                setColor(if (sel) (0x66 shl 24) or (accent and 0xFFFFFF) else 0x26000000)
                if (sel) setStroke(px(2), accent)
            }
            (v.getChildAt(0) as TextView).apply {
                text = glyph(k)
                background = GradientDrawable().apply { cornerRadius = px(11).toFloat(); setColor(tint(k)) }
            }
            val col = v.getChildAt(1) as LinearLayout
            (col.getChildAt(0) as TextView).text = f.name
            (col.getChildAt(1) as TextView).text = if (f.isDirectory) {
                // Folder contents aren't counted per row: listing every folder makes USB drives slow to scroll.
                "Folder  ·  ${dateFmt.format(Date(f.lastModified()))}"
            } else "${size(f.length())}  ·  ${dateFmt.format(Date(f.lastModified()))}"
            (v.getChildAt(2) as TextView).apply {
                // A tick in selection mode, an arrow on folders otherwise.
                text = when {
                    selected.isNotEmpty() -> if (sel) "✓" else "○"
                    f.isDirectory -> "›"
                    else -> ""
                }
                setTextColor(if (sel) accent else 0xCCFFFFFF.toInt())
            }
            return v
        }
    }
}
