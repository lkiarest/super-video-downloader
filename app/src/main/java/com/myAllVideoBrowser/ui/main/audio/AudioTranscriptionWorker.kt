package com.myAllVideoBrowser.ui.main.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.ui.main.home.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume

class AudioTranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val WORK_NAME = "audio_transcription"
        const val URI = "uri"
        const val NAME = "name"
        const val OWNER = "owner"
        const val LANGUAGE = "language"
        const val CREATED_AT = "created_at"
        const val STAGE = "stage"
        const val PROGRESS = "progress"
        const val ERROR = "error"
        private const val CHANNEL = "audio_transcription"
        private const val NOTIFICATION_ID = 7302
        private const val CHUNK_BYTES = 16000 * 2 * 20
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uri = Uri.parse(inputData.getString(URI) ?: return@withContext Result.failure())
        val owner = inputData.getString(OWNER) ?: return@withContext Result.failure()
        val language = inputData.getString(LANGUAGE) ?: "zh"
        if (uri.scheme !in listOf("content", "file") || language !in listOf("zh", "en")) {
            return@withContext Result.failure(workDataOf(ERROR to "decode"))
        }
        val folder = AudioExtractionWorker.directory(applicationContext)
        val base = File(inputData.getString(NAME) ?: "audio").nameWithoutExtension
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim('.', ' ').take(80).ifBlank { "audio" }
        val output = File(folder, "$base-${id.toString().take(8)}.txt")
        val partial = File(folder, ".$id.txt.part")
        val jobFolder = File(applicationContext.noBackupFilesDir, "transcription-$id").apply { mkdirs() }
        val pcm = File(jobFolder, "audio.pcm")
        val decoding = File(jobFolder, "audio.pcm.part")
        val api = WhisperApi(applicationContext)
        var stage = "decode"
        var keepForRetry = false
        try {
            setForeground(foregroundInfo())
            if (output.isFile && output.length() > 0L) return@withContext Result.success(workDataOf(NAME to output.name))
            if (api.account()?.getString("id") != owner) throw WhisperApi.ApiException(401, "login_required")
            cleanupPending(api, owner)
            Log.i("AudioTranscription", "Start id=$id attempt=$runAttemptCount language=$language")
            setProgress(workDataOf(STAGE to stage))
            if (!pcm.isFile) {
                val source = if (uri.scheme == "file") requireNotNull(uri.path)
                    else FFmpegKitConfig.getSafParameterForRead(applicationContext, uri)
                val arguments = arrayOf("-y", "-i", source, "-map", "0:a:0", "-vn",
                    "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", "-f", "s16le", decoding.absolutePath)
                val session = suspendCancellableCoroutine<FFmpegSession> { continuation ->
                    val running = FFmpegKit.executeWithArgumentsAsync(arguments, { completed ->
                        if (continuation.isActive) continuation.resume(completed)
                    }, {}, {})
                    continuation.invokeOnCancellation { FFmpegKit.cancel(running.sessionId) }
                }
                ensureActive()
                if (!ReturnCode.isSuccess(session.returnCode) || decoding.length() == 0L || decoding.length() % 2 != 0L) {
                    Log.w("AudioTranscription", "Decode failed id=$id code=${session.returnCode}")
                    return@withContext Result.failure(workDataOf(ERROR to stage))
                }
                check(decoding.renameTo(pcm)) { "Cannot save prepared audio" }
            }
            stage = "upload"
            val registration = api.request(owner, "PUT", "/v1/sessions/$id", api.jsonBody(JSONObject()
                .put("createdAt", inputData.getString(CREATED_AT))
                .put("language", language).put("model", WhisperApi.MODEL).put("consentVersion", WhisperApi.CONSENT)))
            if (!registration.isNull("model") && registration.getString("model") != WhisperApi.MODEL) {
                throw WhisperApi.ApiException(409, "model_mismatch")
            }
            if (!folder.isDirectory && !folder.mkdirs()) error("Cannot create transcript directory")
            var hasText = false
            pcm.inputStream().buffered().use { input ->
                partial.bufferedWriter(Charsets.UTF_8).use { writer ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    var startSample = 0L
                    var seq = 1
                    while (true) {
                        ensureActive()
                        var count = 0
                        while (count < buffer.size) {
                            val read = input.read(buffer, count, buffer.size - count)
                            if (read < 0) break
                            count += read
                        }
                        if (count == 0) break
                        val segmentId = UUID.nameUUIDFromBytes("$id:$seq".toByteArray(Charsets.UTF_8)).toString()
                        val resultFile = File(jobFolder, "$seq.txt")
                        val text = if (resultFile.isFile) resultFile.readText() else {
                            stage = "upload"
                            setProgress(workDataOf(STAGE to stage, PROGRESS to (startSample * 2 * 100 / pcm.length()).toInt()))
                            val segmentPath = "/v1/sessions/$id/segments/$segmentId"
                            var result = try {
                                api.request(owner, "GET", segmentPath)
                            } catch (error: WhisperApi.ApiException) {
                                if (error.status != 404) throw error
                                val wav = wavBytes(buffer, count)
                                val hash = MessageDigest.getInstance("SHA-256").digest(wav)
                                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                                val metadata = JSONObject().put("segmentId", segmentId).put("seq", seq).put("epoch", 0)
                                    .put("startSample", startSample).put("endSample", startSample + count / 2)
                                    .put("sampleRate", 16000).put("channels", 1).put("sha256", hash)
                                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                                    .addFormDataPart("metadata", metadata.toString())
                                    .addFormDataPart("file", "$segmentId.wav", wav.toRequestBody("audio/wav".toMediaType())).build()
                                api.request(owner, "POST", "/v1/sessions/$id/segments", body, segmentId)
                            }
                            stage = "recognize"
                            var polls = 0
                            while (result.getString("status") in listOf("queued", "processing", "retryWait")) {
                                ensureActive()
                                setProgress(workDataOf(STAGE to stage, PROGRESS to (startSample * 2 * 100 / pcm.length()).toInt()))
                                if (++polls > 300) throw IOException("Recognition polling timed out")
                                delay(result.optLong("pollAfterMs", 2000).coerceIn(1000, 60_000))
                                result = api.request(owner, "GET", segmentPath)
                            }
                            if (result.getString("status") != "succeeded") {
                                throw WhisperApi.ApiException(422, result.optJSONObject("error")?.optString("code") ?: "recognition_failed")
                            }
                            (if (result.isNull("text")) "" else result.getString("text")).also {
                                val temp = File(jobFolder, "$seq.txt.part")
                                temp.writeText(it)
                                check(temp.renameTo(resultFile))
                            }
                        }
                        if (text.isNotBlank()) {
                            writer.appendLine(text.trim())
                            hasText = true
                        }
                        startSample += count / 2
                        seq++
                        Log.d("AudioTranscription", "Segment complete id=$id seq=${seq - 1}")
                    }
                }
            }
            ensureActive()
            if (!hasText) return@withContext Result.failure(workDataOf(ERROR to "no_speech"))
            stage = "save"
            check(partial.renameTo(output)) { "Cannot save transcript" }
            Log.i("AudioTranscription", "Complete id=$id bytes=${output.length()}")
            Result.success(workDataOf(NAME to output.name))
        } catch (cancelled: CancellationException) {
            // WorkManager may interrupt and resume the same work; keep segment IDs and results stable.
            keepForRetry = stopReason != WorkInfo.STOP_REASON_CANCELLED_BY_APP
            Log.i("AudioTranscription", "Stopped id=$id stage=$stage")
            throw cancelled
        } catch (error: Exception) {
            val apiError = error as? WhisperApi.ApiException
            val transient = stage in listOf("upload", "recognize") && error is IOException &&
                (apiError == null || apiError.status == 408 || apiError.status == 429 || apiError.status >= 500)
            Log.e("AudioTranscription", "Failed id=$id stage=$stage http=${apiError?.status} code=${apiError?.code} exception=${error.javaClass.simpleName}")
            if (transient && runAttemptCount < 5) {
                keepForRetry = true
                setProgress(workDataOf(STAGE to "retry"))
                Result.retry()
            } else {
                val reason = when {
                    apiError?.code == "quota_exceeded" -> "quota"
                    apiError?.status == 401 -> "auth"
                    apiError?.status == 403 -> "forbidden"
                    apiError != null -> "server"
                    stage in listOf("upload", "recognize") -> "network"
                    else -> stage
                }
                Result.failure(workDataOf(ERROR to reason))
            }
        } finally {
            partial.delete()
            decoding.delete()
            if (!keepForRetry) {
                jobFolder.deleteRecursively()
                // These are temporary server sessions. Retry a failed cleanup on the next transcription.
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        val pending = File(applicationContext.noBackupFilesDir, "transcription-cleanup").apply { mkdirs() }
                        File(pending, id.toString()).writeText(owner)
                        cleanupPending(api, owner)
                    } catch (error: Exception) {
                        Log.w("AudioTranscription", "Cleanup deferred id=$id exception=${error.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    private suspend fun cleanupPending(api: WhisperApi, owner: String) {
        val pending = File(applicationContext.noBackupFilesDir, "transcription-cleanup")
        val owned = pending.listFiles().orEmpty().filter {
            it.isFile && runCatching { it.readText() == owner }.getOrDefault(false)
        }
        for (file in owned.take(3)) {
            try {
                val session = UUID.fromString(file.name)
                withTimeout(10_000) { api.request(owner, "DELETE", "/v1/sessions/$session") }
                file.delete()
            } catch (error: WhisperApi.ApiException) {
                if (error.status == 404) file.delete()
                else Log.w("AudioTranscription", "Cleanup deferred http=${error.status}")
            } catch (cancelled: CancellationException) {
                if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
                Log.w("AudioTranscription", "Cleanup deferred timeout")
            } catch (error: Exception) {
                Log.w("AudioTranscription", "Cleanup deferred exception=${error.javaClass.simpleName}")
            }
        }
    }

    private fun wavBytes(pcm: ByteArray, count: Int): ByteArray = ByteBuffer.allocate(44 + count).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + count)
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
        putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
        put("data".toByteArray(Charsets.US_ASCII)); putInt(count); put(pcm, 0, count)
    }.array()

    private fun foregroundInfo(): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.transcription_title), NotificationManager.IMPORTANCE_LOW))
        val open = Intent(context, MainActivity::class.java).putExtra("open_audio", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.audio_file_24px).setContentTitle(context.getString(R.string.transcription_running))
            .setContentText(context.getString(R.string.transcription_background))
            .setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).setProgress(0, 0, true)
            .addAction(0, context.getString(R.string.all_text_cancel), WorkManager.getInstance(context).createCancelPendingIntent(id)).build()
        return if (Build.VERSION.SDK_INT >= 29) {
            val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            ForegroundInfo(NOTIFICATION_ID, notification, type)
        } else ForegroundInfo(NOTIFICATION_ID, notification)
    }
}
