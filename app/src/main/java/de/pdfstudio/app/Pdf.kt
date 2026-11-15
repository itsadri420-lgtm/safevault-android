package de.pdfstudio.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class PdfHolder(file: File) {
    private val fd: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try {
        PdfRenderer(fd)
    } catch (e: Throwable) {
        fd.close()
        throw e
    }
    private val mutex = Mutex()
    private var closed = false
    val pageCount: Int = renderer.pageCount

    suspend fun render(index: Int, width: Int): Bitmap? {
        return mutex.withLock {
            if (closed) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val p = renderer.openPage(index)
                        try {
                            val h = (width.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp
                        } finally {
                            p.close()
                        }
                    }.getOrNull()
                }
            }
        }
    }

    fun close() {
        runBlocking {
            mutex.withLock {
                if (!closed) {
                    closed = true
                    runCatching { renderer.close() }
                    runCatching { fd.close() }
                }
            }
        }
    }
}

object Ops {

    private fun rotation(f: File): Int = runCatching {
        when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    fun loadBitmap(f: File, max: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (o.outWidth / s > max || o.outHeight / s > max) s *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
            ?: return null
        val deg = rotation(f)
        if (deg == 0) return bmp
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    fun pageCount(f: File): Int {
        val fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val r = PdfRenderer(fd)
            try {
                return r.pageCount
            } finally {
                r.close()
            }
        } finally {
            fd.close()
        }
    }

    /** "1-3,5,8-10" -> Seitenindizes (0-basiert) */
    fun parseRange(s: String, max: Int): List<Int> {
        val out = LinkedHashSet<Int>()
        for (part in s.split(',')) {
            val t = part.trim()
            if (t.isEmpty()) continue
            val ab = t.split('-')
            val a = ab[0].trim().toIntOrNull() ?: continue
            val b: Int = if (ab.size > 1) {
                ab[1].trim().toIntOrNull() ?: continue
            } else {
                a
            }
            for (x in minOf(a, b)..maxOf(a, b)) {
                if (x in 1..max) out.add(x - 1)
            }
        }
        return out.toList()
    }

    /** Bilder -> eine PDF (A4, Bild eingepasst, Hoch-/Querformat automatisch). */
    fun imagesToPdf(files: List<File>, out: File): Int {
        val doc = PdfDocument()
        var n = 0
        try {
            for (f in files) {
                val bmp = loadBitmap(f, 1800) ?: continue
                val landscape = bmp.width > bmp.height
                val pw = if (landscape) 842 else 595
                val ph = if (landscape) 595 else 842
                n++
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, n).create())
                page.canvas.drawColor(Color.WHITE)
                val scale = minOf(pw / bmp.width.toFloat(), ph / bmp.height.toFloat())
                val w = bmp.width * scale
                val h = bmp.height * scale
                val dst = RectF((pw - w) / 2f, (ph - h) / 2f, (pw + w) / 2f, (ph + h) / 2f)
                page.canvas.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
                doc.finishPage(page)
            }
            if (n == 0) throw IOException("Keine lesbaren Bilder")
            FileOutputStream(out).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        return n
    }

    /** PDF -> JPG-Bilder (eine Datei pro Seite). Gibt Ordner und Anzahl zurueck. */
    fun pdfToImages(ctx: android.content.Context, pdf: File): Pair<File, Int> {
        val dir = uniqueDir(File(outPics(ctx), pdf.nameWithoutExtension))
        val fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        val created = ArrayList<File>()
        try {
            val r = PdfRenderer(fd)
            try {
                for (i in 0 until r.pageCount) {
                    val p = r.openPage(i)
                    try {
                        val w = minOf((p.width * 2.5f).toInt(), 2500).coerceAtLeast(1)
                        val h = (w.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val f = File(dir, String.format("Seite_%02d.jpg", i + 1))
                        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                        bmp.recycle()
                        created.add(f)
                    } finally {
                        p.close()
                    }
                }
            } finally {
                r.close()
            }
        } finally {
            fd.close()
        }
        mediaScan(ctx, created)
        return dir to created.size
    }

    /**
     * Fuegt Seiten aus PDFs zu einer neuen PDF zusammen (Seiten werden als Bild eingebettet).
     * pages == null bedeutet: alle Seiten.
     */
    fun rasterToPdf(sources: List<Pair<File, List<Int>?>>, out: File): Int {
        val doc = PdfDocument()
        var n = 0
        try {
            for ((f, pages) in sources) {
                val fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    val r = PdfRenderer(fd)
                    try {
                        val idx = pages ?: (0 until r.pageCount).toList()
                        for (i in idx) {
                            if (i < 0 || i >= r.pageCount) continue
                            val p = r.openPage(i)
                            try {
                                val pw = p.width
                                val ph = p.height
                                val bmp = Bitmap.createBitmap(pw * 2, ph * 2, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(Color.WHITE)
                                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                n++
                                val pg = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, n).create())
                                pg.canvas.drawBitmap(
                                    bmp,
                                    null,
                                    RectF(0f, 0f, pw.toFloat(), ph.toFloat()),
                                    Paint(Paint.FILTER_BITMAP_FLAG)
                                )
                                doc.finishPage(pg)
                            } finally {
                                p.close()
                            }
                        }
                    } finally {
                        r.close()
                    }
                } finally {
                    fd.close()
                }
            }
            if (n == 0) throw IOException("Keine Seiten gefunden")
            FileOutputStream(out).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        return n
    }
}
