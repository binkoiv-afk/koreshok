package app.koreshok.sync

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.buffer
import okio.sink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Signing in to Google for Drive. Only files Корешок itself creates are visible to it. */
object GoogleAuth {
    private const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_FILE))).build()

    /** Either a token, or a screen the user must pass through first (resolution). */
    suspend fun authorize(context: Context): AuthorizationResult = Identity.getAuthorizationClient(context).authorize(request()).await()

    fun resultFrom(activity: Activity, data: Intent?): AuthorizationResult =
        Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)

    /** A fresh token without any screen; works once the user has agreed. */
    suspend fun token(context: Context): String {
        val result = authorize(context)
        return result.accessToken ?: throw IOException("Войдите в Google ещё раз в «Ещё» → «Облако»")
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(IOException(googleMessage(it), it)) }
    }

    private fun googleMessage(e: Exception): String {
        val text = e.message.orEmpty()
        // 10 = DEVELOPER_ERROR: the app is not registered in Google Cloud yet.
        return if (text.startsWith("10:") || text.contains("DEVELOPER_ERROR")) {
            "Google ещё не знает Корешок: нужна настройка в Google Cloud"
        } else {
            "Google не пустил: ${text.ifEmpty { "неизвестная ошибка" }}"
        }
    }
}

/** Google Диск through its REST API; Корешок keeps everything in its own folder. */
class GoogleDriveDisk(private val token: suspend () -> String) : CloudDisk {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    private var folderId: String? = null

    private suspend fun <T> call(build: Request.Builder, read: (Response) -> T): T {
        val request = build.header("Authorization", "Bearer ${token()}").build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw CloudDisk.httpError(response.code)
                read(response)
            }
        }
    }

    private suspend fun query(q: String, fields: String = "files(id,name,size)", pageToken: String? = null): JSONObject {
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("fields", "nextPageToken,$fields")
            .addQueryParameter("pageSize", "1000")
            .addQueryParameter("spaces", "drive")
            .apply { if (pageToken != null) addQueryParameter("pageToken", pageToken) }
            .build()
        return call(Request.Builder().url(url).get()) { JSONObject(it.body!!.string()) }
    }

    private suspend fun folder(): String {
        folderId?.let { return it }
        val found = query("name = '${CloudDisk.FOLDER}' and mimeType = '$FOLDER_TYPE' and trashed = false").getJSONArray("files")
        val id = if (found.length() > 0) {
            found.getJSONObject(0).getString("id")
        } else {
            val meta = JSONObject().put("name", CloudDisk.FOLDER).put("mimeType", FOLDER_TYPE)
            call(Request.Builder().url("$API/files?fields=id").post(meta.toString().toRequestBody(JSON))) {
                JSONObject(it.body!!.string()).getString("id")
            }
        }
        folderId = id
        return id
    }

    private suspend fun stateFileId(): String? {
        val files = query("name = '${CloudDisk.STATE_FILE}' and '${folder()}' in parents and trashed = false").getJSONArray("files")
        return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    override suspend fun readState(): ByteArray? {
        val id = stateFileId() ?: return null
        return call(Request.Builder().url("$API/files/$id?alt=media").get()) { it.body!!.bytes() }
    }

    override suspend fun writeState(bytes: ByteArray) {
        val id = stateFileId()
        if (id != null) {
            call(Request.Builder().url("$UPLOAD/files/$id?uploadType=media").patch(bytes.toRequestBody(JSON))) { }
        } else {
            createFile(CloudDisk.STATE_FILE, "application/json", bytes.size.toLong(), { bytes.inputStream() }) {}
        }
    }

    override suspend fun books(): List<CloudBook> {
        val result = mutableListOf<CloudBook>()
        var page: String? = null
        do {
            val json = query("'${folder()}' in parents and trashed = false and name != '${CloudDisk.STATE_FILE}'", pageToken = page)
            val files = json.getJSONArray("files")
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                result += CloudBook(f.getString("name"), f.optString("size").toLongOrNull() ?: 0, f.getString("id"))
            }
            page = json.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        return result
    }

    override suspend fun upload(name: String, size: Long, open: () -> InputStream, onProgress: (Float) -> Unit) {
        createFile(name, "application/octet-stream", size, open, onProgress)
    }

    /** Resumable upload: metadata first, then the bytes, so big PDFs do not sit in memory. */
    private suspend fun createFile(name: String, type: String, size: Long, open: () -> InputStream, onProgress: (Float) -> Unit) {
        val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put(folder()))
        val session = call(
            Request.Builder().url("$UPLOAD/files?uploadType=resumable")
                .header("X-Upload-Content-Type", type)
                .header("X-Upload-Content-Length", size.toString())
                .post(meta.toString().toRequestBody(JSON)),
        ) { it.header("Location") ?: throw IOException("Google не дал адрес для загрузки") }
        val body = object : RequestBody() {
            override fun contentType(): MediaType = type.toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                open().use { input -> copyWithProgress(input, sink.outputStream(), size, onProgress) }
            }
        }
        call(Request.Builder().url(session).put(body)) { }
    }

    override suspend fun download(book: CloudBook, target: File, onProgress: (Float) -> Unit) {
        call(Request.Builder().url("$API/files/${book.id}?alt=media").get()) { response ->
            val body = response.body!!
            target.sink().buffer().outputStream().use { out ->
                body.byteStream().use { copyWithProgress(it, out, body.contentLength().takeIf { n -> n > 0 } ?: book.size, onProgress) }
            }
        }
    }

    /** The account's address, to show which Google account is connected. */
    suspend fun email(): String = call(
        Request.Builder().url("$API/about?fields=user(emailAddress)").get(),
    ) { JSONObject(it.body!!.string()).getJSONObject("user").optString("emailAddress") }

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER_TYPE = "application/vnd.google-apps.folder"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * The whole Google sign-in: asks for Drive access, shows Google's screen when needed through
 * [launch], and returns an error message or null once access is granted.
 */
suspend fun signInToGoogle(
    activity: Activity,
    launch: suspend (androidx.activity.result.IntentSenderRequest) -> androidx.activity.result.ActivityResult,
): String? = try {
    var result = GoogleAuth.authorize(activity)
    val pending = result.pendingIntent
    if (result.hasResolution() && pending != null) {
        val answer = launch(androidx.activity.result.IntentSenderRequest.Builder(pending.intentSender).build())
        result = if (answer.resultCode == Activity.RESULT_OK) GoogleAuth.resultFrom(activity, answer.data) else result
        if (answer.resultCode != Activity.RESULT_OK) throw IOException("Вход отменён")
    }
    if (result.accessToken == null) "Google не выдал доступ к диску" else null
} catch (e: Exception) {
    e.message ?: "Google не ответил"
}
