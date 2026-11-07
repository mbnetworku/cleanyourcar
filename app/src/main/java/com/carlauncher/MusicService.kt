package com.carlauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast

/**
 * Background music playback. The music screen and the launcher talk to it directly through [instance];
 * a MediaSession makes steering-wheel buttons, the launcher card and the notification work too.
 */
class MusicService : Service() {

    companion object {
        @Volatile var instance: MusicService? = null
            private set
        private val listeners = ArrayList<() -> Unit>()
        fun addListener(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
        fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }

        /** Starts the service if needed and runs [then] once it is ready. */
        fun with(ctx: android.content.Context, then: (MusicService) -> Unit) {
            instance?.let { then(it); return }
            pending.add(then)
            ctx.startService(Intent(ctx, MusicService::class.java))
        }
        private val pending = ArrayList<(MusicService) -> Unit>()
        private const val CHANNEL = "music"
        private const val NOTE_ID = 7
    }

    private val ui = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager

    var queue: List<Track> = emptyList()
        private set
    var index = -1
        private set
    var shuffle = false
        private set
    /** 0 = off, 1 = repeat all, 2 = repeat one. */
    var repeat = 1
        private set
    var art: Bitmap? = null
        private set
    var metaTitle = ""
        private set
    var metaArtist = ""
        private set
    private var order: List<Int> = emptyList()
    private var failures = 0

    val audioSession: Int get() = try { player?.audioSessionId ?: 0 } catch (e: Exception) { 0 }
    val current: Track? get() = queue.getOrNull(index)
    /** MediaPlayer reports an error if asked for position or length before it is prepared, so only ask once ready. */
    private var prepared = false
    val isPlaying: Boolean get() = prepared && try { player?.isPlaying == true } catch (e: Exception) { false }
    val position: Int get() = if (!prepared) 0 else try { player?.currentPosition ?: 0 } catch (e: Exception) { 0 }
    val duration: Int get() = if (!prepared) 0 else try { player?.duration?.coerceAtLeast(0) ?: 0 } catch (e: Exception) { 0 }

    private val focus = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { if (isPlaying) { pausedByFocus = true; pause() } }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(0.3f, 0.3f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.setVolume(1f, 1f)
                if (pausedByFocus) { pausedByFocus = false; resume() }
            }
        }
    }
    private var pausedByFocus = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val prefs = getSharedPreferences("music", MODE_PRIVATE)
        shuffle = prefs.getBoolean("shuffle", false)
        repeat = prefs.getInt("repeat", 1)
        session = MediaSession(this, "CarLauncherMusic").apply {
            @Suppress("DEPRECATION")
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = prev()
                override fun onStop() = pause()
                override fun onSeekTo(pos: Long) = seek(pos.toInt())
            })
            setSessionActivity(PendingIntent.getActivity(this@MusicService, 0,
                Intent(this@MusicService, MusicActivity::class.java), pendingFlags()))
        }
        instance = this
        val run = ArrayList(pending)
        pending.clear()
        run.forEach { it(this) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "toggle" -> toggle()
            "next" -> next()
            "prev" -> prev()
        }
        return START_NOT_STICKY
    }

    private fun pendingFlags() = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    // ---- queue control ----

    fun playQueue(list: List<Track>, start: Int) {
        if (list.isEmpty()) return
        queue = list
        buildOrder(start)
        index = start.coerceIn(0, list.lastIndex)
        failures = 0
        playCurrent()
    }

    private fun buildOrder(start: Int) {
        order = if (shuffle) (listOf(start) + queue.indices.filter { it != start }.shuffled()) else queue.indices.toList()
    }

    private fun step(dir: Int): Int? {
        if (queue.isEmpty()) return null
        val pos = order.indexOf(index)
        var np = pos + dir
        if (np !in order.indices) {
            if (repeat == 0 && dir > 0) return null
            np = (np + order.size) % order.size
        }
        return order[np]
    }

    fun next() {
        val n = step(1) ?: run { pause(); return }
        index = n
        playCurrent()
    }

    fun prev() {
        if (position > 3000) { seek(0); return }
        index = step(-1) ?: return
        playCurrent()
    }

    fun toggle() = if (isPlaying) pause() else resume()

    fun resume() {
        val p = player ?: run { if (current != null) playCurrent(); return }
        if (!prepared) return
        if (!requestFocus()) return
        p.start()
        changed()
    }

    fun pause() {
        if (prepared) try { player?.pause() } catch (e: Exception) { }
        changed()
    }

    fun seek(ms: Int) {
        if (prepared) try { player?.seekTo(ms) } catch (e: Exception) { }
        changed()
    }

    fun toggleShuffle() {
        shuffle = !shuffle
        if (index >= 0) buildOrder(index)
        getSharedPreferences("music", MODE_PRIVATE).edit().putBoolean("shuffle", shuffle).apply()
        changed()
    }

    fun cycleRepeat() {
        repeat = (repeat + 1) % 3
        getSharedPreferences("music", MODE_PRIVATE).edit().putInt("repeat", repeat).apply()
        changed()
    }

    private fun requestFocus(): Boolean {
        @Suppress("DEPRECATION")
        return audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun playCurrent() {
        val t = current ?: return
        player?.let { AudioFx.detach(it.audioSessionId); it.release() }
        player = null
        art = null
        prepared = false
        metaTitle = t.title
        metaArtist = t.artist.ifEmpty { t.folderName }
        if (!t.isStream) loadMeta(t)
        val mp = MediaPlayer()
        player = mp
        try {
            @Suppress("DEPRECATION")
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            if (t.isStream) mp.setDataSource(t.path)
            else {
                // Open the file in this app and hand over the open file: on newer Android the media server
                // itself may not be allowed to read USB drives, even when this app is.
                val f = java.io.File(t.path)
                if (!f.canRead()) { skipBad(t, if (f.exists()) "no permission to read it. Allow \"All files access\" in Settings → Launcher → Permissions" else "the file is gone"); return }
                java.io.FileInputStream(f).use { mp.setDataSource(it.fd) }
            }
            AudioFx.attach(this, mp.audioSessionId)
            mp.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                prepared = true
                failures = 0
                if (requestFocus()) it.start()
                changed()
            }
            mp.setOnCompletionListener {
                if (repeat == 2) { it.seekTo(0); it.start() } else next()
            }
            mp.setOnErrorListener { p, what, extra ->
                // -38 is "wrong state" noise, not a bad file.
                if (p === player && what != -38) skipBad(t, if (what == 1 && extra == -1010) "this format isn't supported" else "player error $what/$extra")
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            skipBad(t, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
        session.isActive = true
        changed()
    }

    /** A file this box cannot decode (or a missing one): say so and move on, without looping forever. */
    private fun skipBad(t: Track, why: String = "") {
        failures++
        ui.post { Toast.makeText(this, "Can't play ${java.io.File(t.path).name}" + (if (why.isNotEmpty()) ": $why" else ""), Toast.LENGTH_LONG).show() }
        if (failures < queue.size.coerceAtMost(8)) ui.postDelayed({ next() }, 300) else pause()
    }

    private fun loadMeta(t: Track) {
        Thread {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(t.path)
                val title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val pic = r.embeddedPicture?.let { b ->
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(b, 0, b.size, o)
                    var s = 1
                    while (o.outWidth / (s * 2) >= 500) s *= 2
                    BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
                }
                ui.post {
                    if (current !== t) return@post
                    if (!title.isNullOrBlank()) metaTitle = title
                    if (!artist.isNullOrBlank()) metaArtist = artist
                    art = pic
                    changed()
                }
            } catch (e: Exception) {
            } finally {
                try { r.release() } catch (e: Exception) { }
            }
        }.start()
    }

    // ---- state out: session, notification, listeners ----

    private fun changed() {
        val playing = isPlaying
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, position.toLong(), 1f)
            .build())
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, metaTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, metaArtist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration.toLong())
            .apply { art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
            .build())
        showNotification(playing)
        val copy = synchronized(listeners) { ArrayList(listeners) }
        copy.forEach { it() }
    }

    private fun showNotification(playing: Boolean) {
        if (current == null) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Music", NotificationManager.IMPORTANCE_LOW))
        fun action(icon: Int, label: String, act: String) = Notification.Action.Builder(icon, label,
            PendingIntent.getService(this, act.hashCode(), Intent(this, MusicService::class.java).setAction(act), pendingFlags())).build()
        @Suppress("DEPRECATION")
        val b = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this))
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(metaTitle)
            .setContentText(metaArtist)
            .setLargeIcon(art)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MusicActivity::class.java), pendingFlags()))
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(R.drawable.ic_prev, "Previous", "prev"))
            .addAction(action(if (playing) R.drawable.ic_pause else R.drawable.ic_play, if (playing) "Pause" else "Play", "toggle"))
            .addAction(action(R.drawable.ic_next, "Next", "next"))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        val n = b.build()
        if (playing) {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTE_ID, n)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
            nm.notify(NOTE_ID, n)
        }
    }

    override fun onDestroy() {
        instance = null
        player?.release()
        player = null
        @Suppress("DEPRECATION")
        audio.abandonAudioFocus(focus)
        session.release()
        super.onDestroy()
    }
}
