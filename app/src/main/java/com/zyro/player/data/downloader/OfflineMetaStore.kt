package com.zyro.player.data.downloader

import android.content.Context
import android.util.Log
import com.zyro.player.data.remote.CommentInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date

/**
 * Локальное хранилище метаданных офлайн-треков: комментарии (и в будущем
 * другой текст, который не лежит в Room).
 *
 * Файлы складываются в `filesDir/offline_meta/comments` и называются по хешу
 * URL трека — база данных не меняется, поэтому не нужны миграции Room.
 */
class OfflineMetaStore(context: Context) {

    private val commentsDir = File(context.filesDir, "offline_meta/comments")

    /** Сохраняет комментарии трека (перезаписывает предыдущий снимок). */
    fun saveComments(trackUrl: String, comments: List<CommentInfo>) {
        if (trackUrl.isBlank()) return
        runCatching {
            commentsDir.mkdirs()
            val array = JSONArray()
            comments.forEach { comment ->
                array.put(
                    JSONObject().apply {
                        put("id", comment.id)
                        put("body", comment.body)
                        put("username", comment.username)
                        put("userAvatar", comment.userAvatar)
                        put("userPermalink", comment.userPermalink)
                        put("timestampMs", comment.timestampMs)
                        put("createdAt", comment.createdAt?.time ?: 0L)
                    }
                )
            }
            val target = fileFor(trackUrl)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(array.toString())
                tmp.delete()
            }
        }.onFailure {
            Log.w("ZYRO_PLAYER", "Не удалось сохранить офлайн-комментарии: ${it.message}")
        }
    }

    /** Читает сохранённые комментарии; пустой список, если снимка нет. */
    fun loadComments(trackUrl: String): List<CommentInfo> {
        if (trackUrl.isBlank()) return emptyList()
        return runCatching {
            val file = fileFor(trackUrl)
            if (!file.exists()) return emptyList()
            val array = JSONArray(file.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val body = item.optString("body", "")
                    if (body.isBlank()) continue
                    val createdAtMs = item.optLong("createdAt", 0L)
                    add(
                        CommentInfo(
                            id = item.optLong("id", 0L),
                            body = body,
                            username = item.optString("username", ""),
                            userAvatar = item.optString("userAvatar", ""),
                            userPermalink = item.optString("userPermalink", ""),
                            timestampMs = item.optInt("timestampMs", 0),
                            createdAt = createdAtMs.takeIf { it > 0L }?.let(::Date)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Есть ли для трека сохранённый снимок комментариев. */
    fun hasComments(trackUrl: String): Boolean = fileFor(trackUrl).exists()

    fun deleteComments(trackUrl: String) {
        runCatching { fileFor(trackUrl).delete() }
    }

    fun clearAll() {
        runCatching {
            commentsDir.listFiles().orEmpty().forEach { it.delete() }
            commentsDir.delete()
        }
    }

    private fun fileFor(trackUrl: String): File =
        File(commentsDir, "${trackUrl.hashCode()}.json")
}
