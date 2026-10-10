package app.koreshok.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import app.koreshok.KoreshokApp
import app.koreshok.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps reading aloud alive with the screen off, and puts play, pause and stop on the lock
 * screen, in the notification shade and on headphone buttons.
 */
class ReadAloudService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSession
    private val readAloud get() = (application as KoreshokApp).readAloud

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Чтение вслух", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        session = MediaSession(this, "koreshok-read-aloud").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = readAloud.resume()
                override fun onPause() = readAloud.pause()
                override fun onStop() = readAloud.stop()
                override fun onSkipToNext() = readAloud.skip(forward = true)
                override fun onSkipToPrevious() = readAloud.skip(forward = false)
            })
            isActive = true
        }
        startInForeground(notification(readAloud.state.value))
        scope.launch {
            readAloud.state.collect { state ->
                if (!state.active) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                updateSession(state)
                manager.notify(ID, notification(state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> readAloud.toggle()
            ACTION_STOP -> readAloud.stop()
            ACTION_NEXT -> readAloud.skip(forward = true)
            ACTION_PREVIOUS -> readAloud.skip(forward = false)
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(ID, notification)
        }
    }

    private fun updateSession(state: ReadAloudState) {
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Корешок читает вслух")
                .build(),
        )
    }

    private fun notification(state: ReadAloudState): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(icon: Int, title: String, action: String) = Notification.Action.Builder(
            Icon.createWithResource(this, icon),
            title,
            PendingIntent.getService(this, action.hashCode(), Intent(this, ReadAloudService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE),
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle(state.title.ifBlank { "Корешок" })
            .setContentText(state.current?.spoken?.take(120) ?: "Читаю вслух")
            .setContentIntent(open)
            .setOngoing(state.playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "Назад", ACTION_PREVIOUS))
            .addAction(
                if (state.playing) action(android.R.drawable.ic_media_pause, "Пауза", ACTION_TOGGLE)
                else action(android.R.drawable.ic_media_play, "Читать", ACTION_TOGGLE),
            )
            .addAction(action(android.R.drawable.ic_media_next, "Дальше", ACTION_NEXT))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Стоп", ACTION_STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "read-aloud"
        const val ID = 7
        const val ACTION_TOGGLE = "app.koreshok.READ_TOGGLE"
        const val ACTION_STOP = "app.koreshok.READ_STOP"
        const val ACTION_NEXT = "app.koreshok.READ_NEXT"
        const val ACTION_PREVIOUS = "app.koreshok.READ_PREVIOUS"
    }
}
