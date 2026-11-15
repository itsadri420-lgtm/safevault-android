package de.pdfstudio.app

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

const val DAY = 86_400_000L

enum class Kind(val label: String, val colorHex: Long) {
    PDF("PDF", 0xFFFF6B6B),
    IMAGE("Bilder", 0xFF4ECDC4),
    WORD("Word", 0xFF4D96FF),
    EXCEL("Excel", 0xFF2ECC71),
    SLIDES("PowerPoint", 0xFFFFA94D),
    TEXT("Text", 0xFFB0B3C6)
}

data class DocFile(
    val path: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val kind: Kind
) {
    val file: File get() = File(path)
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
}

val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
val TEXT_EXT = setOf("txt", "md", "log", "json", "xml", "csv")

fun kindOf(ext: String): Kind? = when (ext) {
    "pdf" -> Kind.PDF
    in IMAGE_EXT -> Kind.IMAGE
    "doc", "docx", "odt", "rtf" -> Kind.WORD
    "xls", "xlsx", "ods" -> Kind.EXCEL
    "ppt", "pptx", "odp" -> Kind.SLIDES
    in TEXT_EXT -> Kind.TEXT
    else -> null
}

fun docOf(f: File): DocFile =
    DocFile(
        f.path,
        f.name,
        f.length(),
        f.lastModified(),
        kindOf(f.extension.lowercase()) ?: Kind.TEXT
    )

object Store {
    var tick by mutableIntStateOf(0)
    var incoming by mutableStateOf<File?>(null)
}

fun hasAccess(): Boolean = Environment.isExternalStorageManager()

object FileScanner {
    private const val MAX = 30000

    fun scan(): List<DocFile> {
        val out = ArrayList<DocFile>()
        walk(Environment.getExternalStorageDirectory(), 0, out)
        return out
    }

    private fun walk(dir: File, depth: Int, out: MutableList<DocFile>) {
        if (depth > 10 || out.size >= MAX) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            val n = f.name
            if (n.startsWith(".")) continue
            if (f.isDirectory) {
                if (depth == 0 && n == "Android") continue
                walk(f, depth + 1, out)
            } else {
                val kind = kindOf(f.extension.lowercase()) ?: continue
                val len = f.length()
                if (len <= 0L) continue
                out.add(DocFile(f.path, n, len, f.lastModified(), kind))
            }
        }
    }
}

// ---------- Ausgabeordner ----------

fun outDocs(ctx: Context): File {
    val base: File = if (hasAccess()) {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
    } else {
        ctx.getExternalFilesDir("Documents") ?: ctx.filesDir
    }
    return File(base, "PDF Studio").apply { mkdirs() }
}

fun outPics(ctx: Context): File {
    val base: File = if (hasAccess()) {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    } else {
        ctx.getExternalFilesDir("Pictures") ?: ctx.filesDir
    }
    return File(base, "PDF Studio").apply { mkdirs() }
}

fun uniqueFile(dir: File, base: String, ext: String): File {
    var f = File(dir, "$base.$ext")
    var i = 1
    while (f.exists()) {
        f = File(dir, "$base ($i).$ext")
        i++
    }
    return f
}

fun uniqueDir(target: File): File {
    var d = target
    var i = 1
    while (d.exists()) {
        d = File(target.parentFile, "${target.name} ($i)")
        i++
    }
    d.mkdirs()
    return d
}

fun shortPath(f: File): String = f.path.removePrefix("/storage/emulated/0/")

fun mediaScan(ctx: Context, files: List<File>) {
    runCatching {
        MediaScannerConnection.scanFile(ctx, files.map { it.path }.toTypedArray(), null, null)
    }
}

fun toast(ctx: Context, s: String) {
    Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()
}

// ---------- Formatierung ----------

private val dayFmt = SimpleDateFormat("EEEE, dd.MM.yyyy", Locale.GERMANY)
private val dateFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)

fun fmtSize(b: Long): String = when {
    b >= (1L shl 30) -> String.format(Locale.GERMANY, "%.2f GB", b / 1073741824.0)
    b >= (1L shl 20) -> String.format(Locale.GERMANY, "%.1f MB", b / 1048576.0)
    b >= (1L shl 10) -> String.format(Locale.GERMANY, "%.0f KB", b / 1024.0)
    else -> "$b B"
}

fun fmtDate(ms: Long): String = dateFmt.format(Date(ms))

fun stamp(): String = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.GERMANY).format(Date())

fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun dayLabel(ms: Long, today: Long): String = when {
    ms >= today -> "Heute"
    ms >= today - DAY -> "Gestern"
    else -> dayFmt.format(Date(ms))
}

// ---------- Kopieren / Teilen / Oeffnen ----------

fun copyToCache(ctx: Context, uri: Uri, sub: String): File? = runCatching {
    var found: String? = null
    if (uri.scheme == "content") {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) found = it.getString(0)
        }
    }
    var n = (found ?: uri.lastPathSegment ?: "datei").replace('/', '_')
    if (!n.contains('.')) {
        val mime = ctx.contentResolver.getType(uri)
        val e = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        if (e != null) n += ".$e"
    }
    val dir = File(ctx.cacheDir, sub).apply { mkdirs() }
    val out = uniqueFile(dir, n.substringBeforeLast('.'), n.substringAfterLast('.', "bin"))
    ctx.contentResolver.openInputStream(uri)!!.use { input ->
        out.outputStream().use { input.copyTo(it) }
    }
    out
}.getOrNull()

fun mimeOf(f: File): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

fun uriOf(ctx: Context, f: File): Uri =
    FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)

fun openExternal(ctx: Context, f: File) {
    try {
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriOf(ctx, f), mimeOf(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(i)
    } catch (e: Exception) {
        toast(ctx, "Keine passende App für diese Datei gefunden")
    }
}

fun shareFiles(ctx: Context, files: List<File>) {
    if (files.isEmpty()) return
    try {
        val i = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeOf(files[0])
                putExtra(Intent.EXTRA_STREAM, uriOf(ctx, files[0]))
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    ArrayList(files.map { uriOf(ctx, it) })
                )
            }
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Teilen"))
    } catch (e: Exception) {
        toast(ctx, "Teilen nicht möglich")
    }
}

fun openAccessSettings(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${ctx.packageName}")
            )
        )
    } catch (e: Exception) {
        runCatching {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}
