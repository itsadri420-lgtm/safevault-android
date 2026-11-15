package de.safevault.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class VaultFile(val id: String, val name: String, val size: Long, val addedAt: Long)

data class VaultNote(val id: String, val title: String, val text: String, val updatedAt: Long)

object Vault {
    const val MAX_FAILS = 10
    const val QUOTA = 5L * 1024L * 1024L * 1024L
    private const val ALIAS = "safevault_wrap_key"
    private const val CHUNK = 64 * 1024
    private const val AES_GCM = "AES/GCM/NoPadding"
    private val rnd = SecureRandom()

    @Volatile
    private var masterKey: ByteArray? = null

    @Volatile
    var busy = false

    var unlocked by mutableStateOf(false)
        private set

    val files = mutableStateListOf<VaultFile>()
    val notes = mutableStateListOf<VaultNote>()

    // ---------- helpers ----------

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("sv", Context.MODE_PRIVATE)

    private fun dir(ctx: Context): File = File(ctx.filesDir, "vault").apply { mkdirs() }
    private fun wrappedFile(ctx: Context) = File(ctx.filesDir, "wrapped.bin")
    private fun metaFile(ctx: Context) = File(ctx.filesDir, "meta.enc")

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun mk(): ByteArray = masterKey ?: throw IllegalStateException("Tresor ist gesperrt")

    private fun derive(mk: ByteArray, label: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(mk, "HmacSHA256"))
        return mac.doFinal(label)
    }

    private fun atomicWrite(f: File, data: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(data)
            it.fd.sync()
        }
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    fun usedBytes(): Long = files.sumOf { it.size }

    // ---------- state ----------

    fun isSetUp(ctx: Context): Boolean = wrappedFile(ctx).exists()

    fun lock() {
        masterKey = null
        unlocked = false
        files.clear()
        notes.clear()
    }

    fun fails(ctx: Context): Int = prefs(ctx).getInt("fails", 0)

    fun registerFail(ctx: Context): Int {
        val n = fails(ctx) + 1
        prefs(ctx).edit().putInt("fails", n).commit()
        return n
    }

    fun resetFails(ctx: Context) {
        prefs(ctx).edit().putInt("fails", 0).commit()
    }

    /** Loescht Schluessel zuerst (Crypto-Erase), danach alle Daten. */
    fun wipe(ctx: Context) {
        lock()
        runCatching {
            val ks = keyStore()
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        }
        ctx.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        prefs(ctx).edit().clear().commit()
    }

    // ---------- Keystore / Biometrie ----------

    fun createWrapCipher(): Cipher {
        val ks = keyStore()
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
                .setUnlockedDeviceRequired(true)
                .build()
        )
        val key = kg.generateKey()
        return Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    fun createUnwrapCipher(ctx: Context): Cipher {
        val key = keyStore().getKey(ALIAS, null) as? SecretKey
            ?: throw KeyPermanentlyInvalidatedException("Schluessel fehlt")
        val wrapped = wrappedFile(ctx).readBytes()
        val ivLen = wrapped[0].toInt() and 0xFF
        val iv = wrapped.copyOfRange(1, 1 + ivLen)
        return Cipher.getInstance(AES_GCM).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
    }

    fun finishSetup(ctx: Context, cipher: Cipher) {
        val newKey = ByteArray(32).also { rnd.nextBytes(it) }
        val ct = cipher.doFinal(newKey)
        val iv = cipher.iv
        atomicWrite(wrappedFile(ctx), byteArrayOf(iv.size.toByte()) + iv + ct)
        masterKey = newKey
        files.clear()
        notes.clear()
        saveMeta(ctx)
        resetFails(ctx)
        unlocked = true
    }

    fun finishUnlock(ctx: Context, cipher: Cipher) {
        val wrapped = wrappedFile(ctx).readBytes()
        val ivLen = wrapped[0].toInt() and 0xFF
        val ct = wrapped.copyOfRange(1 + ivLen, wrapped.size)
        masterKey = cipher.doFinal(ct)
        try {
            loadMeta(ctx)
        } catch (e: Exception) {
            lock()
            throw e
        }
        unlocked = true
    }

    // ---------- Metadaten (verschluesselt) ----------

    @Synchronized
    fun saveMeta(ctx: Context) {
        val mk = mk()
        val root = JSONObject()
        val fa = JSONArray()
        files.toList().forEach {
            fa.put(
                JSONObject().put("id", it.id).put("name", it.name)
                    .put("size", it.size).put("at", it.addedAt)
            )
        }
        val na = JSONArray()
        notes.toList().forEach {
            na.put(
                JSONObject().put("id", it.id).put("title", it.title)
                    .put("text", it.text).put("at", it.updatedAt)
            )
        }
        root.put("files", fa)
        root.put("notes", na)
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance(AES_GCM)
        c.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(derive(mk, "meta".toByteArray()), "AES"),
            GCMParameterSpec(128, iv)
        )
        val ct = c.doFinal(root.toString().toByteArray(Charsets.UTF_8))
        atomicWrite(metaFile(ctx), iv + ct)
    }

    private fun loadMeta(ctx: Context) {
        files.clear()
        notes.clear()
        val f = metaFile(ctx)
        if (!f.exists()) return
        val mk = mk()
        val data = f.readBytes()
        val iv = data.copyOfRange(0, 12)
        val ct = data.copyOfRange(12, data.size)
        val c = Cipher.getInstance(AES_GCM)
        c.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(derive(mk, "meta".toByteArray()), "AES"),
            GCMParameterSpec(128, iv)
        )
        val root = JSONObject(String(c.doFinal(ct), Charsets.UTF_8))
        val fa = root.getJSONArray("files")
        for (i in 0 until fa.length()) {
            val o = fa.getJSONObject(i)
            files.add(VaultFile(o.getString("id"), o.getString("name"), o.getLong("size"), o.getLong("at")))
        }
        val na = root.getJSONArray("notes")
        for (i in 0 until na.length()) {
            val o = na.getJSONObject(i)
            notes.add(VaultNote(o.getString("id"), o.getString("title"), o.getString("text"), o.getLong("at")))
        }
    }

    // ---------- Streaming-Verschluesselung (AES-256-GCM, 64-KB-Bloecke) ----------

    private fun gcmCipher(mode: Int, key: SecretKey, counter: Long, last: Boolean): Cipher {
        val nonce = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()
        val c = Cipher.getInstance(AES_GCM)
        c.init(mode, key, GCMParameterSpec(128, nonce))
        c.updateAAD(byteArrayOf(if (last) 1 else 0))
        return c
    }

    private fun readFully(i: InputStream, b: ByteArray): Int {
        var off = 0
        while (off < b.size) {
            val r = i.read(b, off, b.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    private fun encrypt(mk: ByteArray, input: InputStream, output: OutputStream): Long {
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val key = SecretKeySpec(derive(mk, "file".toByteArray() + salt), "AES")
        var total = 0L
        DataOutputStream(BufferedOutputStream(output, CHUNK * 2)).use { out ->
            out.write(salt)
            val buf = ByteArray(CHUNK)
            var counter = 0L
            while (true) {
                val n = readFully(input, buf)
                val last = n < CHUNK
                val ct = gcmCipher(Cipher.ENCRYPT_MODE, key, counter, last).doFinal(buf, 0, n)
                out.writeByte(if (last) 1 else 0)
                out.writeInt(ct.size)
                out.write(ct)
                total += n
                counter++
                if (last) break
            }
            out.flush()
        }
        return total
    }

    private fun decrypt(mk: ByteArray, input: InputStream, output: OutputStream) {
        val din = DataInputStream(BufferedInputStream(input, CHUNK * 2))
        val salt = ByteArray(16)
        din.readFully(salt)
        val key = SecretKeySpec(derive(mk, "file".toByteArray() + salt), "AES")
        val out = BufferedOutputStream(output, CHUNK * 2)
        var counter = 0L
        while (true) {
            val flag = din.readUnsignedByte()
            val len = din.readInt()
            if (len < 16 || len > CHUNK + 16) throw IOException("Beschaedigte Datei")
            val ct = ByteArray(len)
            din.readFully(ct)
            out.write(gcmCipher(Cipher.DECRYPT_MODE, key, counter, flag == 1).doFinal(ct))
            counter++
            if (flag == 1) break
        }
        out.flush()
    }

    // ---------- Dateien ----------

    suspend fun importFile(ctx: Context, uri: Uri) {
        val mk = mk()
        var name = "Datei"
        var size = -1L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (size >= 0 && usedBytes() + size > QUOTA) {
            throw IOException("Nicht genug Platz im 5-GB-Tresor")
        }
        val id = UUID.randomUUID().toString()
        val out = File(dir(ctx), "$id.bin")
        busy = true
        try {
            val total = withContext(Dispatchers.IO) {
                val input = ctx.contentResolver.openInputStream(uri)
                    ?: throw IOException("Datei nicht lesbar")
                input.use { encrypt(mk, it, FileOutputStream(out)) }
            }
            if (masterKey !== mk) throw IOException("Tresor wurde waehrend des Imports gesperrt")
            files.add(0, VaultFile(id, name, total, System.currentTimeMillis()))
            withContext(Dispatchers.IO) { saveMeta(ctx) }
        } catch (e: Exception) {
            files.removeAll { it.id == id }
            out.delete()
            throw e
        } finally {
            busy = false
        }
    }

    suspend fun exportFile(ctx: Context, f: VaultFile, dest: Uri) {
        val mk = mk()
        busy = true
        try {
            withContext(Dispatchers.IO) {
                File(dir(ctx), "${f.id}.bin").inputStream().use { inp ->
                    val os = ctx.contentResolver.openOutputStream(dest, "wt")
                        ?: throw IOException("Ziel nicht schreibbar")
                    os.use { decrypt(mk, inp, it) }
                }
            }
        } finally {
            busy = false
        }
    }

    suspend fun deleteFile(ctx: Context, f: VaultFile) {
        files.removeAll { it.id == f.id }
        withContext(Dispatchers.IO) {
            File(dir(ctx), "${f.id}.bin").delete()
            saveMeta(ctx)
        }
    }

    // ---------- Notizen ----------

    suspend fun saveNote(ctx: Context, id: String?, title: String, text: String) {
        val n = VaultNote(
            id ?: UUID.randomUUID().toString(),
            title.ifBlank { "Ohne Titel" },
            text,
            System.currentTimeMillis()
        )
        notes.removeAll { it.id == n.id }
        notes.add(0, n)
        withContext(Dispatchers.IO) { saveMeta(ctx) }
    }

    suspend fun deleteNote(ctx: Context, n: VaultNote) {
        notes.removeAll { it.id == n.id }
        withContext(Dispatchers.IO) { saveMeta(ctx) }
    }
}
