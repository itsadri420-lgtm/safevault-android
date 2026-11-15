package de.pdfstudio.app

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Calendar

// ---------- Filter & Sortierung ----------

enum class Sort(val label: String, val short: String) {
    NEW("Neueste zuerst", "Neueste"),
    OLD("Älteste zuerst", "Älteste"),
    NAME("Name A–Z", "Name"),
    SIZE("Größte zuerst", "Größe")
}

enum class DateRange(val label: String) {
    ALL("Alle Zeit"),
    TODAY("Heute"),
    D7("7 Tage"),
    D30("30 Tage")
}

sealed interface ListRow
data class HeaderRow(val text: String) : ListRow
data class ItemRow(val f: DocFile) : ListRow

fun applyFilter(all: List<DocFile>, q: String, kind: Kind?, range: DateRange, sort: Sort): List<DocFile> {
    val now = System.currentTimeMillis()
    val min = when (range) {
        DateRange.ALL -> 0L
        DateRange.TODAY -> startOfToday()
        DateRange.D7 -> now - 7 * DAY
        DateRange.D30 -> now - 30 * DAY
    }
    val query = q.trim()
    val list = all.filter {
        (kind == null || it.kind == kind) &&
            it.modified >= min &&
            (query.isEmpty() || it.path.contains(query, ignoreCase = true))
    }
    return when (sort) {
        Sort.NEW -> list.sortedByDescending { it.modified }
        Sort.OLD -> list.sortedBy { it.modified }
        Sort.NAME -> list.sortedBy { it.name.lowercase() }
        Sort.SIZE -> list.sortedByDescending { it.size }
    }
}

fun buildRows(list: List<DocFile>, grouped: Boolean): List<ListRow> {
    if (!grouped) return list.map { ItemRow(it) }
    val today = startOfToday()
    val out = ArrayList<ListRow>()
    var last = ""
    for (f in list) {
        val l = dayLabel(f.modified, today)
        if (l != last) {
            out.add(HeaderRow(l))
            last = l
        }
        out.add(ItemRow(f))
    }
    return out
}

fun CoroutineScope.runTask(
    ctx: Context,
    setBusy: (String?) -> Unit,
    label: String,
    block: suspend () -> String
) {
    launch {
        setBusy(label)
        try {
            toast(ctx, block())
        } catch (e: Throwable) {
            toast(ctx, "Fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
        }
        setBusy(null)
    }
}

// ---------- Root ----------

@Composable
fun App() {
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<DocFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var access by remember { mutableStateOf(hasAccess()) }
    var viewing by remember { mutableStateOf<DocFile?>(null) }
    var lastDoc by remember { mutableStateOf<DocFile?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    fun reload() {
        access = hasAccess()
        if (!access) {
            all = emptyList()
            return
        }
        if (loading) return
        scope.launch {
            loading = true
            all = try {
                withContext(Dispatchers.IO) { FileScanner.scan() }
            } catch (e: Throwable) {
                emptyList()
            }
            loading = false
        }
    }

    LaunchedEffect(Store.tick) { reload() }
    LaunchedEffect(Store.incoming) {
        val f = Store.incoming
        if (f != null) {
            val d = docOf(f)
            viewing = d
            lastDoc = d
            Store.incoming = null
        }
    }
    LaunchedEffect(viewing) {
        val v = viewing
        if (v != null) lastDoc = v
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .systemBarsPadding()
    ) {
        Browser(
            all = all,
            loading = loading,
            access = access,
            onReload = { reload() },
            onOpen = { viewing = it },
            setBusy = { busy = it }
        )

        AnimatedVisibility(
            visible = viewing != null,
            enter = fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 6 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(250)) { it / 6 }
        ) {
            val d = viewing ?: lastDoc
            if (d != null) {
                Viewer(
                    doc = d,
                    onClose = { viewing = null },
                    onChanged = { reload() },
                    setBusy = { busy = it }
                )
            }
        }

        busy?.let { txt ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .pointerInput(Unit) {},
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFFFFA94D))
                    Spacer(Modifier.height(16.dp))
                    Text(txt, color = Color.White)
                }
            }
        }
    }

    BackHandler(enabled = viewing != null) { viewing = null }
}

// ---------- Dateiliste ----------

@Composable
fun Browser(
    all: List<DocFile>,
    loading: Boolean,
    access: Boolean,
    onReload: () -> Unit,
    onOpen: (DocFile) -> Unit,
    setBusy: (String?) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<Kind?>(null) }
    var range by remember { mutableStateOf(DateRange.ALL) }
    var sort by remember { mutableStateOf(Sort.NEW) }
    var sortMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }

    val shown = remember(all, query, kind, range, sort) { applyFilter(all, query, kind, range, sort) }
    val rows = remember(shown, sort) { buildRows(shown, sort == Sort.NEW || sort == Sort.OLD) }
    val byPath = remember(all) { all.associateBy { it.path } }
    val selDocs = selected.mapNotNull { byPath[it] }
    val allImages = selDocs.isNotEmpty() && selDocs.all { it.kind == Kind.IMAGE }
    val allPdfs = selDocs.size >= 2 && selDocs.all { it.kind == Kind.PDF }

    BackHandler(enabled = selected.isNotEmpty()) { selected.clear() }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                setBusy("Öffne Datei …")
                val f = withContext(Dispatchers.IO) { copyToCache(ctx, uri, "incoming") }
                setBusy(null)
                if (f != null) onOpen(docOf(f)) else toast(ctx, "Datei konnte nicht geöffnet werden")
            }
        }
    }

    val imagesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.runTask(ctx, setBusy, "Erstelle PDF …") {
                val msg = withContext(Dispatchers.IO) {
                    val tmp = uris.mapNotNull { copyToCache(ctx, it, "pick") }
                    try {
                        val out = uniqueFile(outDocs(ctx), "Bilder_${stamp()}", "pdf")
                        val n = Ops.imagesToPdf(tmp, out)
                        mediaScan(ctx, listOf(out))
                        "PDF mit $n Seiten gespeichert: ${shortPath(out)}"
                    } finally {
                        tmp.forEach { it.delete() }
                    }
                }
                onReload()
                msg
            }
        }
    }

    fun toggle(p: String) {
        if (selected.contains(p)) selected.remove(p) else selected.add(p)
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PDF Studio", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onReload) {
                    Icon(Icons.Default.Refresh, contentDescription = "Neu laden", tint = Color.White)
                }
            }
            Spacer(Modifier.height(8.dp))

            if (!access) {
                GlassCard {
                    Text("Zugriff auf Dateien nötig", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Damit PDF Studio deine Downloads und Dokumente findet und nach Datum und Uhrzeit sortieren kann, braucht die App „Zugriff auf alle Dateien“. Die App hat keine Internet-Berechtigung, nichts verlässt dein Handy.",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    GradientButton("Zugriff erlauben") { openAccessSettings(ctx) }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Suchen …") },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.size(8.dp))
                    Box {
                        ActionPill("⇅ " + sort.short) { sortMenu = true }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            Sort.values().forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.label) },
                                    onClick = {
                                        sort = s
                                        sortMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Chip("Alle Typen", kind == null) { kind = null }
                    Kind.values().forEach { k ->
                        Chip(k.label, kind == k) { kind = if (kind == k) null else k }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DateRange.values().forEach { r ->
                        Chip(r.label, range == r) { range = r }
                    }
                }
                Text(
                    "${shown.size} von ${all.size} Dateien  •  Lange tippen zum Auswählen",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                Box(Modifier.weight(1f)) {
                    when {
                        loading && all.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color(0xFFFFA94D))
                        }

                        rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "Keine Dateien gefunden.\nÄndere Filter oder Suche.",
                                color = Color.White.copy(alpha = 0.55f),
                                textAlign = TextAlign.Center
                            )
                        }

                        else -> LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 130.dp)
                        ) {
                            items(
                                rows,
                                key = {
                                    when (it) {
                                        is HeaderRow -> "h:" + it.text
                                        is ItemRow -> it.f.path
                                    }
                                }
                            ) { r ->
                                when (r) {
                                    is HeaderRow -> Text(
                                        r.text,
                                        color = Color(0xFFFFA94D),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp)
                                    )

                                    is ItemRow -> FadeIn {
                                        DocRow(
                                            f = r.f,
                                            selected = selected.contains(r.f.path),
                                            selecting = selected.isNotEmpty(),
                                            onTap = {
                                                if (selected.isNotEmpty()) toggle(r.f.path) else onOpen(r.f)
                                            },
                                            onLong = { toggle(r.f.path) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = selected.isEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it }
        ) {
            Row(
                Modifier.padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GradientButton("Datei öffnen") { openLauncher.launch(arrayOf("*/*")) }
                GradientButton("Bilder → PDF") { imagesLauncher.launch(arrayOf("image/*")) }
            }
        }

        AnimatedVisibility(
            visible = selected.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it }
        ) {
            GlassCard(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                Text(
                    "${selected.size} ausgewählt (Reihenfolge = Tipp-Reihenfolge)",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionPill("Teilen") { shareFiles(ctx, selDocs.map { it.file }) }
                    if (allImages) {
                        ActionPill("Zu PDF") {
                            val files = selDocs.map { it.file }
                            scope.runTask(ctx, setBusy, "Erstelle PDF …") {
                                val msg = withContext(Dispatchers.IO) {
                                    val out = uniqueFile(outDocs(ctx), "Bilder_${stamp()}", "pdf")
                                    val n = Ops.imagesToPdf(files, out)
                                    mediaScan(ctx, listOf(out))
                                    "PDF mit $n Seiten gespeichert: ${shortPath(out)}"
                                }
                                selected.clear()
                                onReload()
                                msg
                            }
                        }
                    }
                    if (allPdfs) {
                        ActionPill("Zusammenführen") {
                            val sources = selDocs.map { Pair<File, List<Int>?>(it.file, null) }
                            scope.runTask(ctx, setBusy, "Füge PDFs zusammen …") {
                                val msg = withContext(Dispatchers.IO) {
                                    val out = uniqueFile(outDocs(ctx), "Zusammengefuehrt_${stamp()}", "pdf")
                                    val n = Ops.rasterToPdf(sources, out)
                                    mediaScan(ctx, listOf(out))
                                    "PDF mit $n Seiten gespeichert: ${shortPath(out)}"
                                }
                                selected.clear()
                                onReload()
                                msg
                            }
                        }
                    }
                    ActionPill("Löschen", danger = true) { confirmDelete = true }
                    ActionPill("Abbrechen") { selected.clear() }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("${selected.size} Datei(en) löschen?") },
            text = { Text("Die Dateien werden unwiderruflich vom Gerät gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val files = selDocs.map { it.file }
                    scope.launch {
                        withContext(Dispatchers.IO) { files.forEach { it.delete() } }
                        mediaScan(ctx, files)
                        selected.clear()
                        onReload()
                    }
                }) { Text("Löschen", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } }
        )
    }
}

@Composable
fun DocRow(
    f: DocFile,
    selected: Boolean,
    selecting: Boolean,
    onTap: () -> Unit,
    onLong: () -> Unit
) {
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLong)
    val kc = Color(f.kind.colorHex)
    val ext = f.ext.take(4).uppercase().ifEmpty { "DATEI" }
    val mod = Modifier
        .padding(vertical = 5.dp)
        .then(if (selected) Modifier.border(2.dp, Accent, RoundedCornerShape(24.dp)) else Modifier)
        .pointerInput(f.path) {
            detectTapGestures(onTap = { tap() }, onLongPress = { long() })
        }
    GlassCard(mod) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(kc.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Text(ext, color = kc, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    f.name,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${fmtSize(f.size)}  •  ${fmtDate(f.modified)}",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 12.sp
                )
                Text(
                    f.file.parentFile?.name ?: "",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (selecting) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(if (selected) Accent else SolidColor(Color.White.copy(alpha = 0.12f))),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) Text("✓", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ---------- Betrachter ----------

@Composable
fun Viewer(doc: DocFile, onClose: () -> Unit, onChanged: () -> Unit, setBusy: (String?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var zoom by remember(doc.path) { mutableFloatStateOf(1f) }
    var dialog by remember(doc.path) { mutableIntStateOf(0) }
    var input by remember(doc.path) { mutableStateOf("") }
    val file = doc.file
    val ext = doc.ext
    val isPdf = ext == "pdf"
    val isImg = ext in IMAGE_EXT
    val isText = ext in TEXT_EXT

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        Row(
            Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Schließen", tint = Color.White)
            }
            Text(
                doc.name,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { shareFiles(ctx, listOf(file)) }) {
                Icon(Icons.Default.Share, contentDescription = "Teilen", tint = Color.White)
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Mehr", tint = Color.White)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Mit anderer App öffnen") },
                        onClick = {
                            menu = false
                            openExternal(ctx, file)
                        }
                    )
                    if (isPdf) {
                        DropdownMenuItem(
                            text = { Text("PDF → Bilder (JPG)") },
                            onClick = {
                                menu = false
                                scope.runTask(ctx, setBusy, "Erstelle Bilder …") {
                                    withContext(Dispatchers.IO) {
                                        val (dir, n) = Ops.pdfToImages(ctx, file)
                                        "$n Bilder gespeichert: ${shortPath(dir)}"
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Seiten extrahieren") },
                            onClick = {
                                menu = false
                                input = ""
                                dialog = 2
                            }
                        )
                    }
                    if (isImg) {
                        DropdownMenuItem(
                            text = { Text("Als PDF speichern") },
                            onClick = {
                                menu = false
                                scope.runTask(ctx, setBusy, "Erstelle PDF …") {
                                    val msg = withContext(Dispatchers.IO) {
                                        val out = uniqueFile(outDocs(ctx), file.nameWithoutExtension, "pdf")
                                        Ops.imagesToPdf(listOf(file), out)
                                        mediaScan(ctx, listOf(out))
                                        "PDF gespeichert: ${shortPath(out)}"
                                    }
                                    onChanged()
                                    msg
                                }
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Umbenennen") },
                        onClick = {
                            menu = false
                            input = file.nameWithoutExtension
                            dialog = 1
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Löschen", color = Danger) },
                        onClick = {
                            menu = false
                            dialog = 3
                        }
                    )
                }
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when {
                isPdf -> PdfView(file, zoom)
                isImg -> ImageView(file, zoom)
                isText -> TextView(file)
                else -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    GlassCard {
                        Text("Vorschau nicht möglich", color = Color.White, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Dieses Format (${ext.uppercase()}) kann PDF Studio nicht selbst anzeigen oder bearbeiten. Öffne es mit einer installierten Office-App, oder wandle es dort in PDF um.",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(14.dp))
                        GradientButton("Mit anderer App öffnen") { openExternal(ctx, file) }
                    }
                }
            }

            if (isPdf || isImg) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(1f, 1.5f, 2f).forEach { z ->
                        Chip(if (z == 1.5f) "1,5×" else "${z.toInt()}×", zoom == z) { zoom = z }
                    }
                }
            }
        }
    }

    when (dialog) {
        1 -> AlertDialog(
            onDismissRequest = { dialog = 0 },
            title = { Text("Umbenennen") },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val nn = input.trim().replace('/', '_')
                    dialog = 0
                    if (nn.isNotEmpty()) {
                        val target = File(file.parentFile, "$nn.$ext")
                        if (target.exists()) {
                            toast(ctx, "Dieser Name existiert schon")
                        } else if (file.renameTo(target)) {
                            mediaScan(ctx, listOf(target))
                            onChanged()
                            onClose()
                        } else {
                            toast(ctx, "Umbenennen fehlgeschlagen")
                        }
                    }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { dialog = 0 }) { Text("Abbrechen") } }
        )

        2 -> AlertDialog(
            onDismissRequest = { dialog = 0 },
            title = { Text("Seiten extrahieren") },
            text = {
                Column {
                    Text("Welche Seiten sollen in eine neue PDF? Beispiel: 1-3,5", fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = 0
                    val spec = input
                    scope.runTask(ctx, setBusy, "Erstelle PDF …") {
                        val msg = withContext(Dispatchers.IO) {
                            val total = Ops.pageCount(file)
                            val pages = Ops.parseRange(spec, total)
                            if (pages.isEmpty()) throw IOException("Ungültiger Bereich (1–$total)")
                            val out = uniqueFile(outDocs(ctx), file.nameWithoutExtension + "_Auszug", "pdf")
                            Ops.rasterToPdf(listOf(Pair<File, List<Int>?>(file, pages)), out)
                            mediaScan(ctx, listOf(out))
                            "Gespeichert: ${shortPath(out)}"
                        }
                        onChanged()
                        msg
                    }
                }) { Text("Erstellen") }
            },
            dismissButton = { TextButton(onClick = { dialog = 0 }) { Text("Abbrechen") } }
        )

        3 -> AlertDialog(
            onDismissRequest = { dialog = 0 },
            title = { Text("Datei löschen?") },
            text = { Text("${doc.name} wird unwiderruflich gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = 0
                    file.delete()
                    mediaScan(ctx, listOf(file))
                    onChanged()
                    onClose()
                }) { Text("Löschen", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { dialog = 0 }) { Text("Abbrechen") } }
        )
    }
}

@Composable
fun PdfView(file: File, zoom: Float) {
    val result = remember(file.path) { runCatching { PdfHolder(file) } }
    val holder = result.getOrNull()
    DisposableEffect(holder) { onDispose { holder?.close() } }

    if (holder == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            GlassCard {
                Text("PDF kann nicht angezeigt werden", color = Danger, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Die Datei ist beschädigt oder passwortgeschützt.",
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }
    } else {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wDp = (maxWidth - 16.dp) * zoom
            val wPx = with(LocalDensity.current) { wDp.roundToPx() }.coerceIn(200, 2600)
            val listState = rememberLazyListState()
            val hs = rememberScrollState()
            val current by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxHeight()
                    .horizontalScroll(hs),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 90.dp)
            ) {
                items(holder.pageCount) { i ->
                    PageItem(holder, i, wPx, wDp)
                }
            }

            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("Seite $current / ${holder.pageCount}", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun PageItem(holder: PdfHolder, index: Int, wPx: Int, wDp: Dp) {
    val bmp by produceState<Bitmap?>(null, holder, index, wPx) {
        value = holder.render(index, wPx)
    }
    val b = bmp
    Box(
        Modifier
            .padding(horizontal = 8.dp)
            .width(wDp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
    ) {
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(wDp * 1.41f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(0xFFFF6B6B))
            }
        }
    }
}

@Composable
fun ImageView(file: File, zoom: Float) {
    var bmp by remember(file.path) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(file.path) { mutableStateOf(false) }
    LaunchedEffect(file.path) {
        val r = withContext(Dispatchers.IO) {
            runCatching { Ops.loadBitmap(file, 2600) }.getOrNull()
        }
        if (r == null) failed = true else bmp = r
    }
    val b = bmp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        when {
            failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Bild konnte nicht geladen werden", color = Danger)
            }

            b == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFFF6B6B))
            }

            else -> Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
            ) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.width(maxWidth * zoom),
                    contentScale = ContentScale.FillWidth
                )
            }
        }
    }
}

@Composable
fun TextView(file: File) {
    val text by produceState("Lade …", file.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                file.inputStream().use { ins ->
                    val bos = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0
                    while (total < 400_000) {
                        val r = ins.read(buf)
                        if (r < 0) break
                        bos.write(buf, 0, r)
                        total += r
                    }
                    bos.toString("UTF-8")
                }
            }.getOrElse { "Datei konnte nicht gelesen werden" }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .padding(12.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(CardColor)
    ) {
        SelectionContainer {
            Text(
                text,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp)
            )
        }
    }
}
