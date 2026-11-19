package com.myAllVideoBrowser.ui.main.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.FFprobeKit
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.ui.main.home.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

class AudioExtractionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val WORK_NAME = "audio_extraction"
        const val URI = "uri"
        const val NAME = "name"
        const val FORMAT = "format"
        const val PROGRESS = "progress"
        const val ERROR = "error"
        private const val CHANNEL = "audio_extraction"
        private const val NOTIFICATION_ID = 7301

        fun directory(context: Context): File = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "Music"),
            "Extracted"
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uri = Uri.parse(inputData.getString(URI) ?: return@withContext Result.failure())
        val format = inputData.getString(FORMAT) ?: "mp3"
        if (uri.scheme !in listOf("content", "file") || format !in listOf("mp3", "m4a")) {
            return@withContext Result.failure(workDataOf(ERROR to "read"))
        }
        val folder = directory(applicationContext)
        val base = File(inputData.getString(NAME) ?: "audio").nameWithoutExtension
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim('.', ' ').take(80).ifBlank { "audio" }
        val output = File(folder, "$base-${id.toString().take(8)}.$format")
        val partial = File(folder, ".${id}.part")
        var stage = "prepare"
        try {
            setForeground(foregroundInfo())
            if (!folder.isDirectory && !folder.mkdirs()) error("Cannot create audio directory")
            setProgress(workDataOf(PROGRESS to -1))
            Log.i("AudioExtraction", "Start id=$id format=$format")
            stage = "read"
            fun source(): String = if (uri.scheme == "file") {
                requireNotNull(uri.path)
            } else {
                FFmpegKitConfig.getSafParameterForRead(applicationContext, uri)
            }
            val media = FFprobeKit.getMediaInformation(source()).mediaInformation
            if (media == null) {
                Log.w("AudioExtraction", "Cannot read media id=$id")
                return@withContext Result.failure(workDataOf(ERROR to "read"))
            }
            if (media.streams.none { it.type == "audio" }) {
                Log.w("AudioExtraction", "No audio stream id=$id")
                return@withContext Result.failure(workDataOf(ERROR to "no_audio"))
            }
            val durationMs = (media.duration?.toDoubleOrNull() ?: 0.0) * 1000
            ensureActive()
            stage = "extract"
            val arguments = mutableListOf("-y", "-i", source(), "-map", "0:a:0", "-vn")
            if (format == "mp3") {
                arguments.addAll(listOf("-c:a", "libmp3lame", "-q:a", "2", "-ac", "2", "-f", "mp3"))
            } else {
                arguments.addAll(listOf("-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", "-f", "ipod"))
            }
            arguments.add(partial.absolutePath)
            var lastProgress = -1
            val session = suspendCancellableCoroutine<FFmpegSession> { continuation ->
                val running = FFmpegKit.executeWithArgumentsAsync(arguments.toTypedArray(), { completed ->
                    if (continuation.isActive) continuation.resume(completed)
                }, {}, { statistics ->
                    if (durationMs > 0 && continuation.isActive) {
                        val progress = (statistics.time / durationMs * 100).toInt().coerceIn(0, 99)
                        if (progress != lastProgress) {
                            lastProgress = progress
                            setProgressAsync(workDataOf(PROGRESS to progress))
                        }
                    }
                })
                continuation.invokeOnCancellation { FFmpegKit.cancel(running.sessionId) }
            }
            ensureActive()
            if (!ReturnCode.isSuccess(session.returnCode) || partial.length() == 0L) {
                Log.e("AudioExtraction", "Failed id=$id stage=$stage code=${session.returnCode}")
                return@withContext Result.failure(workDataOf(ERROR to "extract"))
            }
            stage = "save"
            if (!partial.renameTo(output)) error("Cannot publish audio file")
            Log.i("AudioExtraction", "Complete id=$id format=$format bytes=${output.length()}")
            Result.success(workDataOf(NAME to output.name))
        } catch (cancelled: CancellationException) {
            Log.i("AudioExtraction", "Cancelled id=$id stage=$stage")
            throw cancelled
        } catch (error: Exception) {
            Log.e("AudioExtraction", "Failed id=$id stage=$stage exception=${error.javaClass.simpleName}")
            Result.failure(workDataOf(ERROR to if (stage == "read") "read" else "extract"))
        } finally {
            partial.delete()
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.audio_extract), NotificationManager.IMPORTANCE_LOW))
        }
        val open = Intent(context, MainActivity::class.java)
            .putExtra("open_audio", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.audio_file_24px)
            .setContentTitle(context.getString(R.string.audio_extracting))
            .setContentText(context.getString(R.string.audio_background_hint))
            .setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .setProgress(0, 0, true)
            .addAction(0, context.getString(R.string.all_text_cancel), WorkManager.getInstance(context).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            ForegroundInfo(NOTIFICATION_ID, notification, type)
        } else ForegroundInfo(NOTIFICATION_ID, notification)
    }
}
