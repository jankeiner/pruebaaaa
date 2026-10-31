/*
 * Copyright (c) 2026 Auxio Project
 * LyricsStore.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.oxycblt.auxio.playback.lyrics

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Remembers which LRC file belongs to which song, and an optional folder (picked once through the
 * system folder picker) that is searched automatically for `<song name>.lrc`.
 *
 * Only Storage Access Framework URIs are stored, so no broad storage permission is required. All
 * functions that touch files block, and must be called from a background thread.
 */
class LyricsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The folder searched automatically for LRC files, or null if none was chosen. */
    var folder: Uri?
        get() = prefs.getString(KEY_FOLDER, null)?.let { Uri.parse(it) }
        set(value) {
            val editor = prefs.edit()
            if (value == null) {
                editor.remove(KEY_FOLDER)
            } else {
                editor.putString(KEY_FOLDER, value.toString())
            }
            editor.apply()
        }

    /** The LRC file the user manually assigned to the song at [songUri], if any. */
    fun assigned(songUri: Uri): Uri? =
        prefs.getString(songKey(songUri), null)?.let { Uri.parse(it) }

    /** Assigns the LRC file at [lrcUri] to the song at [songUri]. Survives app restarts. */
    fun assign(songUri: Uri, lrcUri: Uri) {
        prefs.edit().putString(songKey(songUri), lrcUri.toString()).apply()
    }

    /** Removes the manual assignment of the song at [songUri]. */
    fun unassign(songUri: Uri) {
        prefs.edit().remove(songKey(songUri)).apply()
    }

    /** Reads the text of the document at [uri], or null if it cannot be opened. */
    fun read(uri: Uri): String? =
        try {
            appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            null
        }

    /**
     * Searches [folder] and its sub-folders for a file called `<candidate>.lrc` (case-insensitive)
     * for any of the [candidates]. Returns the first match, or null if nothing was found, no folder
     * is set, or the folder is no longer accessible.
     */
    fun findInFolder(candidates: List<String>): Uri? {
        val tree = folder ?: return null
        val wanted =
            candidates
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .map { "$it.lrc" }
                .toSet()
        if (wanted.isEmpty()) return null
        return try {
            search(tree, wanted)
        } catch (e: Exception) {
            null
        }
    }

    private fun search(tree: Uri, wanted: Set<String>): Uri? {
        val resolver = appContext.contentResolver
        val pending = ArrayList<String>()
        pending.add(DocumentsContract.getTreeDocumentId(tree))
        var visited = 0
        while (pending.isNotEmpty() && visited < MAX_ENTRIES) {
            val parentId = pending.removeAt(0)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val cursor = resolver.query(children, PROJECTION, null, null, null) ?: continue
            cursor.use {
                while (it.moveToNext() && visited < MAX_ENTRIES) {
                    visited++
                    val id = it.getString(0) ?: continue
                    val name = it.getString(1) ?: continue
                    val mime = it.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending.add(id)
                    } else if (name.lowercase() in wanted) {
                        return DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    }
                }
            }
        }
        return null
    }

    private fun songKey(songUri: Uri) = "song:$songUri"

    private companion object {
        const val PREFS_NAME = "jans_lyrics"
        const val KEY_FOLDER = "folder"
        /** Upper bound on scanned entries so a huge folder can never stall the search. */
        const val MAX_ENTRIES = 5000
        val PROJECTION =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
    }
}
