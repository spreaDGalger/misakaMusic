package io.github.spreadglager.misakamusic

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var prefs: SharedPreferences
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var folderList: LinearLayout
    private lateinit var folderScroll: ScrollView
    private lateinit var trackList: ListView
    private lateinit var searchRow: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var filterButton: Button
    private lateinit var loadButton: Button
    private lateinit var playAllButton: Button
    private lateinit var nowTitle: TextView
    private lateinit var timeText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseButton: Button

    private var currentFolder: Folder? = null
    private var currentTracks: List<Track> = emptyList()
    private var visibleTracks: List<Track> = emptyList()
    private var filterMode = 0
    private var userSeeking = false
    private var receiverRegistered = false
    private var downloadReceiverRegistered = false
    private var resumedDownloads = false
    private var lastDuration = 0
    private var pendingPlayKey: String? = null
    private var pendingPlayAll = false
    private val downloadingKeys = HashSet<String>()

    private val bg = Color.rgb(8, 11, 16)
    private val panel = Color.rgb(15, 22, 30)
    private val stroke = Color.rgb(28, 48, 62)
    private val blue = Color.rgb(127, 219, 255)
    private val textColor = Color.rgb(232, 247, 255)
    private val muted = Color.rgb(122, 143, 158)

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != PlaybackService.ACTION_STATE) return
            val playing = intent.getBooleanExtra(PlaybackService.EXTRA_STATE_PLAYING, false)
            val position = intent.getIntExtra(PlaybackService.EXTRA_STATE_POSITION, 0)
            val duration = intent.getIntExtra(PlaybackService.EXTRA_STATE_DURATION, 0)
            val title = intent.getStringExtra(PlaybackService.EXTRA_STATE_TITLE).orEmpty()
            val index = intent.getIntExtra(PlaybackService.EXTRA_STATE_INDEX, 0)
            val count = intent.getIntExtra(PlaybackService.EXTRA_STATE_COUNT, 0)

            lastDuration = duration
            playPauseButton.text = if (playing) "暂停" else "播放"
            nowTitle.text = if (title.isBlank()) "未播放" else title
            if (count > 0) {
                nowTitle.text = "${nowTitle.text}  (${index + 1}/$count)"
            }
            if (duration > 0) {
                seekBar.max = 1000
                if (!userSeeking) {
                    seekBar.progress = (position.toLong() * 1000L / duration).toInt()
                }
                timeText.text = "${formatTime(position)} / ${formatTime(duration)}"
            } else {
                seekBar.progress = 0
                timeText.text = "00:00 / 00:00"
            }
        }
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadService.ACTION_STATE) return
            val state = intent.getStringExtra(DownloadService.EXTRA_STATUS).orEmpty()
            val key = intent.getStringExtra(DownloadService.EXTRA_KEY).orEmpty()
            val title = intent.getStringExtra(DownloadService.EXTRA_MESSAGE).orEmpty()
            val done = intent.getIntExtra(DownloadService.EXTRA_DONE, 0)
            val count = intent.getIntExtra(DownloadService.EXTRA_COUNT, 0)
            when (state) {
                "downloading" -> {
                    if (key.isNotBlank()) downloadingKeys.add(key)
                    status.text = if (count > 0) "自动下载 $done/$count：$title" else "自动下载：$title"
                    trackAdapter.notifyDataSetChanged()
                }
                "done" -> {
                    downloadingKeys.remove(key)
                    trackAdapter.notifyDataSetChanged()
                    status.text = "已下载 $done/$count"
                    if (key == pendingPlayKey) {
                        pendingPlayKey = null
                        playDownloadedKey(key)
                    }
                }
                "error" -> {
                    downloadingKeys.remove(key)
                    trackAdapter.notifyDataSetChanged()
                    status.text = "下载失败：$title"
                }
                "all_done" -> {
                    downloadingKeys.clear()
                    trackAdapter.notifyDataSetChanged()
                    status.text = "自动下载完成（$done/$count）"
                    if (pendingPlayAll) {
                        pendingPlayAll = false
                        startDownloadedAll()
                    }
                    pendingPlayKey?.let { pending ->
                        pendingPlayKey = null
                        playDownloadedKey(pending)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("state", MODE_PRIVATE)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val baseLeft = dp(20)
        val baseTop = dp(18)
        val baseRight = dp(20)
        val baseBottom = dp(18)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(baseLeft, baseTop, baseRight, baseBottom)
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(
                baseLeft + insets.systemWindowInsetLeft,
                baseTop + insets.systemWindowInsetTop,
                baseRight + insets.systemWindowInsetRight,
                baseBottom + insets.systemWindowInsetBottom
            )
            insets
        }

        val title = TextView(this).apply {
            text = "御坂Music"
            textSize = 30f
            setTextColor(blue)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = 0.04f
        }
        val subtitle = TextView(this).apply {
            text = "公开收藏夹 · 本地 m4s · 后台播放"
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(4), 0, dp(14))
        }

        input = EditText(this).apply {
            hint = "输入 UID 或个人空间链接，例如 686127"
            setSingleLine(true)
            textSize = 15f
            setTextColor(textColor)
            setHintTextColor(Color.rgb(77, 97, 112))
            setPadding(dp(16), 0, dp(16), 0)
            minHeight = dp(52)
            background = rounded(panel, stroke, 14)
        }

        loadButton = makeButton("加载收藏夹", accent = true).apply {
            setOnClickListener { loadFolders() }
        }
        playAllButton = makeButton("全部播放", accent = false).apply {
            isEnabled = false
            setOnClickListener { playAll() }
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        actionRow.addView(loadButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginEnd = dp(10)
        })
        actionRow.addView(playAllButton, LinearLayout.LayoutParams(0, dp(48), 1f))

        status = TextView(this).apply {
            text = "准备就绪"
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(12), 0, dp(8))
            setTextIsSelectable(true)
        }

        searchInput = EditText(this).apply {
            hint = "搜索曲目"
            setSingleLine(true)
            textSize = 14f
            setTextColor(textColor)
            setHintTextColor(Color.rgb(77, 97, 112))
            setPadding(dp(14), 0, dp(14), 0)
            minHeight = dp(42)
            background = rounded(Color.rgb(13, 20, 28), stroke, 12)
        }
        filterButton = makeSmallButton("全部").apply {
            setOnClickListener {
                filterMode = (filterMode + 1) % 3
                filterButton.text = when (filterMode) {
                    1 -> "已下载"
                    2 -> "未下载"
                    else -> "全部"
                }
                applyFilter()
            }
        }
        searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(0, 0, 0, dp(8))
        }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            marginEnd = dp(8)
        })
        searchRow.addView(filterButton, LinearLayout.LayoutParams(dp(86), dp(42)))
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                applyFilter()
            }
        })

        folderList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        folderScroll = ScrollView(this).apply {
            addView(folderList)
        }

        trackList = ListView(this).apply {
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(6)
            setSelector(ColorDrawable(Color.TRANSPARENT))
            adapter = trackAdapter
            visibility = View.GONE
        }
        trackList.setOnItemClickListener { _, _, position, _ ->
            visibleTracks.getOrNull(position)?.let { playTrack(it) }
        }

        val listHost = FrameLayout(this).apply {
            addView(folderScroll, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(trackList, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }

        val playerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(panel, stroke, 16)
        }
        nowTitle = TextView(this).apply {
            text = "未播放"
            textSize = 14f
            setTextColor(textColor)
            maxLines = 2
        }
        timeText = TextView(this).apply {
            text = "00:00 / 00:00"
            textSize = 11f
            setTextColor(muted)
            setPadding(0, dp(4), 0, 0)
        }
        seekBar = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(blue)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(26, 42, 54))
            thumbTintList = ColorStateList.valueOf(blue)
            setPadding(0, dp(4), 0, dp(2))
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (lastDuration > 0) {
                    val position = progress.toLong() * lastDuration / 1000L
                    timeText.text = "${formatTime(position.toInt())} / ${formatTime(lastDuration)}"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userSeeking = false
                if (lastDuration > 0 && seekBar != null) {
                    val position = seekBar.progress.toLong() * lastDuration / 1000L
                    sendService(PlaybackService.ACTION_SEEK) {
                        it.putExtra(PlaybackService.EXTRA_POSITION, position.toInt())
                    }
                }
            }
        })

        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        }
        val prevButton = makeSmallButton("上一首").apply {
            setOnClickListener { sendService(PlaybackService.ACTION_PREV) }
        }
        playPauseButton = makeSmallButton("播放").apply {
            setOnClickListener { sendService(PlaybackService.ACTION_TOGGLE) }
        }
        val nextButton = makeSmallButton("下一首").apply {
            setOnClickListener { sendService(PlaybackService.ACTION_NEXT) }
        }
        val stopButton = makeSmallButton("停止").apply {
            setOnClickListener { sendService(PlaybackService.ACTION_STOP) }
        }
        controlRow.addView(prevButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            marginEnd = dp(6)
        })
        controlRow.addView(playPauseButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            marginEnd = dp(6)
        })
        controlRow.addView(nextButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            marginEnd = dp(6)
        })
        controlRow.addView(stopButton, LinearLayout.LayoutParams(0, dp(40), 1f))

        playerPanel.addView(nowTitle)
        playerPanel.addView(seekBar)
        playerPanel.addView(timeText)
        playerPanel.addView(controlRow)

        root.addView(title)
        root.addView(subtitle)
        root.addView(input)
        root.addView(actionRow)
        root.addView(status)
        root.addView(searchRow)
        root.addView(
            listHost,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        root.addView(
            playerPanel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
        )
        setContentView(root)

        input.setText(prefs.getString("last_uid", ""))
        restoreCache()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter(PlaybackService.ACTION_STATE)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(stateReceiver, filter)
            }
            receiverRegistered = true
        }
        if (!downloadReceiverRegistered) {
            val filter = IntentFilter(DownloadService.ACTION_STATE)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(downloadReceiver, filter)
            }
            downloadReceiverRegistered = true
        }
        if (!resumedDownloads) {
            resumedDownloads = true
            resumePendingDownloads()
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(stateReceiver)
            } catch (_: Throwable) {
            }
            receiverRegistered = false
        }
        if (downloadReceiverRegistered) {
            try {
                unregisterReceiver(downloadReceiver)
            } catch (_: Throwable) {
            }
            downloadReceiverRegistered = false
        }
        super.onStop()
    }

    private fun loadFolders() {
        val uid = BiliApi.parseUid(input.text.toString())
        if (uid == null) {
            status.text = "无法识别 UID。请输入数字 UID 或个人空间链接。"
            return
        }
        val uidText = uid.toString()
        if (prefs.getString("last_uid", "") != uidText) {
            prefs.edit().remove("last_tracks").apply()
        }
        prefs.edit().putString("last_uid", uidText).apply()
        loadButton.isEnabled = false
        playAllButton.isEnabled = false
        currentFolder = null
        currentTracks = emptyList()
        visibleTracks = emptyList()
        trackAdapter.notifyDataSetChanged()
        status.text = "正在读取 UID=$uid 的公开收藏夹..."
        folderList.removeAllViews()
        showFolders()
        worker.execute {
            try {
                val folders = BiliApi.folderList(uid)
                main.post {
                    loadButton.isEnabled = true
                    renderFolders(folders)
                }
            } catch (t: Throwable) {
                main.post {
                    loadButton.isEnabled = true
                    status.text = "读取失败：${t.message}"
                }
            }
        }
    }

    private fun renderFolders(folders: List<Folder>) {
        folderList.removeAllViews()
        showFolders()
        if (folders.isEmpty()) {
            status.text = "没有找到公开收藏夹。请先在 B 站把收藏夹设为公开。"
            return
        }
        status.text = "找到 ${folders.size} 个公开收藏夹，点击一个查看曲目"
        for (folder in folders) {
            val button = makeListButton("${folder.title}  (${folder.count})").apply {
                setOnClickListener { loadTracks(folder) }
            }
            folderList.addView(button)
        }
    }

    private fun loadTracks(folder: Folder) {
        loadButton.isEnabled = false
        playAllButton.isEnabled = false
        status.text = "正在读取「${folder.title}」曲目（分页）..."
        worker.execute {
            try {
                val tracks = BiliApi.tracks(folder.id) { page, total ->
                    main.post {
                        status.text = "「${folder.title}」读取第 $page 页，已 $total 首..."
                    }
                }
                main.post {
                    loadButton.isEnabled = true
                    currentFolder = folder
                    currentTracks = tracks
                    renderTracks(folder, tracks)
                    saveCache(folder, tracks)
                    autoDownload(tracks)
                }
            } catch (t: Throwable) {
                main.post {
                    loadButton.isEnabled = true
                    status.text = "读取曲目失败：${t.message}"
                }
            }
        }
    }

    private fun renderTracks(folder: Folder, tracks: List<Track>, cached: Boolean = false) {
        currentFolder = folder
        currentTracks = tracks
        searchRow.visibility = View.VISIBLE
        folderScroll.visibility = View.GONE
        trackList.visibility = View.VISIBLE
        searchInput.setText("")
        filterMode = 0
        filterButton.text = "全部"
        applyFilter()
        playAllButton.isEnabled = tracks.isNotEmpty()
        status.text = if (cached) {
            "已恢复上次缓存：${folder.title}，共 ${tracks.size} 首；点“加载收藏夹”可刷新"
        } else {
            "「${folder.title}」共 ${tracks.size} 首，已开始自动下载"
        }
        if (tracks.isEmpty()) {
            status.text = "「${folder.title}」没有可下载的视频曲目。"
        }
    }

    private fun showFolders() {
        folderScroll.visibility = View.VISIBLE
        trackList.visibility = View.GONE
        searchRow.visibility = View.GONE
        playAllButton.isEnabled = false
    }

    private fun applyFilter() {
        val query = searchInput.text.toString().trim()
        visibleTracks = currentTracks.filter { track ->
            val matchesText = query.isEmpty() || track.title.contains(query, ignoreCase = true)
            val downloaded = isDownloaded(track)
            val matchesFilter = when (filterMode) {
                1 -> downloaded
                2 -> !downloaded
                else -> true
            }
            matchesText && matchesFilter
        }
        trackAdapter.notifyDataSetChanged()
        if (currentTracks.isNotEmpty()) {
            status.text = "显示 ${visibleTracks.size}/${currentTracks.size} 首"
        }
    }

    private fun autoDownload(tracks: List<Track>) {
        val need = tracks.filter { track ->
            !isDownloaded(track) ||
                (track.coverUrl.isNotBlank() && CoverStore.path(this, track.bvid) == null)
        }
        if (need.isEmpty()) {
            status.text = "「${currentFolder?.title ?: "收藏夹"}」全部已缓存"
            return
        }
        enqueueDownloads(need)
        status.text = "已开始自动下载 ${need.size} 首（后台）"
    }

    private fun playTrack(track: Track) {
        if (!isDownloaded(track)) {
            pendingPlayKey = trackKey(track)
            enqueueDownloads(listOf(track))
            status.text = "正在下载后播放：${track.title}"
            return
        }
        val downloaded = currentTracks.filter { isDownloaded(it) }
        val startIndex = downloaded.indexOfFirst { trackKey(it) == trackKey(track) }
            .coerceAtLeast(0)
        if (downloaded.isEmpty()) {
            status.text = "没有可播放的本地文件"
            return
        }
        if (track.coverUrl.isNotBlank() && CoverStore.path(this, track.bvid) == null) {
            enqueueDownloads(listOf(track))
        }
        startQueue(downloaded, startIndex)
    }

    private fun playAll() {
        if (currentTracks.isEmpty()) return
        val downloaded = currentTracks.filter { isDownloaded(it) }
        if (downloaded.size == currentTracks.size) {
            startQueue(downloaded, 0)
            return
        }
        pendingPlayAll = true
        val need = currentTracks.filter { !isDownloaded(it) }
        enqueueDownloads(need)
        status.text = "正在下载 ${need.size} 首，完成后自动播放"
    }

    private fun startDownloadedAll() {
        val downloaded = currentTracks.filter { isDownloaded(it) }
        if (downloaded.isEmpty()) {
            status.text = "没有可播放的本地文件"
            return
        }
        startQueue(downloaded, 0)
    }

    private fun playDownloadedKey(key: String) {
        val track = currentTracks.firstOrNull { trackKey(it) == key } ?: return
        if (isDownloaded(track)) {
            playTrack(track)
        }
    }

    private fun enqueueDownloads(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val array = JSONArray()
        for (track in tracks) {
            array.put(
                JSONObject()
                    .put("bvid", track.bvid)
                    .put("cid", track.cid)
                    .put("title", track.title)
                    .put("cover", track.coverUrl)
            )
        }
        val intent = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_ENQUEUE
            putExtra(DownloadService.EXTRA_TASKS, array.toString())
        }
        startForegroundService(intent)
    }

    private fun resumePendingDownloads() {
        val raw = getSharedPreferences("downloads", MODE_PRIVATE)
            .getString("pending", null)
        if (raw.isNullOrBlank() || raw == "[]") return
        val intent = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_ENQUEUE
            putExtra(DownloadService.EXTRA_TASKS, "[]")
        }
        startForegroundService(intent)
    }

    private fun isDownloaded(track: Track): Boolean {
        val file = trackFile(track)
        return file.exists() && file.length() > 0L
    }

    private fun trackFile(track: Track): File {
        val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: File(filesDir, "music")
        dir.mkdirs()
        return File(dir, "${track.bvid}_${track.cid}.m4s")
    }

    private fun trackKey(track: Track): String = "${track.bvid}:${track.cid}"

    private fun startQueue(tracks: List<Track>, startIndex: Int) {
        val array = JSONArray()
        for (track in tracks) {
            val file = trackFile(track)
            if (!file.exists() || file.length() <= 0L) continue
            array.put(
                JSONObject()
                    .put("path", file.absolutePath)
                    .put("title", track.title)
                    .put("coverPath", CoverStore.path(this, track.bvid) ?: "")
            )
        }
        if (array.length() == 0) {
            status.text = "没有可播放的本地文件"
            return
        }
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_PLAY_QUEUE
            putExtra(PlaybackService.EXTRA_QUEUE, array.toString())
            putExtra(
                PlaybackService.EXTRA_INDEX,
                startIndex.coerceIn(0, array.length() - 1)
            )
        }
        startForegroundService(intent)
    }

    private fun sendService(action: String, configure: (Intent) -> Unit = {}) {
        val intent = Intent(this, PlaybackService::class.java).apply {
            this.action = action
        }
        configure(intent)
        startService(intent)
    }

    private fun saveCache(folder: Folder, tracks: List<Track>) {
        val array = JSONArray()
        for (track in tracks) {
            array.put(
                JSONObject()
                    .put("bvid", track.bvid)
                    .put("cid", track.cid)
                    .put("title", track.title)
                    .put("cover", track.coverUrl)
            )
        }
        val obj = JSONObject()
            .put("folderId", folder.id)
            .put("folderTitle", folder.title)
            .put("tracks", array)
        prefs.edit().putString("last_tracks", obj.toString()).apply()
    }

    private fun restoreCache() {
        val raw = prefs.getString("last_tracks", null) ?: return
        try {
            val obj = JSONObject(raw)
            val array = obj.optJSONArray("tracks") ?: return
            val tracks = ArrayList<Track>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val bvid = item.optString("bvid", "")
                val cid = item.optLong("cid", 0L)
                val title = item.optString("title", bvid)
                val cover = item.optString("cover", "")
                if (bvid.isNotEmpty() && cid > 0L) {
                    tracks.add(Track(bvid, cid, title, cover))
                }
            }
            if (tracks.isEmpty()) return
            val folder = Folder(
                id = obj.optLong("folderId", 0L),
                title = obj.optString("folderTitle", "上次的收藏夹"),
                count = tracks.size,
            )
            renderTracks(folder, tracks, cached = true)
        } catch (_: Throwable) {
            prefs.edit().remove("last_tracks").apply()
        }
    }

    private fun makeButton(label: String, accent: Boolean): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(if (accent) bg else textColor)
            background = rounded(if (accent) blue else panel, if (accent) blue else stroke, 14)
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(16), 0, dp(16), 0)
        }
    }

    private fun makeSmallButton(label: String): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            setTextColor(blue)
            background = rounded(Color.rgb(18, 28, 38), stroke, 10)
            minHeight = dp(40)
            minimumHeight = dp(40)
            setPadding(dp(6), 0, dp(6), 0)
        }
    }

    private fun makeListButton(label: String): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(textColor)
            background = rounded(Color.rgb(13, 20, 28), stroke, 12)
            minHeight = dp(52)
            minimumHeight = dp(52)
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }
    }

    private fun rounded(fill: Int, strokeColor: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            setStroke(dp(1), strokeColor)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun formatTime(ms: Int): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val minutes = total / 60
        val seconds = total % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    private inner class TrackAdapter : BaseAdapter() {
        override fun getCount(): Int = visibleTracks.size

        override fun getItem(position: Int): Any? = visibleTracks.getOrNull(position)

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                textSize = 14f
                setTextColor(textColor)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                minHeight = dp(52)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                background = rounded(Color.rgb(13, 20, 28), stroke, 12)
            }
            val track = visibleTracks[position]
            val downloaded = isDownloaded(track)
            val downloading = downloadingKeys.contains(trackKey(track))
            val prefix = when {
                downloading -> "↓ "
                downloaded -> "✓ "
                else -> ""
            }
            view.text = prefix + track.title
            view.setTextColor(if (downloaded) blue else textColor)
            return view
        }
    }

    private val trackAdapter = TrackAdapter()

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(stateReceiver)
            } catch (_: Throwable) {
            }
            receiverRegistered = false
        }
        if (downloadReceiverRegistered) {
            try {
                unregisterReceiver(downloadReceiver)
            } catch (_: Throwable) {
            }
            downloadReceiverRegistered = false
        }
        worker.shutdownNow()
        super.onDestroy()
    }
}
