package app.koreshok.ui.pages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
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

    /** Draws a page [width] pixels wide; [crop] cuts white margins where the format allows. */
    suspend fun render(index: Int, width: Int, crop: Boolean = false): Bitmap

    companion object {
        val formats = setOf(BookFormat.PDF, BookFormat.CBZ, BookFormat.CBR)

        suspend fun open(context: Context, uri: String, format: BookFormat): PageSource = withContext(Dispatchers.IO) {
            when (format) {
                BookFormat.PDF -> PdfSource(openDescriptor(context, uri))
                BookFormat.CBZ -> CbzSource(localCopy(context, uri))
                BookFormat.CBR -> {
                    val file = localCopy(context, uri)
                    // Plenty of .cbr files are zip archives with the wrong extension.
                    if (isZip(file)) CbzSource(file) else FolderSource(unrar(file))
                }
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

        private fun isZip(file: File) = file.inputStream().use { input ->
            val head = ByteArray(2)
            input.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        }

        /** RAR has no random access worth the name; the pages are unpacked once next to the copy. */
        private fun unrar(file: File): File {
            val dir = File(file.parentFile, file.name + ".pages")
            val done = File(dir, ".done")
            if (!done.exists()) {
                dir.deleteRecursively()
                dir.mkdirs()
                try {
                    com.github.junrar.Junrar.extract(file, dir)
                } catch (e: Exception) {
                    dir.deleteRecursively()
                    throw java.io.IOException("Не удалось распаковать CBR: ${e.message ?: "архив повреждён или RAR5"}", e)
                }
                done.createNewFile()
            }
            return dir
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
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(6)?.forEach { it.deleteRecursively() }
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

    /** Content bounds of each page as fractions of its size, found once from a small rendering. */
    private val bounds = arrayOfNulls<RectF>(pageCount)

    override suspend fun render(index: Int, width: Int, crop: Boolean): Bitmap = lock.withLock {
        withContext(Dispatchers.IO) {
            synchronized(renderer) {
                renderer.openPage(index).use { page ->
                    val area = if (crop) bounds[index] ?: contentBounds(page).also { bounds[index] = it } else FULL
                    val cropWidth = area.width() * page.width
                    val cropHeight = area.height() * page.height
                    aspects[index] = cropHeight / cropWidth
                    val height = (width * aspects[index]).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    val scale = width / cropWidth
                    val transform = Matrix().apply {
                        setScale(scale, scale)
                        postTranslate(-area.left * page.width * scale, -area.top * page.height * scale)
                    }
                    page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    }

    /**
     * Where the ink is: the page is drawn small and scanned for non-white pixels. A thin border
     * is kept around the text, and pages that are almost empty are left whole.
     */
    private fun contentBounds(page: PdfRenderer.Page): RectF {
        val w = PROBE_WIDTH
        val h = (w * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
        val probe = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        probe.eraseColor(Color.WHITE)
        page.render(probe, null, Matrix().apply { setScale(w / page.width.toFloat(), h / page.height.toFloat()) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        val pixels = IntArray(w * h)
        probe.getPixels(pixels, 0, w, 0, 0, w, h)
        probe.recycle()
        var left = w
        var right = -1
        var top = h
        var bottom = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val c = pixels[row + x]
                val luma = (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10
                if (luma < INK) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < 0 || (right - left) < w / 5 || (bottom - top) < h / 10) return FULL
        val padX = w * PAD
        val padY = h * PAD
        return RectF(
            ((left - padX) / w).coerceAtLeast(0f),
            ((top - padY) / h).coerceAtLeast(0f),
            ((right + 1 + padX) / w).coerceAtMost(1f),
            ((bottom + 1 + padY) / h).coerceAtMost(1f),
        )
    }

    override fun close() {
        synchronized(renderer) { renderer.close() }
        descriptor.close()
    }

    private companion object {
        val FULL = RectF(0f, 0f, 1f, 1f)
        const val PROBE_WIDTH = 240
        const val INK = 225
        const val PAD = 0.02f
    }
}

/** Pages already unpacked into a folder (from a CBR). */
private class FolderSource(dir: File) : PageSource {
    private val pages = dir.walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in CbzSource.IMAGE_EXTENSIONS && !it.path.contains("__MACOSX") }
        .sortedWith { a, b -> CbzSource.naturalCompare(a.relativeTo(dir).path, b.relativeTo(dir).path) }
        .toList()
    private val aspects = FloatArray(pages.size) { 0f }

    override val pageCount: Int = pages.size
    override val isPaper = false

    override fun aspect(index: Int): Float = aspects[index].takeIf { it > 0f } ?: aspects.firstOrNull { it > 0f } ?: 1.4f

    override suspend fun render(index: Int, width: Int, crop: Boolean): Bitmap = withContext(Dispatchers.IO) {
        val path = pages[index].path
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(path, it) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Не удалось прочитать страницу ${index + 1}")
        aspects[index] = bitmap.height.toFloat() / bitmap.width
        bitmap
    }

    override fun close() = Unit
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

    override suspend fun render(index: Int, width: Int, crop: Boolean): Bitmap = withContext(Dispatchers.IO) {
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
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

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
