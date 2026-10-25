package io.github.spreadglager.misakamusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.json.JSONArray
import java.io.File

class PlaybackService : Service() {
    companion object {
        const val ACTION_PLAY = "io.github.spreadglager.misakamusic.PLAY"
        const val ACTION_PLAY_QUEUE = "io.github.spreadglager.misakamusic.PLAY_QUEUE"
        const val ACTION_TOGGLE = "io.github.spreadglager.misakamusic.TOGGLE"
        const val ACTION_PAUSE = "io.github.spreadglager.misakamusic.PAUSE"
        const val ACTION_NEXT = "io.github.spreadglager.misakamusic.NEXT"
        const val ACTION_PREV = "io.github.spreadglager.misakamusic.PREV"
        const val ACTION_STOP = "io.github.spreadglager.misakamusic.STOP"
        const val ACTION_SEEK = "io.github.spreadglager.misakamusic.SEEK"
        const val ACTION_STATE = "io.github.spreadglager.misakamusic.STATE"

        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"
        const val EXTRA_QUEUE = "queue"
        const val EXTRA_INDEX = "index"
        const val EXTRA_POSITION = "position"

        const val EXTRA_STATE_PLAYING = "state_playing"
        const val EXTRA_STATE_POSITION = "state_position"
        const val EXTRA_STATE_DURATION = "state_duration"
        const val EXTRA_STATE_TITLE = "state_title"
        const val EXTRA_STATE_INDEX = "state_index"
        const val EXTRA_STATE_COUNT = "state_count"

        private const val TAG = "MisakaMusicPlayback"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1001

        const val EXTRA_COVER = "cover"
        private const val PREF = "playback"
        private const val KEY_QUEUE = "queue"
        private const val KEY_INDEX = "index"
        private const val KEY_POSITION = "position"
    }

    private data class QueueItem(
        val path: String,
        val title: String,
        val coverPath: String = "",
    )

    private var player: MediaPlayer? = null
    private var mediaSession: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var foregroundStarted = false
    private lateinit var prefs: SharedPreferences
    private var currentCover: Bitmap? = null
    private var lastSave = 0L
    private var pendingSeek = 0

    private val queue = ArrayList<QueueItem>()
    private var index = 0

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (player != null) {
                updatePlaybackState(player?.isPlaying == true)
                broadcastState()
                if (System.currentTimeMillis() - lastSave > 5000L) {
                    savePlaybackState()
                }
                progressHandler.postDelayed(this, 1000)
            }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            pause()
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREF, MODE_PRIVATE)
        createChannel()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        mediaSession = MediaSession(this, "御坂Music").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = start()
                override fun onPause() = pause()
                override fun onStop() = stopPlayback()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onSeekTo(pos: Long) {
                    seekTo(pos.toInt())
                }
            })
            isActive = true
        }
        registerNoisyReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_QUEUE -> {
                val raw = intent.getStringExtra(EXTRA_QUEUE)
                val start = intent.getIntExtra(EXTRA_INDEX, 0)
                val items = parseQueue(raw)
                if (items.isEmpty()) {
                    stopPlayback()
                    return START_NOT_STICKY
                }
                queue.clear()
                queue.addAll(items)
                index = start.coerceIn(0, queue.lastIndex)
                pendingSeek = 0
                startForegroundCompat(buildNotification(false))
                playIndex(index)
            }

            ACTION_PLAY -> {
                val path = intent.getStringExtra(EXTRA_PATH)
                if (path.isNullOrBlank()) {
                    start()
                } else {
                    queue.clear()
                    queue.add(
                        QueueItem(
                            path = path,
                            title = intent.getStringExtra(EXTRA_TITLE) ?: "御坂Music",
                            coverPath = intent.getStringExtra(EXTRA_COVER) ?: "",
                        )
                    )
                    index = 0
                    pendingSeek = 0
                    startForegroundCompat(buildNotification(false))
                    playIndex(0)
                }
            }

            ACTION_TOGGLE -> toggle()
            ACTION_PAUSE -> pause()
            ACTION_NEXT -> next()
            ACTION_PREV -> previous()
            ACTION_STOP -> stopPlayback()
            ACTION_SEEK -> seekTo(intent.getIntExtra(EXTRA_POSITION, 0))
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun parseQueue(raw: String?): List<QueueItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val result = ArrayList<QueueItem>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val path = obj.optString("path", "")
                if (path.isBlank() || !File(path).exists()) continue
                result.add(
                    QueueItem(
                        path = path,
                        title = obj.optString("title", "未命名曲目"),
                        coverPath = obj.optString("coverPath", ""),
                    )
                )
            }
            result
        } catch (t: Throwable) {
            Log.e(TAG, "bad queue json", t)
            emptyList()
        }
    }

    private fun playIndex(i: Int) {
        if (queue.isEmpty()) {
            stopPlayback()
            return
        }
        if (i !in queue.indices) {
            stopPlayback()
            return
        }
        index = i
        val item = queue[index]
        savePlaybackState(force = true)
        if (!foregroundStarted) {
            startForegroundCompat(buildNotification(false))
        } else {
            updateNotification(false)
        }
        playFile(item.path, item.title, item.coverPath)
    }

    private fun playFile(path: String, title: String, coverPath: String) {
        releasePlayer()
        currentCover = loadCover(coverPath)
        updateMetadata(title, 0L)
        updatePlaybackState(false)
        broadcastState()
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setWakeMode(this@PlaybackService, PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(path)
                setOnPreparedListener {
                    requestFocus()
                    it.start()
                    val seek = pendingSeek
                    pendingSeek = 0
                    if (seek > 0) {
                        it.seekTo(seek.coerceAtMost(it.duration))
                    }
                    updateMetadata(title, it.duration.toLong())
                    updatePlaybackState(true)
                    updateNotification(true)
                    startProgressUpdates()
                    broadcastState()
                }
                setOnCompletionListener {
                    Log.i(TAG, "completed index=$index of ${queue.size}")
                    nextOrStop()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error what=$what extra=$extra path=$path")
                    nextOrStop()
                    true
                }
                prepareAsync()
            }
            Log.i(TAG, "preparing index=$index title=$title path=$path")
        } catch (t: Throwable) {
            Log.e(TAG, "cannot prepare playback", t)
            nextOrStop()
        }
    }

    private fun nextOrStop() {
        if (index + 1 < queue.size) {
            playIndex(index + 1)
        } else {
            stopPlayback()
        }
    }

    private fun next() {
        if (queue.isEmpty()) return
        if (index + 1 < queue.size) {
            playIndex(index + 1)
        } else {
            stopPlayback()
        }
    }

    private fun previous() {
        if (queue.isEmpty()) return
        if (index > 0) {
            playIndex(index - 1)
        } else {
            seekTo(0)
        }
    }

    private fun toggle() {
        val p = player
        if (p == null) {
            if (queue.isEmpty()) {
                restoreSavedQueue()
            }
            if (queue.isNotEmpty()) {
                playIndex(index)
            }
            return
        }
        try {
            if (p.isPlaying) pause() else start()
        } catch (t: Throwable) {
            Log.e(TAG, "toggle failed", t)
        }
    }

    private fun start() {
        val p = player ?: return
        try {
            if (!p.isPlaying) {
                requestFocus()
                p.start()
                updatePlaybackState(true)
                updateNotification(true)
                startProgressUpdates()
                broadcastState()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
        }
    }

    private fun pause() {
        val p = player ?: return
        try {
            if (p.isPlaying) {
                p.pause()
                savePlaybackState(force = true)
                updatePlaybackState(false)
                updateNotification(false)
                broadcastState()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "pause failed", t)
        }
    }

    private fun seekTo(position: Int) {
        val p = player ?: return
        try {
            p.seekTo(position.coerceAtLeast(0))
            savePlaybackState(force = true)
            updatePlaybackState(p.isPlaying)
            broadcastState()
        } catch (t: Throwable) {
            Log.e(TAG, "seek failed", t)
        }
    }

    private fun stopPlayback() {
        progressHandler.removeCallbacks(progressRunnable)
        savePlaybackState(force = true)
        releasePlayer()
        updatePlaybackState(false)
        broadcastStopped()
        mediaSession?.isActive = false
        abandonFocus()
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    private fun releasePlayer() {
        try {
            player?.release()
        } catch (_: Throwable) {
        }
        player = null
    }

    private fun startProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.post(progressRunnable)
    }

    private fun requestFocus() {
        audioManager?.requestAudioFocus(
            focusListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        )
    }

    private fun abandonFocus() {
        try {
            audioManager?.abandonAudioFocus(focusListener)
        } catch (_: Throwable) {
        }
    }

    private fun currentPosition(): Int {
        return try {
            player?.currentPosition ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    private fun currentDuration(): Int {
        return try {
            player?.duration ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    private fun currentTitle(): String {
        return if (index in queue.indices) queue[index].title else "御坂Music"
    }

    private fun updateMetadata(title: String, duration: Long) {
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
        currentCover?.let { cover ->
            builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover)
            builder.putBitmap(MediaMetadata.METADATA_KEY_ART, cover)
            builder.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, cover)
        }
        mediaSession?.setMetadata(builder.build())
    }

    private fun loadCover(path: String): Bitmap? {
        if (path.isBlank() || !File(path).exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) {
                sample *= 2
            }
            BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun fallbackCover(): Bitmap? {
        return try {
            BitmapFactory.decodeResource(
                resources,
                R.drawable.ic_launcher_foreground,
                BitmapFactory.Options().apply { inSampleSize = 2 }
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun restoreSavedQueue() {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return
        val items = parseQueue(raw)
        if (items.isEmpty()) return
        queue.clear()
        queue.addAll(items)
        index = prefs.getInt(KEY_INDEX, 0).coerceIn(0, queue.lastIndex)
        pendingSeek = prefs.getInt(KEY_POSITION, 0).coerceAtLeast(0)
    }

    private fun savePlaybackState(force: Boolean = false) {
        if (queue.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 3000L && player != null) return
        lastSave = now
        val array = JSONArray()
        for (item in queue) {
            array.put(
                org.json.JSONObject()
                    .put("path", item.path)
                    .put("title", item.title)
                    .put("coverPath", item.coverPath)
            )
        }
        prefs.edit()
            .putString(KEY_QUEUE, array.toString())
            .putInt(KEY_INDEX, index)
            .putInt(KEY_POSITION, currentPosition())
            .apply()
    }

    private fun updatePlaybackState(playing: Boolean) {
        val state = when {
            playing -> PlaybackState.STATE_PLAYING
            player == null -> PlaybackState.STATE_NONE
            else -> PlaybackState.STATE_PAUSED
        }
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(state, currentPosition().toLong(), 1f)
                .build()
        )
    }

    private fun broadcastState() {
        val intent = Intent(ACTION_STATE).setPackage(packageName)
            .putExtra(EXTRA_STATE_PLAYING, player?.isPlaying == true)
            .putExtra(EXTRA_STATE_POSITION, currentPosition())
            .putExtra(EXTRA_STATE_DURATION, currentDuration())
            .putExtra(EXTRA_STATE_TITLE, currentTitle())
            .putExtra(EXTRA_STATE_INDEX, index)
            .putExtra(EXTRA_STATE_COUNT, queue.size)
        sendBroadcast(intent)
    }

    private fun broadcastStopped() {
        val intent = Intent(ACTION_STATE).setPackage(packageName)
            .putExtra(EXTRA_STATE_PLAYING, false)
            .putExtra(EXTRA_STATE_POSITION, 0)
            .putExtra(EXTRA_STATE_DURATION, 0)
            .putExtra(EXTRA_STATE_TITLE, "")
            .putExtra(EXTRA_STATE_INDEX, 0)
            .putExtra(EXTRA_STATE_COUNT, 0)
        sendBroadcast(intent)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun updateNotification(playing: Boolean) {
        if (!foregroundStarted) {
            startForegroundCompat(buildNotification(playing))
        } else {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(playing))
        }
    }

    private fun buildNotification(playing: Boolean): Notification {
        val toggleIntent = pendingService(1, ACTION_TOGGLE)
        val prevIntent = pendingService(2, ACTION_PREV)
        val nextIntent = pendingService(3, ACTION_NEXT)
        val stopIntent = pendingService(4, ACTION_STOP)
        val contentIntent = PendingIntent.getActivity(
            this,
            5,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music)
            .setLargeIcon(currentCover ?: fallbackCover())
            .setContentTitle(currentTitle())
            .setContentText(
                if (playing) {
                    "正在播放 · ${index + 1}/${queue.size.coerceAtLeast(1)}"
                } else {
                    "已暂停"
                }
            )
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "上一首", prevIntent)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "暂停" else "播放",
                toggleIntent
            )
            .addAction(android.R.drawable.ic_media_next, "下一首", nextIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopIntent)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession!!.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        mediaSession?.setSessionActivity(contentIntent)
        return builder.build()
    }

    private fun pendingService(requestCode: Int, action: String): PendingIntent {
        return PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "播放",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "御坂Music 后台播放控制"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun registerNoisyReceiver() {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(noisyReceiver, filter)
        }
    }

    override fun onDestroy() {
        progressHandler.removeCallbacks(progressRunnable)
        savePlaybackState(force = true)
        releasePlayer()
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: Throwable) {
        }
        mediaSession?.release()
        mediaSession = null
        abandonFocus()
        super.onDestroy()
    }
}
