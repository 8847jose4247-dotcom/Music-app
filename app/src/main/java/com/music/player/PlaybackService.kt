package com.music.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock

/**
 * Mantiene viva la reproducción con la pantalla apagada y muestra la notificación
 * con portada y controles (también en la pantalla de bloqueo).
 * El audio en sí lo reproduce la página (WebView); este servicio solo refleja su estado.
 */
class PlaybackService : Service() {

    private class State(
        val title: String,
        val artist: String,
        val album: String,
        val playing: Boolean,
        val posMs: Long,
        val durMs: Long,
        val cover: Bitmap?
    )

    companion object {
        private const val CHANNEL = "music_playback"
        private const val NOTIF_ID = 1

        @Volatile
        private var instance: PlaybackService? = null

        @Volatile
        private var state: State? = null

        fun update(
            ctx: Context, title: String, artist: String, album: String,
            playing: Boolean, posMs: Long, durMs: Long, cover: Bitmap?
        ) {
            state = State(title, artist, album, playing, posMs, durMs, cover)
            val s = instance
            if (s != null) {
                s.refresh()
            } else {
                val i = Intent(ctx, PlaybackService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                } catch (e: Exception) {
                }
            }
        }

        fun stop(ctx: Context) {
            state = null
            ctx.stopService(Intent(ctx, PlaybackService::class.java))
        }
    }

    private lateinit var session: MediaSession
    private lateinit var nm: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Reproducción", NotificationManager.IMPORTANCE_LOW)
            )
        }
        session = MediaSession(this, "Music")
        session.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = control("play")
            override fun onPause() = control("pause")
            override fun onSkipToNext() = control("next")
            override fun onSkipToPrevious() = control("prev")
            override fun onSeekTo(pos: Long) = control("seek", pos)
        })
        session.setActive(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action: String = intent?.action ?: ""
        when (action) {
            "prev", "toggle", "next" -> {
                if (MainActivity.instance == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                control(action)
            }
            else -> refresh()
        }
        return START_NOT_STICKY
    }

    private fun control(cmd: String, arg: Long = 0L) {
        val a = MainActivity.instance ?: return
        if (cmd == "seek") a.js("window.nativeControl && window.nativeControl('seek', $arg)")
        else a.js("window.nativeControl && window.nativeControl('$cmd')")
    }

    fun refresh() {
        val st = state ?: State("Music", "", "", false, 0L, 0L, null)

        val md = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, st.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, st.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, st.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, st.durMs)
        if (st.cover != null) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, st.cover)
        session.setMetadata(md.build())

        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (st.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    st.posMs,
                    if (st.playing) 1f else 0f,
                    SystemClock.elapsedRealtime()
                )
                .build()
        )

        updateWakeLock(st.playing)

        val n = buildNotification(st)
        if (!inForeground) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(NOTIF_ID, n)
                }
                inForeground = true
            } catch (e: Exception) {
                nm.notify(NOTIF_ID, n)
            }
        } else {
            nm.notify(NOTIF_ID, n)
        }
    }

    private fun action(icon: Int, title: String, act: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, act.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()
    }

    private fun buildNotification(st: State): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)

        b.setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(st.title)
            .setContentText(if (st.artist.isEmpty()) "Artista desconocido" else st.artist)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(R.drawable.ic_prev, "Anterior", "prev"))
            .addAction(
                if (st.playing) action(R.drawable.ic_pause, "Pausa", "toggle")
                else action(R.drawable.ic_play, "Reproducir", "toggle")
            )
            .addAction(action(R.drawable.ic_next, "Siguiente", "next"))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        if (st.cover != null) b.setLargeIcon(st.cover)
        return b.build()
    }

    private fun updateWakeLock(playing: Boolean) {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "music:playback").apply {
                setReferenceCounted(false)
            }
        }
        val wl = wakeLock ?: return
        if (playing) {
            if (!wl.isHeld) wl.acquire(6 * 60 * 60 * 1000L)
        } else if (wl.isHeld) {
            wl.release()
        }
    }

    override fun onDestroy() {
        instance = null
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        session.release()
        super.onDestroy()
    }
}
