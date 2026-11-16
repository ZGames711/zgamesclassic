package com.evolutionofgames.zgamespsx.install

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import com.evolutionofgames.zgamespsx.emulator.MemstickStorage
import com.evolutionofgames.zgamespsx.emulator.PathResolver
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray

data class InstallRequest(
    val key: String,
    val title: String,
    val uri: String?,
    val sizeHint: Long,
    val downloadId: Long? = null,
    val force: Boolean = false,
    val fileName: String? = null
)

data class GameChoice(val title: String, val gameId: String)

sealed interface InstallEvent {
    data class Finished(
        val request: InstallRequest,
        val success: Boolean,
        val sourceDeleted: Boolean
    ) : InstallEvent
}

sealed interface InstallState {
    object Idle : InstallState
    data class NeedFolder(val title: String, val problem: String? = null) : InstallState
    data class NeedGameId(
        val title: String,
        val error: String? = null,
        val suggestions: List<GameChoice> = emptyList()
    ) : InstallState
    data class NeedPassword(
        val title: String,
        val wrongPassword: Boolean = false,
        val attemptsLeft: Int = 3
    ) : InstallState
    data class Working(
        val title: String,
        val stage: String,
        val doneBytes: Long,
        val totalBytes: Long,
        val doneFiles: Int,
        val totalFiles: Int,
        val currentFile: String,
        val queued: Int = 0
    ) : InstallState
    data class Done(
        val title: String,
        val games: Int,
        val textureFiles: Int,
        val saveFiles: Int,
        val otherFiles: Int,
        val gameId: String?,
        val texturesSkipped: Boolean = false
    ) : InstallState
    data class Info(val title: String, val message: String) : InstallState
    data class Error(val title: String, val message: String, val details: String? = null) : InstallState
}

/**
 * The mod installer: archives (ZIP/RAR/7z, password prompt, max 3
 * attempts, password in-memory only) and single game images.
 *
 * Archives unpack by plan: game images go to Download/ZGames PSX
 * (MediaStore, no permission needed); textures go to
 * TEXTURES/<gameId>/ with replacement on (exactly-one-match rule for
 * texture-only packs: title match, else ask the user, else skip — never
 * install textures to a guessed game); saves and other mapped files go
 * to the memory-stick folder (main-storage /sdcard/PSP when All Files
 * Access is granted, else the previously saved SAF folder).
 *
 * Installed keys persist across restarts (filesDir
 * installed_mod_keys.json): a re-queued record for an already installed
 * (and deleted) source is a silent no-op, never an error.
 */
class ModInstaller(context: Context) {

    private val appCtx = context.applicationContext
    private val prefs = appCtx.getSharedPreferences("psx_installer", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<InstallEvent> = _events

    /** Installed games offered as texture-target suggestions (set by the UI layer). */
    var suggestionProvider: (() -> List<GameChoice>)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val queue = ArrayDeque<InstallRequest>()
    private val handled = LinkedHashSet<String>()
    private val installedKeysFile = File(appCtx.filesDir, "installed_mod_keys.json")
    private val installedKeys = LinkedHashSet<String>()

    private var current: InstallRequest? = null
    private var waitingForFolder: InstallRequest? = null
    private var idAnswer: CompletableDeferred<String?>? = null
    /** Password supplied by the user for the current install (in-memory only). */
    private var passwordAnswer: CompletableDeferred<String?>? = null
    private val main = Handler(Looper.getMainLooper())

    init {
        try {
            if (installedKeysFile.isFile) {
                val arr = JSONArray(installedKeysFile.readText())
                for (i in 0 until arr.length()) {
                    installedKeys.add(arr.optString(i))
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun saveInstalledKeys() {
        try {
            val arr = JSONArray()
            synchronized(this) { installedKeys.forEach { arr.put(it) } }
            val tmp = File(installedKeysFile.parentFile, "installed_mod_keys.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(installedKeysFile)) {
                installedKeysFile.writeText(arr.toString()); tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    /** True when this file was already installed (persisted across restarts). */
    fun isInstalledKey(key: String): Boolean = synchronized(this) { key in installedKeys }

    // ───────────────────────────── public API ─────────────────────────────

    @Synchronized
    fun enqueue(req: InstallRequest) {
        if (current?.key == req.key || waitingForFolder?.key == req.key || queue.any { it.key == req.key }) return
        if (!req.force && req.key in handled) return
        // Persisted across restarts: a re-queued record for an already
        // installed (and deleted) source is a silent no-op, never an error.
        if (!req.force && req.key in installedKeys) return
        queue.addLast(req)
        pump()
    }

    @Synchronized
    private fun pump() {
        if (job?.isActive == true) return
        val st = _state.value
        if (st is InstallState.NeedFolder || st is InstallState.NeedGameId || st is InstallState.NeedPassword) return
        val next = queue.removeFirstOrNull() ?: return
        current = next
        val j = scope.launch { runInstall(next) }
        job = j
        j.invokeOnCompletion { synchronized(this@ModInstaller) { current = null }; pump() }
    }

    /** Called by the UI after the user picked a folder in the system picker. Returns an error text or null. */
    suspend fun setFolder(uri: Uri): String? {
        val doc = try { DocumentFile.fromTreeUri(appCtx, uri) } catch (_: Exception) { null }
            ?: return "That folder can't be used. Please pick your game folder again."
        val name = doc.name.orEmpty().uppercase()
        if (name == "GAME" || name == "SAVEDATA" || name == "TEXTURES") {
            return "Please pick the main game folder (the one that contains GAME, SAVEDATA and TEXTURES), not its \"$name\" subfolder."
        }
        if (!doc.canWrite()) return "This folder isn't writable. Pick a folder in your phone's internal storage, e.g. PSP."
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            appCtx.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) { }
        try {
            val old = prefs.getString(KEY_FOLDER_URI, null)
            if (old != null && old != uri.toString()) {
                appCtx.contentResolver.releasePersistableUriPermission(
                    Uri.parse(old),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        } catch (_: Exception) { }
        prefs.edit().putString(KEY_FOLDER_URI, uri.toString()).apply()
        return null
    }

    /** True when the user previously picked a PSP folder manually (advanced override). */
    fun hasSavedFolderUri(): Boolean =
        try {
            prefs.getString(KEY_FOLDER_URI, null) != null
        } catch (_: Exception) {
            false
        }

    /** After a successful [setFolder] from the "need folder" prompt: carry on with the waiting install. */
    @Synchronized
    fun resumePending() {
        val w = waitingForFolder
        if (w != null && _state.value is InstallState.NeedFolder) {
            waitingForFolder = null
            queue.addFirst(w)
            _state.value = InstallState.Idle
            pump()
        }
    }

    fun provideGameId(raw: String) {
        val id = raw.trim().uppercase()
        val d = idAnswer ?: return
        val st = _state.value as? InstallState.NeedGameId ?: return
        if (!ArchivePlanner.isGameId(id)) {
            _state.value = st.copy(error = "A game ID is 4 letters + 5 digits, e.g. ULUS10041.")
            return
        }
        d.complete(id)
    }

    /** Called by the password dialog: continue the waiting install with this password. */
    fun providePassword(pw: String) {
        passwordAnswer?.complete(pw)
    }

    /** Called by the password dialog's Cancel: stop the waiting install (no error card). */
    fun cancelPassword() {
        passwordAnswer?.complete(null)
    }

    /** Stops the install that is running right now (queued ones continue). */
    fun cancel() {
        idAnswer?.complete(null)
        passwordAnswer?.complete(null)
        job?.cancel()
        if (_state.value !is InstallState.Done) _state.value = InstallState.Idle
    }

    @Synchronized
    fun dismiss() {
        when (_state.value) {
            is InstallState.NeedGameId -> {
                // Dismissing the game pick skips the textures (old behavior).
                idAnswer?.complete(null)
                _state.value = InstallState.Idle
                pump()
            }
            is InstallState.NeedPassword -> {
                // Dismissing the password prompt abandons this install quietly.
                passwordAnswer?.complete(null)
                _state.value = InstallState.Idle
                pump()
            }
            is InstallState.NeedFolder -> {
                val w = waitingForFolder
                waitingForFolder = null
                _state.value = InstallState.Idle
                if (w != null) _events.tryEmit(InstallEvent.Finished(w, success = false, sourceDeleted = false))
                pump()
            }
            is InstallState.Done, is InstallState.Error, is InstallState.Info -> _state.value = InstallState.Idle
            else -> Unit
        }
    }

    // ───────────────────────────── implementation ─────────────────────────────

    private suspend fun resolveRoot(): Pair<DocumentFile?, String?> {
        val saved = prefs.getString(KEY_FOLDER_URI, null) ?: return null to null
        val uri = Uri.parse(saved)
        val granted = appCtx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        if (!granted) return null to "Access to your game folder was lost. Please choose it again."
        val doc = try { DocumentFile.fromTreeUri(appCtx, uri) } catch (_: Exception) { null }
        if (doc == null || !doc.exists()) return null to "Your game folder no longer exists. Please choose it again."
        if (!doc.canWrite()) return null to "Your game folder isn't writable. Please choose it again."
        return normalizeRoot(doc) to null
    }

    private fun normalizeRoot(d: DocumentFile): DocumentFile {
        val known = setOf("GAME", "SAVEDATA", "TEXTURES")
        val kids = try { d.listFiles() } catch (_: Exception) { return d }
        val hasMemstickDirs = kids.any { it.isDirectory && (it.name ?: "").uppercase() in known }
        if (!hasMemstickDirs) {
            kids.firstOrNull { it.isDirectory && it.name.equals("PSP", true) }?.let { return it }
        }
        return d
    }

    private fun displayName(uri: Uri): String? = try {
        when (uri.scheme) {
            "file" -> uri.path?.substringAfterLast('/')
            else -> appCtx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }
    } catch (_: Exception) { null }

    private fun realPath(uri: Uri): File? = try {
        if (uri.scheme == "file") {
            uri.path?.let { File(it) }?.takeIf { it.isFile && it.canRead() }
        } else {
            PathResolver.mediaStorePathFor(appCtx, uri.toString())
                ?.let { File(it) }?.takeIf { it.isFile && it.canRead() }
        }
    } catch (_: Exception) { null }

    private fun inModsFolder(uri: Uri): Boolean = try {
        if (uri.scheme == "file") uri.path.orEmpty().contains("/${PathResolver.MODS_DIR_NAME}/")
        else if (Build.VERSION.SDK_INT >= 29) {
            appCtx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use {
                it.moveToFirst() && (it.getString(0) ?: "").startsWith(PathResolver.MODS_RELATIVE_PATH)
            } ?: false
        } else false
    } catch (_: Exception) { false }

    private fun deleteSource(uri: Uri): Boolean = try {
        if (uri.scheme == "file") uri.path?.let { File(it).delete() } ?: false
        else appCtx.contentResolver.delete(uri, null, null) > 0
    } catch (_: Exception) { false }

    private fun canOpen(uri: Uri): Boolean = try {
        appCtx.contentResolver.openInputStream(uri)?.use { true } ?: false
    } catch (_: Exception) { false }

    /** If the saved URI can't be opened (stale after a rename), look the file up again by name. */
    private fun resolveUri(req: InstallRequest, uri: Uri): Uri {
        if (canOpen(uri)) return uri
        val name = req.fileName ?: return uri
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                // 1) Same name inside the ZGames PSX folder (the usual case).
                appCtx.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf(name, PathResolver.MODS_RELATIVE_PATH + "%"), "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                )?.use {
                    while (it.moveToNext()) {
                        val u = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0))
                        if (canOpen(u)) return u
                    }
                }
                // 2) Same name ANYWHERE in Downloads, newest first: the old
                // lookup failed whenever a download landed outside the Mods
                // folder (changed location, DownloadManager quirks) and the
                // stale URI then errored out as "file moved".
                appCtx.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                    arrayOf(name), "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                )?.use {
                    while (it.moveToNext()) {
                        val u = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0))
                        if (canOpen(u)) return u
                    }
                }
            }
            // 3) The app's own Mods dir on disk, by filename.
            val f = File(legacyDir(), name)
            if (f.isFile) return Uri.fromFile(f)
        } catch (_: Exception) { }
        // Only now (all lookups failed) does the caller report it as gone.
        return uri
    }

    /**
     * A just-completed download can briefly be unresolvable — MediaStore
     * visibility of the finalized entry can lag the completion event, so
     * the first install attempt found nothing while a second download (by
     * which time the first file was visible) worked. Retry the whole
     * lookup chain for up to ~10s before giving up; a genuinely missing
     * file still falls through to the same honest error below.
     */
    private suspend fun resolveUriWithRetry(req: InstallRequest, uri: Uri): Uri {
        repeat(6) { attempt ->
            val resolved = resolveUri(req, uri)
            if (canOpen(resolved)) return resolved
            if (attempt < 5) delay(2000L)
        }
        return uri
    }

    private fun describe(e: Throwable): String {
        val sb = StringBuilder()
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 3) {
            sb.append(t.javaClass.name).append(": ").append(t.message ?: "").append('\n')
            t.stackTrace.take(3).forEach { sb.append("  at ").append(it).append('\n') }
            t = t.cause; depth++
        }
        return sb.toString().trim()
    }

    private suspend fun runInstall(req: InstallRequest) {
        var tmp: File? = null
        val title = req.title
        var emit = true
        var success = false
        var deleted = false
        try {
            // Show "Installing" IMMEDIATELY, before any fallible work:
            // resolveUriWithRetry can silently wait ~10s on MediaStore
            // lag, and until this emission the user sees "Downloaded"
            // with no Installing indicator at all.
            progress(InstallState.Working(title, "Preparing…", 0, 0, 0, 0, "", queueSize()))
            val uriStr = req.uri ?: throw InstallException("This download's file is no longer available. Download it again.")
            val uri = resolveUriWithRetry(req, Uri.parse(uriStr))

            val kind = detectUri(uri)
            val result: InstallState.Done = when (kind) {
                Kind.ZIP, Kind.RAR, Kind.SEVENZ -> {
                    // Archives need random access: use the real file when readable, else a cache copy.
                    var file: File? = realPath(uri)
                    if (file != null) {
                        try { file.inputStream().use { it.read() } } catch (_: IOException) { file = null }
                    }
                    if (file == null) {
                        val f = copyToCache(uri, req.sizeHint)
                        tmp = f; file = f
                    }
                    ensureActive()
                    val srcFile: File = requireNotNull(file)
                    // Password retry loop: a password-protected archive prompts
                    // instead of erroring. The password lives only in this
                    // local variable — never stored. 3 attempts, then a plain error.
                    var password: String? = null
                    var attemptsLeft = 3
                    var wrongBefore = false
                    var done: InstallState.Done? = null
                    while (done == null) {
                        try {
                            val reader: ArchiveReader = when (kind) {
                                Kind.ZIP -> if (password != null) Zip4jArchiveReader.open(srcFile, password) else ZipArchiveReader.open(srcFile)
                                Kind.RAR -> RarArchiveReader.open(srcFile, password)
                                else -> SevenZArchiveReader.open(srcFile, password)
                            }
                            done = reader.use { installArchive(title, reader, password, 0) }
                        } catch (e: PasswordNeededException) {
                            attemptsLeft--
                            if (attemptsLeft <= 0) {
                                throw InstallException("Wrong password — this mod couldn't be installed. Check the password and try again.")
                            }
                            wrongBefore = e.wrongPassword
                            password = askPassword(title, wrongBefore, attemptsLeft)
                                ?: throw CancellationException("password prompt cancelled")
                        }
                    }
                    done
                }
                Kind.GAME_ISO, Kind.GAME_CSO, Kind.GAME_PBP, Kind.GAME_ZSO, Kind.GAME_CHD, Kind.GAME_BIN, Kind.GAME_GZ, Kind.GAME_IMG, Kind.GAME_MDF -> registerSingleGame(title, uri, kind, req.sizeHint)
                // ZGamez's UNKNOWN message, byte-identical. The
                // diagnosis of WHAT the file actually is (HTML page vs
                // unknown extension) rides in `details`, shown under
                // the message in the install-error dialog.
                Kind.UNKNOWN -> throw InstallException(
                    "This file isn't a ZIP, RAR, 7z or game image, so there's nothing to install.",
                    details = unknownFileDiagnosis(uri)
                )
            }
            success = true
            _state.value = result
            val isArchive = kind == Kind.ZIP || kind == Kind.RAR || kind == Kind.SEVENZ
            if (isArchive && (result.games + result.textureFiles + result.saveFiles + result.otherFiles) > 0 &&
                prefs.getBoolean(KEY_DELETE_ARCHIVE, true) && inModsFolder(uri)
            ) {
                deleted = deleteSource(uri)
            }
            toast(
                when {
                    result.games > 0 && result.textureFiles > 0 -> "Ready to play: $title — mod installed with textures"
                    result.games > 0 && result.texturesSkipped -> "Ready to play: $title — textures couldn't be matched to a game, so they were skipped"
                    result.games > 0 -> "Ready to play: $title — open the Games tab"
                    result.textureFiles > 0 -> "Mod installed with textures: $title"
                    result.texturesSkipped -> "Installed: $title — textures couldn't be matched to a game, so they were skipped"
                    else -> "Installed: $title"
                }
            )
        } catch (e: SourceGoneException) {
            // "already" now survives restarts (persisted installedKeys), so
            // a re-queued record for a deleted-but-installed source is never
            // reported as an error again.
            val already = synchronized(this) { req.key in handled || req.key in installedKeys }
            if (already && !req.force) {
                // Second request for a file we just installed (and removed): nothing to do, nothing to report.
                _state.value = if (_state.value is InstallState.Working) InstallState.Idle else _state.value
            } else if (already) {
                // User explicitly re-tapped Install on an already-installed
                // file whose source is gone: tell them where the game is,
                // as a notice — never as an error card.
                _state.value = InstallState.Info(title, "This file was already installed and removed — look for the game in the Games tab.")
            } else {
                _state.value = InstallState.Error(title, e.message ?: "The downloaded file couldn't be found.")
            }
        } catch (e: NeedFolderSignal) {
            emit = false
            synchronized(this) { waitingForFolder = req }
            _state.value = InstallState.NeedFolder(title, e.problem)
        } catch (e: CancellationException) {
            _state.value = InstallState.Idle
            throw e
        } catch (e: Throwable) {
            if (!scope.isActive || job?.isActive == false) {
                _state.value = InstallState.Idle
            } else {
                val msg = friendly(e)
                // An InstallException with user-facing details (the
                // UNKNOWN diagnosis) shows those; everything else keeps
                // the stack-trace details for debugging (the dialog
                // only renders plain-text details, never stack traces).
                val details = (e as? InstallException)?.details ?: describe(e)
                _state.value = InstallState.Error(title, msg, details)
                toast("Couldn't install $title: $msg")
            }
        } finally {
            try { tmp?.delete() } catch (_: Exception) { }
            idAnswer = null
            passwordAnswer = null
            if (emit) {
                if (success) synchronized(this) {
                    handled.add(req.key); while (handled.size > 200) handled.remove(handled.first())
                    // Persist, so a restart can't turn this into a "file moved" error.
                    if (installedKeys.add(req.key)) {
                        while (installedKeys.size > 500) installedKeys.remove(installedKeys.first())
                        saveInstalledKeys()
                    }
                }
                _events.tryEmit(InstallEvent.Finished(req, success, deleted))
            }
        }
    }

    @Synchronized private fun queueSize() = queue.size

    private fun progress(s: InstallState) { _state.value = s }

    private enum class Kind { ZIP, RAR, SEVENZ, GAME_ISO, GAME_CSO, GAME_PBP, GAME_ZSO, GAME_CHD, GAME_BIN, GAME_GZ, GAME_IMG, GAME_MDF, UNKNOWN }

    private fun detectUri(uri: Uri): Kind {
        val head = ByteArray(16)
        var n = -1
        var failure: Exception? = null
        val ins = try { appCtx.contentResolver.openInputStream(uri) } catch (e: Exception) { failure = e; null }
        if (ins == null) {
            if (failure is SecurityException) {
                throw InstallException("Android didn't let this app read this file — it was probably saved by an older install of the app. Download it again.", failure)
            }
            throw SourceGoneException()
        }
        try { ins.use { n = it.read(head) } } catch (e: Exception) { failure = e }
        if (n < 0) {
            if (failure != null) throw InstallException("Couldn't read the downloaded file (${failure.javaClass.simpleName}). Download it again.", failure)
            throw InstallException("The downloaded file is empty. Download it again.")
        }
        fun b(i: Int) = if (i < n) head[i].toInt() and 0xFF else -1
        if (b(0) == 0x50 && b(1) == 0x4B && (b(2) == 3 || b(2) == 5)) return Kind.ZIP
        if (b(0) == 0x52 && b(1) == 0x61 && b(2) == 0x72 && b(3) == 0x21) return Kind.RAR
        if (b(0) == 0x37 && b(1) == 0x7A && b(2) == 0xBC && b(3) == 0xAF) return Kind.SEVENZ
        // gzip'd disc image: magic 1F 8B.
        if (b(0) == 0x1F && b(1) == 0x8B) return Kind.GAME_GZ
        if (b(0) == 'C'.code && b(1) == 'I'.code && b(2) == 'S'.code && b(3) == 'O'.code) return Kind.GAME_CSO
        if (b(0) == 'Z'.code && b(1) == 'I'.code && b(2) == 'S'.code && b(3) == 'O'.code) return Kind.GAME_ZSO
        if (b(0) == 0 && b(1) == 'P'.code && b(2) == 'B'.code && b(3) == 'P'.code) return Kind.GAME_PBP
        if (b(0) == 'M'.code && b(1) == 'C'.code && b(2) == 'o'.code && b(3) == 'm'.code && b(4) == 'p'.code) return Kind.GAME_CHD
        // Raw PS2 BIN (2352-byte sectors): Mode2 sync header
        // 00 FF FF FF FF FF FF FF FF FF FF 00 at offset 0. A 2048-byte
        // "BIN" is just an ISO and is caught by the CD001 check below.
        if (n >= 12 && b(0) == 0 && b(11) == 0 &&
            (1..10).all { b(it) == 0xFF }) return Kind.GAME_BIN
        // Raw BIN PVD: "CD001" at sector 16 of 2352-byte sectors.
        // NOTE: on EOF here we must NOT return UNKNOWN — a short file can
        // still be a valid 2048-sector ISO, which the check below catches.
        // So EOF just ends this check (binPvd stays false).
        var binPvd = false
        try {
            appCtx.contentResolver.openInputStream(uri)?.use { inp ->
                var toSkip = 16 * 2352L + 1
                var eof = false
                while (toSkip > 0 && !eof) {
                    val sk = inp.skip(toSkip)
                    if (sk <= 0) { if (inp.read() < 0) eof = true else toSkip-- } else toSkip -= sk
                }
                if (!eof) {
                    val sig = ByteArray(5)
                    var got = 0
                    while (got < 5) { val r = inp.read(sig, got, 5 - got); if (r < 0) break; got += r }
                    binPvd = got == 5 && String(sig, Charsets.ISO_8859_1) == "CD001"
                }
            }
        } catch (_: Exception) { }
        if (binPvd) return Kind.GAME_BIN
        // ISO9660: "CD001" at byte 0x8001. A raw .img dump has the same
        // layout as an ISO, so it is its own kind (kept with an .img
        // extension) rather than renamed to .iso.
        try {
            appCtx.contentResolver.openInputStream(uri)?.use { inp ->
                var toSkip = 16 * 2048L + 1
                while (toSkip > 0) {
                    val sk = inp.skip(toSkip)
                    if (sk <= 0) { if (inp.read() < 0) return Kind.UNKNOWN else toSkip-- } else toSkip -= sk
                }
                val sig = ByteArray(5)
                var got = 0
                while (got < 5) { val r = inp.read(sig, got, 5 - got); if (r < 0) break; got += r }
                if (got == 5 && String(sig, Charsets.ISO_8859_1) == "CD001") {
                    return if (displayName(uri)?.substringAfterLast('.', "")?.lowercase() == "img") {
                        Kind.GAME_IMG
                    } else {
                        Kind.GAME_ISO
                    }
                }
            }
        } catch (_: Exception) { }
        // Extension fallback for formats with no reliable magic: .mdf
        // (Alcohol image) has no trustworthy header, and the PSP core never
        // uses these extensions, so a bare download with one is a PS2 game
        // image to copy as-is — never an "unknown file" error.
        return when (displayName(uri)?.substringAfterLast('.', "")?.lowercase()) {
            "mdf" -> Kind.GAME_MDF
            "gz" -> Kind.GAME_GZ
            "img" -> Kind.GAME_IMG
            else -> Kind.UNKNOWN
        }
    }

    /**
     * Diagnosis for a file the installer can't recognize, shown UNDER
     * ZGamez's exact UNKNOWN message in the error dialog: a saved HTML
     * page says so outright, and any other format names its extension.
     * (The download HTML guard + the WebView resolver now stop HTML
     * pages from reaching the installer in the first place; this is
     * the backstop diagnosis if one still does.)
     */
    private fun unknownFileDiagnosis(uri: Uri): String {
        val name = displayName(uri)
        val ext = name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() }
        val head = ByteArray(16)
        var n = 0
        try { appCtx.contentResolver.openInputStream(uri)?.use { n = it.read(head) } } catch (_: Exception) { }
        var i = 0
        while (i < n && (head[i] == 0x20.toByte() || head[i] == 0x09.toByte() || head[i] == 0x0A.toByte() || head[i] == 0x0D.toByte())) i++
        val looksHtml = i < n && head[i] == 0x3C.toByte()
        return when {
            looksHtml -> "This file looks like a web page (HTML), not a game file — the download saved a preview page instead of the real file. Delete it and download again."
            ext != null -> "This is a .$ext file, which the installer doesn't recognize."
            else -> "This file has no recognizable game or archive format."
        }
    }

    private fun copyToCache(uri: Uri, sizeHint: Long): File {
        val dir = File(appCtx.cacheDir, "mod_install").apply { mkdirs() }
        dir.listFiles()?.forEach { try { it.delete() } catch (_: Exception) { } }
        if (sizeHint > 0 && dir.usableSpace < sizeHint + 64L * 1024 * 1024) {
            throw InstallException("Not enough free phone storage to unpack this mod. Free up about ${(sizeHint / (1024 * 1024)) + 64} MB and try again.")
        }
        val out = File(dir, "src.bin")
        val input = try { appCtx.contentResolver.openInputStream(uri) } catch (e: Exception) { null }
            ?: throw SourceGoneException()
        try {
            input.use { i -> out.outputStream().use { o ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val r = i.read(buf)
                    if (r < 0) break
                    o.write(buf, 0, r)
                    ensureActive()
                }
            } }
        } catch (e: IOException) {
            try { out.delete() } catch (_: Exception) { }
            throw InstallException("Not enough free phone storage to unpack this mod.", e)
        }
        return out
    }

    private fun ensureActive() { if (job?.isActive == false) throw CancellationException("cancelled") }

    private inner class Tracker(val title: String, val totalBytes: Long, val totalFiles: Int) {
        var doneBytes = 0L
        var doneFiles = 0
        var current = ""
        private var last = 0L
        fun report(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - last < 200) return
            last = now
            _state.value = InstallState.Working(title, "Installing…", doneBytes, totalBytes, doneFiles, totalFiles, current, queueSize())
        }
    }

    private inner class CountingOut(out: OutputStream, val t: Tracker) : FilterOutputStream(out) {
        override fun write(b: Int) { out.write(b); t.doneBytes++; check() }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); t.doneBytes += len; check() }
        private fun check() { ensureActive(); t.report() }
    }

    // ── game-image sink: Download/ZGames PSX (MediaStore, no permission needed) ──

    private class SinkFile(val out: OutputStream, val commit: () -> Uri?, val abort: () -> Unit)

    private fun sinkExistingSize(name: String): Long? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            appCtx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.SIZE),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(name, PathResolver.MODS_RELATIVE_PATH), null
            )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        } else legacyDir().let { File(it, name).takeIf { f -> f.isFile }?.length() }
    } catch (_: Exception) { null }

    @Suppress("DEPRECATION")
    private fun legacyDir(): File =
        File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), PathResolver.MODS_DIR_NAME).also { it.mkdirs() }

    private fun sinkDelete(name: String) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                appCtx.contentResolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(name, PathResolver.MODS_RELATIVE_PATH)
                )
            } else File(legacyDir(), name).delete()
        } catch (_: Exception) { }
    }

    private fun sinkCreate(name: String): SinkFile? {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = appCtx.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, PathResolver.MODS_RELATIVE_PATH)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                val out = resolver.openOutputStream(uri) ?: run { resolver.delete(uri, null, null); return null }
                return SinkFile(
                    out,
                    commit = {
                        try {
                            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                            uri
                        } catch (_: Exception) { null }
                    },
                    abort = { try { resolver.delete(uri, null, null) } catch (_: Exception) { } }
                )
            } else {
                val f = File(legacyDir(), name)
                return SinkFile(FileOutputStream(f), commit = { Uri.fromFile(f) }, abort = { try { f.delete() } catch (_: Exception) { } })
            }
        } catch (_: Exception) { return null }
    }

    /** Reads title/ID/cover straight from a file written to the sink. */
    private fun infoFromUri(uri: Uri): GameInfo? {
        val pfd = try { appCtx.contentResolver.openFileDescriptor(uri, "r") } catch (_: Exception) { null } ?: return null
        return try {
            val ch = FileInputStream(pfd.fileDescriptor).channel
            IsoInfo.readInfo(object : IsoInfo.Source {
                override fun read(pos: Long, buf: ByteArray, len: Int): Int {
                    ch.position(pos)
                    val bb = java.nio.ByteBuffer.wrap(buf, 0, len)
                    var total = 0
                    while (bb.hasRemaining()) { val n = ch.read(bb); if (n <= 0) break; total += n }
                    return total
                }
            })
        } finally { try { pfd.close() } catch (_: Exception) { } }
    }

    private fun cleanName(n: String) = n.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim().trimEnd('.', ' ').ifEmpty { "game.iso" }

    /**
     * A game image under GAME/ at ANY depth (GAME/game.iso and
     * GAME/sub/game.iso alike) is a game to sink into ZGames PSX
     * and count — the old exactly-one-slash rule dropped game images
     * nested in a GAME subfolder (written to the memstick, never
     * counted, never shown with Play). EBOOT.PBP keeps its memstick
     * path (it must stay in its GAME/<id>/ folder) and is counted by
     * the separate EBOOT check, so it is excluded here.
     */
    private fun isGameImageDest(dest: String) =
        dest.startsWith("GAME/") &&
                dest.substringAfterLast('.', "").lowercase() in setOf("iso", "cso", "pbp", "chd", "zso", "bin", "cue", "gz", "img", "mdf", "mds") &&
                !dest.endsWith("EBOOT.PBP", true)

    /** A .cue sidecar is written like a game image (same ZGames PSX folder as its .bin) but is not a game itself — never counted. */
    private fun isCueDest(dest: String) = dest.substringAfterLast('.', "").lowercase() == "cue"

    /** Writes one archive entry to its final place. Returns the sink Uri for game images. */
    private fun writeEntry(
        reader: ArchiveReader, e: ArchiveEntry, dest: String, writer: MemstickWriter?, t: Tracker,
        sinkName: String? = null
    ): Uri? {
        t.current = dest.substringAfterLast('/')
        t.report(true)
        if (isGameImageDest(dest)) {
            val name = sinkName ?: cleanName(dest.substringAfter('/'))
            // ALWAYS rewrite. The old "same name + same size = already
            // unpacked, reuse it" shortcut silently skipped every patched
            // ISO: TTT mods keep the base game's file name AND byte size,
            // so the mod was never written and the old game stayed.
            if (sinkExistingSize(name) != null) sinkDelete(name)
            val sink = sinkCreate(name)
                ?: throw InstallException("Couldn't create \"$name\" in Download/ZGames PSX. Check that your phone has free space.")
            var ok = false
            try {
                CountingOut(sink.out, t).use { reader.extract(e, it) }
                ok = true
            } catch (ex: Throwable) {
                sink.abort()
                if (ex is IOException) throw InstallException("Writing the game failed — your phone may be out of space.", ex)
                throw ex
            } finally { if (!ok) try { sink.out.close() } catch (_: Exception) { } }
            val uri = sink.commit()
            t.doneFiles++; t.report(true)
            return uri
        }
        val w = writer ?: throw InstallException("Game folder isn't set.")
        val raw = w.openOutput(dest)
            ?: throw InstallException("Couldn't create \"$dest\" in your game folder. Check that the folder is still there and your phone has free space.")
        try {
            CountingOut(raw, t).use { reader.extract(e, it) }
        } catch (ex: Throwable) {
            w.delete(dest)
            if (ex is IOException) throw InstallException("Writing to your game folder failed — your phone may be out of space.", ex)
            throw ex
        }
        t.doneFiles++; t.report(true)
        return null
    }

    private fun findSinkUri(name: String): Uri? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            appCtx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(name, PathResolver.MODS_RELATIVE_PATH), null
            )?.use { if (it.moveToFirst()) android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0)) else null }
        } else Uri.fromFile(File(legacyDir(), name))
    } catch (_: Exception) { null }

    /**
     * Matches a texture-only archive to an already-installed game WITHOUT
     * guessing wrong. Delegates to [TitleMatcher] (pure): abbreviation
     * expansion (DBZ/TTT), the known TTT game IDs, and the exactly-one
     * rule. Zero or ambiguous matches return null: the caller then asks
     * the user (askGameId) or skips the textures — never installs them
     * to a guessed game.
     */
    private fun matchGameIdFromTitle(title: String, paths: List<String>): String? {
        val installed = try {
            suggestionProvider?.invoke().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        return TitleMatcher.matchGame(
            title, paths,
            installed.mapNotNull { g -> InstalledGameRef(g.title, g.gameId) }
        )
    }

    /** Turns texture replacement ON for the memstick this install wrote to. */
    private fun enableTextureReplacement(writer: MemstickWriter?, memstickDir: File?) {
        try {
            if (memstickDir != null) {
                MemstickStorage.enableTextureReplacement(memstickDir)
                return
            }
            val saf = writer as? SafWriter ?: return
            val existing = saf.readText("SYSTEM/ppsspp.ini")
            val patched = MemstickStorage.patchIniForTextureReplacement(existing)
            saf.openOutput("SYSTEM/ppsspp.ini")?.use { it.write(patched.toByteArray(Charsets.UTF_8)) }
        } catch (_: Exception) {
        }
    }

    private fun isArchiveName(p: String): Boolean {
        val l = p.lowercase()
        return l.endsWith(".zip") || l.endsWith(".rar") || l.endsWith(".7z")
    }

    /** Magic-byte sniff of a file on disk (used for archives found inside archives). */
    private fun sniffArchive(f: File): Kind? = try {
        val h = ByteArray(8)
        val n = f.inputStream().use { it.read(h) }
        fun b(i: Int) = if (i < n) h[i].toInt() and 0xFF else -1
        when {
            b(0) == 0x50 && b(1) == 0x4B -> Kind.ZIP
            b(0) == 0x52 && b(1) == 0x61 && b(2) == 0x72 && b(3) == 0x21 -> Kind.RAR
            b(0) == 0x37 && b(1) == 0x7A && b(2) == 0xBC && b(3) == 0xAF -> Kind.SEVENZ
            else -> null
        }
    } catch (_: Exception) { null }

    /**
     * Mods are very often "a 7z inside a zip" (or the reverse). Instead of
     * giving up, unpack the inner archive to cache and install from it.
     */
    private suspend fun installNested(
        title: String, reader: ArchiveReader, inner: ArchiveEntry, password: String?, depth: Int
    ): InstallState.Done {
        val dir = File(appCtx.cacheDir, "mod_install_nested").apply { mkdirs() }
        dir.listFiles()?.forEach { try { it.delete() } catch (_: Exception) { } }
        if (inner.size > 0 && dir.usableSpace < inner.size + 64L * 1024 * 1024) {
            throw InstallException("Not enough free phone storage to unpack this mod. Free up about ${(inner.size / (1024 * 1024)) + 64} MB and try again.")
        }
        val f = File(dir, "inner$depth.bin")
        try {
            _state.value = InstallState.Working(title, "Unpacking…", 0, maxOf(inner.size, 0L), 0, 1, inner.path.substringAfterLast('/'), queueSize())
            val t = Tracker(title, maxOf(inner.size, 0L), 1)
            t.current = inner.path.substringAfterLast('/')
            try {
                CountingOut(FileOutputStream(f), t).use { reader.extract(inner, it) }
            } catch (ex: IOException) {
                throw InstallException("Writing the inner archive failed — your phone may be out of space.", ex)
            }
            val k = sniffArchive(f)
                ?: throw InstallException("The archive inside this mod couldn't be recognized. Download it again.")
            val r: ArchiveReader = when (k) {
                Kind.ZIP -> if (password != null) Zip4jArchiveReader.open(f, password) else ZipArchiveReader.open(f)
                Kind.RAR -> RarArchiveReader.open(f, password)
                else -> SevenZArchiveReader.open(f, password)
            }
            return r.use { installArchive(title, r, password, depth + 1) }
        } finally {
            try { f.delete() } catch (_: Exception) { }
        }
    }

    private suspend fun installArchive(
        title: String, reader: ArchiveReader, password: String? = null, depth: Int = 0
    ): InstallState.Done {
        val files = reader.entries.filter { !it.isDir }
        val paths = files.map { it.path }
        val an = ArchivePlanner.analyze(paths)
        if (an.installable == 0) {
            val inner = files.firstOrNull { isArchiveName(it.path) }
            if (inner != null && depth < 2) return installNested(title, reader, inner, password, depth)
            val unpackedIso = paths.any { p -> p.replace('\\', '/').split('/').any { it.equals("PSP_GAME", true) } }
            throw InstallException(
                when {
                    inner != null -> "This archive contains archives nested too deeply. Extract it with a file manager first."
                    unpackedIso -> "This mod contains unpacked game files (a PSP_GAME folder), not a ready-to-play game image. Look for the ISO / CSO version of this mod."
                    else -> "Nothing usable was found in this archive (no game image, TEXTURES or SAVEDATA)."
                }
            )
        }
        val ctx = ArchivePlanner.buildContext(paths)
        val planned = files.mapNotNull { e ->
            val m = ArchivePlanner.map(e.path, ctx, null)
            if (m is ArchivePlanner.Mapped.Skip) null else e to m
        }
        val needsWriter = planned.any { (_, m) -> m !is ArchivePlanner.Mapped.Dest || !isGameImageDest(m.path) }

        // Where texture/save files go — FULLY AUTOMATIC, no folder
        // picker in the normal flow: All Files Access granted → plain
        // files in the main-storage PSP folder (/sdcard/PSP); otherwise
        // the previously saved SAF folder when one is still valid; else
        // the auto app-private PSP folder (created + selected at
        // startup, and the same folder the core reads from
        // memstick_dir.txt). A first install therefore never blocks on
        // a manual pick. A manual override stays available in Settings
        // (advanced) for users who want to pick a folder themselves.
        var writer: MemstickWriter? = null
        var memstickDir: File? = null
        if (needsWriter) {
            if (MemstickStorage.isMainStorageGranted(appCtx)) {
                memstickDir = MemstickStorage.ensureConfigured(appCtx)
                writer = FileMemstickWriter(memstickDir)
            } else {
                val (root, _) = resolveRoot()
                if (root != null) {
                    writer = SafWriter(appCtx, root)
                } else {
                    memstickDir = MemstickStorage.ensureConfigured(appCtx)
                    writer = FileMemstickWriter(memstickDir)
                }
            }
        }

        // Give every unpacked game image a name that belongs to THIS mod.
        // One image: the mod's own title (what the user sees in Games).
        // Several: "<title> - <file>". Two different mods that both ship a
        // file called e.g. "TTT.iso" can therefore never overwrite each
        // other, and the library shows the mod name instead of "TTT".
        val imageEntries = planned.filter { (_, m) ->
            m is ArchivePlanner.Mapped.Dest && isGameImageDest(m.path) && !isCueDest(m.path)
        }
        val modBase = cleanName(title)
            .replace(Regex("(?i)\\.(iso|cso|zso|pbp|chd|7z|rar|zip)$"), "")
            .take(90).trim().trimEnd('.', ' ').ifEmpty { "Mod" }
        fun sinkNameFor(path: String): String {
            val orig = cleanName(path.substringAfter('/'))
            val ext = orig.substringAfterLast('.', "")
            return when {
                title.isBlank() -> orig
                imageEntries.size <= 1 -> if (ext.isEmpty()) modBase else "$modBase.$ext"
                else -> cleanName("$modBase - ${orig.substringAfterLast('/')}")
            }
        }
        val tracker = Tracker(title, planned.sumOf { maxOf(it.first.size, 0L) }, planned.size)
        var gameId: String? = null
        val done = HashSet<ArchiveEntry>()
        var games = 0; var tex = 0; var save = 0; var other = 0
        var texturesSkipped = false

        // Game images first: they are needed anyway, and may tell us the game ID for texture packs.
        // This runs for SOLID archives too (7z): the reader walks forward past
        // non-game entries, and the second pass re-opens when it must go back
        // — so textures listed BEFORE the ISO in a 7z no longer get skipped.
        for ((e, m) in planned) {
            if (m is ArchivePlanner.Mapped.Dest && isGameImageDest(m.path)) {
                val uri = writeEntry(reader, e, m.path, writer, tracker, sinkNameFor(m.path))
                done.add(e)
                // A .cue sidecar is written but is not a game itself, and
                // carries no game ID — skip both the count and the probe.
                if (!isCueDest(m.path)) games++
                if (gameId == null && an.needsGameId && uri != null && !isCueDest(m.path)) {
                    gameId = infoFromUri(uri)?.gameId
                }
            }
        }
        // Match a textures-only pack to an installed game by title/folder
        // name (TitleMatcher: abbreviations, TTT markers, exactly-one
        // rule). If automatic matching fails but the user HAS games to
        // choose from, ask them which game it's for instead of silently
        // skipping the textures — the dialog lists their installed games
        // and Cancel keeps the old skip behavior. No games installed at
        // all: skip as before (textures can't apply anywhere).
        if (an.needsGameId && gameId == null) gameId = matchGameIdFromTitle(title, paths)
        if (an.needsGameId && gameId == null) {
            val sugg = try { suggestionProvider?.invoke().orEmpty() } catch (_: Exception) { emptyList() }
            if (sugg.isNotEmpty()) gameId = askGameId(title)
        }
        if (an.needsGameId) tracker.report(true)

        for ((e, _) in planned) {
            if (e in done) continue
            val m = ArchivePlanner.map(e.path, ctx, gameId)
            if (m !is ArchivePlanner.Mapped.Dest) {
                if (ArchivePlanner.map(e.path, ctx, null) is ArchivePlanner.Mapped.NeedsId) texturesSkipped = true
                continue
            }
            val uri = writeEntry(reader, e, m.path, writer, tracker, if (isGameImageDest(m.path)) sinkNameFor(m.path) else null)
            when {
                m.path.startsWith("TEXTURES/") -> tex++
                m.path.startsWith("SAVEDATA/") -> save++
                (isGameImageDest(m.path) && !isCueDest(m.path)) || (m.path.startsWith("GAME/") && m.path.endsWith("EBOOT.PBP", true)) -> {
                    games++
                    // Solid archives extract in order: an ISO written here
                    // can still identify later texture entries.
                    if (gameId == null && an.needsGameId && uri != null) {
                        gameId = infoFromUri(uri)?.gameId
                    }
                }
                else -> other++
            }
        }
        if (gameId == null) gameId = firstTextureId(paths, ctx)
        if (tex > 0) enableTextureReplacement(writer, memstickDir)
        return InstallState.Done(title, games, tex, save, other, gameId, texturesSkipped)
    }

    private fun firstTextureId(paths: List<String>, ctx: ArchivePlanner.Context): String? {
        for (p in paths) {
            val m = ArchivePlanner.map(p, ctx, null)
            if (m is ArchivePlanner.Mapped.Dest && m.path.startsWith("TEXTURES/")) return m.path.split('/').getOrNull(1)
        }
        return null
    }

    private suspend fun askGameId(title: String): String? {
        val d = CompletableDeferred<String?>()
        idAnswer = d
        val sugg = try { suggestionProvider?.invoke().orEmpty() } catch (_: Exception) { emptyList() }
        _state.value = InstallState.NeedGameId(title, null, sugg)
        return try { d.await() } finally { idAnswer = null }
    }

    /**
     * Shows the password prompt and waits for the user's answer (null = cancelled).
     * The password is held only in the runInstall retry loop's local variable.
     */
    private suspend fun askPassword(title: String, wrongBefore: Boolean, attemptsLeft: Int): String? {
        val d = CompletableDeferred<String?>()
        passwordAnswer = d
        _state.value = InstallState.NeedPassword(title, wrongBefore, attemptsLeft)
        return try { d.await() } finally { passwordAnswer = null }
    }

    /** A plain ISO/CSO/PBP download: it already sits in ZGames PSX, so there is nothing to copy. */
    private fun registerSingleGame(title: String, uri: Uri, kind: Kind, sizeHint: Long): InstallState.Done {
        // PSP games (ISO/CSO/PBP that carry PSP_GAME) are also placed in the PSP folder's GAME
        // directory on main storage, so the emulator's own game list sees them. PS2 images are not
        // touched. Best effort: a failure here leaves the game playable from ZGames PSX.
        try {
            val isPspImage = (kind == Kind.GAME_ISO || kind == Kind.GAME_CSO || kind == Kind.GAME_PBP) &&
                    (try { infoFromUri(uri) != null } catch (_: Exception) { false })
            if (isPspImage && MemstickStorage.isMainStorageGranted(appCtx)) copyToPspGameDir(title, uri, kind, sizeHint)
        } catch (_: Exception) { }
        if (inModsFolder(uri)) return InstallState.Done(title, 1, 0, 0, 0, null)
        val ext = when (kind) { Kind.GAME_CSO -> "cso"; Kind.GAME_PBP -> "pbp"; Kind.GAME_ZSO -> "zso"; Kind.GAME_CHD -> "chd"; Kind.GAME_BIN -> "bin"; Kind.GAME_GZ -> "gz"; Kind.GAME_IMG -> "img"; Kind.GAME_MDF -> "mdf"; else -> "iso" }
        var name = cleanName(displayName(uri) ?: title)
        if (!name.lowercase().endsWith(".$ext")) name = name.substringBeforeLast('.', name) + ".$ext"
        val t = Tracker(title, sizeHint, 1)
        t.current = name
        t.report(true)
        val input = try { appCtx.contentResolver.openInputStream(uri) } catch (_: Exception) { null }
            ?: throw SourceGoneException()
        val sink = sinkCreate(name) ?: run { input.close(); throw InstallException("Couldn't create the game file in Download/ZGames PSX. Check your free space.") }
        try {
            CountingOut(sink.out, t).use { out ->
                input.use { inp ->
                    val buf = ByteArray(256 * 1024)
                    while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                }
            }
        } catch (e: Throwable) {
            sink.abort()
            if (e is IOException) throw InstallException("Writing the game failed — your phone may be out of space.", e)
            throw e
        }
        sink.commit()
        return InstallState.Done(title, 1, 0, 0, 0, null)
    }

    /** Copies a PSP game image to <PSP>/GAME/<name> (skips when an identical file is already there). */
    private fun copyToPspGameDir(title: String, uri: Uri, kind: Kind, sizeHint: Long) {
        val ext = when (kind) { Kind.GAME_CSO -> "cso"; Kind.GAME_PBP -> "pbp"; else -> "iso" }
        var name = cleanName(displayName(uri) ?: title)
        if (!name.lowercase().endsWith(".$ext")) name = name.substringBeforeLast('.', name) + ".$ext"
        val gameDir = File(MemstickStorage.ensureConfigured(appCtx), "GAME").apply { mkdirs() }
        val dest = File(gameDir, name)
        val srcSize = try {
            appCtx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (_: Exception) { -1L }
        if (dest.isFile && srcSize > 0 && dest.length() == srcSize) return
        val tmpFile = File(gameDir, "$name.part")
        val t = Tracker(title, sizeHint, 1)
        t.current = name
        t.report(true)
        try {
            val input = appCtx.contentResolver.openInputStream(uri) ?: return
            CountingOut(FileOutputStream(tmpFile), t).use { out ->
                input.use { inp ->
                    val buf = ByteArray(256 * 1024)
                    while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmpFile.renameTo(dest)) tmpFile.delete()
        } catch (_: Exception) {
            try { tmpFile.delete() } catch (_: Exception) { }
        }
    }

    private fun friendly(e: Throwable): String = when {
        e is InstallException -> e.message ?: "Install failed."
        e is OutOfMemoryError -> "Your phone ran out of memory while unpacking. Close other apps and try again."
        e is SecurityException -> "Access to your game folder was denied. Please choose the folder again."
        e is IOException -> "Read/write error: ${e.message ?: "unknown"}. Make sure there's free space and try again."
        else -> "Install failed: ${e.javaClass.simpleName}${e.message?.let { " – $it" } ?: ""}"
    }

    private fun toast(msg: String) {
        main.post { try { Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show() } catch (_: Exception) { } }
    }

    companion object {
        private const val KEY_FOLDER_URI = "psp_folder_uri"
        private const val KEY_DELETE_ARCHIVE = "delete_archive_after_install"
    }
}

private class NeedFolderSignal(val problem: String?) : Exception()