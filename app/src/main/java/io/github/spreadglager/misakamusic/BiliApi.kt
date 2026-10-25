package io.github.spreadglager.misakamusic

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class Folder(
    val id: Long,
    val title: String,
    val count: Int,
)

data class Track(
    val bvid: String,
    val cid: Long,
    val title: String,
    val coverUrl: String = "",
)

object BiliApi {
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/135.0 Safari/537.36 BiliMusicSpike/0.1"
    private const val REFERER = "https://www.bilibili.com/"

    fun parseUid(input: String): Long? {
        val text = input.trim()
        text.toLongOrNull()?.let { if (it > 0) return it }
        val match = Regex("""space\.bilibili\.com/(\d+)""").find(text)
        if (match != null) {
            return match.groupValues[1].toLongOrNull()
        }
        return null
    }

    fun folderList(uid: Long): List<Folder> {
        val json = getJson(
            "https://api.bilibili.com/x/v3/fav/folder/created/list-all" +
                "?up_mid=$uid&type=2"
        )
        ensureOk(json)
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        val result = ArrayList<Folder>(list.length())
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optLong("id", 0L)
            if (id <= 0L) continue
            result.add(
                Folder(
                    id = id,
                    title = item.optString("title", "未命名收藏夹"),
                    count = item.optInt("media_count", 0),
                )
            )
        }
        return result
    }

    fun tracks(
        mediaId: Long,
        onPage: (page: Int, total: Int) -> Unit = { _, _ -> },
    ): List<Track> {
        val result = ArrayList<Track>()
        val seen = HashSet<String>()
        var pn = 1
        var hasMore = true

        while (hasMore && pn <= 50) {
            val json = getJson(
                "https://api.bilibili.com/x/v3/fav/resource/list" +
                    "?media_id=$mediaId&pn=$pn&ps=20&platform=web"
            )
            ensureOk(json)
            val data = json.optJSONObject("data")
            val medias = data?.optJSONArray("medias")
            if (medias == null) break

            for (i in 0 until medias.length()) {
                val media = medias.optJSONObject(i) ?: continue
                if (media.optInt("type", 0) != 2) continue
                if (media.optInt("attr", 0) != 0) continue
                val bvid = media.optString("bvid", "")
                if (bvid.isEmpty()) continue
                val title = media.optString("title", bvid)
                val cover = normalizeCover(media.optString("cover", ""))
                val pageCount = media.optInt("page", 1)
                val firstCid = media.optJSONObject("ugc")?.optLong("first_cid", 0L) ?: 0L

                if (pageCount <= 1) {
                    val cid = if (firstCid > 0L) firstCid else viewFirstCid(bvid)
                    if (cid > 0L) {
                        val key = "$bvid:$cid:1"
                        if (seen.add(key)) result.add(Track(bvid, cid, title, cover))
                    }
                } else {
                    val pages = videoPages(bvid)
                    if (pages.isEmpty()) {
                        if (firstCid > 0L) {
                            val key = "$bvid:$firstCid:1"
                            if (seen.add(key)) result.add(Track(bvid, firstCid, title, cover))
                        }
                    } else {
                        for (page in pages) {
                            val key = "$bvid:${page.cid}:${page.page}"
                            if (seen.add(key)) {
                                result.add(
                                    Track(
                                        bvid = bvid,
                                        cid = page.cid,
                                        title = "$title - P${page.page} ${page.part}",
                                        coverUrl = cover,
                                    )
                                )
                            }
                        }
                    }
                }
            }

            onPage(pn, result.size)
            hasMore = data?.optBoolean("has_more", false) == true
            pn++
            if (hasMore) {
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        return result
    }

    private data class Page(val cid: Long, val page: Int, val part: String)

    private fun viewFirstCid(bvid: String): Long {
        val json = getJson("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
        ensureOk(json)
        return json.optJSONObject("data")?.optLong("cid", 0L) ?: 0L
    }

    private fun videoPages(bvid: String): List<Page> {
        val json = getJson("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
        ensureOk(json)
        val pages = json.optJSONObject("data")?.optJSONArray("pages") ?: return emptyList()
        val result = ArrayList<Page>(pages.length())
        for (i in 0 until pages.length()) {
            val page = pages.optJSONObject(i) ?: continue
            val cid = page.optLong("cid", 0L)
            if (cid <= 0L) continue
            result.add(
                Page(
                    cid = cid,
                    page = page.optInt("page", i + 1),
                    part = page.optString("part", "P${i + 1}"),
                )
            )
        }
        return result
    }

    fun audioUrl(bvid: String, cid: Long): String {
        val json = getJson(
            "https://api.bilibili.com/x/player/playurl" +
                "?bvid=$bvid&cid=$cid&fnval=16&fnver=0&fourk=1"
        )
        ensureOk(json)
        val dash = json.optJSONObject("data")?.optJSONObject("dash")
            ?: throw IOException("没有 DASH 音频流")
        val audio = dash.optJSONArray("audio")
            ?: throw IOException("没有可用音频")
        var best: JSONObject? = null
        var bestBandwidth = -1L
        for (i in 0 until audio.length()) {
            val item = audio.optJSONObject(i) ?: continue
            val bandwidth = item.optLong("bandwidth", 0L)
            if (bandwidth > bestBandwidth) {
                bestBandwidth = bandwidth
                best = item
            }
        }
        val item = best ?: throw IOException("没有可用音频")
        val url = item.optString("baseUrl", "").ifEmpty { item.optString("base_url", "") }
        if (url.isEmpty()) throw IOException("音频地址为空")
        return url
    }

    fun download(
        url: String,
        dest: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        shouldStop: () -> Boolean = { false },
    ) {
        dest.parentFile?.mkdirs()
        val temp = File(dest.parentFile, dest.name + ".part")
        val existing = if (temp.exists()) temp.length() else 0L
        val connection = openConnection(url)
        try {
            if (existing > 0L) {
                connection.setRequestProperty("Range", "bytes=$existing-")
            }
            val code = connection.responseCode
            var append = false
            var total = connection.contentLengthLong
            when {
                code == 206 && existing > 0L -> {
                    append = true
                    if (total > 0L) total += existing
                }
                code == 200 -> {
                    append = false
                    if (existing > 0L) temp.delete()
                }
                code == 416 && existing > 0L -> {
                    temp.delete()
                    throw IOException("断点续传范围失效，请重试")
                }
                code !in 200..299 -> throw IOException("下载失败 HTTP $code")
            }
            var copied = if (append) existing else 0L
            connection.inputStream.use { input ->
                FileOutputStream(temp, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (shouldStop()) throw IOException("下载已取消")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(copied, total)
                    }
                }
            }
            if (temp.length() <= 0L) throw IOException("下载文件为空")
            if (dest.exists()) dest.delete()
            if (!temp.renameTo(dest)) {
                temp.copyTo(dest, overwrite = true)
                temp.delete()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeCover(value: String): String {
        val cover = value.trim()
        if (cover.isEmpty()) return ""
        return if (cover.startsWith("//")) "https:$cover" else cover
    }

    private fun getJson(url: String): JSONObject {
        val connection = openConnection(url)
        return try {
            val code = connection.responseCode
            val stream: InputStream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: throw IOException("HTTP $code")
            }
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (text.isBlank()) throw IOException("接口返回为空")
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", UA)
        connection.setRequestProperty("Referer", REFERER)
        connection.setRequestProperty("Accept", "application/json, text/plain, */*")
        return connection
    }

    private fun ensureOk(json: JSONObject) {
        val code = json.optInt("code", -1)
        if (code != 0) {
            val message = json.optString("message", "未知错误")
            throw IOException("B站接口错误 $code: $message")
        }
    }
}
