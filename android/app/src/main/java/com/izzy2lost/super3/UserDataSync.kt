package com.izzy2lost.super3

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

object UserDataSync {
    val DIRS_GAME_SYNC = listOf("NVRAM", "Saves", "Config", "Flyers")
    val DIRS_SETTINGS_ONLY = listOf("Config")

    fun syncFromTreeIntoInternal(
        context: Context,
        treeUri: Uri,
        internalUserRoot: File,
        dirs: List<String> = DIRS_GAME_SYNC,
    ) {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return
        for (dirName in dirs) {
            val from = tree.findFile(dirName) ?: continue
            val to = File(internalUserRoot, dirName)
            copyDocDirToFileDir(context.contentResolver, from, to)
        }
    }

    fun syncInternalIntoTree(
        context: Context,
        internalUserRoot: File,
        treeUri: Uri,
        dirs: List<String> = DIRS_GAME_SYNC,
    ) {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return
        for (dirName in dirs) {
            val from = File(internalUserRoot, dirName)
            if (!from.exists() || !from.isDirectory) continue
            val to = ensureDocDir(tree, dirName)
            copyFileDirToDocDir(context.contentResolver, from, to)
        }
    }

    private fun ensureDocDir(parent: DocumentFile, name: String): DocumentFile {
        val existing = parent.findFile(name)
        if (existing != null && existing.isDirectory) return existing
        return parent.createDirectory(name) ?: parent
    }

    private fun copyDocDirToFileDir(resolver: ContentResolver, from: DocumentFile, to: File) {
        if (!from.isDirectory) return
        if (!to.exists()) to.mkdirs()
        for (child in from.listFiles()) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                copyDocDirToFileDir(resolver, child, File(to, name))
            } else {
                copyDocFileToFile(resolver, child, File(to, name))
            }
        }
    }

    private fun copyDocFileToFile(resolver: ContentResolver, from: DocumentFile, to: File) {
        if (!shouldCopyDocToFile(from, to)) return
        val uri = from.uri
        resolver.openInputStream(uri)?.use { input ->
            to.parentFile?.mkdirs()
            to.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun copyFileDirToDocDir(resolver: ContentResolver, from: File, to: DocumentFile) {
        val files = from.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) {
                val childDir = ensureDocDir(to, f.name)
                copyFileDirToDocDir(resolver, f, childDir)
            } else {
                copyFileToDocFile(resolver, f, to)
            }
        }
    }

    private fun copyFileToDocFile(resolver: ContentResolver, from: File, toDir: DocumentFile) {
        val existing = toDir.findFile(from.name)
        if (!shouldCopyFileToDoc(from, existing)) return
        val outDoc = existing ?: toDir.createFile("application/octet-stream", from.name) ?: return
        resolver.openOutputStream(outDoc.uri)?.use { output ->
            from.inputStream().use { input ->
                input.copyTo(output)
            }
        }
    }

    private fun shouldCopyDocToFile(from: DocumentFile, to: File): Boolean {
        if (!to.exists()) return true
        val srcMod = from.lastModified()
        val dstMod = to.lastModified()
        if (srcMod > 0L && dstMod > 0L) return srcMod > dstMod
        if (srcMod > 0L && dstMod == 0L) return true
        if (srcMod == 0L && dstMod > 0L) return false
        val srcLen = from.length()
        val dstLen = to.length()
        if (srcLen > 0L && dstLen > 0L) return srcLen != dstLen
        return false
    }

    private fun shouldCopyFileToDoc(from: File, to: DocumentFile?): Boolean {
        if (to == null || !to.exists()) return true
        val srcMod = from.lastModified()
        val dstMod = to.lastModified()
        if (srcMod > 0L && dstMod > 0L) return srcMod > dstMod
        if (srcMod > 0L && dstMod == 0L) return true
        if (srcMod == 0L && dstMod > 0L) return false
        val srcLen = from.length()
        val dstLen = to.length()
        if (srcLen > 0L && dstLen > 0L) return srcLen != dstLen
        return false
    }
}
