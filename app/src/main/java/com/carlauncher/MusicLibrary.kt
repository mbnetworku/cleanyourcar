package com.carlauncher

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import java.io.File

/** One audio file. Title and artist come from the file name ("Artist - Title.mp3") so scanning stays fast. */
class Track(val path: String, val source: String, label: String? = null, sub: String? = null) {
    private val f = File(path)
    val name: String = f.nameWithoutExtension
    val folder: String = f.parent ?: ""
    val folderName: String = File(folder).name
    val title: String = label ?: name.substringAfter(" - ", name).trim()
    val artist: String = sub ?: if (name.contains(" - ")) name.substringBefore(" - ").trim() else ""
    val isStream: Boolean get() = path.startsWith("http")
}

/** A place music can live: internal storage, an SD card, or one USB drive. */
class MusicSource(val label: String, val root: File)

/**
 * Finds music by walking every storage root itself instead of waiting for the system media scanner,
 * so a freshly plugged USB drive shows up at once. Each drive is kept as its own source.
 */
object MusicLibrary {
    val extensions = setOf(
        "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "amr", "awb", "mid", "midi",
        "3gp", "mka", "wma", "ape", "aiff", "aif", "mp2", "ac3", "webm", "mp4"
    )

    @Volatile var tracks: List<Track> = emptyList()
        private set
    @Volatile var sources: List<MusicSource> = emptyList()
        private set
    @Volatile var scanning = false
        private set
    private val listeners = ArrayList<() -> Unit>()
    private val ui = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    /** Storage roots with a friendly label: Internal storage, SD card, USB 1, USB 2... */
    fun findSources(ctx: Context): List<MusicSource> {
        val internal = try { Environment.getExternalStorageDirectory().canonicalFile } catch (e: Exception) { null }
        val found = LinkedHashMap<String, File>()
        internal?.let { found[it.path] = it }
        ctx.getExternalFilesDirs(null).filterNotNull().forEach { f ->
            try { File(f.path.substringBefore("/Android/")).canonicalFile.let { found[it.path] = it } } catch (e: Exception) { }
        }
        val skip = setOf("self", "emulated", "secure", "asec", "obb", "runtime", "user", "shell", "appfuse", "expand", "sdcard0", "legacy")
        for (base in listOf("/storage", "/mnt", "/mnt/media_rw", "/mnt/usb", "/mnt/usb_storage", "/mnt/usbhost", "/udisk")) {
            File(base).listFiles()?.forEach { f ->
                if (f.isDirectory && f.canRead() && f.name.lowercase() !in skip && (f.list()?.isNotEmpty() == true))
                    try { f.canonicalFile.let { found[it.path] = it } } catch (e: Exception) { }
            }
        }
        // Drop roots nested inside another root, and the internal storage seen under another name.
        // Some boxes expose internal storage a second time under another name (a bind mount):
        // same device and inode, so it is the same folder.
        fun id(f: File) = try { android.system.Os.stat(f.path).let { "${it.st_dev}:${it.st_ino}" } } catch (e: Exception) { f.path }
        val roots = found.values.sortedWith(compareBy({ if (internal != null && it.path == internal.path) 0 else 1 }, { it.path.length }))
        val keep = ArrayList<File>()
        val ids = HashSet<String>()
        for (r in roots) {
            if (keep.any { r.path == it.path || r.path.startsWith(it.path + "/") || it.path.startsWith(r.path + "/") }) continue
            if (!ids.add(id(r))) continue
            keep.add(r)
        }
        var usb = 0
        var sd = 0
        return keep.map { r ->
            val p = r.path.lowercase()
            val label = when {
                internal != null && r.path == internal.path -> "Internal storage"
                p.contains("usb") || p.contains("udisk") || p.contains("sda") || p.contains("sdb") -> "USB ${++usb}"
                p.contains("sd") || p.contains("ext") || p.contains("card") -> if (++sd == 1) "SD card" else "SD card $sd"
                else -> "USB ${++usb}"
            }
            MusicSource(label, r)
        }.sortedBy { if (it.label == "Internal storage") "0" else it.label }
    }

    fun scan(ctx: Context) {
        if (scanning) return
        scanning = true
        fire()
        val app = ctx.applicationContext
        Thread {
            val srcs = findSources(app)
            val out = ArrayList<Track>()
            val seen = HashSet<String>()
            for (s in srcs) walk(s.root, 0, s.label, out, seen)
            sources = srcs.filter { s -> out.any { it.source == s.label } }
            tracks = out.sortedBy { it.title.lowercase() }
            scanning = false
            fire()
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
            } else if (f.extension.lowercase() in extensions && f.length() > 16 * 1024) {
                out.add(Track(f.path, source))
            }
        }
    }

    fun inSource(source: String?): List<Track> = if (source == null) tracks else tracks.filter { it.source == source }

    /** Folder path to its tracks, for the given source (null = all). */
    fun folders(source: String?): List<Pair<String, List<Track>>> =
        inSource(source).groupBy { it.folder }.toList().sortedBy { File(it.first).name.lowercase() }
            .map { it.first to it.second.sortedBy { t -> t.name.lowercase() } }

    fun artists(source: String?): List<Pair<String, List<Track>>> =
        inSource(source).groupBy { it.artist.ifEmpty { "Unknown artist" } }.toList().sortedBy { it.first.lowercase() }

    private fun fire() {
        val copy = synchronized(listeners) { ArrayList(listeners) }
        ui.post { copy.forEach { it() } }
    }
}
