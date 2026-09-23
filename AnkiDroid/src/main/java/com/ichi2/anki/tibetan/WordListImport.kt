/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Tibetan fork: imports word lists dropped into `<AnkiDroid folder>/tibetan-import/`
 * (e.g. pushed from a computer with `adb push`). Picked up the next time the
 * home screen refreshes; each file is moved to `tibetan-import/done/` afterwards.
 *
 * Format:
 * {
 *   "cards": [
 *     { "tibetan": "…", "english": "…", "decks": ["Verbs", "Verbs::Irregular"],
 *       "updateNoteId": 123,    // optional: overwrite this existing word instead of adding
 *       "overwrite": true }     // optional: if the Tibetan side already exists, replace its English side
 *   ]
 * }
 * A card whose Tibetan side exactly matches an existing word isn't duplicated;
 * the existing word is just added to the listed decks. New words go to the Library.
 */

package com.ichi2.anki.tibetan

import android.content.Context
import com.ichi2.anki.CollectionHelper
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.Note
import com.ichi2.anki.libanki.NoteId
import com.ichi2.anki.libanki.Utils
import org.json.JSONObject
import timber.log.Timber
import java.io.File

object WordListImport {
    private const val FOLDER = "tibetan-import"

    fun importPending(
        col: Collection,
        context: Context,
    ) {
        val dir = File(CollectionHelper.getCurrentAnkiDroidDirectory(context), FOLDER)
        val files = dir.listFiles { f -> f.isFile && f.extension == "json" }?.sortedBy { it.name } ?: return
        if (files.isEmpty()) return
        val notetype = col.notetypes.byName("Basic") ?: return
        val done = File(dir, "done").apply { mkdirs() }

        for (file in files) {
            try {
                val count = importFile(col, notetype.id, JSONObject(file.readText()))
                Timber.i("Imported %d words from %s", count, file.name)
                file.renameTo(File(done, file.name))
            } catch (e: Exception) {
                Timber.w(e, "Could not import %s", file.name)
                file.renameTo(File(dir, file.name + ".failed"))
            }
        }
    }

    private fun importFile(
        col: Collection,
        notetypeId: Long,
        json: JSONObject,
    ): Int {
        val existingByFront = mutableMapOf<String, NoteId>()
        col.db.query("select id, flds from notes").use { cursor ->
            while (cursor.moveToNext()) {
                existingByFront[Utils.splitFields(cursor.getString(1))[0].trim()] = cursor.getLong(0)
            }
        }
        val existingIds = existingByFront.values.toSet()
        val byDeck = mutableMapOf<String, MutableList<NoteId>>()

        val cards = json.getJSONArray("cards")
        for (i in 0 until cards.length()) {
            val card = cards.getJSONObject(i)
            val tibetan = card.getString("tibetan").trim()
            val english = card.getString("english").trim()
            val updateId = card.optLong("updateNoteId", 0L)

            val noteId =
                when {
                    updateId != 0L && updateId in existingIds -> {
                        val note = col.getNote(updateId)
                        note.setField(0, tibetan)
                        note.setField(1, english)
                        col.updateNote(note)
                        updateId
                    }
                    tibetan in existingByFront -> {
                        val id = existingByFront.getValue(tibetan)
                        if (card.optBoolean("overwrite", false)) {
                            val note = col.getNote(id)
                            if (note.fields[1] != english) {
                                note.setField(1, english)
                                col.updateNote(note)
                            }
                        }
                        id
                    }
                    else -> {
                        val note = Note.fromNotetypeId(col, notetypeId)
                        note.setField(0, tibetan)
                        note.setField(1, english)
                        col.addNote(note, Library.LIBRARY_DECK_ID)
                        existingByFront[tibetan] = note.id
                        note.id
                    }
                }
            val decks = card.optJSONArray("decks")
            for (d in 0 until (decks?.length() ?: 0)) {
                byDeck.getOrPut(decks!!.getString(d)) { mutableListOf() }.add(noteId)
            }
        }
        for ((deck, noteIds) in byDeck) {
            Library.addToDeck(col, deck, noteIds)
        }
        return cards.length()
    }
}
