package app.koreshok.ui.pages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.koreshok.core.model.BookFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** A book made of fixed pages: PDF or a comic archive. */
interface PageSource : AutoCloseable {
    val pageCount: Int

    /** Text on white paper, which night themes can invert. */
    val isPaper: Boolean

    /**
     * Height divided by width, for the placeholder before the page is drawn. Pages not drawn
     * yet are assumed to match the first one, which keeps opening a big book instant.
     */
    fun aspect(index: Int): Float

    suspend fun render(index: Int, width: Int): Bitmap

    companion object {
        val formats = setOf(BookFormat.PDF, BookFormat.CBZ)

        suspend fun open(context: Context, uri: String, format: BookFormat): PageSource = withContext(Dispatchers.IO) {
            when (format) {
                BookFormat.PDF -> PdfSource(openDescriptor(context, uri))
                BookFormat.CBZ -> CbzSource(localCopy(context, uri))
                else -> throw UnsupportedOperationException("$format не листается постранично")
            }
        }

        /** The first page as a PNG, for the cover on the shelf. */
        suspend fun cover(context: Context, uri: String, format: BookFormat): ByteArray? = runCatching {
            open(context, uri, format).use { source ->
                if (source.pageCount == 0) return null
                val bitmap = source.render(0, COVER_WIDTH)
                ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            }
        }.getOrNull()

        private fun openDescriptor(context: Context, uri: String): ParcelFileDescriptor {
            val parsed = Uri.parse(uri)
            return if (parsed.scheme == "file") {
                ParcelFileDescriptor.open(File(parsed.path.orEmpty()), ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                context.contentResolver.openFileDescriptor(parsed, "r") ?: throw java.io.FileNotFoundException(uri)
            }
        }

        /** ZipFile needs random access, which a content URI cannot give, so archives are copied once. */
        private fun localCopy(context: Context, uri: String): File {
            val parsed = Uri.parse(uri)
            if (parsed.scheme == "file") return File(parsed.path.orEmpty())
            val dir = File(context.cacheDir, "comics").apply { mkdirs() }
            val name = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
            val file = File(dir, name)
            if (!file.exists()) {
                // Keep only the most recent few archives around.
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
                val partial = File(dir, "$name.part")
                context.contentResolver.openInputStream(parsed)?.use { input ->
                    partial.outputStream().use { input.copyTo(it) }
                } ?: throw java.io.FileNotFoundException(uri)
                partial.renameTo(file)
            }
            file.setLastModified(System.currentTimeMillis())
            return file
        }

        private const val COVER_WIDTH = 360
    }
}

private class PdfSource(private val descriptor: ParcelFileDescriptor) : PageSource {
    private val renderer = PdfRenderer(descriptor)
    // PdfRenderer allows one open page at a time.
    private val lock = Mutex()
    private val aspects = FloatArray(renderer.pageCount) { 0f }

    override val pageCount: Int = renderer.pageCount
    override val isPaper = true

    init {
        if (pageCount > 0) renderer.openPage(0).use { aspects[0] = it.height.toFloat() / it.width }
    }

    override fun aspect(index: Int): Float = aspects[index].takeIf { it > 0f } ?: aspects.firstOrNull() ?: 1.4f

    override suspend fun render(index: Int, width: Int): Bitmap = lock.withLock {
        withContext(Dispatchers.IO) {
            synchronized(renderer) {
                renderer.openPage(index).use { page ->
                    aspects[index] = page.height.toFloat() / page.width
                    val height = (width * aspects[index]).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    }

    override fun close() {
        synchronized(renderer) { renderer.close() }
        descriptor.close()
    }
}

private class CbzSource(file: File) : PageSource {
    private val zip = ZipFile(file)
    private val pages = zip.entries().asSequence()
        .filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS }
        .filterNot { it.name.startsWith("__MACOSX") }
        .sortedWith { a, b -> naturalCompare(a.name, b.name) }
        .toList()
    private val aspects = FloatArray(pages.size) { 0f }

    override val pageCount: Int = pages.size
    override val isPaper = false

    init {
        if (pageCount > 0) aspects[0] = bounds(0).let { if (it.outWidth > 0) it.outHeight.toFloat() / it.outWidth else 0f }
    }

    override fun aspect(index: Int): Float = aspects[index].takeIf { it > 0f } ?: aspects.firstOrNull()?.takeIf { it > 0f } ?: 1.4f

    private fun bounds(index: Int) = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        zip.getInputStream(pages[index]).use { BitmapFactory.decodeStream(it, null, this) }
    }

    override suspend fun render(index: Int, width: Int): Bitmap = withContext(Dispatchers.IO) {
        val bounds = bounds(index)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = zip.getInputStream(pages[index]).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Не удалось прочитать страницу ${index + 1}")
        aspects[index] = bitmap.height.toFloat() / bitmap.width
        bitmap
    }

    override fun close() = zip.close()

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

        /** "page2" before "page10". */
        fun naturalCompare(a: String, b: String): Int {
            val chunk = Regex("\\d+|\\D+")
            val left = chunk.findAll(a.lowercase()).map { it.value }.toList()
            val right = chunk.findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(left.size, right.size)) {
                val x = left[i]
                val y = right[i]
                val result = if (x[0].isDigit() && y[0].isDigit()) {
                    compareValues(x.trimStart('0').length, y.trimStart('0').length)
                        .takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
                } else {
                    x.compareTo(y)
                }
                if (result != 0) return result
            }
            return left.size - right.size
        }
    }
}
