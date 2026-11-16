package com.evolutionofgames.zgamespsx.install

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Writes files below a PSP memory-stick root, e.g. "TEXTURES/ULUS10041/a.png".
 * Two backends: [SafWriter] (SAF tree, used when All Files Access isn't
 * granted) and [FileMemstickWriter] (plain files, used for the main-storage
 * /sdcard/PSP folder once All Files Access is granted).
 */
interface MemstickWriter {
    /** Opens (creating/truncating) [relPath]. Null on failure. */
    fun openOutput(relPath: String): OutputStream?

    /** Deletes [relPath] if present. */
    fun delete(relPath: String)
}

/** Plain-[File] writer for a memory-stick root the app can write directly. */
class FileMemstickWriter(private val root: File) : MemstickWriter {

    private fun clean(seg: String): String {
        var s = seg.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trimEnd('.', ' ')
        if (s.isEmpty()) s = "_"
        return s
    }

    override fun openOutput(relPath: String): OutputStream? {
        val parts = relPath.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return try {
            val dir = parts.dropLast(1).fold(root) { d, raw -> File(d, clean(raw)) }
            dir.mkdirs()
            val file = File(dir, clean(parts.last()))
            if (file.isDirectory) file.deleteRecursively()
            FileOutputStream(file)
        } catch (_: Exception) {
            null
        }
    }

    override fun delete(relPath: String) {
        try {
            val parts = relPath.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return
            val file = parts.fold(root) { d, raw -> File(d, clean(raw)) }
            if (file.isFile) file.delete()
        } catch (_: Exception) {
        }
    }
}

/**
 * Writes files below a SAF tree root, creating folders on demand. Directory
 * listings are cached so each folder is only listed once (SAF calls are slow).
 */
class SafWriter(private val context: Context, private val root: DocumentFile) : MemstickWriter {

    private class Dir(val doc: DocumentFile) {
        var loaded = false
        val children = HashMap<String, DocumentFile>()
    }

    private val dirs = HashMap<String, Dir>()
    private val rootDir = Dir(root)

    private fun clean(seg: String): String {
        var s = seg.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trimEnd('.', ' ')
        if (s.isEmpty()) s = "_"
        return s
    }

    private fun load(d: Dir) {
        if (d.loaded) return
        d.children.clear()
        for (c in d.doc.listFiles()) {
            val n = c.name ?: continue
            d.children[n] = c
        }
        d.loaded = true
    }

    private fun dirFor(segments: List<String>): Dir? {
        var cur = rootDir
        var key = ""
        for (raw in segments) {
            val seg = clean(raw)
            key = if (key.isEmpty()) seg else "$key/$seg"
            val cached = dirs[key]
            if (cached != null) { cur = cached; continue }
            load(cur)
            var child = cur.children[seg]
            if (child != null && !child.isDirectory) {
                // A file is in the way — remove it so the folder can be created.
                child.delete(); cur.children.remove(seg); child = null
            }
            if (child == null) {
                child = cur.doc.createDirectory(seg) ?: return null
                // Some providers rename on clash; trust what we got.
                cur.children[child.name ?: seg] = child
            }
            val nd = Dir(child)
            dirs[key] = nd
            cur = nd
        }
        return cur
    }

    /** Opens (creating/truncating) [relPath] such as "TEXTURES/ULUS10041/a.png". Null on failure. */
    override fun openOutput(relPath: String): OutputStream? {
        val parts = relPath.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val name = clean(parts.last())
        val dir = dirFor(parts.dropLast(1)) ?: return null
        load(dir)
        var file = dir.children[name]
        if (file != null && file.isDirectory) { file.delete(); dir.children.remove(name); file = null }
        if (file == null) {
            file = dir.doc.createFile("application/octet-stream", name) ?: return null
            if (file.name != name) { // provider appended an extension
                try { if (file.renameTo(name)) file = dir.doc.findFile(name) ?: file } catch (_: Exception) { }
            }
            dir.children[name] = file
        }
        return try {
            context.contentResolver.openOutputStream(file.uri, "wt")
        } catch (_: Exception) { null }
    }

    override fun delete(relPath: String) {
        try {
            val parts = relPath.split('/').filter { it.isNotEmpty() }
            val dir = dirFor(parts.dropLast(1)) ?: return
            load(dir)
            val n = clean(parts.last())
            dir.children[n]?.delete()
            dir.children.remove(n)
        } catch (_: Exception) {
        }
    }

    /** Reads a small text file below the root (e.g. "SYSTEM/ppsspp.ini"). Null when absent/unreadable. */
    fun readText(relPath: String): String? {
        return try {
            val parts = relPath.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            val dir = dirFor(parts.dropLast(1)) ?: return null
            load(dir)
            val file = dir.children[clean(parts.last())] ?: return null
            context.contentResolver.openInputStream(file.uri)?.use { inp ->
                inp.readBytes().toString(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
    }
}
