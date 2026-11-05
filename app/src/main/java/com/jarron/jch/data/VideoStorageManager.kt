package com.jarron.jch.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class VideoStorageManager(private val context: Context) {
    private val videosDir: File by lazy {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "JumpCutHero")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    private val metadataFile: File by lazy {
        File(context.filesDir, "recorded_videos_metadata.json")
    }

    private val _videos = MutableStateFlow<List<RecordedVideo>>(emptyList())
    val videos: StateFlow<List<RecordedVideo>> = _videos.asStateFlow()

    init {
        loadVideos()
    }

    fun getVideosDirectory(): File = videosDir

    fun createNewVideoOutputFile(): File {
        val timestamp = System.currentTimeMillis()
        return File(videosDir, "JumpCutHero_$timestamp.mp4")
    }

    fun createChunkDirectory(sessionId: String): File {
        val dir = File(context.cacheDir, "chunks_$sessionId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun saveVideo(video: RecordedVideo) {
        val current = _videos.value.toMutableList()
        current.add(0, video)
        _videos.value = current
        persistMetadata(current)
    }

    fun deleteVideo(video: RecordedVideo) {
        val file = File(video.filePath)
        if (file.exists()) {
            file.delete()
        }
        val updated = _videos.value.filter { it.id != video.id }
        _videos.value = updated
        persistMetadata(updated)
    }

    fun shareVideo(video: RecordedVideo) {
        val file = File(video.filePath)
        if (!file.exists()) return

        val uri: Uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            Uri.fromFile(file)
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, video.title)
            putExtra(
                Intent.EXTRA_TEXT,
                "Check out this jump cut video! Sliced ${video.percentSaved}% of silence with Jump Cut Hero."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Jump Cut Video").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun loadVideos() {
        if (!metadataFile.exists()) {
            _videos.value = emptyList()
            return
        }
        try {
            val content = metadataFile.readText()
            val jsonArray = JSONArray(content)
            val list = mutableListOf<RecordedVideo>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val filePath = obj.getString("filePath")
                if (File(filePath).exists()) {
                    list.add(
                        RecordedVideo(
                            id = obj.getString("id"),
                            title = obj.getString("title"),
                            filePath = filePath,
                            timestamp = obj.getLong("timestamp"),
                            originalDurationMs = obj.getLong("originalDurationMs"),
                            jumpCutDurationMs = obj.getLong("jumpCutDurationMs"),
                            cutCount = obj.getInt("cutCount"),
                            fileSizeBytes = obj.getLong("fileSizeBytes")
                        )
                    )
                }
            }
            _videos.value = list
        } catch (e: Exception) {
            _videos.value = emptyList()
        }
    }

    private fun persistMetadata(list: List<RecordedVideo>) {
        try {
            val jsonArray = JSONArray()
            for (v in list) {
                val obj = JSONObject().apply {
                    put("id", v.id)
                    put("title", v.title)
                    put("filePath", v.filePath)
                    put("timestamp", v.timestamp)
                    put("originalDurationMs", v.originalDurationMs)
                    put("jumpCutDurationMs", v.jumpCutDurationMs)
                    put("cutCount", v.cutCount)
                    put("fileSizeBytes", v.fileSizeBytes)
                }
                jsonArray.put(obj)
            }
            metadataFile.writeText(jsonArray.toString())
        } catch (ignored: Exception) {
        }
    }
}
