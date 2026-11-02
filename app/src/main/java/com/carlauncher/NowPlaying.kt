package com.carlauncher

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.KeyEvent

/** Does nothing itself; Android only lets an enabled listener read other apps' media sessions. */
class NowPlayingService : NotificationListenerService()

/** Follows whichever music/radio app is playing: title, artwork and transport controls. */
class NowPlaying(private val ctx: Context, private val onChange: () -> Unit) {

    var controller: MediaController? = null
        private set

    private val component = ComponentName(ctx, NowPlayingService::class.java)
    private val manager = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private var started = false

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = onChange()
        override fun onPlaybackStateChanged(state: PlaybackState?) = onChange()
        override fun onSessionDestroyed() = refresh()
    }

    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }

    val isPlaying: Boolean get() = controller?.playbackState?.state == PlaybackState.STATE_PLAYING

    fun hasAccess(): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(ctx.packageName)
    }

    fun start() {
        if (started || !hasAccess()) return
        try {
            manager.addOnActiveSessionsChangedListener(listener, component)
            started = true
            refresh()
        } catch (e: SecurityException) {
        }
    }

    fun stop() {
        if (started) {
            manager.removeOnActiveSessionsChangedListener(listener)
            started = false
        }
        controller?.unregisterCallback(callback)
        controller = null
    }

    private fun refresh() {
        try {
            pick(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            pick(null)
        }
    }

    private fun pick(list: List<MediaController>?) {
        controller?.unregisterCallback(callback)
        controller = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        controller?.registerCallback(callback)
        onChange()
    }

    fun prev() {
        val t = controller?.transportControls
        if (t != null) t.skipToPrevious() else key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    fun next() {
        val t = controller?.transportControls
        if (t != null) t.skipToNext() else key(KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun toggle() {
        val t = controller?.transportControls
        if (t != null) {
            if (isPlaying) t.pause() else t.play()
        } else {
            key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
    }

    /** Fallback that works with any player even without notification access. */
    private fun key(code: Int) {
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}
