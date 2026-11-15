package de.safevault.app

import android.app.Activity
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher

data class NoteDraft(val id: String?, val title: String, val text: String)

// ---------- Helfer ----------

fun fmt(b: Long): String = when {
    b >= (1L shl 30) -> String.format(Locale.GERMANY, "%.2f GB", b / 1073741824.0)
    b >= (1L shl 20) -> String.format(Locale.GERMANY, "%.1f MB", b / 1048576.0)
    b >= (1L shl 10) -> String.format(Locale.GERMANY, "%.0f KB", b / 1024.0)
    else -> "$b B"
}

fun fmtDate(ms: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(ms))

fun isCancel(code: Int): Boolean =
    code == BiometricPrompt.ERROR_USER_CANCELED ||
        code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
        code == BiometricPrompt.ERROR_CANCELED

fun authenticate(
    activity: FragmentActivity,
    cipher: Cipher,
    title: String,
    subtitle: String,
    onSuccess: (Cipher) -> Unit,
    onFail: () -> Unit,
    onError: (Int, CharSequence) -> Unit
): BiometricPrompt {
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            result.cryptoObject?.cipher?.let(onSuccess)
        }

        override fun onAuthenticationFailed() {
            onFail()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            onError(errorCode, errString)
        }
    })
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setNegativeButtonText("Abbrechen")
        .setConfirmationRequired(false)
        .build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    return prompt
}

// ---------- Root ----------

@Composable
fun App(activity: MainActivity) {
    val ctx = LocalContext.current
    var setUp by remember { mutableStateOf(Vault.isSetUp(ctx)) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .systemBarsPadding()
            .imePadding()
    ) {
        when {
            !setUp -> SetupScreen(activity) { setUp = true }
            !Vault.unlocked -> LockScreen(activity) { setUp = false }
            else -> HomeScreen { setUp = false }
        }
    }
}

@Composable
fun PulsingLock() {
    val t = rememberInfiniteTransition(label = "pulse")
    val s by t.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    Box(
        Modifier
            .size(140.dp)
            .scale(s)
            .clip(CircleShape)
            .background(Accent),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(64.dp))
    }
}

// ---------- Einrichtung ----------

@Composable
fun SetupScreen(activity: MainActivity, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        PulsingLock()
        Spacer(Modifier.height(32.dp))
        Text("SafeVault", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(12.dp))
        Text(
            "Dein verschlüsselter Tresor für Dateien und Notizen. Zugang nur per Fingerabdruck.",
            color = Color.White.copy(alpha = 0.75f),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Nach ${Vault.MAX_FAILS} falschen Fingerabdrücken werden Schlüssel und Daten unwiderruflich gelöscht.",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(30.dp))
        GradientButton("Tresor einrichten") {
            error = null
            val can = BiometricManager.from(ctx)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            if (can != BiometricManager.BIOMETRIC_SUCCESS) {
                error = "Bitte richte zuerst einen Fingerabdruck in den Android-Einstellungen ein."
                return@GradientButton
            }
            try {
                val cipher = Vault.createWrapCipher()
                authenticate(
                    activity, cipher, "Tresor einrichten", "Bestätige mit deinem Fingerabdruck",
                    onSuccess = { c ->
                        try {
                            Vault.finishSetup(ctx, c)
                            onDone()
                        } catch (e: Exception) {
                            error = "Einrichtung fehlgeschlagen: ${e.message}"
                        }
                    },
                    onFail = {},
                    onError = { code, msg -> if (!isCancel(code)) error = msg.toString() }
                )
            } catch (e: Exception) {
                error = "Fehler: ${e.message}"
            }
        }
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = Danger, textAlign = TextAlign.Center)
        }
    }
}

// ---------- Sperrbildschirm ----------

@Composable
fun LockScreen(activity: MainActivity, onWiped: () -> Unit) {
    val ctx = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var fails by remember { mutableIntStateOf(Vault.fails(ctx)) }
    var invalid by remember { mutableStateOf(false) }
    val holder = remember { arrayOfNulls<BiometricPrompt>(1) }

    fun unlock() {
        error = null
        try {
            val cipher = Vault.createUnwrapCipher(ctx)
            holder[0] = authenticate(
                activity, cipher, "SafeVault entsperren", "Lege deinen Finger auf den Sensor",
                onSuccess = { c ->
                    try {
                        Vault.finishUnlock(ctx, c)
                        Vault.resetFails(ctx)
                    } catch (e: Exception) {
                        error = "Entschlüsselung fehlgeschlagen"
                    }
                },
                onFail = {
                    fails = Vault.registerFail(ctx)
                    if (fails >= Vault.MAX_FAILS) {
                        holder[0]?.cancelAuthentication()
                        Vault.wipe(ctx)
                        onWiped()
                    }
                },
                onError = { code, msg -> if (!isCancel(code)) error = msg.toString() }
            )
        } catch (e: KeyPermanentlyInvalidatedException) {
            invalid = true
        } catch (e: Exception) {
            error = "Fehler: ${e.message}"
        }
    }

    LaunchedEffect(Unit) { unlock() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        PulsingLock()
        Spacer(Modifier.height(32.dp))
        Text("Gesperrt", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(8.dp))
        if (fails > 0) {
            Text(
                "Fehlversuche: $fails / ${Vault.MAX_FAILS}",
                color = if (fails >= Vault.MAX_FAILS - 3) Danger else Color.White.copy(alpha = 0.7f)
            )
        }
        Spacer(Modifier.height(24.dp))
        if (invalid) {
            GlassCard {
                Text("Schlüssel ungültig", color = Danger, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Die Fingerabdrücke wurden geändert oder der Schlüssel ist verloren. Die Daten sind nicht mehr entschlüsselbar.",
                    color = Color.White.copy(alpha = 0.8f)
                )
                Spacer(Modifier.height(14.dp))
                TextButton(onClick = {
                    Vault.wipe(ctx)
                    onWiped()
                }) { Text("Tresor zurücksetzen (alles löschen)", color = Danger) }
            }
        } else {
            GradientButton("Mit Fingerabdruck entsperren") { unlock() }
        }
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = Danger, textAlign = TextAlign.Center)
        }
    }
}

// ---------- Hauptbildschirm ----------

@Composable
fun HomeScreen(onWiped: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var busyText by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<NoteDraft?>(null) }
    var exporting by remember { mutableStateOf<VaultFile?>(null) }
    var delFile by remember { mutableStateOf<VaultFile?>(null) }
    var delNote by remember { mutableStateOf<VaultNote?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        if (message != null) {
            delay(3500)
            message = null
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                for ((i, u) in uris.withIndex()) {
                    busyText = "Verschlüssele ${i + 1}/${uris.size} …"
                    try {
                        Vault.importFile(ctx, u)
                    } catch (e: Exception) {
                        message = e.message ?: "Import fehlgeschlagen"
                    }
                }
                busyText = null
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val f = exporting
        exporting = null
        if (uri != null && f != null) {
            scope.launch {
                busyText = "Entschlüssele …"
                try {
                    Vault.exportFile(ctx, f, uri)
                    message = "Exportiert (am Ziel unverschlüsselt!)"
                } catch (e: Exception) {
                    message = "Export fehlgeschlagen"
                }
                busyText = null
            }
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val result = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
            val pdf = result?.pdf
            if (pdf != null) {
                scope.launch {
                    busyText = "Verschlüssele Scan …"
                    try {
                        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.GERMANY).format(Date())
                        Vault.importFile(ctx, pdf.uri, "Scan_$stamp.pdf", true)
                        message = "Scan verschlüsselt gespeichert"
                    } catch (e: Exception) {
                        message = e.message ?: "Scan fehlgeschlagen"
                    }
                    busyText = null
                }
            }
        }
    }

    fun startScan() {
        val act = ctx as? Activity
        if (act == null) {
            message = "Scanner nicht verfügbar"
            return
        }
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(20)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        MainActivity.picking = true
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(act)
            .addOnSuccessListener { sender ->
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { e ->
                message = "Scanner nicht verfügbar: ${e.message}"
            }
    }

    val draft = editing
    if (draft != null) {
        NoteEditor(draft, onClose = { editing = null })
    } else {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SafeVault", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { confirmWipe = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Tresor löschen", tint = Danger)
                    }
                    IconButton(onClick = { Vault.lock() }) {
                        Icon(Icons.Default.Lock, contentDescription = "Sperren", tint = Color.White)
                    }
                }
                Spacer(Modifier.height(12.dp))

                val used = Vault.usedBytes()
                GlassCard {
                    Text(
                        "Belegt: ${fmt(used)} von 5 GB",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { (used.toFloat() / Vault.QUOTA.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        color = Color(0xFF00CEC9),
                        trackColor = Color.White.copy(alpha = 0.12f)
                    )
                }
                Spacer(Modifier.height(14.dp))

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(CardColor)
                        .padding(4.dp)
                ) {
                    listOf("Dateien", "Notizen").forEachIndexed { i, label ->
                        val sel = tab == i
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (sel) Accent else SolidColor(Color.Transparent))
                                .clickable { tab = i }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                color = Color.White,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                Crossfade(targetState = tab, modifier = Modifier.weight(1f), label = "tab") { t ->
                    if (t == 0) {
                        if (Vault.files.isEmpty()) {
                            EmptyHint("Noch keine Dateien.\nTippe unten auf Scannen oder Hinzufügen.")
                        } else {
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
                                items(Vault.files, key = { it.id }) { f ->
                                    FadeIn {
                                        FileCard(
                                            f,
                                            onExport = {
                                                exporting = f
                                                MainActivity.picking = true
                                                exportLauncher.launch(f.name)
                                            },
                                            onDelete = { delFile = f }
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        if (Vault.notes.isEmpty()) {
                            EmptyHint("Noch keine Notizen.\nTippe unten auf Neue Notiz.")
                        } else {
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
                                items(Vault.notes, key = { it.id }) { n ->
                                    FadeIn {
                                        NoteCard(
                                            n,
                                            onOpen = { editing = NoteDraft(n.id, n.title, n.text) },
                                            onDelete = { delNote = n }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (tab == 0) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GradientButton("Scannen") { startScan() }
                    GradientButton("+  Dateien") {
                        MainActivity.picking = true
                        importLauncher.launch(arrayOf("*/*"))
                    }
                }
            } else {
                GradientButton(
                    "+  Neue Notiz",
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 22.dp)
                ) {
                    editing = NoteDraft(null, "", "")
                }
            }

            message?.let {
                GlassCard(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 20.dp, end = 20.dp, bottom = 90.dp)
                ) {
                    Text(it, color = Color.White)
                }
            }

            busyText?.let {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.65f))
                        .pointerInput(Unit) {},
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF00CEC9))
                        Spacer(Modifier.height(16.dp))
                        Text(it, color = Color.White)
                    }
                }
            }
        }
    }

    delFile?.let { f ->
        AlertDialog(
            onDismissRequest = { delFile = null },
            title = { Text("Datei löschen?") },
            text = { Text("${f.name} wird unwiderruflich gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    delFile = null
                    scope.launch { Vault.deleteFile(ctx, f) }
                }) { Text("Löschen", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { delFile = null }) { Text("Abbrechen") } }
        )
    }

    delNote?.let { n ->
        AlertDialog(
            onDismissRequest = { delNote = null },
            title = { Text("Notiz löschen?") },
            text = { Text("${n.title} wird unwiderruflich gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    delNote = null
                    scope.launch { Vault.deleteNote(ctx, n) }
                }) { Text("Löschen", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { delNote = null }) { Text("Abbrechen") } }
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Tresor komplett löschen?") },
            text = { Text("Schlüssel, Dateien und Notizen werden unwiderruflich vernichtet.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    Vault.wipe(ctx)
                    onWiped()
                }) { Text("Alles löschen", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Abbrechen") } }
        )
    }
}

@Composable
fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White.copy(alpha = 0.55f), textAlign = TextAlign.Center)
    }
}

@Composable
fun FileCard(f: VaultFile, onExport: () -> Unit, onDelete: () -> Unit) {
    val ext = f.name.substringAfterLast('.', "").take(4).uppercase().ifEmpty { "DATEI" }
    GlassCard(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Accent),
                contentAlignment = Alignment.Center
            ) {
                Text(ext, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                    "${fmt(f.size)}  •  ${fmtDate(f.addedAt)}",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
            IconButton(onClick = onExport) {
                Icon(Icons.Default.Share, contentDescription = "Exportieren", tint = Color.White)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Löschen", tint = Danger)
            }
        }
    }
}

@Composable
fun NoteCard(n: VaultNote, onOpen: () -> Unit, onDelete: () -> Unit) {
    GlassCard(
        Modifier
            .padding(vertical = 6.dp)
            .clickable(onClick = onOpen)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    n.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    n.text,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(fmtDate(n.updatedAt), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Löschen", tint = Danger)
            }
        }
    }
}

@Composable
fun NoteEditor(draft: NoteDraft, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(draft.title) }
    var text by remember { mutableStateOf(draft.text) }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler { onClose() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Schließen", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            GradientButton("Speichern") {
                scope.launch {
                    try {
                        Vault.saveNote(ctx, draft.id, title, text)
                        onClose()
                    } catch (e: Exception) {
                        error = "Speichern fehlgeschlagen"
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Titel") },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Notiz") },
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Danger)
        }
    }
}
