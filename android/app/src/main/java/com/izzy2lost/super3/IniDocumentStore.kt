package com.izzy2lost.super3

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

object IniDocumentStore {
    fun ensureIniDocument(context: Context, treeUri: Uri): DocumentFile? {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val configDir =
            tree.findFile("Config")?.takeIf { it.isDirectory } ?: tree.createDirectory("Config") ?: return null
        val existing = findBestIniDoc(configDir)
        if (existing != null) return existing

        val created = configDir.createFile("application/octet-stream", "Supermodel.ini") ?: return null
        seedIniDocument(context, created)
        return created
    }

    fun readIniText(context: Context, doc: DocumentFile): String? {
        return runCatching {
            val input = context.contentResolver.openInputStream(doc.uri) ?: return@runCatching null
            input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
    }

    fun writeIniText(context: Context, doc: DocumentFile, text: String): Boolean {
        return runCatching {
            val out = context.contentResolver.openOutputStream(doc.uri) ?: return@runCatching false
            out.writer(Charsets.UTF_8).use { it.write(text) }
            true
        }.getOrDefault(false)
    }

    private fun findBestIniDoc(configDir: DocumentFile): DocumentFile? {
        val exact = configDir.findFile("Supermodel.ini")
        if (exact != null && exact.isFile) return exact

        val candidates =
            configDir
                .listFiles()
                .filter { it.isFile }
                .mapNotNull { doc ->
                    val name = doc.name ?: return@mapNotNull null
                    if (!name.startsWith("Supermodel.ini", ignoreCase = true)) return@mapNotNull null
                    doc
                }
        if (candidates.isEmpty()) return null

        fun nameScore(doc: DocumentFile): Int {
            val name = doc.name?.lowercase() ?: ""
            return when {
                name == "supermodel.ini" -> 0
                name == "supermodel.ini.txt" -> 1
                name.startsWith("supermodel.ini(") -> 2
                else -> 3
            }
        }

        val best =
            if (candidates.any { it.lastModified() > 0L }) {
                candidates.maxWithOrNull(
                    compareBy<DocumentFile> { it.lastModified() }
                        .thenBy { -nameScore(it) }
                        .thenBy { -(it.name?.length ?: Int.MAX_VALUE) },
                )
            } else {
                candidates.minWithOrNull(
                    compareBy<DocumentFile> { nameScore(it) }
                        .thenBy { it.name?.length ?: Int.MAX_VALUE },
                )
            } ?: return null

        val bestName = best.name ?: return best
        if (!bestName.equals("Supermodel.ini", ignoreCase = true)) {
            val renamed = runCatching { best.renameTo("Supermodel.ini") }.getOrDefault(false)
            if (renamed) {
                val renamedDoc = configDir.findFile("Supermodel.ini")
                if (renamedDoc != null && renamedDoc.isFile) return renamedDoc
            }
        }
        return best
    }

    private fun seedIniDocument(context: Context, doc: DocumentFile) {
        val internal = File(File(context.getExternalFilesDir(null), "super3/Config"), "Supermodel.ini")
        val input =
            when {
                internal.exists() -> runCatching { internal.inputStream() }.getOrNull()
                else -> runCatching { context.assets.open("Config/Supermodel.ini") }.getOrNull()
            }
        if (input == null) return
        input.use { ins ->
            context.contentResolver.openOutputStream(doc.uri)?.use { outs ->
                ins.copyTo(outs)
            }
        }
    }
}
