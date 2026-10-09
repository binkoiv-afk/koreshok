package app.koreshok.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.buffer
import okio.sink
import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** Яндекс Диск, Nextcloud, a home NAS: anything that speaks WebDAV. */
class WebDavDisk(private val account: SyncAccount) : CloudDisk {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    private val auth = Credentials.basic(account.login, account.password)
    private val root: HttpUrl = account.url.trimEnd('/').plus("/").toHttpUrl()
    private val folder: HttpUrl = root.newBuilder().addPathSegment(CloudDisk.FOLDER).addPathSegment("").build()

    private fun request(url: HttpUrl) = Request.Builder().url(url).header("Authorization", auth).header("User-Agent", "Koreshok/1.0 (Android)")

    private suspend fun <T> call(request: Request, allow404: Boolean = false, read: (okhttp3.Response) -> T): T? = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> read(response)
                allow404 && response.code == 404 -> null
                else -> throw CloudDisk.httpError(response.code)
            }
        }
    }

    override suspend fun readState(): ByteArray? =
        call(request(root.resolve(CloudDisk.STATE_FILE)!!).get().build(), allow404 = true) { it.body!!.bytes() }

    override suspend fun writeState(bytes: ByteArray) {
        call(request(root.resolve(CloudDisk.STATE_FILE)!!).put(bytes.toRequestBody(JSON)).build()) { }
    }

    override suspend fun books(): List<CloudBook> {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:getcontentlength/><d:resourcetype/></d:prop></d:propfind>"""
        val xml = call(
            request(folder).method("PROPFIND", body.toRequestBody(XML)).header("Depth", "1").build(),
            allow404 = true,
        ) { it.body!!.bytes() } ?: return emptyList()
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(xml.inputStream())
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        return (0 until responses.length).mapNotNull { i ->
            val response = responses.item(i) as Element
            val href = response.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent ?: return@mapNotNull null
            if (href.endsWith("/")) return@mapNotNull null
            val isFolder = response.getElementsByTagNameNS("DAV:", "collection").length > 0
            if (isFolder) return@mapNotNull null
            val name = URLDecoder.decode(href.substringAfterLast('/'), "UTF-8")
            val size = response.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.trim()?.toLongOrNull() ?: 0
            CloudBook(name, size, name)
        }
    }

    private suspend fun ensureFolder() {
        withContext(Dispatchers.IO) {
            client.newCall(request(folder).method("MKCOL", null).build()).execute().use { response ->
                // 405: it is already there.
                if (!response.isSuccessful && response.code != 405) throw CloudDisk.httpError(response.code)
            }
        }
    }

    override suspend fun upload(name: String, size: Long, open: () -> InputStream, onProgress: (Float) -> Unit) {
        ensureFolder()
        val body = object : RequestBody() {
            override fun contentType(): MediaType = BINARY
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                open().use { input -> copyWithProgress(input, sink.outputStream(), size, onProgress) }
            }
        }
        val url = folder.newBuilder().addPathSegment(name).build()
        call(request(url).put(body).build()) { }
    }

    override suspend fun download(book: CloudBook, target: File, onProgress: (Float) -> Unit) {
        val url = folder.newBuilder().addPathSegment(book.id).build()
        call(request(url).get().build()) { response ->
            val body = response.body!!
            target.sink().buffer().outputStream().use { out ->
                body.byteStream().use { copyWithProgress(it, out, body.contentLength().takeIf { n -> n > 0 } ?: book.size, onProgress) }
            }
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val XML = "application/xml; charset=utf-8".toMediaType()
        val BINARY = "application/octet-stream".toMediaType()
    }
}
