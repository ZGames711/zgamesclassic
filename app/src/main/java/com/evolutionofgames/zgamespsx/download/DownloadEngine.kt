package com.evolutionofgames.zgamespsx.download

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import com.evolutionofgames.zgamespsx.emulator.PathResolver
import com.evolutionofgames.zgamespsx.prefs.AppPrefs
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Lifecycle state of a download tracked by [DownloadEngine]. */
enum class DownloadStatus { RUNNING, PAUSED, FAILED, COMPLETED }

/**
 * One file captured by the WebView resolver's DownloadListener —
 * exactly ZGamez's InAppBrowser handover: the captured file URL plus
 * the browser's User-Agent, Content-Disposition, MIME type, cookies
 * and referer, so [DownloadEngine.enqueueWebView] can re-request the
 * real file as if it were the browser.
 */
data class CapturedDownload(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    val cookie: String?,
    val referer: String?
)

data class DownloadItem(
    val id: Long,
    val title: String,
    val url: String,
    val status: DownloadStatus,
    val progressPercent: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val contentUri: String?,
    val speedBytesPerSec: Long,
    val etaSeconds: Long,
    val errorMessage: String? = null,
    val expectedSha256: String? = null,
    val verified: Boolean = false
)

/**
 * File downloads into public Download/ZGames PSX (no permission
 * needed on modern Android via MediaStore).
 *
 * Same proven shape as the old downloader, rewritten:
 * - OkHttp transfer with a kept `.part` file, so pause is a true pause:
 *   the in-flight call is cancelled but the part is kept, and resume
 *   continues from the same byte offset via `Range: bytes=<size>-`.
 *   Servers without Range support restart from 0%.
 * - The file's REAL name comes from the server's response headers in
 *   [resolveFileName] before any file is created (provisional name at
 *   enqueue time is only a guess from the URL, often "download.bin").
 * - Progress snapshot (bytes/total/speed/ETA) as a StateFlow; completed
 *   history persists in SharedPreferences so completed games survive
 *   restarts. Finished files in ZGames PSX are left on disk when a
 *   record is removed.
 */
class DownloadEngine(context: Context) {

    private val appCtx = context.applicationContext
    // A stray Error (OutOfMemoryError, NoClassDefFoundError...) in a transfer coroutine must
    // never reach the default handler and kill the app: log it and keep the process alive.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
                android.util.Log.e("DownloadEngine", "transfer coroutine failed", t)
            }
    )
    private val prefs = appCtx.getSharedPreferences("psx_downloads", Context.MODE_PRIVATE)

    /** Tiny in-memory cookie jar: cookies from a file-host probe must
     * carry to the real download request (Drive's confirm flow sets
     * cookies the confirmed download needs). No extra dependency. */
    private val cookieJar = object : CookieJar {
        private val store = mutableListOf<Cookie>()
        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (cookie in cookies) {
                store.removeAll {
                    it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
                }
                store += cookie
            }
        }
        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            store.removeAll { it.expiresAt != Long.MAX_VALUE && it.expiresAt < now }
            return store.filter { it.matches(url) }
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        // ZGamez's cookie rule, mirrored: the browser's cookies are only
        // meant for the site they came from (e.g. Google Drive). On each
        // hop, drop them when a redirect leaves that site, so they can
        // never leak to an unrelated host. The marker header itself is
        // stripped before the request goes out.
        .addNetworkInterceptor { chain ->
            val req = chain.request()
            val originHost = req.header(COOKIE_HOST_HEADER)
            val builder = req.newBuilder().removeHeader(COOKIE_HOST_HEADER)
            if (originHost != null && !sameSite(originHost, req.url.host)) {
                builder.removeHeader("Cookie")
            }
            chain.proceed(builder.build())
        }
        .build()

    /** Same registrable-ish site: last two host labels match (drive.google.com ~ usercontent.google.com). */
    private fun sameSite(a: String, b: String): Boolean {
        fun root(h: String) = h.lowercase().split('.').takeLast(2).joinToString(".")
        return root(a) == root(b)
    }

    private class ActiveEntry(
        val stableId: Long,
        val title: String,
        var url: String,
        val mirrors: List<String> = emptyList(),
        var mirrorIndex: Int = 0,
        /** File-host page resolved to the host's real file URL (once per download; resume reuses it). */
        var resolvedUrl: String? = null,
        /** The WebView's own User-Agent / cookies / referer (WebView captures only) so the
         * re-request looks like the WebView's — ZGamez's WebView DownloadListener variant. */
        val userAgent: String? = null,
        val cookie: String? = null,
        val requiresBrowser: Boolean = false,
        val referer: String? = null,
        var fileName: String,
        var nameResolved: Boolean = false,
        var resumeValidator: String? = null,
        val expectedSha256: String? = null,
        var titleIsAuto: Boolean = false,
        @Volatile var bytesDownloaded: Long = 0L,
        var totalBytes: Long = -1L,
        var speedBps: Long = 0L,
        @Volatile var paused: Boolean = false,
        var failed: Boolean = false,
        var errorReason: String? = null,
        var completedAt: Long = 0L,
        var contentUri: String? = null,
        @Volatile var pauseRequested: Boolean = false,
        @Volatile var generation: Int = 0,
        @Volatile var call: Call? = null,
        val transferMutex: Mutex = Mutex(),
        /** In-flight parallel segment calls; cancelled on pause so a stalled segment cannot delay it. */
        val segCalls: java.util.concurrent.CopyOnWriteArrayList<Call> = java.util.concurrent.CopyOnWriteArrayList(),
        /** Strong ETag + total size that the kept .segN files belong to (null = no reusable segments). */
        @Volatile var segEtag: String? = null,
        @Volatile var segTotal: Long = -1L
    )

    private data class HistoryRecord(
        val stableId: Long,
        val title: String,
        val url: String,
        val contentUri: String?,
        val completedAt: Long,
        val verified: Boolean = false
    )

    private val lock = Any()
    private var nextStableId = 1L
    private val active = LinkedHashMap<Long, ActiveEntry>()
    // Only entries paused by the foreground policy are automatically resumed.
    private val policyPaused = mutableSetOf<Long>()
    private var appForeground = false
    private var foregroundOnly = true

    private fun queueTransfer(entry: ActiveEntry) {
        val generation = entry.generation
        scope.launch {
            // The previous transfer must close its .part file before a resumed
            // transfer can inspect its length and append.
            entry.transferMutex.withLock {
                if (!entry.pauseRequested && !entry.paused && !entry.failed &&
                    entry.completedAt == 0L && entry.generation == generation) {
                    try {
                        runDownload(entry, generation)
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        entry.failed = true
                        entry.speedBps = 0L
                        entry.errorReason = t.message?.takeIf { it.isNotBlank() } ?: "Download failed"
                        refreshSnapshot()
                    }
                }
            }
        }
    }

    fun setForegroundPolicy(appIsForeground: Boolean, enabled: Boolean) {
        var started = false
        synchronized(lock) {
            appForeground = appIsForeground
            foregroundOnly = enabled
            if (foregroundOnly && !appForeground) {
                active.values.filter { !it.paused && !it.failed && it.completedAt == 0L }.forEach { entry ->
                    policyPaused.add(entry.stableId)
                    pauseEntry(entry)
                }
            } else {
                val ids = policyPaused.toList()
                policyPaused.clear()
                ids.forEach { id ->
                    active[id]?.let { entry ->
                        if (entry.paused && !entry.failed && entry.completedAt == 0L) {
                            resumeEntry(entry)
                            started = true
                        }
                    }
                }
            }
        }
        refreshSnapshot()
        if (started) DownloadService.start(appCtx)
    }

    // Caller holds lock. Invalidate the active generation before cancelling.
    private fun pauseEntry(entry: ActiveEntry) {
        entry.pauseRequested = true
        entry.generation++
        entry.call?.cancel()
        entry.segCalls.forEach { try { it.cancel() } catch (_: Exception) { } }
        entry.paused = true
        entry.speedBps = 0L
    }
    private fun resumeEntry(entry: ActiveEntry) {
        entry.paused = false
        entry.failed = false
        entry.pauseRequested = false
        entry.generation++
        queueTransfer(entry)
    }

    private fun startOrDefer(entry: ActiveEntry) {
        synchronized(lock) {
            if (foregroundOnly && !appForeground) {
                entry.paused = true
                entry.pauseRequested = true
                policyPaused.add(entry.stableId)
            } else if (entry.paused && policyPaused.remove(entry.stableId)) {
                resumeEntry(entry)
            } else if (!entry.paused) queueTransfer(entry)
        }
    }

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    init {
        // Continue ids after the persisted history so a new download can
        // never reuse (and hide) a finished download's id after a restart.
        restoreActive()
        // Segment files stay only when their download journal recorded a strong ETag + size
        // (they are resumed segment by segment); any other leftover segment is deleted.
        partsDir().listFiles()?.filter { it.name.matches(Regex("[0-9]+\\.seg[0-3]")) }?.forEach { f ->
            val segId = f.name.substringBefore('.').toLongOrNull()
            if (segId == null || active[segId]?.segEtag == null) f.delete()
        }
        nextStableId = maxOf(readHistory().maxOfOrNull { it.stableId } ?: 0L, active.keys.maxOrNull() ?: 0L, prefs.getLong("last_id", 0L)) + 1L
        refreshSnapshot()
    }

    /** Enqueues a download. Returns the stable id, or -1 for a bad URL. */
    fun enqueue(url: String, title: String, expectedSha256: String? = null, mirrors: List<String> = emptyList()): Long {
        return try {
            val cleanUrl = url.trim()
            if (cleanUrl.isBlank()) return -1
            val uri = Uri.parse(cleanUrl) ?: return -1
            if (uri.scheme != "http" && uri.scheme != "https") return -1
            // Provisional name only (URL guess) — the REAL name is taken
            // from the server's response headers in runDownload.
            val rawGuess = URLUtil.guessFileName(cleanUrl, null, null).ifBlank { "download.bin" }
            val baseName = sanitizeFileName(rawGuess)
            val displayTitle = title.ifBlank { baseName }
            val titleIsAuto = title.isBlank() || title == rawGuess || title == baseName
            val stableId = synchronized(lock) {
                val id = nextStableId++
                check(prefs.edit().putLong("last_id", id).commit())
                active[id] = ActiveEntry(
                    stableId = id,
                    title = displayTitle,
                    url = cleanUrl,
                    mirrors = if (expectedSha256 != null) mirrors.filter { it.startsWith("https://") && it != cleanUrl }.distinct().take(5) else emptyList(),
                    fileName = baseName,
                    titleIsAuto = titleIsAuto,
                    expectedSha256 = expectedSha256?.takeIf { Regex("^[0-9a-fA-F]{64}$").matches(it) }?.lowercase()
                )
                id
            }
            val entry = synchronized(lock) { active[stableId] }!!
            startOrDefer(entry)
            refreshSnapshot()
            if (!entry.paused) DownloadService.start(appCtx)
            stableId
        } catch (_: Exception) {
            -1L
        }
    }

    /**
     * WebView DownloadListener variant — mirrors ZGamez's
     * AppDownloadManager.enqueue WebView overload exactly: the captured
     * file URL is used as-is (never re-resolved through the file-host
     * scrapers), the browser's User-Agent / cookies / referer travel
     * with the re-request so Drive and MediaFire serve the real file
     * (not a page), and a browser-supplied Content-Disposition name is
     * already the real one. Returns the stable id, or -1 for a bad URL
     * (ZGamez's enqueue returns -1 and does not throw for a bad URL or
     * a failed start — only then does the UI show "Download started").
     */
    fun enqueueWebView(
        url: String,
        title: String,
        contentDisposition: String?,
        mimeType: String?,
        userAgent: String?,
        cookie: String?,
        referer: String?,
        expectedSha256: String? = null
    ): Long {
        return try {
            val cleanUrl = url.trim()
            if (cleanUrl.isBlank()) return -1
            val uri = Uri.parse(cleanUrl) ?: return -1
            if (uri.scheme != "http" && uri.scheme != "https") return -1
            val rawGuess = URLUtil.guessFileName(cleanUrl, contentDisposition, mimeType)
                .ifBlank { "download.bin" }
            val baseName = sanitizeFileName(rawGuess)
            val displayTitle = title.ifBlank { baseName }
            val titleIsAuto = title.isBlank() || title == rawGuess || title == baseName
            val hasRealName = !contentDisposition.isNullOrBlank() &&
                    parseContentDispositionName(contentDisposition) != null
            val stableId = synchronized(lock) {
                val id = nextStableId++
                check(prefs.edit().putLong("last_id", id).commit())
                active[id] = ActiveEntry(
                    stableId = id,
                    title = displayTitle,
                    url = cleanUrl,
                    // Captured URL IS the real file URL: skip resolution.
                    resolvedUrl = cleanUrl,
                    userAgent = userAgent,
                    cookie = cookie,
                    requiresBrowser = !cookie.isNullOrBlank(),
                    expectedSha256 = expectedSha256?.takeIf { Regex("^[0-9a-fA-F]{64}$").matches(it) }?.lowercase(),
                    referer = referer,
                    fileName = baseName,
                    titleIsAuto = titleIsAuto
                ).also {
                    if (hasRealName) it.fileName =
                        sanitizeFileName(parseContentDispositionName(contentDisposition)!!)
                }
                id
            }
            val entry = synchronized(lock) { active[stableId] }!!
            startOrDefer(entry)
            refreshSnapshot()
            if (!entry.paused) DownloadService.start(appCtx)
            stableId
        } catch (_: Exception) {
            -1L
        }
    }

    /** Builds the (optionally ranged) request, mirroring the browser's UA/cookies/referer. */
    private fun buildRequest(entry: ActiveEntry, url: String, rangeFrom: Long): Request {
        val b = Request.Builder()
            .url(url)
            // Keep the transferred representation stable for byte-range resumes.
            .header("Accept-Encoding", "identity")
            .header("User-Agent", entry.userAgent?.takeIf { it.isNotBlank() } ?: DIRECT_UA)
        entry.cookie?.takeIf { it.isNotBlank() }?.let {
            b.header("Cookie", it)
            // Marks which host the cookie belongs to (stripped by the network interceptor).
            Uri.parse(url).host?.let { h -> b.header(COOKIE_HOST_HEADER, h) }
        }
        entry.referer?.takeIf { it.isNotBlank() }?.let { b.header("Referer", it) }
        if (rangeFrom > 0) {
            b.header("Range", "bytes=$rangeFrom-")
            entry.resumeValidator?.let { b.header("If-Range", it) }
        }
        return b.build()
    }

    /** A manually paused download stays paused until the player resumes it. */
    fun pauseDownload(id: Long) {
        synchronized(lock) {
            policyPaused.remove(id)
            val entry = active[id] ?: return
            if (entry.paused || entry.failed || entry.completedAt > 0L) return
            pauseEntry(entry)
        }
        refreshSnapshot()
    }

    /**
     * Clears a download's stored resolved URL so a retry re-resolves it from scratch.
     * Used by the ViewModel's Resume path for HTML-failed rows: without this, resume
     * reuses the stored (short or page) URL and fails the HTML guard again.
     */
    fun resetResolution(id: Long) {
        try {
            synchronized(lock) { active[id]?.resolvedUrl = null }
        } catch (_: Exception) {
        }
    }

    /** Resume after the previous writer has closed; Range support depends on the server. */
    fun resumeDownload(id: Long) {
        var started = false
        synchronized(lock) {
            val entry = active[id] ?: return
            if (entry.completedAt > 0L || entry.requiresBrowser && entry.cookie == null || (!entry.paused && !entry.failed)) return
            if (foregroundOnly && !appForeground) {
                entry.failed = false
                entry.paused = true
                entry.pauseRequested = true
                policyPaused.add(id)
            } else {
                policyPaused.remove(id)
                resumeEntry(entry)
                started = true
            }
        }
        refreshSnapshot()
        if (started) DownloadService.start(appCtx)
    }

    /**
     * Remove a download. An in-flight transfer is cancelled and the
     * `.part` file is deleted; completed history records are dropped too.
     * Finished files in ZGames PSX are left on disk.
     */
    fun removeDownload(id: Long) {
        try {
            val entry = synchronized(lock) { policyPaused.remove(id); active.remove(id) }
            if (entry != null) {
                entry.pauseRequested = true
                entry.call?.cancel()
                entry.segCalls.forEach { try { it.cancel() } catch (_: Exception) { } }
                deletePartial(entry)
            }
            removeHistory(id)
            refreshSnapshot()
        } catch (_: Exception) {
        }
    }

    /** Cancels an in-flight download (Downloads screen Cancel button). */
    fun cancelDownload(id: Long) = removeDownload(id)

    private fun partsDir(): File =
        File(appCtx.filesDir, "download_parts").also { if (!it.exists()) it.mkdirs() }

    private fun partFile(entry: ActiveEntry): File = File(partsDir(), "${entry.stableId}.part")

    private fun deletePartial(entry: ActiveEntry) {
        try { partFile(entry).delete() } catch (_: Exception) { }
        deleteSegments(entry)
    }

    private fun segFile(entry: ActiveEntry, i: Int): File = File(partsDir(), "${entry.stableId}.seg$i")

    /** Exact bytes already stored in the kept segment files (each file is written unbuffered, so its length is trustworthy). */
    private fun segmentBytesOnDisk(entry: ActiveEntry): Long {
        var sum = 0L
        for (i in 0 until 4) {
            val f = segFile(entry, i)
            if (f.exists()) sum += f.length()
        }
        return sum
    }

    private fun deleteSegments(entry: ActiveEntry) {
        for (i in 0 until 4) { try { segFile(entry, i).delete() } catch (_: Exception) { } }
        entry.segEtag = null
        entry.segTotal = -1L
    }

    /** The server cannot do safe parallel ranges: drop the segments and use one stream instead. */
    private class ParallelUnsupported(message: String) : java.io.IOException(message)

    private suspend fun runDownload(entry: ActiveEntry, generation: Int) {
        val part = partFile(entry)
        var resumeFrom = if (part.exists()) part.length() else 0L
        // Without an entity validator, reusing an old partial could join bytes
        // from two versions of a file. Restart rather than risk corruption.
        // A known total size is enough to identify the file when the host sends no ETag /
        // Last-Modified (Drive, MediaFire); the 206 reply is checked against it below, and the
        // final size / SHA-256 checks still protect the result. Only with neither a validator
        // nor a known size do we restart, to avoid joining bytes of two different files.
        if (resumeFrom > 0L && ((entry.resumeValidator == null && entry.totalBytes <= 0L) ||
                    (entry.totalBytes > 0L && resumeFrom >= entry.totalBytes))) resumeFrom = 0L
        var canSwitch = true
        var finishing = false
        try {
            // File-host pages are NOT files: a MediaFire file page or a
            // Drive preview page must first be resolved to the host's
            // real file URL (the way a browser gets it), or the app
            // would save the host's HTML page as a fake ".bin" file and
            // the installer would later reject it as "not a game file".
            // Resolved once per download; resume reuses the same URL.
            if (entry.resolvedUrl == null) entry.resolvedUrl = resolveFileHostUrl(entry.url)
            val downloadUrl = entry.resolvedUrl ?: entry.url
            if (entry.pauseRequested || entry.generation != generation) return
            if (AppPrefs.wifiOnly(appCtx) && isMeteredNetwork()) throw Exception(WIFI_ONLY_MESSAGE)
            val call = client.newCall(buildRequest(entry, downloadUrl, resumeFrom))
            entry.call = call
            if (entry.pauseRequested || entry.generation != generation) { call.cancel(); return }
            val response = call.execute()
            response.use { resp ->
                if (!resp.isSuccessful && resp.code != 206) {
                    throw Exception("Server returned ${resp.code}")
                }
                // Server ignored Range: restart from 0.
                if (resumeFrom > 0 && resp.code != 206) {
                    resumeFrom = 0
                    part.delete()
                }
                if (resumeFrom > 0 && resp.code == 206) {
                    val range = resp.header("Content-Range").orEmpty()
                    val rangeMatch = Regex("^bytes (\\d+)-\\d+/(\\d+)$", RegexOption.IGNORE_CASE).matchEntire(range)
                    val start = rangeMatch?.groupValues?.get(1)?.toLongOrNull()
                    val rangeTotal = rangeMatch?.groupValues?.get(2)?.toLongOrNull()
                    if (start != resumeFrom) throw Exception("Server returned an invalid resume range. Remove this download and try again.")
                    if (entry.totalBytes > 0L && rangeTotal != null && rangeTotal != entry.totalBytes) {
                        throw Exception("The file changed on the server. Remove this download and try again.")
                    }
                }
                val responseEtag = resp.header("ETag")?.takeIf { !it.startsWith("W/", ignoreCase = true) }
                val responseModified = resp.header("Last-Modified")
                if (resumeFrom > 0 && resp.code == 206) {
                    val returnedValidator = if (entry.resumeValidator?.startsWith("\"") == true) responseEtag else responseModified
                    // A 206 to a request carrying If-Range already proves the validator matched, and
                    // many hosts omit ETag / Last-Modified on ranged replies. Fail only when the host
                    // returns a DIFFERENT validator; a missing one is accepted (size was checked above).
                    if (entry.resumeValidator != null && returnedValidator != null && returnedValidator != entry.resumeValidator) {
                        throw Exception("The file changed on the server. Remove this download and try again.")
                    }
                } else if (resumeFrom == 0L) {
                    entry.resumeValidator = responseEtag ?: responseModified
                }
                val body = resp.body ?: return
                // Guard: NEVER save a web page as a game file. A file-host
                // preview page (HTML) reaching here means resolution failed;
                // fail with a specific message instead of writing a fake
                // ".bin" that the installer would later reject.
                val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
                if (contentType.contains("text/html")) throw Exception(htmlPageMessage)
                if (resumeFrom == 0L) {
                    val peek = try { resp.peekBody(512).bytes() } catch (_: Exception) { null }
                    if (peek != null) {
                        var i = 0
                        while (i < peek.size && (peek[i] == 0x20.toByte() || peek[i] == 0x09.toByte() || peek[i] == 0x0A.toByte() || peek[i] == 0x0D.toByte())) i++
                        if (i < peek.size && peek[i] == 0x3C.toByte()) throw Exception(htmlPageMessage)
                    }
                }
                if (!entry.nameResolved) {
                    entry.fileName = sanitizeFileName(resolveFileName(resp, entry))
                    entry.nameResolved = true
                }
                val contentLength = body.contentLength()
                val total = when {
                    resp.code == 206 -> {
                        parseTotalFromContentRange(resp.header("Content-Range")) ?: (contentLength + resumeFrom)
                    }
                    else -> contentLength
                }
                entry.totalBytes = total
                persistActive() // Persist validator before writing resumable bytes.
                checkFreeSpace(total, resumeFrom)
                entry.bytesDownloaded = resumeFrom
                var lastReport = 0L
                var lastBytes = resumeFrom
                var lastTime = System.currentTimeMillis()
                val canParallel = resumeFrom == 0L && total >= 16L * 1024 * 1024 && total < Long.MAX_VALUE / 4 && responseEtag != null && responseEtag.startsWith("\"") && responseEtag.endsWith("\"") && !entry.requiresBrowser && com.evolutionofgames.zgamespsx.remote.WpConfig.flag("download_parallel", true)
                if (!canParallel && entry.segEtag != null) deleteSegments(entry)
                val parallelDone = if (canParallel) {
                    part.delete() // discard an invalid old partial before allocating segments
                    parallelTransfer(entry, generation, downloadUrl, part, total, responseEtag!!)
                } else false
                // Once the segments are merged into a complete .part, a late pause/resume must not
                // throw that finished file away: finish verification + commit instead.
                finishing = parallelDone
                fun interrupted(): Boolean =
                    if (finishing) synchronized(lock) { active[entry.stableId] !== entry }
                    else entry.pauseRequested || entry.generation != generation
                if (!parallelDone && (entry.pauseRequested || entry.generation != generation)) { refreshSnapshot(); return }
                if (!parallelDone) {
                    entry.bytesDownloaded = resumeFrom
                    FileOutputStream(part, resumeFrom > 0).use { out ->
                        val buf = ByteArray(256 * 1024)
                        val input = body.byteStream()
                        while (true) {
                            if (entry.pauseRequested || entry.generation != generation) return
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            entry.bytesDownloaded += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport >= 200) {
                                val dt = (now - lastTime).coerceAtLeast(1)
                                val instant = (entry.bytesDownloaded - lastBytes) * 1000 / dt
                                entry.speedBps = if (entry.speedBps == 0L) instant else (entry.speedBps * 7 + instant * 3) / 10
                                lastBytes = entry.bytesDownloaded; lastTime = now; lastReport = now
                                refreshSnapshot()
                            }
                        }
                    }
                }
                if (interrupted()) return
                // Size verification: a stream that ends early (dropped
                // mobile connection, server cutting off a large ZIP)
                // must NEVER be committed as a complete file — the old
                // code broke out of the write loop on end-of-stream
                // and marked the partial file COMPLETED, so a truncated
                // ZIP reached the installer with a corrupt central
                // directory. Fail instead and keep the .part file so
                // Resume can continue via Range. Unknown totals
                // (chunked, total <= 0) skip the check.
                if (total > 0 && entry.bytesDownloaded != total) {
                    throw Exception(
                        "Download incomplete — got ${entry.bytesDownloaded} of $total bytes. " +
                                "Resume to continue downloading."
                    )
                }
                // Compare against an independently provided digest BEFORE publishing or installing.
                entry.expectedSha256?.let { expected ->
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    part.inputStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            if (interrupted()) return
                            val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n)
                        }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    if (actual != expected) {
                        part.delete(); entry.resumeValidator = null; entry.bytesDownloaded = 0
                        throw Exception("SHA-256 mismatch. The file was not installed. Check the link and checksum before retrying.")
                    }
                }
                if (interrupted()) return
                canSwitch = false // Local storage/publish errors must not trigger mirror retries.
                // Commit: move the part into ZGames PSX under its real name.
                val finalName = uniqueFileName(entry.fileName)
                val contentUri = writeToModsDownloads(part, finalName)
                    ?: throw Exception("Couldn't save the file in Download/ZGames PSX")
                entry.contentUri = contentUri
                entry.completedAt = System.currentTimeMillis()
                entry.bytesDownloaded = entry.totalBytes.takeIf { it > 0 } ?: entry.bytesDownloaded
                entry.speedBps = 0L
                part.delete()
                addHistory(
                    HistoryRecord(entry.stableId, entry.title, entry.url, contentUri, entry.completedAt, entry.expectedSha256 != null)
                )
                refreshSnapshot()
            }
        } catch (e: Exception) {
            if (!finishing && (entry.pauseRequested || entry.generation != generation)) {
                // Paused or superseded: part / segments are kept, state already set.
                refreshSnapshot()
                return
            }
            if (e is kotlinx.coroutines.CancellationException) {
                refreshSnapshot()
                return
            }
            if (canSwitch && entry.expectedSha256 != null && !entry.requiresBrowser && com.evolutionofgames.zgamespsx.remote.WpConfig.flag("download_mirrors", true) && entry.mirrorIndex < entry.mirrors.size && e.message != WIFI_ONLY_MESSAGE && !e.message.orEmpty().contains("space", ignoreCase = true)) {
                entry.url = entry.mirrors[entry.mirrorIndex++]
                entry.resolvedUrl = null; entry.resumeValidator = null; entry.totalBytes = -1L
                entry.bytesDownloaded = 0; entry.nameResolved = false; part.delete(); deleteSegments(entry)
                persistActive(); refreshSnapshot()
                runDownload(entry, generation)
                return
            }
            entry.failed = true
            entry.speedBps = 0L
            entry.errorReason = e.message ?: "Download failed"
            refreshSnapshot()
        }
    }

    /**
     * Use separate checked byte ranges only for a strong ETag; a server that cannot do safe
     * ranges returns to the original single stream.
     *
     * Pause/resume is segment-exact: each of the four .segN files is kept on pause (and on a
     * network failure) and a later run continues every segment from its own byte offset with
     * its own Range request, so a pause at 50% resumes at 50% instead of falling back to the
     * contiguous prefix of segment 0 (~12%), which looked like a restart from 0.
     */
    private suspend fun parallelTransfer(entry: ActiveEntry, generation: Int, url: String, part: File, total: Long, etag: String): Boolean {
        val chunks = (0 until 4).map { segFile(entry, it) }
        val starts = LongArray(4) { total * it / 4 }
        val ends = LongArray(4) { total * (it + 1) / 4 - 1 }
        // Reuse kept segments only for the same strong ETag and size.
        val reusable = entry.segEtag == etag && entry.segTotal == total
        val existing = LongArray(4) { i ->
            val f = chunks[i]
            val len = ends[i] - starts[i] + 1
            when {
                !reusable || !f.exists() -> { f.delete(); 0L }
                f.length() > len -> { f.delete(); 0L }
                else -> f.length()
            }
        }
        entry.segEtag = etag
        entry.segTotal = total
        persistActive()
        val progress = AtomicLong(existing.sum())
        entry.bytesDownloaded = progress.get()
        val lastReported = AtomicLong(0L)
        // Speed sampling (the single-stream loop did this; the parallel path never did, so the
        // Downloads screen showed "—" for every game over 16 MB).
        val speedBytes = AtomicLong(existing.sum())
        val speedTime = AtomicLong(android.os.SystemClock.elapsedRealtime())
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            coroutineScope {
                (0 until 4).map { i -> async(Dispatchers.IO) {
                    try {
                        val len = ends[i] - starts[i] + 1
                        if (existing[i] < len) {
                            val from = starts[i] + existing[i]
                            val to = ends[i]
                            val req = buildRequest(entry, url, 0).newBuilder().header("Range", "bytes=$from-$to").header("If-Range", etag).build()
                            val call = client.newCall(req)
                            entry.segCalls.add(call)
                            try {
                                if (stop.get() || entry.pauseRequested || entry.generation != generation) throw java.io.IOException("Paused")
                                call.execute().use { response ->
                                    val range = response.header("Content-Range").orEmpty()
                                    if (response.code != 206 || range != "bytes $from-$to/$total" || response.header("ETag") != etag || response.header("Content-Type").orEmpty().contains("text/html", true)) throw ParallelUnsupported("Server does not support safe parallel ranges")
                                    val body = response.body ?: throw ParallelUnsupported("Empty range")
                                    var count = existing[i]
                                    FileOutputStream(chunks[i], existing[i] > 0).use { out ->
                                        body.byteStream().use { input ->
                                            val buf = ByteArray(128 * 1024)
                                            while (true) {
                                                if (stop.get() || entry.pauseRequested || entry.generation != generation) throw java.io.IOException("Paused")
                                                val n = input.read(buf); if (n < 0) break
                                                count += n; if (count > len) throw ParallelUnsupported("Invalid range length")
                                                out.write(buf, 0, n)
                                                progress.addAndGet(n.toLong())
                                                val now = android.os.SystemClock.elapsedRealtime()
                                                val last = lastReported.get()
                                                if (now - last >= 400 && lastReported.compareAndSet(last, now)) {
                                                    val bytesNow = progress.get()
                                                    val prevT = speedTime.getAndSet(now)
                                                    val prevB = speedBytes.getAndSet(bytesNow)
                                                    val instant = (bytesNow - prevB).coerceAtLeast(0L) * 1000 / (now - prevT).coerceAtLeast(1L)
                                                    entry.speedBps = if (entry.speedBps == 0L) instant else (entry.speedBps * 7 + instant * 3) / 10
                                                    entry.bytesDownloaded = bytesNow
                                                    refreshSnapshot()
                                                }
                                            }
                                        }
                                    }
                                    if (count != len) throw java.io.IOException("Incomplete range")
                                }
                            } finally { entry.segCalls.remove(call) }
                        }
                        Unit
                    } catch (e: Exception) { stop.set(true); throw e }
                } }.forEach { it.await() }
            }
            // Paused with every segment already complete: keep them; resume merges them without a re-download.
            if (entry.pauseRequested || entry.generation != generation) {
                entry.bytesDownloaded = segmentBytesOnDisk(entry)
                return false
            }
            // Merge. Deliberately not pausable: it takes seconds, and abandoning it half-way would
            // throw away a finished download.
            entry.speedBps = 0L
            FileOutputStream(part, false).use { out ->
                chunks.forEach { chunk ->
                    chunk.inputStream().use { input ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n)
                        }
                    }
                    chunk.delete()
                }
            }
            if (part.length() != total) throw java.io.IOException("Incomplete merge")
            entry.segEtag = null
            entry.segTotal = -1L
            persistActive()
            entry.bytesDownloaded = total
            return true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val stillActive = synchronized(lock) { active[entry.stableId] === entry }
            if (!stillActive) {
                // The download was removed: nothing to resume, drop everything.
                part.delete(); deleteSegments(entry); entry.bytesDownloaded = 0
                return false
            }
            if (entry.pauseRequested || entry.generation != generation) {
                // Paused or superseded: KEEP every segment with the exact bytes it holds.
                part.delete()
                entry.bytesDownloaded = segmentBytesOnDisk(entry)
                return false
            }
            if (e is ParallelUnsupported) {
                part.delete(); deleteSegments(entry); entry.bytesDownloaded = 0
                persistActive()
                return false
            }
            // Network failure: keep the verified bytes so Resume continues instead of restarting.
            part.delete()
            entry.bytesDownloaded = segmentBytesOnDisk(entry)
            throw e
        }
    }

    // ── file-host resolution ──────────────────────────────────────
    // A post's download-button link is often a file-host PAGE, not the
    // file itself (MediaFire file page, Drive preview page). ZGamez gets
    // the real file because its WebView runs the page and its
    // DownloadListener captures the real file URL. This app has no
    // WebView, so it resolves the page to the real file URL itself
    // before downloading — otherwise it would save the page's HTML
    // as a fake game file.

    /**
     * A download that returned a web page instead of a file. Read by
     * the ViewModel: an HTML failure is NOT a dead end — it routes the
     * link to the WebView resolver as a fallback (except for downloads
     * that already came out of the resolver, which must not loop).
     */
    internal val htmlPageMessage =
        "This link opened a web page instead of a file (the file host's preview page), so there is nothing to install. Try the download again or pick another download link for this game."

    private val browserUa =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    /**
     * Resolves a file-host page URL to the host's real file URL. Direct file URLs pass through untouched.
     *
     * MediaFire file pages are DELIBERATELY not resolved here anymore:
     * scraping is proven unworkable — verified live 2026-11-03 on evo
     * post 6568's link, the page's download button carries NO href in
     * static HTML (its href is set by JavaScript) and its repair URL
     * redirects to an HTML error page, so there is NO static direct
     * file URL to scrape. MediaFire pages go DIRECTLY to the WebView
     * resolver (the ViewModel routes them there before any enqueue);
     * if one ever reaches this engine directly, it passes through
     * unchanged, the HTML guard fails it, and the ViewModel's HTML
     * fallback opens the resolver — never a fake ".bin" file.
     *
     * Drive view pages keep the confirm+uuid fast path in
     * [resolveDrive] below (verified live: 206 + attachment +
     * "DBS Budokai Power Up Mod.rar" on the real zgames 2409 link).
     */
    private fun resolveFileHostUrl(url: String): String {
        val httpUrl = try { url.toHttpUrlOrNull() } catch (_: Exception) { null } ?: return url
        val host = httpUrl.host.lowercase()
        return when {
            host.contains("drive.google.com") && !httpUrl.pathSegments.contains("uc") -> resolveDrive(url)
            else -> url
        }
    }

    /**
     * Drive preview page (/file/d/<id>/view) -> the real file URL.
     * Small files: the uc URL redirects straight to the file. Large
     * files (over Google's virus-scan limit — most game mods): Drive
     * answers with a "Virus scan warning" HTML page whose download form
     * carries BOTH a confirm value AND a uuid. The previous code only
     * kept confirm and dropped uuid, so Drive served the warning page
     * again and the HTML guard failed the download (proven on the real
     * Power Up mod link: "DBS Budokai Power Up Mod.rar", 208M). This
     * version parses the warning form's hidden inputs (confirm + uuid)
     * and builds the confirmed usercontent URL — the confirmed URL for
     * that real link answers 206 + the real .rar.
     */
    private fun resolveDrive(url: String): String {
        val httpUrl = try { url.toHttpUrlOrNull() } catch (_: Exception) { null } ?: return url
        val id = when {
            httpUrl.pathSegments.size >= 3 && httpUrl.pathSegments[0] == "file" && httpUrl.pathSegments[1] == "d" -> httpUrl.pathSegments[2]
            else -> httpUrl.queryParameter("id")
        } ?: return url
        val direct = "https://drive.google.com/uc?export=download&id=$id"
        return try {
            // Fetch the uc URL (redirects followed). peekBody caps the
            // read, so a small real file is never downloaded by the probe.
            val req = Request.Builder().url(direct).header("User-Agent", browserUa).build()
            client.newCall(req).execute().use { resp ->
                val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
                if (!contentType.contains("text/html")) return direct
                val html = try { resp.peekBody(128 * 1024).string() } catch (_: Exception) { "" }
                if (html.isBlank()) return direct
                val confirm = hiddenFormValue(html, "confirm")
                    ?: Regex("""confirm=([0-9A-Za-z_-]+)""").find(html)?.groupValues?.getOrNull(1)
                val uuid = hiddenFormValue(html, "uuid")
                    ?: Regex("""uuid=([0-9A-Za-z_-]+)""").find(html)?.groupValues?.getOrNull(1)
                if (confirm != null && uuid != null) {
                    val action = Regex("""<form[^>]*\baction\s*=\s*["']([^"']+)["']""")
                        .find(html)?.groupValues?.getOrNull(1)
                        ?.takeIf { it.startsWith("http") }
                        ?: "https://drive.usercontent.google.com/download"
                    val base = try { action.toHttpUrlOrNull() } catch (_: Exception) { null }
                    if (base != null) {
                        return base.newBuilder()
                            .setQueryParameter("id", id)
                            .setQueryParameter("export", "download")
                            .setQueryParameter("confirm", confirm)
                            .setQueryParameter("uuid", uuid)
                            .build().toString()
                    }
                    return "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=$confirm&uuid=$uuid"
                }
                if (confirm != null) return "$direct&confirm=$confirm"
                return direct
            }
        } catch (_: Exception) {
            direct
        }
    }

    /** Reads a hidden <input name="X" value="..."> (either attribute order) from an HTML form. */
    private fun hiddenFormValue(html: String, name: String): String? {
        val escaped = Regex.escape(name)
        return Regex("""name\s*=\s*["']$escaped["']\s+value\s*=\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""value\s*=\s*["']([^"']+)["']\s+name\s*=\s*["']$escaped["']""")
                .find(html)?.groupValues?.getOrNull(1)
    }

    private fun writeToModsDownloads(part: File, name: String): String? {
        return try {
            if (android.os.Build.VERSION.SDK_INT < 29) {
                val dest = File(PathResolver.modsDownloadsDir(), name)
                part.inputStream().use { inp ->
                    FileOutputStream(dest).use { out -> inp.copyTo(out) }
                }
                return Uri.fromFile(dest).toString()
            }
            val resolver = appCtx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, PathResolver.MODS_RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    part.inputStream().use { inp -> inp.copyTo(out) }
                } ?: run { resolver.delete(uri, null, null); return null }
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null, null
                )
                uri.toString()
            } catch (_: Exception) {
                try { resolver.delete(uri, null, null) } catch (_: Exception) { }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Pick a file name that doesn't collide with an existing ZGames PSX file. */
    private fun uniqueFileName(base: String): String {
        return try {
            var name = base
            var n = 1
            while (downloadFileExists(name)) {
                val dot = base.lastIndexOf('.')
                name = if (dot > 0) {
                    "${base.substring(0, dot)} ($n)${base.substring(dot)}"
                } else {
                    "$base ($n)"
                }
                n++
                if (n > 100) break
            }
            name
        } catch (_: Exception) {
            base
        }
    }

    private fun downloadFileExists(displayName: String): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT < 29) {
                return File(PathResolver.modsDownloadsDir(), displayName).exists()
            }
            appCtx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(displayName, PathResolver.MODS_RELATIVE_PATH),
                null
            )?.use { it.moveToFirst() } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun sanitizeFileName(raw: String): String {
        return raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim().trimEnd('.', ' ')
            .ifEmpty { "download.bin" }
    }

    private fun hasUsableExtension(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val ext = name.substringAfterLast('.', "")
        if (ext.isEmpty() || ext.length > 6 || !ext.all { it.isLetterOrDigit() }) return false
        return ext.lowercase() !in setOf("bin", "php", "asp", "aspx", "jsp", "cgi", "html", "htm", "part")
    }

    /** Identifies common mod/archive formats from the first bytes when no header names the file. */
    private fun sniffExtension(response: okhttp3.Response): String? {
        val head = try {
            response.peekBody(8).bytes()
        } catch (_: Exception) {
            return null
        }
        fun starts(vararg b: Int) = head.size >= b.size && b.indices.all { head[it] == b[it].toByte() }
        return when {
            starts(0x50, 0x4B, 0x03, 0x04) || starts(0x50, 0x4B, 0x05, 0x06) -> "zip"
            starts(0x52, 0x61, 0x72, 0x21) -> "rar"
            starts(0x37, 0x7A, 0xBC, 0xAF) -> "7z"
            starts(0x43, 0x49, 0x53, 0x4F) -> "cso"
            starts(0x25, 0x50, 0x44, 0x46) -> "pdf"
            starts(0x1F, 0x8B) -> "gz"
            else -> null
        }
    }

    /**
     * The file's real name, in priority order: Content-Disposition → last
     * path segment of the FINAL url (after redirects) → a query parameter
     * → the MIME type / magic-byte extension on a sensible base name.
     */
    private fun resolveFileName(response: okhttp3.Response, entry: ActiveEntry): String {
        parseContentDispositionName(response.header("Content-Disposition"))?.let { return it }
        val finalUrl = response.request.url
        val pathName = finalUrl.pathSegments.lastOrNull()?.let {
            try { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { it }
        }
        if (hasUsableExtension(pathName)) return pathName!!
        for (key in listOf("filename", "file", "fn", "name")) {
            val q = finalUrl.queryParameter(key)
            if (hasUsableExtension(q)) return q!!
        }
        if (hasUsableExtension(entry.fileName)) return entry.fileName
        val mime = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
        val mimeExt = mime
            ?.takeIf { it != "application/octet-stream" && it != "binary/octet-stream" && it != "text/html" }
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        val ext = mimeExt ?: sniffExtension(response) ?: "bin"
        val generic = setOf("download", "uc", "file", "get", "dl", "d", "view", "export")
        val base = (pathName ?: "").substringBeforeLast('.')
            .takeIf { it.isNotBlank() && it.lowercase() !in generic }
            ?: entry.title.takeIf { !entry.titleIsAuto && it.isNotBlank() }
            ?: "download"
        return "$base.$ext"
    }

    private fun parseContentDispositionName(header: String?): String? {
        if (header.isNullOrBlank()) return null
        return try {
            // filename*=UTF-8''... or filename="...".
            val star = Regex("filename\\*\\s*=\\s*(?:UTF-8'')?([^;]+)", RegexOption.IGNORE_CASE).find(header)
            if (star != null) {
                return URLDecoder.decode(star.groupValues[1].trim().trim('"'), "UTF-8")
                    .takeIf { hasUsableExtension(it) || it.isNotBlank() }
            }
            val plain = Regex("filename\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(header)
                ?: Regex("filename\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE).find(header)
            plain?.groupValues?.getOrNull(1)?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /** Parse `Content-Range: bytes 500-999/2000` → 2000. */
    private fun parseTotalFromContentRange(contentRange: String?): Long? {
        if (contentRange == null) return null
        return try {
            contentRange.substringAfterLast('/').toLong().takeIf { it > 0 }
        } catch (_: Exception) {
            null
        }
    }

    private fun refreshSnapshot() {
        persistActive()
        val items = mutableListOf<DownloadItem>()
        val entries = synchronized(lock) { active.values.toList() }
        for (entry in entries) {
            val status = when {
                entry.completedAt > 0L -> DownloadStatus.COMPLETED
                entry.paused -> DownloadStatus.PAUSED
                entry.failed -> DownloadStatus.FAILED
                else -> DownloadStatus.RUNNING
            }
            val downloaded = entry.bytesDownloaded
            val total = entry.totalBytes
            val pct = when {
                total > 0 -> ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                status == DownloadStatus.COMPLETED -> 100
                else -> 0
            }
            val speed = if (status == DownloadStatus.RUNNING) entry.speedBps.coerceAtLeast(0L) else 0L
            val eta = if (speed > 0 && total > downloaded) (total - downloaded) / speed else -1L
            items += DownloadItem(
                id = entry.stableId,
                title = entry.title,
                url = entry.url,
                status = status,
                progressPercent = pct,
                bytesDownloaded = downloaded,
                totalBytes = total,
                contentUri = entry.contentUri,
                speedBytesPerSec = speed,
                etaSeconds = eta,
                errorMessage = if (status == DownloadStatus.FAILED) entry.errorReason else null,
                expectedSha256 = entry.expectedSha256, verified = status == DownloadStatus.COMPLETED && entry.expectedSha256 != null
            )
        }
        val activeIds = entries.map { it.stableId }.toSet()
        for (record in readHistory()) {
            if (record.stableId in activeIds) continue
            items += DownloadItem(
                id = record.stableId,
                title = record.title,
                url = record.url,
                status = DownloadStatus.COMPLETED,
                progressPercent = 100,
                bytesDownloaded = -1L,
                totalBytes = -1L,
                contentUri = record.contentUri,
                speedBytesPerSec = 0L,
                etaSeconds = -1L, verified = record.verified
            )
        }
        items.sortWith(
            compareByDescending<DownloadItem> { it.status != DownloadStatus.COMPLETED }
                .thenByDescending { it.id }
        )
        _items.value = items
    }

    // Keep only private task metadata; never store WebView cookies. A cookie-dependent
    // transfer must be restarted from its post instead of silently losing authentication.
    private fun persistActive() = synchronized(lock) {
        val arr = JSONArray()
        active.values.filter { it.completedAt == 0L }.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.stableId); put("title", e.title); put("url", e.url)
                put("mirrors", JSONArray(e.mirrors)); put("mirror_index", e.mirrorIndex)
                put("name", e.fileName); put("named", e.nameResolved)
                put("sha256", e.expectedSha256 ?: ""); put("validator", e.resumeValidator ?: ""); put("total", e.totalBytes)
                put("paused", e.paused); put("policy", e.stableId in policyPaused)
                put("failed", e.failed); put("error", e.errorReason ?: "")
                put("browser", e.requiresBrowser)
                put("segetag", e.segEtag ?: ""); put("segtotal", e.segTotal)
            })
        }
        val value = arr.toString()
        if (prefs.getString("active_v1", null) != value) prefs.edit().putString("active_v1", value).commit()
    }
    private fun restoreActive() {
        try {
            val arr = JSONArray(prefs.getString("active_v1", "[]"))
            val finished = readHistory().map { it.stableId }.toSet()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optLong("id"); val url = o.optString("url")
                if (id <= 0 || id in finished || Uri.parse(url).scheme !in listOf("http", "https")) continue
                val needsBrowser = o.optBoolean("browser")
                val wasPaused = o.optBoolean("paused")
                val wasFailed = o.optBoolean("failed")
                val mirrorsJson = o.optJSONArray("mirrors")
                val mirrors = if (mirrorsJson == null) emptyList() else (0 until minOf(mirrorsJson.length(), 5)).map { mirrorsJson.optString(it) }.filter { it.startsWith("https://") }
                val e = ActiveEntry(stableId = id, title = o.optString("title"), url = url, mirrors = mirrors, mirrorIndex = o.optInt("mirror_index", 0).coerceIn(0, mirrors.size),
                    fileName = sanitizeFileName(o.optString("name", "download.bin")),
                    nameResolved = o.optBoolean("named"), expectedSha256 = o.optString("sha256").takeIf { Regex("^[0-9a-f]{64}$").matches(it) }, resumeValidator = o.optString("validator").ifBlank { null },
                    totalBytes = o.optLong("total", -1), bytesDownloaded = File(partsDir(), "$id.part").length(),
                    paused = !wasFailed && !needsBrowser, pauseRequested = !wasFailed && !needsBrowser,
                    failed = wasFailed || needsBrowser, requiresBrowser = needsBrowser,
                    errorReason = if (needsBrowser) "This link needs a browser session. Remove it and start again from the game post." else o.optString("error").ifBlank { null })
                o.optString("segetag").takeIf { it.isNotBlank() }?.let { tag ->
                    e.segEtag = tag
                    e.segTotal = o.optLong("segtotal", -1L)
                    e.bytesDownloaded = segmentBytesOnDisk(e)
                }
                active[id] = e
                if (!needsBrowser && !wasFailed && (!wasPaused || o.optBoolean("policy"))) policyPaused.add(id)
            }
        } catch (_: Exception) { /* A malformed journal must not crash startup. */ }
    }

    private fun readHistory(): List<HistoryRecord> {
        return try {
            val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                HistoryRecord(
                    stableId = o.optLong("id"),
                    title = o.optString("title"),
                    url = o.optString("url"),
                    contentUri = o.optString("uri").ifBlank { null },
                    completedAt = o.optLong("at"), verified = o.optBoolean("verified")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun addHistory(record: HistoryRecord) {
        try {
            val list = readHistory().toMutableList()
            list.removeAll { it.stableId == record.stableId }
            list.add(record)
            while (list.size > 200) list.removeAt(0)
            val arr = JSONArray()
            for (r in list) {
                arr.put(
                    JSONObject().apply {
                        put("id", r.stableId)
                        put("title", r.title)
                        put("url", r.url)
                        put("uri", r.contentUri ?: "")
                        put("at", r.completedAt)
                        put("verified", r.verified)
                    }
                )
            }
            prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun removeHistory(id: Long) {
        try {
            val list = readHistory().filter { it.stableId != id }
            val arr = JSONArray()
            for (r in list) {
                arr.put(
                    JSONObject().apply {
                        put("id", r.stableId)
                        put("title", r.title)
                        put("url", r.url)
                        put("uri", r.contentUri ?: "")
                        put("at", r.completedAt)
                        put("verified", r.verified)
                    }
                )
            }
            prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun isMeteredNetwork(): Boolean = try {
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.isActiveNetworkMetered
    } catch (_: Exception) {
        false
    }

    /**
     * The file is first written to app storage (.part) and then copied into
     * Download/ZGames PSX, so on the usual single-volume phone the peak
     * need is the remaining download plus one full copy. Fails early with a
     * readable message instead of dying mid-way with a vague IO error.
     */
    private fun checkFreeSpace(total: Long, resumeFrom: Long) {
        if (total <= 0) return
        val safety = 150L * 1024 * 1024
        val remaining = (total - resumeFrom).coerceAtLeast(0L)
        val internalFree = partsDir().usableSpace
        val externalFree = try { Environment.getExternalStorageDirectory().usableSpace } catch (_: Exception) { internalFree }
        val sameVolume = kotlin.math.abs(internalFree - externalFree) < 64L * 1024 * 1024
        val needed = if (sameVolume) remaining + total else maxOf(remaining, total)
        val free = if (sameVolume) internalFree else minOf(internalFree, externalFree)
        if (free < needed + safety) {
            val gb = (needed + safety) / 1_073_741_824.0
            throw Exception(
                String.format(
                    "Not enough free storage. This download needs about %.1f GB free. " +
                            "Free some space, then tap Resume.", gb
                )
            )
        }
    }

    companion object {
        @Volatile private var instance: DownloadEngine? = null

        /**
         * Crash-loop breaker (see PsxApp): forgets the saved download queue and partial files
         * so a poisoned entry can never keep the app from starting. Finished games are kept.
         * Must run BEFORE [get] creates the engine.
         */
        fun safeReset(context: Context) {
            try {
                val app = context.applicationContext
                app.getSharedPreferences("psx_downloads", Context.MODE_PRIVATE).edit().remove("active_v1").commit()
                File(app.filesDir, "download_parts").deleteRecursively()
            } catch (_: Throwable) { }
        }

        /** Process-wide engine, so the download service and the UI share one set of downloads. */
        fun get(context: Context): DownloadEngine =
            instance ?: synchronized(this) {
                instance ?: DownloadEngine(context.applicationContext).also { instance = it }
            }

        const val WIFI_ONLY_MESSAGE =
            "Wi-Fi only is on, and you're not on Wi-Fi. Connect to Wi-Fi (or turn this off in Settings), then tap Resume."
        private const val KEY_HISTORY = "completed_history"
        /** Internal marker header: host the Cookie was meant for (stripped by the network interceptor). */
        private const val COOKIE_HOST_HEADER = "X-PSX-Cookie-Host"
        /** UA for direct (non-WebView) downloads — unchanged from before, so proven direct behavior is untouched. */
        private const val DIRECT_UA = "ZGamesPSX/1.0 Android"
    }
}