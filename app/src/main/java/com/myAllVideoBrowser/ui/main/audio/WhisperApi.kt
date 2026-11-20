package com.myAllVideoBrowser.ui.main.audio

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Matches whisper_app/lib/services/api.dart. Provider credentials stay on the server. */
internal class WhisperApi(context: Context) {
    companion object {
        const val BASE_URL = "https://voice.xwhisper.uk"
        const val MODEL = "whisper-large-v3-turbo"
        const val CONSENT = "2026-11-08-models"
        private const val KEY_ALIAS = "whisper_session"
        private val authLock = Mutex()
        private val client = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()
    }

    class ApiException(val status: Int, val code: String) : IOException("HTTP $status ($code)")
    private val sessionFile = AtomicFile(File(context.noBackupFilesDir, "whisper-session.enc"))

    suspend fun account(): JSONObject? = withContext(Dispatchers.IO) {
        authLock.withLock { readSession()?.getJSONObject("user") }
    }

    suspend fun login(username: String, password: String) = withContext(Dispatchers.IO) {
        authLock.withLock {
            val session = send("POST", "/v1/auth/login", jsonBody(JSONObject()
                .put("username", username.trim()).put("password", password)))
            if (session.getJSONObject("user").optBoolean("mustChangePassword")) {
                // Do not persist an account that the backend forbids from transcribing.
                try {
                    send("POST", "/v1/auth/logout", jsonBody(JSONObject().put("refreshToken", session.getString("refreshToken"))))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    // This unusable session was never saved locally.
                }
                throw ApiException(403, "password_change_required")
            }
            saveSession(session)
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        authLock.withLock {
            val session = readSession()
            if (session != null) {
                send("POST", "/v1/auth/logout", jsonBody(JSONObject().put("refreshToken", session.getString("refreshToken"))))
            }
            sessionFile.delete()
        }
    }

    private suspend fun token(owner: String, force: Boolean = false): String = withContext(Dispatchers.IO) {
        authLock.withLock {
            val saved = readSession() ?: throw ApiException(401, "login_required")
            if (saved.getJSONObject("user").getString("id") != owner) throw ApiException(401, "account_changed")
            if (!force && saved.getLong("expiresAt") * 1000 > System.currentTimeMillis() + 60_000) {
                return@withLock saved.getString("accessToken")
            }
            val fresh = send("POST", "/v1/auth/refresh", jsonBody(JSONObject().put("refreshToken", saved.getString("refreshToken"))))
            if (fresh.getJSONObject("user").getString("id") != owner) throw ApiException(401, "account_changed")
            saveSession(fresh)
            fresh.getString("accessToken")
        }
    }

    suspend fun request(owner: String, method: String, path: String, body: RequestBody? = null, idempotencyKey: String? = null): JSONObject {
        val access = token(owner)
        try {
            return send(method, path, body, access, idempotencyKey)
        } catch (error: ApiException) {
            if (error.status != 401) throw error
            // Uploads carry a stable idempotency key, so an auth retry cannot create a second segment.
            return send(method, path, body, token(owner, force = true), idempotencyKey)
        }
    }

    fun jsonBody(value: JSONObject): RequestBody = value.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

    private suspend fun send(method: String, path: String, body: RequestBody?, token: String? = null, idempotencyKey: String? = null): JSONObject {
        val request = Request.Builder().url(BASE_URL + path).method(method, body).apply {
            if (token != null) header("Authorization", "Bearer $token")
            if (idempotencyKey != null) header("Idempotency-Key", idempotencyKey)
        }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val raw = it.body?.charStream()?.use { reader ->
                                val text = StringBuilder()
                                val buffer = CharArray(8192)
                                while (true) {
                                    val count = reader.read(buffer)
                                    if (count < 0) break
                                    if (text.length + count > 4 * 1024 * 1024) throw IOException("Response too large")
                                    text.append(buffer, 0, count)
                                }
                                text.toString()
                            }.orEmpty()
                            val json = runCatching { JSONObject(raw) }.getOrNull()
                            if (!it.isSuccessful) throw ApiException(it.code,
                                json?.optJSONObject("error")?.optString("code")?.take(80).orEmpty())
                            if (raw.isBlank()) JSONObject() else json ?: throw IOException("Invalid server response")
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun readSession(): JSONObject? {
        if (!sessionFile.baseFile.exists()) return null
        val envelope = JSONObject(sessionFile.openRead().bufferedReader().use { it.readText() })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)))
        }
        return JSONObject(String(cipher.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP)), Charsets.UTF_8))
    }

    private fun saveSession(session: JSONObject) {
        // Validate before replacing a usable session. Passwords are never stored.
        session.getJSONObject("user").getString("id")
        session.getString("accessToken")
        session.getString("refreshToken")
        session.getLong("expiresAt")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.doFinal(session.toString().toByteArray(Charsets.UTF_8))
        val envelope = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(data, Base64.NO_WRAP))
        val output = sessionFile.startWrite()
        try {
            output.write(envelope.toString().toByteArray(Charsets.UTF_8))
            sessionFile.finishWrite(output)
        } catch (error: Exception) {
            sessionFile.failWrite(output)
            throw error
        }
    }
}
