package io.github.spreadglager.misakamusic

import android.content.Context
import java.io.File

object CoverStore {
    fun dir(context: Context): File {
        val dir = File(context.filesDir, "covers")
        dir.mkdirs()
        return dir
    }

    fun file(context: Context, bvid: String): File {
        return File(dir(context), "$bvid.jpg")
    }

    fun existing(context: Context, bvid: String): File? {
        val file = file(context, bvid)
        return if (file.exists() && file.length() > 0L) file else null
    }

    fun path(context: Context, bvid: String): String? {
        return existing(context, bvid)?.absolutePath
    }

    fun ensure(context: Context, track: Track): File? {
        if (track.coverUrl.isBlank()) return null
        existing(context, track.bvid)?.let { return it }
        val file = file(context, track.bvid)
        return try {
            BiliApi.download(track.coverUrl, file)
            if (file.exists() && file.length() > 0L) file else null
        } catch (_: Throwable) {
            null
        }
    }
}
