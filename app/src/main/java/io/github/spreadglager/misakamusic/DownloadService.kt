package io.github.spreadglager.misakamusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class DownloadService : Service() {
    companion object {
        const val ACTION_ENQUEUE = "io.github.spreadglager.misakamusic.DOWNLOAD_ENQUEUE"
        const val ACTION_CANCEL = "io.github.spreadglager.misakamusic.DOWNLOAD_CANCEL"
        const val ACTION_STATE = "io.github.spreadglager.misakamusic.DOWNLOAD_STATE"

        const val EXTRA_TASKS = "tasks"
        const val EXTRA_KEY = "key"
        const val EXTRA_TITLE = "title"
        const val EXTRA_STATUS = "status"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_DONE = "done"
        const val EXTRA_COUNT = "count"

        private const val TAG = "MisakaDownload"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1002
        private const val PREF = "downloads"
        private const val KEY_PENDING = "pending"
    }

    data class Task(
        val bvid: String,
        val cid: Long,
        val title: String,
        val coverUrl: String = "",
    ) {
        val key: String get() = "$bvid:$cid"
    }

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var prefs: SharedPreferences
    private var foregroundStarted = false
    private var working = false

    @Volatile
    private var cancelled = false
    private var totalCount = 0
    private var doneCount = 0
    private var currentTitle = ""
    private var lastNotify = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREF, MODE_PRIVATE)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENQUEUE -> {
                cancelled = false
                val incoming = parseTasks(intent.getStringExtra(EXTRA_TASKS))
                val merged = merge(loadPending(), incoming)
                savePending(merged)
                totalCount = merged.size
                if (merged.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundCompat(
                    buildNotification("准备下载 ${merged.size} 首", 0, merged.size, true)
                )
                ensureWorker()
            }

            ACTION_CANCEL -> {
                cancelled = true
                savePending(emptyList())
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureWorker() {
        if (working) return
        working = true
        worker.execute {
            try {
                processLoop()
            } finally {
                working = false
            }
        }
    }

    private fun processLoop() {
        while (!cancelled) {
            val pending = loadPending()
            if (pending.isEmpty()) break
            val task = pending.first()
            processTask(task)
            removePending(task.key)
        }
        if (cancelled) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
            return
        }
        broadcast("all_done", "", 0, 0, "ok", doneCount, totalCount)
        if (foregroundStarted) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(
                NOTIFICATION_ID,
                buildNotification("下载完成 · $doneCount/$totalCount", totalCount, totalCount, false)
                    .also { it.flags = it.flags and Notification.FLAG_ONGOING_EVENT.inv() }
            )
        }
        Thread.sleep(1200)
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    private fun processTask(task: Task) {
        currentTitle = task.title
        if (cancelled) return
        broadcast("downloading", task.key, 0, 100, task.title, doneCount, totalCount)
        notifyProgress(task.title, 0, 0, true)
        try {
            val audio = audioFile(task)
            if (!audio.exists() || audio.length() <= 0L) {
                val url = BiliApi.audioUrl(task.bvid, task.cid)
                BiliApi.download(url, audio, { copied, total ->
                    val percent = if (total > 0L) (copied * 100L / total).toInt() else 0
                    notifyProgress(task.title, percent, copied, total <= 0L)
                }, { cancelled })
            }
            if (task.coverUrl.isNotBlank()) {
                CoverStore.ensure(this, Track(task.bvid, task.cid, task.title, task.coverUrl))
            }
            if (cancelled) return
            doneCount++
            broadcast("done", task.key, 100, 100, task.title, doneCount, totalCount)
            notifyProgress("$doneCount/$totalCount 完成", 0, 0, false)
        } catch (t: Throwable) {
            if (cancelled) return
            Log.e(TAG, "download failed ${task.key}", t)
            doneCount++
            broadcast("error", task.key, 0, 100, t.message ?: "下载失败", doneCount, totalCount)
            notifyProgress("失败：${t.message}", 0, 0, false)
        }
    }

    private fun notifyProgress(title: String, percent: Int, copied: Long, indeterminate: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 800L && !indeterminate) return
        lastNotify = now
        if (!foregroundStarted) return
        val text = if (percent > 0) "$title · $percent%" else title
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(text, percent, 100, indeterminate)
        )
    }

    private fun broadcast(
        status: String,
        key: String,
        progress: Int,
        total: Int,
        message: String,
        done: Int,
        count: Int,
    ) {
        val intent = Intent(ACTION_STATE).setPackage(packageName)
            .putExtra(EXTRA_STATUS, status)
            .putExtra(EXTRA_KEY, key)
            .putExtra(EXTRA_PROGRESS, progress)
            .putExtra(EXTRA_TOTAL, total)
            .putExtra(EXTRA_MESSAGE, message)
            .putExtra(EXTRA_DONE, done)
            .putExtra(EXTRA_COUNT, count)
        sendBroadcast(intent)
    }

    private fun buildNotification(
        text: String,
        progress: Int,
        max: Int,
        indeterminate: Boolean,
    ): Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            10,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            11,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle("御坂Music 自动下载")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setProgress(max, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消下载", cancelIntent)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun createChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "自动下载",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "打开收藏夹后的后台下载"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun audioFile(task: Task): File {
        val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(filesDir, "music")
        dir.mkdirs()
        return File(dir, "${task.bvid}_${task.cid}.m4s")
    }

    private fun loadPending(): List<Task> {
        val raw = prefs.getString(KEY_PENDING, null) ?: return emptyList()
        return parseTasks(raw)
    }

    private fun savePending(tasks: List<Task>) {
        val array = JSONArray()
        for (task in tasks) {
            array.put(
                JSONObject()
                    .put("bvid", task.bvid)
                    .put("cid", task.cid)
                    .put("title", task.title)
                    .put("cover", task.coverUrl)
            )
        }
        prefs.edit().putString(KEY_PENDING, array.toString()).apply()
    }

    private fun removePending(key: String) {
        val next = loadPending().filterNot { it.key == key }
        savePending(next)
    }

    private fun merge(current: List<Task>, incoming: List<Task>): List<Task> {
        val map = LinkedHashMap<String, Task>()
        for (task in current) map[task.key] = task
        for (task in incoming) map.putIfAbsent(task.key, task)
        return map.values.toList()
    }

    private fun parseTasks(raw: String?): List<Task> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val result = ArrayList<Task>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val bvid = obj.optString("bvid", "")
                val cid = obj.optLong("cid", 0L)
                if (bvid.isBlank() || cid <= 0L) continue
                result.add(
                    Task(
                        bvid = bvid,
                        cid = cid,
                        title = obj.optString("title", bvid),
                        coverUrl = obj.optString("cover", ""),
                    )
                )
            }
            result
        } catch (t: Throwable) {
            Log.e(TAG, "bad task json", t)
            emptyList()
        }
    }

    override fun onDestroy() {
        cancelled = true
        worker.shutdownNow()
        super.onDestroy()
    }
}
